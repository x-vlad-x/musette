#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Link an aarch64 runtime with a matching Temurin x86-64 JDK.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$root/runtime.env"
out="$root/out/runtime"
release="jdk-$TEMURIN_VERSION%2B$TEMURIN_BUILD"
artifact_version="${TEMURIN_VERSION}_$TEMURIN_BUILD"
base_url="https://github.com/adoptium/temurin25-binaries/releases/download/$release"
host_archive="OpenJDK25U-jdk_x64_linux_hotspot_$artifact_version.tar.gz"
target_archive="OpenJDK25U-jmods_aarch64_linux_hotspot_$artifact_version.tar.gz"
config_id="$(cat "$root/runtime.env" "$root/tools/build-runtime.sh" | sha256sum | cut -c1-12)"
image_dir="$out/temurin-$artifact_version-aarch64-$config_id"
image="$image_dir/jre"

verify() { printf '%s  %s\n' "$2" "$1" | sha256sum --check --status; }

fetch() {
  local archive="$out/downloads/$1"
  if ! verify "$archive" "$2" 2>/dev/null; then
    printf 'Downloading %s\n' "$1" >&2
    curl -fLsS --retry 2 -o "$scratch/$1" "$base_url/$1"
    verify "$scratch/$1" "$2" || { printf 'Invalid SHA-256 for %s\n' "$1" >&2; return 1; }
    mv -- "$scratch/$1" "$archive"
  fi
}

mkdir -p "$out/downloads"
exec 9>"$out/.lock"
flock 9
if [[ ! -x "$image/bin/java" ]]; then
  if [[ "$(uname -s)-$(uname -m)" != Linux-x86_64 ]]; then
    echo "The runtime prototype uses an x86-64 Linux build host" >&2
    exit 1
  fi
  objcopy="$(command -v llvm-objcopy)" || { echo 'llvm-objcopy is required for aarch64 linking' >&2; exit 1; }
  scratch="$(mktemp -d "$out/.build.XXXXXX")"
  trap 'rm -rf -- "$scratch"' EXIT
  fetch "$host_archive" "$TEMURIN_X64_JDK_SHA256"
  fetch "$target_archive" "$TEMURIN_AARCH64_JMODS_SHA256"
  mkdir "$scratch/jdk" "$scratch/jmods"
  tar -xzf "$out/downloads/$host_archive" -C "$scratch/jdk" --strip-components=1
  tar -xzf "$out/downloads/$target_archive" -C "$scratch/jmods" --strip-components=1

  # jdeps finds the statically linked modules. Add the locale, crypto, ZIP and
  # scripting modules used by services and native bridges at runtime.
  modules=java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.scripting,java.sql,java.xml.crypto,jdk.charsets,jdk.crypto.ec,jdk.dynalink,jdk.localedata,jdk.security.auth,jdk.unsupported,jdk.zipfs
  "$scratch/jdk/bin/jlink" --module-path "$scratch/jmods" --endian little \
    --add-modules "$modules" --strip-java-debug-attributes --strip-native-debug-symbols="objcopy=$objcopy" \
    --no-header-files --no-man-pages --compress=zip-6 --output "$scratch/jre"
  mkdir -p "$image_dir"
  mv -T -- "$scratch/jre" "$image"
fi

printf '%s\n' "$image"
