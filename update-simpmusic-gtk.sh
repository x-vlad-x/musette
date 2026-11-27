#!/usr/bin/env bash
# Builds SimpMusic with a native GNOME interface (GTK4 + libadwaita) for aarch64 Linux phones
# (tested on the FuriLabs FLX1s with FuriOS) and installs it over SSH.
#
#   ./update-simpmusic-gtk.sh                 build and install the revision in upstream.env
#   ./update-simpmusic-gtk.sh --force         rebuild and reinstall even if the version is the same
#   ./update-simpmusic-gtk.sh --build-only    only build, don't install on the phone
#   ./update-simpmusic-gtk.sh --check         compare the phone installation with the pinned build
#   ./update-simpmusic-gtk.sh --uninstall     remove from the phone (account, history and settings are kept;
#                                             --uninstall --purge removes them too)
#
#   PHONE=user@host ./update-simpmusic-gtk.sh  SSH destination (default: "furios", e.g. from ~/.ssh/config)
#
# How it works: SimpMusic's core (YouTube Music client, streams, database, mpv player) and its
# ViewModels come from the official repository, with three small patches (patches/). The interface
# is our own, built on GTK4/libadwaita through java-gi (gtk-ui/). The JVM bytecode is the same on
# every architecture, so everything is built on this PC; the phone gets its own Java 25 runtime.
# On the phone the program lives in ~/.local/share/simpmusic-gtk, its data in the home/ folder there.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$(realpath -m "${SIMPMUSIC_SRC:-$SCRIPT_DIR/SimpMusic}")"
PATCH_DIR="$SCRIPT_DIR/patches"
OUT_DIR="$SCRIPT_DIR/out"
PHONE="${PHONE:-furios}"                       # SSH host, e.g. from ~/.ssh/config
REMOTE_DIR=".local/share/simpmusic-gtk"        # relative to $HOME on the phone
APP_ID="io.github.x_vlad_x.SimpMusicGtk"
JRE_URL="https://api.adoptium.net/v3/binary/latest/25/ga/linux/aarch64/jre/hotspot/normal/eclipse"
# Left over from earlier builds (the Compose desktop build and the old app id), removed on install.
LEGACY_FILES=".local/share/simpmusic .local/share/applications/simpmusic.desktop .skiko"
LEGACY_FILES+=" .local/share/applications/com.maxrave.simpmusic.gtk.desktop .local/share/icons/hicolor/256x256/apps/simpmusic.png"

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
stop_app() { rsh "pkill -f '[s]impmusicgtk[.]MainKt|[s]impmusic[.]gtk[.]MainKt' ; true"; }

ui_rev() {
  find "$SCRIPT_DIR/gtk-ui/src" "$SCRIPT_DIR/gtk-ui/build.gradle.kts" "$PATCH_DIR" "$SCRIPT_DIR/packaging" \
       "$SCRIPT_DIR/sixel-stub/sixel_stub.c" "$SCRIPT_DIR/upstream.env" "$SCRIPT_DIR/tools/fetch-upstream.sh" \
       "$SCRIPT_DIR/update-simpmusic-gtk.sh" -type f -print0 | sort -z | xargs -0 cat | md5sum | cut -c1-8
}

# --- uninstall ---------------------------------------------------------------
if [[ $UNINSTALL -eq 1 ]]; then
  [[ $PURGE -eq 1 ]] && log "Removing SimpMusic (GTK) from the phone, including its data" \
                     || log "Removing SimpMusic (GTK) from the phone (account, history and settings are kept)"
  stop_app
  if [[ $PURGE -eq 1 ]]; then cmd="rm -rf $REMOTE_DIR;"
  else cmd="find $REMOTE_DIR -mindepth 1 -maxdepth 1 ! -name home -exec rm -rf {} + 2>/dev/null;"; fi
  cmd+="rm -rf .local/share/applications/$APP_ID.desktop .cache/simpmusic-gtk $LEGACY_FILES;"
  cmd+="update-desktop-database .local/share/applications 2>/dev/null; true"
  rsh "$cmd"
  rsh "ls -d $REMOTE_DIR/home 2>/dev/null | sed 's/^/  kept: ~\//'" || true
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
DIST="$OUT_DIR/simpmusic-gtk-$FULL_VERSION"
if [[ -f "$DIST/VERSION" && $FORCE -eq 0 ]]; then
  log "Already built: $DIST"
else
  [[ -x "$JAVA_HOME/bin/java" ]] || die "no JDK 21 in $JAVA_HOME (set JAVA_HOME_21)"
  [[ -d "$ANDROID_HOME/platforms" ]] || die "no Android SDK in $ANDROID_HOME (SimpMusic's Gradle project needs it to configure)"
  command -v clang >/dev/null && command -v ld.lld >/dev/null || die "clang and lld are required (for sixel-stub/)"

  log "Preparing pinned SimpMusic sources"
  SIMPMUSIC_SRC="$SRC_DIR" "$SCRIPT_DIR/tools/fetch-upstream.sh"
  cd "$SRC_DIR"
  echo "sdk.dir=$ANDROID_HOME" > local.properties

  restore_sources() {
    git -C "$SRC_DIR" restore --worktree :/
    git -C "$SRC_DIR/core" restore --worktree :/
  }
  trap restore_sources EXIT
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
  cp "$SCRIPT_DIR/packaging/simpmusic-gtk" "$SCRIPT_DIR/packaging/$APP_ID.desktop" \
     "$SCRIPT_DIR/packaging/$APP_ID.png" "$DIST/"
  echo "$FULL_VERSION" > "$DIST/VERSION"
  echo "Done: $DIST ($(du -sh "$DIST" | cut -f1))"
fi

[[ $BUILD_ONLY -eq 1 ]] && exit 0

# --- install -----------------------------------------------------------------
log "Installing on $PHONE"
rsh "mkdir -p $REMOTE_DIR .local/share/applications"

if ! rsh "test -x $REMOTE_DIR/jre/bin/java"; then
  if rsh "test -x .local/share/simpmusic/jre/bin/java"; then
    echo "  taking over the Java runtime of an earlier installation"
    rsh "mv .local/share/simpmusic/jre $REMOTE_DIR/jre"
  else
    log "Installing Temurin 25 JRE (aarch64) on the phone"
    TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
    curl -fsSL -o "$TMP/jre.tar.gz" "$JRE_URL"
    scp -q "$TMP/jre.tar.gz" "$PHONE:/tmp/simpmusic-jre.tar.gz"
    rsh "rm -rf $REMOTE_DIR/jre && mkdir $REMOTE_DIR/jre \
         && tar -xzf /tmp/simpmusic-jre.tar.gz -C $REMOTE_DIR/jre --strip-components=1 && rm /tmp/simpmusic-jre.tar.gz"
  fi
fi

# The new jars go next to the old ones and replace them in one go; jre/ and home/ stay as they are.
tar -C "$DIST" -cf - . | gzip -1 | rsh "set -e; cd $REMOTE_DIR; rm -rf .new && mkdir .new && tar -C .new -xzf -
  rm -rf lib.old; [ -d lib ] && mv lib lib.old; mv .new/lib lib; rm -rf lib.old
  cp -r .new/. . && rm -rf .new && chmod +x simpmusic-gtk"
rsh "set -e; sed \"s|@HOME@|\$HOME|g\" $REMOTE_DIR/$APP_ID.desktop > .local/share/applications/$APP_ID.desktop
     rm -rf $LEGACY_FILES
     update-desktop-database .local/share/applications 2>/dev/null || true"

if rsh "pgrep -f '[s]impmusicgtk[.]MainKt' >/dev/null"; then
  echo "SimpMusic is running: the new version starts with the next launch."
fi
log "Installed: $FULL_VERSION, look for \"SimpMusic\" in the app list"
