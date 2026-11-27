#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the release packages into out/packages:
#   musette_<version>-1_arm64.deb and Musette-<version>-aarch64.flatpak
#
#   tools/build-packages.sh [deb|flatpak|all]     (default: all)
#
# Requirements: what tools/build-dist.sh and tools/build-runtime.sh need, podman, and
# qemu-user for aarch64 registered through binfmt_misc (packaging runs ARM64 tools).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
what="${1:-all}"
out="$root/out/packages"
case "$what" in deb | flatpak | all) ;; *) echo "Usage: $0 [deb|flatpak|all]" >&2; exit 1 ;; esac

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*" >&2; }

[[ -r /proc/sys/fs/binfmt_misc/qemu-aarch64 ]] ||
  { echo "Register qemu-user for aarch64 first (binfmt_misc)" >&2; exit 1; }

dist="$("$root/tools/build-dist.sh")"
runtime="$("$root/tools/build-runtime.sh")"
log "Checking THIRD-PARTY.md"
python3 "$root/tools/third-party.py" "$dist/lib" --check

mkdir -p "$out"
if [[ $what == deb || $what == all ]]; then
  log "DEB"
  rm -f "$out"/musette_*_arm64.deb
  deb="$("$root/packaging/debian/build-deb.sh" "$dist" "$runtime" "$out")"
  log "lintian"
  podman run --rm --platform linux/arm64 --network=none -v "$out:/out:ro,Z" localhost/musette-deb-arm64 \
    lintian --allow-root --fail-on error "/out/$(basename "$deb")" >&2
fi
if [[ $what == flatpak || $what == all ]]; then
  log "Flatpak"
  rm -f "$out"/Musette-*-aarch64.flatpak
  "$root/packaging/flatpak/build-flatpak.sh" "$dist" "$runtime" "$out" >/dev/null
fi

commit="$(git -C "$root" rev-parse HEAD)"
dirty=false
[[ -z "$(git -C "$root" status --porcelain)" ]] || dirty=true
source "$root/upstream.env"
source "$root/runtime.env"
cat > "$out/build-info.json" <<EOF
{
  "version": "$(cat "$root/VERSION")",
  "project_commit": "$commit",
  "project_dirty": $dirty,
  "simpmusic_tag": "$SIMPMUSIC_TAG",
  "simpmusic_commit": "$SIMPMUSIC_COMMIT",
  "core_commit": "$CORE_COMMIT",
  "temurin": "$TEMURIN_VERSION+$TEMURIN_BUILD",
  "dist": "$(basename "$dist")"
}
EOF
(cd "$out" && shopt -s nullglob && sha256sum -- *.deb *.flatpak) >&2
log "Packages in $out"
