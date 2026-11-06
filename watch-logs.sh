#!/bin/bash
# Watch the Drive phone-node/logs folder (every 30s) for the outcome of
# a new build's first setup run.
#
#   watch-logs.sh <new-build-id> <baseline-log-file-id>
#
# Exits 0 with "WATCH RESULT: SUCCESS" when a log from the new build
# shows the setup marker or proot running; exits 0 with "FAILURE" when
# the new process records a failure after having had time to run;
# exits 0 with "STILL RUNNING" if the new build is active for 20
# minutes without a verdict; exits 1 on a 45-minute overall timeout.
CLI=/opt/hatch/bin/hatch_gws_cli
FOLDER=1J70swI3m3NkpanldqszeCH2dbx7DsSjv
NEW_BUILD="$1"
BASELINE_ID="$2"
DEADLINE=$(( $(date +%s) + 2700 ))
FIRST_NEW_SEEN=0
LOG=/tmp/watch-log.txt

summary() {
  grep -E "exported:|service:|heartbeat:|proxyListening:|prootRunning:|lastError:|buildId:|updateStatus:|rootfs/bin/sh:|setup marker:" "$LOG"
  echo "--- proot setup output (tail section) ---"
  sed -n '/proot setup output/,$p' "$LOG" | head -25
}

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  json=$("$CLI" drive files list --params "{\"q\":\"'$FOLDER' in parents and trashed=false\",\"pageSize\":1,\"orderBy\":\"modifiedTime desc\",\"fields\":\"files(id,name)\"}" 2>/dev/null)
  id=$(printf '%s' "$json" | grep -o '"id": "[^"]*"' | head -1 | cut -d'"' -f4)
  if [ -n "$id" ] && [ "$id" != "$BASELINE_ID" ]; then
    if "$CLI" drive files get --params "{\"fileId\":\"$id\",\"alt\":\"media\"}" --output "$LOG" >/dev/null 2>&1 && [ -s "$LOG" ]; then
      if grep -q "setup marker: true" "$LOG" || grep -q "prootRunning: true" "$LOG"; then
        echo "WATCH RESULT: SUCCESS"
        summary
        exit 0
      fi
      if grep -q "buildId: $NEW_BUILD" "$LOG"; then
        now=$(date +%s)
        [ "$FIRST_NEW_SEEN" = 0 ] && FIRST_NEW_SEEN=$now
        uptime=$(grep -oE "uptime [0-9]+s" "$LOG" | grep -oE "[0-9]+" | head -1)
        if grep -qE "^lastError: 2026" "$LOG" && [ "${uptime:-0}" -ge 90 ]; then
          echo "WATCH RESULT: FAILURE"
          summary
          exit 0
        fi
        if [ $((now - FIRST_NEW_SEEN)) -ge 1200 ]; then
          echo "WATCH RESULT: STILL RUNNING (20 min without verdict)"
          summary
          exit 0
        fi
      fi
    fi
  fi
  sleep 30
done
echo "WATCH RESULT: TIMEOUT (45 min, no decisive log)"
exit 1
