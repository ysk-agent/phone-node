#!/bin/bash
# Copies the generated (git-ignored) files from a staged template app
# tree into a target app tree. Used by the session loop, which stages
# once and then applies the result to every accepted checkout.
# Usage: ci/apply-staged.sh <template-app-dir> <target-app-dir>
set -euo pipefail
SRC="${1:?template app dir}"
DST="${2:?target app dir}"
mkdir -p "$DST/src/main/jniLibs" "$DST/src/main/assets"
cp -r "$SRC/src/main/jniLibs/." "$DST/src/main/jniLibs/"
cp "$SRC/src/main/assets/debian-rootfs.tgz" "$DST/src/main/assets/"
cp "$SRC/src/main/assets/opencode.tgz" "$DST/src/main/assets/"
cp "$SRC/src/main/assets/authorized_keys" "$DST/src/main/assets/"
echo "applied staged assets: $SRC -> $DST"
