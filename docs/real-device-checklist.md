# Real-device verification — Mi 11 Lite 5G

Order matters: each stage's result decides whether the next is worth
doing. "Moriya" steps happen on the phone; "Muse" steps run from the
Muse VM or the Mac.

## First run on moto instead (Moriya, 2026-11-05)

- APKs come from the Google Drive folder `phone-node`
  (phonenode-app.apk, 108MB; phonenode-helper.apk). Muse's built-in
  file storage rejected the 108MB APK (HTTP 413), so Drive is the
  delivery route; the Mac staging below is superseded for this run.
- moto runs near-stock Motorola Android: read Stage 1's MIUI items as
  Settings -> Apps -> Phone Node / Phone Node Helper -> Battery ->
  Unrestricted. Locking in Recents is optional.
- Tailscale is already installed and logged in on moto (tailnet
  100.102.129.64); Stage 3 reduces to confirming it is connected.
- Framing: moto is the slowest device, so Stages 2 and 4 are a
  feasibility floor. Too slow here does not rule out the Mi 11 Lite
  5G, and Stage 5 says nothing about MIUI survival — that judgement
  still belongs to a later Mi 11 Lite 5G run.

## Stage 0 — install
- Moriya: install app-signed.apk and helper-signed.apk (staged on the
  Mac under ~/Documents/projects/phone-node/). Allow "install via USB"
  or file-manager install as MIUI requires.
- Moriya: open Phone Node once, tap "ノードサービスを開始".
- Pass: the foreground-service notification appears.

## Stage 1 — settings that only Moriya can change
- Battery exemption for both apps (in-app buttons guide there).
- MIUI: battery saver "No restrictions" + autostart allowed, for
  both apps.
- Lock both apps in Recents.
- Pass: both apps show 電池最適化の除外： 済み in their UIs.

## Stage 2 — proot first launch (the plan's biggest unknown)
- The service extracts the rootfs and runs the in-rootfs setup
  (apt install of openssh-server and git, sshd config, opencode
  placement). This takes minutes and needs the phone online.
- Muse: no external check yet; Moriya reports whether the status
  endpoint's prootRunning becomes true (or Muse checks in Stage 3
  via sshd, which implies it).
- Pass: setup marker written, proot session stays up.
- Fail meaning: if proot cannot execute at all on this device, the
  opencode-node concept falls back to proxy-only for this phone.

## Stage 3 — tailnet reachability
- Moriya: install the stock Tailscale app (phased route), log in,
  connect; set it as the always-on VPN. Note the phone's tailnet IP.
- Muse: from the VM, SSH to <phone tailnet IP>:8022 as root with the
  VM key (5s timeouts), and fetch http://<phone tailnet IP>:8080 via
  the phone's proxy to compare egress IPs.
- Pass: sshd answers, `opencode --version` inside the session prints
  1.19.4, and the proxy's egress IP is the phone location's IP.

## Stage 4 — the discriminator
- Muse (over the Stage 3 SSH): inside /root/work (a git repo), run
  one opencode free-model call with a short timeout.
- Pass: a response returns within the timeout. This is the result the
  whole node concept stands or falls on.

## Stage 5 — survival behaviour
- Leave both apps running, phone plugged in, for a day of normal
  MIUI behaviour. Helper's UI counters show whether the main app
  died, whether it came back by itself, and whether the helper had
  to resurrect it.
- Also test once: reboot the phone and see what returns on its own
  (boot receivers + sticky services), and what does not.

## After verification
- Decide the Tailscale integration (phased vs full) from Stage 5's
  data: if the main app never died, integration stays low priority;
  if it died and resurrection was shaky, integration moves up.
