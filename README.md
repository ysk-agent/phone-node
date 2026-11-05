# phone-node

Moriya's custom Android app for spare-smartphone nodes: a resident
foreground service with a native forward proxy, a loopback status
endpoint, and (in later steps) proot + Debian with sshd and opencode,
an integrated Tailscale connection, and a companion helper app that
checks liveness and resurrects the main app.

## Build

The primary build runs on GitHub Actions (public repository, standard
runners are free), because Gradle cannot run on the Muse VM: its
client-to-daemon IPC uses loopback TCP, which the egress gateway
intercepts for Java processes, and sdkmanager cannot answer the
proxy's authentication challenge.

- Normal CI: `.github/workflows/build.yml` builds both APKs on every
  push to `main` and uploads them as a run artifact.
- Build sessions: `.github/workflows/build-session.yml` sets the
  environment up once and then accepts checkouts pushed to the
  `build/inbox` branch for about 30 minutes, so iterative builds skip
  the setup cost. See docs/build-session.md. From the Muse VM:

      python3 ci/request-build.py

APKs are re-signed in CI with the debug keystore held as a repository
secret, so signatures are stable across machines and sessions.

The manual pipeline remains as a local fallback on the Muse VM
(toolchain in ~/workspace/toolchains/, fetched with curl; the VM-local
helper files it needs are not part of this repository):

    ./build-manual.sh [app|helper|all]

Outputs land in build-manual/<module>/<module>-signed.apk.

## Status (2026-11-05)

- Main app skeleton builds: foreground service, 10s heartbeat, status
  endpoint on 127.0.0.1:18090 reporting service start time, heartbeat,
  proxy and proot state; battery-exemption request and MIUI settings
  guidance in the UI; service restart on boot (best effort).
- Native forward proxy implemented (CONNECT + plain HTTP, source
  allowlist of Moriya's tailnet device addresses, port 8080) and wired
  into the service. JVM-level functional testing inside the Muse VM
  is not possible — the same interception that blocks Gradle also
  breaks local socket tests for JVM processes (an early apparent pass
  turned out to be curl bypassing the proxy via NO_PROXY). Functional
  verification is deferred to the real-device step, as planned.
- proot integration built (2026-11-05): Termux's proot 5.1.107.102,
  its loader, libtalloc (proot's DT_NEEDED rewritten from
  libtalloc.so.2 to libtalloc.so so the installer extracts it) and
  libandroid-shmem are bundled in jniLibs/arm64-v8a; the app extracts
  them to the native library directory and executes proot from there.
  Assets carry the Debian bookworm-slim arm64 rootfs (from the
  official Docker image, digest-verified), opencode 1.19.4 linux-arm64,
  Moriya's VM public key as authorized_keys, and the first-launch
  setup / run scripts. ProotManager extracts the rootfs on first
  launch (commons-compress), installs openssh-server and git inside
  the rootfs, configures sshd on port 8022 (key-only, the VM's key),
  places opencode in /usr/local/bin, creates a git repository at
  /root/work, then supervises a long-running proot session (sshd +
  idle) with restart on exit. The APK is 108MB. Execution on a real
  device is still unverified — that is the real-device step.
- Helper app built (2026-11-05): its own foreground service polls the
  main app's status endpoint every minute and judges continuity —
  two failed polls mean dead, an unmoving heartbeat across two polls
  means wedged, a changed start time means the main app recovered by
  itself (recorded, not acted on). On dead/wedged it restarts the main
  app's node service. The main app returns the favour by re-starting
  the helper's service every 10 minutes. Counts and last-seen state
  are shown in the helper's UI. Like everything else here, behaviour
  on a real device (especially MIUI's cross-app start restrictions)
  is verified only in the real-device step.
- Not yet done: Tailscale integration (phased vs full — awaiting
  Moriya's choice, see docs/tailscale-integration.md), real-device
  tests.
