# Real-device verification — moto (first pass)

Moriya's call 2026-11-05 (side chat, 11:29 UTC): verify on moto first,
not the Mi 11 Lite 5G. Adapted from `real-device-checklist.md`
(Mi 11 Lite 5G version). Order matters: each stage's result decides
whether the next is worth doing. "Moriya" steps happen on the phone;
"Muse" steps run from the Muse VM or the Mac.

## How this pass differs from the Mi 11 Lite plan

- **moto is Moriya's daily phone, not a spare.** Anything that
  disturbs daily use (always-on VPN routing all traffic, a reboot
  test, leaving it plugged in all day) needs his call at the time,
  not a default yes.
- **No MIUI.** The MIUI-only steps (battery saver "No restrictions",
  autostart, locking apps in Recents, "install via USB") do not apply.
  Stock-Android battery exemption replaces them.
- **moto is the slow end of the range.** It was already ruled out as
  an opencode execution candidate for being slow. This pass is a
  lower-bound check: if the concept works here, it works on the
  Mi 11 Lite 5G; if it is merely slow here, that does **not** rule
  out the Mi 11 Lite 5G. Judge Stage 4 on "works at all", not speed.
- **moto already has Termux installed** (confirmed 2026-11-04), but
  its sshd was not running (ports 8022/22 probed, no banner). Do not
  confuse Termux's sshd with the node app's proot sshd (also 8022,
  inside the Debian rootfs) — the node test uses the latter.
- **moto's tailnet address is already known: 100.102.129.64.**
  No need to ask Moriya for it in Stage 3.

## Stage 0 — install
- Moriya: install app-signed.apk and helper-signed.apk (helper is
  689 KB and already sent; the main APK is 104 MB and is being
  rebuilt with opencode recompressed to xz to fit the file-send
  limit — wait for the rebuilt APK, do not use the 104 MB one).
- Install via the file manager; allow installs from unknown sources
  if asked. No MIUI "install via USB" step.
- Moriya: open Phone Node once, tap "ノードサービスを開始".
- Pass: the foreground-service notification appears.

## Stage 1 — settings that only Moriya can change
- Battery exemption for both apps (stock Android: Settings > Apps >
  Phone Node / Helper > Battery > Unrestricted, or the in-app
  buttons if they guide there).
- No autostart toggle and no Recents lock on stock Android; skip
  those Mi 11 Lite steps.
- Pass: both apps show 電池最適化の除外： 済み in their UIs.

## Stage 2 — proot first launch (the plan's biggest unknown)
- The service extracts the rootfs and runs the in-rootfs setup
  (apt install of openssh-server and git, sshd config, opencode
  placement). On moto this takes longer than it would on the
  Mi 11 Lite 5G — allow extra time and do not fail it for slowness
  alone. The phone needs to be online.
- Muse: no external check yet; Moriya reports whether the status
  endpoint's prootRunning becomes true (or Muse checks in Stage 3
  via sshd, which implies it).
- Pass: setup marker written, proot session stays up.
- Fail meaning: if proot cannot execute at all on this device, the
  opencode-node concept falls back to proxy-only for this phone —
  and the Mi 11 Lite 5G test is still worth doing, since this
  failure mode is device-specific.

## Stage 3 — tailnet reachability
- Moriya: install the stock Tailscale app (phased route), log in,
  connect; set it as the always-on VPN. **Flag:** on a daily phone
  this routes all of his traffic through the tailnet while testing —
  confirm he is happy with that for the test window.
- Muse: from the VM, SSH to 100.102.129.64:8022 as root with the
  VM key (5s timeouts), and fetch http://100.102.129.64:8080 via
  the phone's proxy to compare egress IPs.
- Pass: sshd answers, `opencode --version` inside the session prints
  1.19.4, and the proxy's egress IP is the phone location's IP.

## Stage 4 — the discriminator
- Muse (over the Stage 3 SSH): inside /root/work (a git repo), run
  one opencode free-model call with a **longer timeout than the
  Mi 11 Lite plan** — moto is slow, so a slow success still passes.
- Pass: a response returns within the timeout. This is the result
  the whole node concept stands or falls on, at the lower bound.
- A timeout here does not close the Mi 11 Lite 5G question; it only
  says moto is too slow (or the setup needs more time).

## Stage 5 — survival behaviour (lighter on a daily phone)
- Helper's UI counters show whether the main app died, whether it
  came back by itself, and whether the helper had to resurrect it.
  Read these after a day of normal use — no need to leave the phone
  plugged in all day as a dedicated test.
- Reboot test: optional and deferrable. On a daily phone, do it only
  when Moriya picks a convenient moment; note what returns on its
  own (boot receivers + sticky services) and what does not.

## After verification
- Decide the Tailscale integration (phased vs full) from Stage 5's
  data, same as the Mi 11 Lite plan: if the main app never died,
  integration stays low priority; if it died and resurrection was
  shaky, integration moves up.
- Mi 11 Lite 5G verification remains open as the real
  execution-candidate test, whatever moto shows — moto only sets
  the lower bound.
