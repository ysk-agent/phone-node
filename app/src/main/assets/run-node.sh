#!/bin/sh
# Long-running entry point inside the rootfs. Starts sshd, then idles;
# the Android app supervises this proot process from outside and
# restarts it if it exits.
mkdir -p /run/sshd
/usr/sbin/sshd
echo "run-node: sshd started"
# Headless opencode server on port 4096, for the loosely coupled web
# UI (docs/ui.html). The UI depends only on this public REST API, so
# either side can change without the other. Supervised: restarts 5s
# after any exit. Runs from /root/work because opencode stalls when
# started outside a git repository.
( while true; do
    cd /root/work && opencode serve --port 4096 --hostname 0.0.0.0 \
      --cors https://ysk-agent.github.io --cors null \
      >> /var/log/opencode-serve.log 2>&1
    sleep 5
  done ) &
echo "run-node: opencode serve started, idling"
while true; do
  sleep 3600
done
