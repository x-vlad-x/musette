#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds Musette for the FuriLabs FLX1s with FuriOS and installs it over SSH.
#
#   ./update-musette.sh                 build and install the revision in upstream.env
#   ./update-musette.sh --force         rebuild and reinstall even if the version is the same
#   ./update-musette.sh --build-only    only build, don't install on the phone
#   ./update-musette.sh --check         compare the phone installation with the pinned build
#   ./update-musette.sh --uninstall     remove from the phone (account, history and settings are kept;
#                                             --uninstall --purge removes them too)
#
#   PHONE=user@host ./update-musette.sh  SSH destination (default: "furios", e.g. from ~/.ssh/config)
#
# How it works: SimpMusic's core (YouTube Music client, streams, database, mpv player) and its
# ViewModels come from the official repository, with small patches (patches/). The interface
# is our own, built on GTK4/libadwaita through java-gi (gtk-ui/). The JVM bytecode is the same on
# every architecture, so everything is built on this PC; the phone gets its own Java 25 runtime.
# The developer install lives in ~/.local/lib/musette. The launcher uses the XDG data and cache directories.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$(realpath -m "${SIMPMUSIC_SRC:-$SCRIPT_DIR/SimpMusic}")"
PATCH_DIR="$SCRIPT_DIR/patches"
OUT_DIR="$SCRIPT_DIR/out"
PHONE="${PHONE:-furios}"                       # SSH host, e.g. from ~/.ssh/config
REMOTE_DIR=".local/lib/musette"
APP_ID="io.github.x_vlad_x.Musette"
JRE_VERSION="Temurin-25.0.4.1+1"
JRE_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jre_aarch64_linux_hotspot_25.0.4.1_1.tar.gz"
JRE_SHA256="34828cbb93ed31c281c84ecb31ddab655d11a802f263c1fc019d42e9e0230fed"

export JAVA_HOME="${JAVA_HOME_21:-$HOME/Android/jdk-21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

FORCE=0; BUILD_ONLY=0; CHECK=0; UNINSTALL=0; PURGE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --force) FORCE=1 ;;
    --build-only) BUILD_ONLY=1 ;;
    --check) CHECK=1 ;;
    --uninstall) UNINSTALL=1 ;;
    --purge) PURGE=1 ;;
    -h|--help) sed -n '2,/^$/p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mError: %s\033[0m\n' "$*" >&2; exit 1; }
rsh() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$PHONE" "$@"; }
stop_app() { rsh "pkill -f '[i]o.github.x_vlad_x.musette[.]MainKt' || true"; }

ui_rev() {
  find "$SCRIPT_DIR/gtk-ui/src" "$SCRIPT_DIR/gtk-ui/build.gradle.kts" "$PATCH_DIR" "$SCRIPT_DIR/packaging" \
       "$SCRIPT_DIR/sixel-stub/sixel_stub.c" "$SCRIPT_DIR/upstream.env" "$SCRIPT_DIR/tools/fetch-upstream.sh" \
       "$SCRIPT_DIR/update-musette.sh" -type f -print0 | sort -z | xargs -0 cat | md5sum | cut -c1-8
}

# --- uninstall ---------------------------------------------------------------
if [[ $UNINSTALL -eq 1 ]]; then
  if [[ $PURGE -eq 1 ]]; then
    log "Removing Musette and its profile from the phone"
  else
    log "Removing Musette from the phone and keeping its profile"
  fi
  stop_app
  rsh bash -s -- "$REMOTE_DIR" "$APP_ID" "$PURGE" <<'REMOTE'
set -e
install_dir=$1
app_id=$2
data_home=${XDG_DATA_HOME:-$HOME/.local/share}
cache_home=${XDG_CACHE_HOME:-$HOME/.cache}
if [ "$(readlink .local/bin/musette || :)" = "$HOME/$install_dir/musette" ]; then
  rm .local/bin/musette
fi
rm -rf "$install_dir" "$cache_home/musette"
rm -f "$data_home/applications/$app_id.desktop" "$data_home/metainfo/$app_id.metainfo.xml" \
  "$data_home/icons/hicolor/scalable/apps/$app_id.svg" "$data_home/icons/hicolor/symbolic/apps/$app_id-symbolic.svg"
if [ "$3" -eq 1 ]; then rm -rf "$data_home/musette"; fi
update-desktop-database "$data_home/applications" 2>/dev/null || true
REMOTE
  log "Done"
  exit 0
fi

# --- which version -----------------------------------------------------------
source "$SCRIPT_DIR/upstream.env"
VERSION="${SIMPMUSIC_TAG#v}"
UI_REV="$(ui_rev)"
FULL_VERSION="$VERSION+gtk.$UI_REV"
log "SimpMusic: $VERSION, interface: $UI_REV"

INSTALLED=""
if [[ $BUILD_ONLY -eq 0 ]]; then
  INSTALLED=$(rsh "if test -f $REMOTE_DIR/VERSION; then cat $REMOTE_DIR/VERSION; fi")
  echo "On the phone: ${INSTALLED:-not installed}"
fi
if [[ $CHECK -eq 1 ]]; then
  if [[ "$INSTALLED" == "$FULL_VERSION" ]]; then echo "Up to date."
  else echo "Update available: ${INSTALLED:-none} -> $FULL_VERSION. Install: $0"; fi
  exit 0
fi
if [[ $BUILD_ONLY -eq 0 && "$INSTALLED" == "$FULL_VERSION" && $FORCE -eq 0 ]]; then
  echo "The pinned build is already installed. To reinstall: --force"
  exit 0
fi

# --- build -------------------------------------------------------------------
DIST="$OUT_DIR/musette-$FULL_VERSION"
if [[ -f "$DIST/VERSION" && $FORCE -eq 0 ]]; then
  log "Already built: $DIST"
else
  [[ -x "$JAVA_HOME/bin/java" ]] || die "no JDK 21 in $JAVA_HOME (set JAVA_HOME_21)"
  [[ -d "$ANDROID_HOME/platforms" ]] || die "no Android SDK in $ANDROID_HOME (SimpMusic's Gradle project needs it to configure)"
  command -v clang >/dev/null && command -v ld.lld >/dev/null || die "clang and lld are required (for sixel-stub/)"

  log "Preparing pinned SimpMusic sources"
  SIMPMUSIC_SRC="$SRC_DIR" "$SCRIPT_DIR/tools/fetch-upstream.sh"
  restore_sources() {
    git -C "$SRC_DIR" restore --worktree :/
    git -C "$SRC_DIR/core" restore --worktree :/
  }
  trap restore_sources EXIT
  cd "$SRC_DIR"
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  log "Building (5-10 minutes the first time)"
  GTK_UI_DIR="$SCRIPT_DIR/gtk-ui" ./gradlew --no-configuration-cache -PisFullBuild=false :gtkApp:gtkDist ||
    die "the build failed, see the errors above"
  restore_sources
  trap - EXIT

  log "Packaging: $DIST"
  rm -rf "$DIST" && mkdir -p "$DIST/native"
  cp -r "$SCRIPT_DIR/gtk-ui/build/gtk-dist/lib" "$DIST/lib"
  clang --target=aarch64-linux-gnu -O2 -fPIC -shared -nostdlib -fuse-ld=lld -Wl,-soname,libsixel.so.1 \
    -o "$DIST/native/libsixel.so.1" "$SCRIPT_DIR/sixel-stub/sixel_stub.c"
  cp "$SCRIPT_DIR"/packaging/* "$DIST/"
  echo "$FULL_VERSION" > "$DIST/VERSION"
  echo "Done: $DIST ($(du -sh "$DIST" | cut -f1))"
fi

[[ $BUILD_ONLY -eq 1 ]] && exit 0

# --- install -----------------------------------------------------------------
log "Installing on $PHONE"
rsh "mkdir -p '$REMOTE_DIR' .local/bin"

if ! rsh "test -x '$REMOTE_DIR/jre/bin/java'"; then
  legacy_jre=".local/share/simpmusic-gtk/jre"
  if rsh "test -x '$legacy_jre/bin/java' && grep -Fxq 'IMPLEMENTOR_VERSION=\"$JRE_VERSION\"' '$legacy_jre/release'"; then
    rsh "cp -a '$legacy_jre' '$REMOTE_DIR/jre'"
  else
    log "Installing Temurin 25 JRE (aarch64) on the phone"
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    curl -fsSL -o "$TMP/jre.tar.gz" "$JRE_URL"
    printf '%s  %s\n' "$JRE_SHA256" "$TMP/jre.tar.gz" | sha256sum --check --status
    rsh "set -e; cd '$REMOTE_DIR'; mkdir -p .jre-new
         tar -xzf - -C .jre-new --strip-components=1
         mv -T .jre-new jre" < "$TMP/jre.tar.gz"
  fi
fi

tar -C "$DIST" --exclude=./VERSION -cf - . | gzip -1 | rsh "set -e; cd '$REMOTE_DIR'; rm -rf .new && mkdir .new && tar -C .new -xzf -
  rm -rf lib.old; [ -d lib ] && mv lib lib.old; mv .new/lib lib; rm -rf lib.old
  cp -r .new/. . && rm -rf .new && chmod +x musette"

rsh bash -s -- "$REMOTE_DIR" "$APP_ID" "$FULL_VERSION" <<'REMOTE'
set -e
install_dir=$1
app_id=$2
data_home=${XDG_DATA_HOME:-$HOME/.local/share}
icons="$data_home/icons/hicolor"
mkdir -p "$data_home/applications" "$data_home/metainfo" "$icons/scalable/apps" "$icons/symbolic/apps"
sed 's|^Exec=musette$|Exec="'"$HOME/$install_dir/musette"'"|' \
  "$install_dir/$app_id.desktop" > "$data_home/applications/$app_id.desktop"
cp "$install_dir/$app_id.metainfo.xml" "$data_home/metainfo/"
cp "$install_dir/$app_id.svg" "$icons/scalable/apps/"
cp "$install_dir/$app_id-symbolic.svg" "$icons/symbolic/apps/"
if [ ! -e .local/bin/musette ] && [ ! -L .local/bin/musette ]; then
  ln -s "$HOME/$install_dir/musette" .local/bin/musette
fi
update-desktop-database "$data_home/applications" 2>/dev/null || true
printf '%s\n' "$3" > "$install_dir/VERSION"
REMOTE
log "Installed $FULL_VERSION. Look for Musette in the app list."
