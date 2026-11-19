# Multi-instance opencode serve inside one phone proot — trial draft

Status: DRAFT for Moriya's approval. Nothing has been run on moto. Prepared 2026-11-13
after Moriya chose "phone, multiple instances (proot)" as the venue for sharing the
common opencode parts across simultaneous executions.

## Decision already made

- Venue: inside the phone (proot), not Mac/Docker.
- Recommended shape (Way 1): several `opencode serve` processes in the SAME proot,
  sharing one rootfs and one opencode binary. No APK rebuild.
- Trial device: moto (Libero is down since 2026-11-13 morning and is memory-tight).

## Port catch — do NOT use 4097 for the second instance

AndCode's local opencode serve on moto already listens on `127.0.0.1:4097`
(established 2026-11-12). The earlier plan's "4096, 4097, ..." would collide
with it whenever AndCode runs. Use:

- Instance 1 (existing): `0.0.0.0:4096`, HOME=/root, work=/root/work
- Instance 2 (trial): `0.0.0.0:4098`, HOME=/root/inst2, work=/root/work2

## Why the separation matters

- HOME: opencode keeps config, cache and session state under HOME. A shared HOME
  would mix the two instances' sessions. Separate HOME per instance.
- Work dir: opencode stalls outside a git repository, so /root/work2 must be
  `git init`'d like /root/work. Keep the repos distinct so the two instances
  never share a working tree.
- Log: separate file, e.g. /var/log/opencode-serve-2.log, so instance 2's
  output never mixes with instance 1's.
- CORS: instance 2 needs the same `--cors https://ysk-agent.github.io --cors null`
  flags if the browser UI is to reach it.

## Draft change to run-node.sh (guest file only, no APK change)

Add a second supervised loop next to the existing one:

```sh
# Second instance: same binary, separate HOME / work / port / log.
( while true; do
    HOME=/root/inst2 sh -c 'cd /root/work2 && exec opencode serve \
      --port 4098 --hostname 0.0.0.0 \
      --cors https://ysk-agent.github.io --cors null' \
      >> /var/log/opencode-serve-2.log 2>&1
    sleep 5
  done ) &
```

Preparation inside the guest before starting it (one-off, no download):

```sh
mkdir -p /root/inst2 /root/work2
cd /root/work2 && git init -q
```

## Memory budget (measured, not assumed)

- moto: one serve uses ~220MB; ~4GB free. Two instances ≈ 440MB — fits.
- Libero: 3.6GB total, swap already ~1.5GB used, and currently down — not a
  trial candidate.

## Trial steps on moto (light SSH control traffic only — no bulk transfer,
## so the moto mobile-data rule is not triggered; no download is needed)

1. Start instance 2 on 4098; check `http://<moto>:4098/global/health` returns
   healthy with version 1.19.4, and instance 1 on 4096 still answers.
2. Session isolation: create a session on each port and confirm neither
   instance lists the other's session.
3. Run both at once, read free memory and both processes' usage, record the
   per-instance figure as the per-phone ceiling.
4. If clean, fold the second loop into run-node.sh permanently so both return
   after a restart; if not, stop at step 1–3 and keep the draft only.

## Open points to confirm during the trial

- The helper app checks the main app's status endpoint (4096) every 60s; it
  does not know about 4098. Instance 2 is supervised only by run-node.sh's
  restart loop. Whether that is enough is a trial observation, not an
  assumption.
- The phone-node status/heartbeat endpoint reflects instance 1; instance 2
  needs its own health check (the /global/health call above) until a
  combined status exists.

## Rollback

Kill the instance-2 loop and remove /root/inst2 and /root/work2. Instance 1,
run-node.sh's original loop, the APK and the helper are untouched.
