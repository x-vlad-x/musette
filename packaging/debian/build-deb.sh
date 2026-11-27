#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build musette_<version>-1_arm64.deb from a prepared build and the linked Java runtime.
#
#   packaging/debian/build-deb.sh DIST_DIR RUNTIME_DIR OUT_DIR
#
# DIST_DIR comes from tools/build-dist.sh, RUNTIME_DIR from tools/build-runtime.sh.
# dpkg-shlibdeps, the libsixel stub and lintian run in an ARM64 Debian 13 container.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dist="$(realpath "$1")"
runtime="$(realpath "$2")"
out="$(realpath -m "$3")"
app_id=io.github.x_vlad_x.Musette
image=localhost/musette-deb-arm64
ver="$("$root/packaging/debian/deb-version.sh" "$(cat "$root/VERSION")")"
stamp="${SOURCE_DATE_EPOCH:-$(git -C "$root" log -1 --format=%ct)}"
# GitHub rewrites "~" in release file names; the APT repository expects the rewritten name.
deb_file="musette_${ver//\~/-}_arm64.deb"

recipe_id="$(sha256sum "$root/packaging/debian/Containerfile" | cut -c1-16)"
if [[ "$(podman image inspect --format '{{index .Labels "recipe"}}' "$image" 2>/dev/null)" != "$recipe_id" ]]; then
  echo "Building the $image container" >&2
  podman build --platform linux/arm64 --label "recipe=$recipe_id" -t "$image" \
    -f "$root/packaging/debian/Containerfile" "$root/packaging/debian" >&2
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/musette-deb.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT
in_image() {
  podman run --rm --platform linux/arm64 --network=none -v "$work:/work:Z" -v "$root:/src:ro,Z" -w /work "$image" "$@"
}

p="$work/pkg"
lib="$p/usr/lib/musette"
doc="$p/usr/share/doc/musette"
install -d "$p/DEBIAN" "$lib" "$p/usr/bin" "$doc"
cp -r "$dist/lib" "$lib/lib"
cp -a "$runtime" "$lib/jre"
install -m755 "$root/packaging/musette" "$lib/musette"
ln -s ../lib/musette/musette "$p/usr/bin/musette"
install -Dm644 "$root/packaging/$app_id.desktop" "$p/usr/share/applications/$app_id.desktop"
install -Dm644 "$root/packaging/$app_id.metainfo.xml" "$p/usr/share/metainfo/$app_id.metainfo.xml"
install -Dm644 "$root/packaging/$app_id.svg" "$p/usr/share/icons/hicolor/scalable/apps/$app_id.svg"
install -Dm644 "$root/packaging/$app_id-symbolic.svg" "$p/usr/share/icons/hicolor/symbolic/apps/$app_id-symbolic.svg"
install -d "$p/usr/share/man/man1"
gzip -9n -c "$root/packaging/musette.1" > "$p/usr/share/man/man1/musette.1.gz"
install -m644 "$root/packaging/debian/copyright" "$doc/copyright"
install -m644 "$root/NOTICE" "$root/THIRD-PARTY.md" "$dist/build-info.json" "$doc/"
gzip -9n -c "$root/CHANGELOG.md" > "$doc/NEWS.gz"
{
  echo "musette ($ver) unstable; urgency=medium"
  echo
  echo "  * See /usr/share/doc/musette/NEWS.gz."
  echo
  echo " -- Vladislav Shadiuk <6992531+x-vlad-x@users.noreply.github.com>  $(date -u -R -d "@$stamp")"
} | gzip -9n > "$doc/changelog.Debian.gz"

# The stub is built against the target C library here, so that it declares its dependency.
install -d "$lib/native"
in_image gcc -O2 -fPIC -shared -Wl,-soname,libsixel.so.1 -Wl,-z,relro,-z,now -Wl,--no-as-needed -lc \
  -o /work/pkg/usr/lib/musette/native/libsixel.so.1 /src/sixel-stub/sixel_stub.c
chmod 644 "$lib/native/libsixel.so.1"
# jlink keeps the symbol tables of the runtime's binaries.
in_image sh -c 'find /work/pkg/usr/lib/musette/jre /work/pkg/usr/lib/musette/native -type f \( -name "*.so*" -o -path "*/jre/bin/*" -o -name jspawnhelper -o -name jexec \) \
  -exec strip --strip-unneeded --remove-section=.comment --remove-section=.note {} +'
install -Dm644 "$root/packaging/debian/lintian-overrides" "$p/usr/share/lintian/overrides/musette"

find "$p" -type d -exec chmod 755 {} +
find "$p/usr" -type f -exec chmod u+rw,go+r,go-w {} +

# Libraries the program opens itself (through java-gi and JNA) are not visible to
# dpkg-shlibdeps, so they are listed here; the rest comes from the ELF files.
mapfile -t elfs < <(cd "$p" && find usr/lib/musette/jre usr/lib/musette/native -type f \
  \( -name '*.so*' -o -path '*/bin/*' -o -name jspawnhelper -o -name jexec \) | sort)
mkdir -p "$work/debian"
printf 'Source: musette\n\nPackage: musette\nArchitecture: arm64\n' > "$work/debian/control"
shlibs="$(in_image sh -c 'cd /work && dpkg-shlibdeps -O --warnings=0 \
  -l/work/pkg/usr/lib/musette/jre/lib -l/work/pkg/usr/lib/musette/jre/lib/server \
  $(for f in "$@"; do printf -- "-e/work/pkg/%s " "$f"; done)' sh "${elfs[@]}" | sed -n 's/^shlibs:Depends=//p')"
[[ -n "$shlibs" ]] || { echo "dpkg-shlibdeps found no dependencies" >&2; exit 1; }
rm -rf "$work/debian"
depends="$shlibs, libgtk-4-1 (>= 4.18), libadwaita-1-0 (>= 1.7), libwebkitgtk-6.0-4, libmpv2"
size="$(du -sk "$p/usr" | cut -f1)"

cat > "$p/DEBIAN/control" <<EOF
Package: musette
Version: $ver
Architecture: arm64
Maintainer: Vladislav Shadiuk <6992531+x-vlad-x@users.noreply.github.com>
Installed-Size: $size
Depends: $depends
Section: sound
Priority: optional
Homepage: https://github.com/x-vlad-x/musette
Description: YouTube Music player for Linux phones
 Musette is an unofficial YouTube Music client built on SimpMusic, with a
 GTK 4 and libadwaita interface for Phosh: home feed, search, albums,
 artists, playlists, your library, a queue and background playback with
 controls on the lock screen. It is not affiliated with SimpMusic or Google.
EOF
(cd "$p" && find usr -type f -print0 | sort -z | xargs -0 md5sum > DEBIAN/md5sums)
chmod 644 "$p/DEBIAN/control" "$p/DEBIAN/md5sums"

mkdir -p "$out"
in_image env SOURCE_DATE_EPOCH="$stamp" fakeroot dpkg-deb --root-owner-group -Zxz --build pkg "/work/$deb_file" >&2
cp "$work/$deb_file" "$out/$deb_file"
printf '%s\n' "$out/$deb_file"
