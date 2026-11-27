#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build Musette-<version>-aarch64.flatpak from a prepared build and the linked Java runtime.
#
#   packaging/flatpak/build-flatpak.sh DIST_DIR RUNTIME_DIR OUT_DIR
#
# Needs flatpak-builder (or the org.flatpak.Builder Flatpak) and, on an x86-64 machine,
# qemu-user registered for aarch64 with the F flag: libmpv is compiled for aarch64 in the
# GNOME 51 SDK. The SDK and runtime are installed for the user from Flathub when missing.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dist="$(realpath "$1")"
runtime="$(realpath "$2")"
out="$(realpath -m "$3")"
app_id=io.github.x_vlad_x.Musette
version="$(cat "$root/VERSION")"
bundle="$out/Musette-$version-aarch64.flatpak"
work="$root/out/flatpak"
stage="$root/out/packages/flatpak-app"

if command -v flatpak-builder >/dev/null; then
  builder=(flatpak-builder)
else
  flatpak info org.flatpak.Builder >/dev/null 2>&1 ||
    flatpak install --user --noninteractive flathub org.flatpak.Builder
  builder=(flatpak run org.flatpak.Builder)
fi

rm -rf "$stage"
install -d "$stage/lib/musette" "$stage/share" "$stage/doc/musette"
cp -r "$dist/lib" "$stage/lib/musette/lib"
cp -a "$runtime" "$stage/lib/musette/jre"
install -m755 "$root/packaging/musette" "$stage/lib/musette/musette"
install -m644 "$root"/packaging/$app_id.desktop "$root"/packaging/$app_id.metainfo.xml \
  "$root"/packaging/$app_id.svg "$root"/packaging/$app_id-symbolic.svg "$stage/share/"
install -m644 "$root/LICENSE" "$root/NOTICE" "$root/THIRD-PARTY.md" "$dist/build-info.json" "$stage/doc/musette/"

mkdir -p "$work" "$out"
"${builder[@]}" --arch=aarch64 --user --install-deps-from=flathub --force-clean \
  --default-branch=stable --state-dir="$work/state" --repo="$work/repo" \
  --subject="Musette $version" "$work/build" "$root/packaging/flatpak/$app_id.yml" >&2
flatpak build-bundle --arch=aarch64 --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
  "$work/repo" "$bundle" "$app_id" stable >&2
printf '%s\n' "$bundle"
