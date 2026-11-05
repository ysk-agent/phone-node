#!/bin/bash
# Builds both APKs from a staged source tree with Gradle and, when a
# signing keystore is provided via the environment, re-signs them with
# it so signatures stay stable across machines and sessions.
# Usage: ci/build.sh <src-dir> <out-dir>
# Env: SIGNING_KEYSTORE (path; unset = keep Gradle's debug signature),
#      SIGNING_STORE_PASS / SIGNING_KEY_PASS (default: android),
#      SIGNING_KEY_ALIAS (default: androiddebugkey)
set -euo pipefail
SRC="${1:?src dir}"
OUT="${2:?out dir}"
mkdir -p "$OUT"
cd "$SRC"

./gradlew --console=plain :app:assembleDebug :helper:assembleDebug

BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
echo "build-tools: $BT"
sign_one() { # $1 = gradle apk, $2 = final name
  local in="$1" name="$2"
  "$BT/zipalign" -p -f 4 "$in" "$OUT/$name.aligned"
  if [ -n "${SIGNING_KEYSTORE:-}" ]; then
    "$BT/apksigner" sign --ks "$SIGNING_KEYSTORE" \
      --ks-pass "pass:${SIGNING_STORE_PASS:-android}" \
      --key-pass "pass:${SIGNING_KEY_PASS:-android}" \
      --ks-key-alias "${SIGNING_KEY_ALIAS:-androiddebugkey}" \
      --out "$OUT/$name" "$OUT/$name.aligned"
  else
    mv "$OUT/$name.aligned" "$OUT/$name"
  fi
  rm -f "$OUT/$name.aligned"
  "$BT/apksigner" verify "$OUT/$name"
  echo "built: $OUT/$name"
}
sign_one app/build/outputs/apk/debug/app-debug.apk phonenode-app.apk
sign_one helper/build/outputs/apk/debug/helper-debug.apk phonenode-helper.apk

python3 - "$OUT" <<'EOF'
import hashlib, json, os, sys, datetime
out = sys.argv[1]
info = {"built_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "resigned": bool(os.environ.get("SIGNING_KEYSTORE")), "apks": {}}
for name in ("phonenode-app.apk", "phonenode-helper.apk"):
    p = os.path.join(out, name)
    info["apks"][name] = {"size": os.path.getsize(p),
                          "sha256": hashlib.sha256(open(p, "rb").read()).hexdigest()}
json.dump(info, open(os.path.join(out, "build-info.json"), "w"), indent=1)
print(json.dumps(info, indent=1))
EOF
