#!/bin/bash
# Downloads the pinned binary inputs listed in ci/assets.txt into $1
# (default: ci-dl/) and verifies each file's SHA-256. Files that are
# already present with the correct hash are left untouched, so the
# directory can be carried by actions/cache between runs.
set -euo pipefail
cd "$(dirname "$0")/.."
DL="${1:-ci-dl}"
mkdir -p "$DL"

fetch_oci_layer() { # $1 = repo (library/debian), $2 = digest, $3 = outfile
  local token
  token=$(curl -sf --connect-timeout 15 --max-time 60 \
    "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$1:pull" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
  curl -sfL --connect-timeout 15 --max-time 600 \
    -H "Authorization: Bearer $token" \
    "https://registry-1.docker.io/v2/$1/blobs/sha256:$2" -o "$3"
}

while read -r sha name url; do
  case "$sha" in ''|\#*) continue ;; esac
  out="$DL/$name"
  if [ -f "$out" ] && echo "$sha  $out" | sha256sum -c --quiet - 2>/dev/null; then
    echo "cached: $name"
    continue
  fi
  echo "fetching: $name"
  tmp="$out.tmp"
  case "$url" in
    oci:*)
      spec="${url#oci:}"
      fetch_oci_layer "${spec%@*}" "${spec#*@}" "$tmp"
      ;;
    *)
      curl -sfL --connect-timeout 15 --max-time 600 "$url" -o "$tmp"
      ;;
  esac
  echo "$sha  $tmp" | sha256sum -c --quiet -
  mv "$tmp" "$out"
done < ci/assets.txt
echo "all assets verified in $DL"
