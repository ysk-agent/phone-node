#!/bin/bash
# Stages the native binaries and large assets into an app tree, the
# same way scripts/stage-native.sh does for the local manual pipeline:
#   <app>/src/main/jniLibs/arm64-v8a/  proot + loader + libtalloc + shmem
#   <app>/src/main/assets/             Debian rootfs, opencode, ssh key
# Usage: ci/stage-assets.sh [dl-dir] [app-dir]
set -euo pipefail
cd "$(dirname "$0")/.."
DL="${1:-ci-dl}"
APP="${2:-app}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$APP/src/main/jniLibs/arm64-v8a" "$APP/src/main/assets"

extract_deb() { # $1 = deb path, $2 = dest dir
  mkdir -p "$2"
  (cd "$2" && ar x "$1" && tar -xJf data.tar.xz && rm -f data.tar.xz control.tar.xz debian-binary)
}
extract_deb "$DL/proot_5.1.107.102_aarch64.deb" "$WORK/proot"
extract_deb "$DL/libtalloc_2.5.0_aarch64.deb" "$WORK/talloc"
extract_deb "$DL/libandroid-shmem_0.7_aarch64.deb" "$WORK/shmem"
USR_P="$WORK/proot/data/data/com.termux/files/usr"
USR_T="$WORK/talloc/data/data/com.termux/files/usr"
USR_S="$WORK/shmem/data/data/com.termux/files/usr"

cp "$USR_P/bin/proot" "$APP/src/main/jniLibs/arm64-v8a/libproot.so"
cp "$USR_P/libexec/proot/loader" "$APP/src/main/jniLibs/arm64-v8a/libproot-loader.so"
cp "$USR_T/lib/libtalloc.so.2.5.0" "$APP/src/main/jniLibs/arm64-v8a/libtalloc.so"
cp "$USR_S/lib/libandroid-shmem.so" "$APP/src/main/jniLibs/arm64-v8a/libandroid-shmem.so"

# proot's DT_NEEDED names libtalloc.so.2, which the Android installer
# would not extract from jniLibs; rewrite it to libtalloc.so in place.
python3 - "$APP/src/main/jniLibs/arm64-v8a/libproot.so" <<'EOF'
import sys
path = sys.argv[1]
data = bytearray(open(path, "rb").read())
old = b"libtalloc.so.2\x00"
new = b"libtalloc.so\x00\x00\x00"
assert len(old) == len(new)
count = data.count(old)
assert count == 1, f"expected exactly 1 occurrence, found {count}"
open(path, "wb").write(data.replace(old, new))
print("patched libtalloc.so.2 -> libtalloc.so in libproot.so")
EOF
chmod 755 "$APP/src/main/jniLibs/arm64-v8a/"*.so

cp "$DL/debian-bookworm-slim-arm64.tar.gz" "$APP/src/main/assets/debian-rootfs.tgz"
cp "$DL/opencode-linux-arm64.tar.gz" "$APP/src/main/assets/opencode.tgz"
if [ -n "${AUTHORIZED_KEYS_FILE:-}" ]; then
  cp "$AUTHORIZED_KEYS_FILE" "$APP/src/main/assets/authorized_keys"
else
  curl -sf --connect-timeout 15 --max-time 30 https://github.com/ysk-agent.keys \
    -o "$APP/src/main/assets/authorized_keys"
fi
echo "staged assets into $APP"
