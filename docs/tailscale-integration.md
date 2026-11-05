# Tailscale integration — assessment (2026-11-05)

Goal: the app itself becomes the Android VpnService, so the
always-on-VPN designation protects the whole node process (tailnet
connection, proxy, proot supervisor) instead of only the separate
stock Tailscale app. Android allows one active VPN per profile, so
integration and the stock app are mutually exclusive on a node phone.

## What the upstream source actually requires

Checked against a shallow clone of github.com/tailscale/tailscale-android
(kept at ~/workspace/tailscale-android-src):

- The connection core is a Go library, `libtailscale`, built with
  gomobile into `android/libs/libtailscale.aar`
  (jni/arm64-v8a/libgojni.so). Its go.mod requires Go 1.27.3, which is
  exactly the Go version installed on the Muse VM.
- The build needs Android NDK 23.1.7779620 (r23b), fetchable as a zip
  with curl like the other SDK pieces (~1.2GB).
- The Kotlin glue on their side is compact in line count
  (IPNService.kt 276 lines, backend.go 428 lines) but coupled to
  their App object, local API client, notification and UI state
  machinery. A faithful minimal host in our app means reimplementing
  that contract: VpnService.Builder tun fd handover, DNS and route
  configuration from the Go backend's callbacks, network-change
  notifications, and the login flow.
- Node enrolment for an unattended device realistically uses a
  reusable auth key issued by Moriya in the Tailscale admin console,
  entered on the device at setup time. Interactive login (browser
  round trip) is the alternative.

## Options

A. **Full integration now.** Download the NDK, install gomobile,
   build libtailscale.aar, write the VpnService host, enrol with an
   auth key. The AAR build is mechanical; the service glue and its
   debugging are the long tail, and most verification can only happen
   on the real device. APK grows by roughly 15–25MB.

B. **Phased (recommended).** Do the real-device step first with the
   stock Tailscale app in the VPN slot: it receives always-on-VPN
   protection itself, the tailnet link stays up, and our app is
   protected by its foreground service, battery exemptions and the
   helper's resurrection. Then integrate with real data about which
   process actually dies in practice, if it dies at all.

C. **tailscaled inside proot (userspace networking).** No VPN slot
   and no process protection, so it serves neither purpose of the
   integration; strictly weaker than B. Recorded only to close it.

## Status

Awaiting Moriya's choice between A and B (2026-11-05). Helper
implementation proceeds either way, since it is needed under both.
