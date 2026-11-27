#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Prepare a clean checkout of the pinned upstream with the GTK patches applied.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$(realpath -m "${SIMPMUSIC_SRC:-$root/SimpMusic}")"
source "$root/upstream.env"
export LC_ALL=C

die() { printf 'Error: %s\n' "$*" >&2; exit 1; }

[[ $SIMPMUSIC_TAG =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ &&
   $SIMPMUSIC_COMMIT =~ ^[0-9a-f]{40}$ && $CORE_COMMIT =~ ^[0-9a-f]{40}$ ]] ||
  die "invalid revision in upstream.env"

if [[ ! -e "$src/.git" ]]; then
  [[ ! -e "$src" || -d "$src" && -z "$(ls -A "$src")" ]] ||
    die "$src is not an empty directory or a Git checkout"
  git init --quiet "$src"
  git -C "$src" remote add origin https://github.com/maxrave-dev/SimpMusic
fi

[[ -z "$(git -C "$src" status --porcelain --ignore-submodules=all)" ]] ||
  die "commit or restore local changes in $src first"
if [[ -e "$src/core/.git" ]]; then
  [[ -z "$(git -C "$src/core" status --porcelain)" ]] ||
    die "commit or restore local changes in $src/core first"
fi

if ! git -C "$src" cat-file -e "$SIMPMUSIC_COMMIT^{commit}" 2>/dev/null; then
  git -C "$src" fetch --quiet --depth=1 --no-tags origin "$SIMPMUSIC_COMMIT"
fi
[[ "$(git -C "$src" ls-tree "$SIMPMUSIC_COMMIT" core)" == "160000 commit $CORE_COMMIT"$'\t'core ]] ||
  die "$SIMPMUSIC_COMMIT does not pin core at $CORE_COMMIT"
git -C "$src" checkout --quiet --detach "$SIMPMUSIC_COMMIT"
git -C "$src" submodule sync --quiet --recursive
git -C "$src" submodule update --quiet --init --recursive --checkout --depth=1
[[ "$(git -C "$src" rev-parse HEAD)" == "$SIMPMUSIC_COMMIT" &&
   "$(git -C "$src/core" rev-parse HEAD)" == "$CORE_COMMIT" ]] ||
  die "checkout differs from upstream.env"

restore_patches() {
  git -C "$src" restore --worktree :/
  git -C "$src/core" restore --worktree :/
}

trap restore_patches EXIT
for patch_file in "$root"/patches/*.patch; do
  patch_name="${patch_file##*/}"
  patch_repo="$src"
  case "$patch_name" in
    *-core-*) patch_repo="$src/core" ;;
  esac
  printf '  %s\n' "$patch_name"
  if ! result="$(git -C "$patch_repo" -c apply.ignoreWhitespace=no apply --check --verbose --whitespace=nowarn "$patch_file" 2>&1)"; then
    die "$patch_name does not apply"$'\n'"$result"
  fi
  if [[ "$result" == *"(offset "* ]]; then
    die "$patch_name applies with an offset"$'\n'"$result"
  fi
  git -C "$patch_repo" -c apply.ignoreWhitespace=no apply --whitespace=nowarn "$patch_file"
done
trap - EXIT
printf '%s at %s (core %s)\n' "$SIMPMUSIC_TAG" "$SIMPMUSIC_COMMIT" "$CORE_COMMIT"
