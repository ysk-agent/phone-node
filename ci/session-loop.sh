#!/bin/bash
# Build-session loop. Runs inside one GitHub Actions job after the
# environment has been set up and the assets staged into a template.
# It announces readiness by creating a prerelease named $SESSION_TAG,
# then polls the $INBOX_BRANCH branch every 15 seconds; every new
# commit pushed there is built once, and its APKs, log and a
# result-<short>.json are uploaded as assets of the session release.
# Checkouts are accepted for $SESSION_MINUTES minutes from readiness;
# a build already running at the deadline is allowed to finish.
# The loop never restarts itself.
#
# Required env: SESSION_TAG, GITHUB_REPOSITORY, GH_TOKEN,
#               TEMPLATE_APP (staged template app dir)
# Optional env: SESSION_MINUTES (default 30), INBOX_BRANCH
#               (default build/inbox), BUILD_TIMEOUT (default 900)
set -uo pipefail
cd "$(dirname "$0")/.."
TAG="${SESSION_TAG:?}"
MINUTES="${SESSION_MINUTES:-30}"
INBOX="${INBOX_BRANCH:-build/inbox}"
BUILD_TIMEOUT="${BUILD_TIMEOUT:-900}"
SHORT_POLL=15

iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }
upload() { gh release upload "$TAG" "$@" --clobber; }

SCRATCH="$(mktemp -d)"

# Do not build whatever the inbox branch happened to point at before
# this session started; only new pushes are accepted.
last_sha="$(git ls-remote origin "refs/heads/$INBOX" | awk '{print $1}')"
echo "session $TAG: inbox starts at ${last_sha:-<none>}"

cat > "$SCRATCH/session.json" <<EOF
{"tag": "$TAG", "status": "ready", "started_at": "$(iso)",
 "accept_until": "$(date -u -d "+$MINUTES minutes" +%Y-%m-%dT%H:%M:%SZ)",
 "inbox_branch": "$INBOX", "repository": "$GITHUB_REPOSITORY"}
EOF
gh release create "$TAG" --prerelease \
  --title "Build session $TAG" \
  --notes "Ephemeral build-session release. APK assets appear here as builds complete." \
  "$SCRATCH/session.json"
echo "session $TAG: ready, accepting checkouts for $MINUTES minutes"

build_one() { # $1 = commit sha
  local sha="$1" short dir work log status started ended
  short="${sha:0:12}"
  dir="$(mktemp -d)"
  work="$dir/tree"
  log="$dir/build-$short.log"
  started="$(iso)"
  echo "=== build $sha (short $short) started $started ==="
  git fetch --depth 1 origin "$sha" >>"$log" 2>&1
  if ! git worktree add --detach "$work" "$sha" >>"$log" 2>&1; then
    status="checkout-failed"
  else
    ci/apply-staged.sh "$TEMPLATE_APP" "$work/app" >>"$log" 2>&1
    if timeout "$BUILD_TIMEOUT" ci/build.sh "$work" "$dir/out" >>"$log" 2>&1; then
      status="success"
    else
      status="failed"
    fi
  fi
  ended="$(iso)"
  local files=("$log")
  if [ "$status" = "success" ]; then
    cp "$dir/out/phonenode-app.apk" "$dir/phonenode-app-$short.apk"
    cp "$dir/out/phonenode-helper.apk" "$dir/phonenode-helper-$short.apk"
    files+=("$dir/phonenode-app-$short.apk" "$dir/phonenode-helper-$short.apk")
  fi
  python3 - "$dir" "$sha" "$short" "$status" "$started" "$ended" <<'EOF'
import json, os, sys
d, sha, short, status, started, ended = sys.argv[1:7]
result = {"sha": sha, "short": short, "status": status,
          "started_at": started, "ended_at": ended}
info_path = os.path.join(d, "out", "build-info.json")
if os.path.exists(info_path):
    result["build_info"] = json.load(open(info_path))
json.dump(result, open(os.path.join(d, f"result-{short}.json"), "w"), indent=1)
EOF
  files+=("$dir/result-$short.json")
  upload "${files[@]}" || echo "WARNING: asset upload failed for $short"
  echo "=== build $short: $status ($started .. $ended) ==="
  git worktree remove --force "$work" >/dev/null 2>&1 || rm -rf "$work"
  git worktree prune >/dev/null 2>&1 || true
  rm -rf "$dir"
}

deadline=$(( $(date +%s) + MINUTES * 60 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  sha="$(git ls-remote origin "refs/heads/$INBOX" | awk '{print $1}')"
  if [ -n "$sha" ] && [ "$sha" != "$last_sha" ]; then
    build_one "$sha"
    last_sha="$sha"
  else
    sleep "$SHORT_POLL"
  fi
done

cat > "$SCRATCH/session-closed.json" <<EOF
{"tag": "$TAG", "status": "closed", "closed_at": "$(iso)",
 "last_built_sha": "$last_sha"}
EOF
upload "$SCRATCH/session-closed.json" || true
echo "session $TAG: closed"
