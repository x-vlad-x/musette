#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Turn a Musette version into a Debian version: 0.1.0 -> 0.1.0-1, 0.2.0-rc.1 -> 0.2.0~rc1-1.
# The "~" sorts a release candidate before the release. DEB_REVISION sets the revision (1);
# the APT repository at deb.xvlad.com expects <version>-<revision> packages.
set -eu
ver="$1"
rev="${DEB_REVISION:-1}"
case "$rev" in '' | *[!0-9]*) echo "DEB_REVISION must be a number" >&2; exit 1 ;; esac
if ! printf '%s\n' "$ver" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$'; then
  echo "Not a version: $ver" >&2
  exit 1
fi
base="${ver%%-*}"
if [ "$base" = "$ver" ]; then
  echo "$base-$rev"
else
  pre="${ver#*-}"
  echo "$base~$(printf '%s' "$pre" | tr -d .)-$rev"
fi
