#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the architecture-independent part of Musette (the interface and every jar it needs) plus
# the aarch64 libsixel stub into out/musette-<version>, then print that directory.
#
#   tools/build-dist.sh [--force]
#
# The directory name carries a hash of everything that goes into the build, so an existing
# build is reused until a source, patch or packaging file changes.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$(realpath -m "${SIMPMUSIC_SRC:-$root/SimpMusic}")"
force=0
[[ "${1-}" == --force ]] && force=1

export JAVA_HOME="${JAVA_HOME_21:-$HOME/Android/jdk-21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*" >&2; }
die() { printf '\033[1;31mError: %s\033[0m\n' "$*" >&2; exit 1; }

ui_rev() {
  find "$root/gtk-ui/src" "$root/gtk-ui/build.gradle.kts" "$root/patches" "$root/packaging/musette" \
       "$root"/packaging/io.github.x_vlad_x.Musette* \
       "$root/sixel-stub/sixel_stub.c" "$root/VERSION" "$root/upstream.env" "$root/tools/fetch-upstream.sh" \
       "$root/tools/build-dist.sh" -type f -print0 | sort -z | xargs -0 cat | md5sum | cut -c1-8
}

source "$root/upstream.env"
version="$(cat "$root/VERSION")"
dist="$root/out/musette-$version+gtk.$(ui_rev)"

if [[ -f "$dist/VERSION" && $force -eq 0 ]]; then
  log "Already built: $dist"
  printf '%s\n' "$dist"
  exit 0
fi

[[ -x "$JAVA_HOME/bin/java" ]] || die "no JDK 21 in $JAVA_HOME (set JAVA_HOME_21)"
[[ -d "$ANDROID_HOME/platforms" ]] || die "no Android SDK in $ANDROID_HOME (SimpMusic's Gradle project needs it to configure)"
command -v clang >/dev/null && command -v ld.lld >/dev/null || die "clang and lld are required (for sixel-stub/)"

log "Preparing pinned SimpMusic sources"
SIMPMUSIC_SRC="$src" "$root/tools/fetch-upstream.sh" >&2
restore_sources() {
  git -C "$src" restore --worktree :/
  git -C "$src/core" restore --worktree :/
}
trap restore_sources EXIT
echo "sdk.dir=$ANDROID_HOME" > "$src/local.properties"
log "Building (5-10 minutes the first time)"
(cd "$src" && GTK_UI_DIR="$root/gtk-ui" ./gradlew --no-configuration-cache -PisFullBuild=false :gtkApp:gtkDist >&2) ||
  die "the build failed, see the errors above"
restore_sources
trap - EXIT

log "Collecting: $dist"
rm -rf "$dist" && mkdir -p "$dist/native"
cp -r "$root/gtk-ui/build/gtk-dist/lib" "$dist/lib"
clang --target=aarch64-linux-gnu -O2 -fPIC -shared -nostdlib -fuse-ld=lld -Wl,-soname,libsixel.so.1 \
  -o "$dist/native/libsixel.so.1" "$root/sixel-stub/sixel_stub.c"
cp "$root"/packaging/musette "$root"/packaging/io.github.x_vlad_x.Musette* "$dist/"
printf '{\n  "version": "%s",\n  "simpmusicTag": "%s",\n  "simpmusicCommit": "%s",\n  "coreCommit": "%s"\n}\n' \
  "$version" "$SIMPMUSIC_TAG" "$SIMPMUSIC_COMMIT" "$CORE_COMMIT" > "$dist/build-info.json"
basename "$dist" | sed 's/^musette-//' > "$dist/VERSION"
echo "Done: $dist ($(du -sh "$dist" | cut -f1))" >&2
printf '%s\n' "$dist"
