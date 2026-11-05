#!/bin/sh
# Long-running entry point inside the rootfs. Starts sshd, then idles;
# the Android app supervises this proot process from outside and
# restarts it if it exits.
mkdir -p /run/sshd
/usr/sbin/sshd
echo "run-node: sshd started, idling"
while true; do
  sleep 3600
done
