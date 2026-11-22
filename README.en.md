# XiaoQiao SmartRun Treadmill — Offline Controller

> English summary of the project. Full documentation (protocol spec, provisioning, build steps) is in Chinese: [README.md](README.md) · [PROTOCOL.md](PROTOCOL.md) · [PROVISION.md](PROVISION.md)

A from-scratch Android controller for the **XiaoQiao (小乔) SmartRun WiFi treadmill**, built after the vendor's cloud service shut down and the official app (`com.qiaoports.xqiao`, "小乔快跑") stopped working.

The treadmill itself is fine — its panel still starts, stops and adjusts speed. What broke is the phone side: the official app depends on a vendor server for discovery, login, courses and records. This project replaces that phone side with a fully local implementation.

## Why this exists

Reverse engineering showed the treadmill is **not a Bluetooth device** — it is a LAN device with a plain local protocol:

| Layer | Detail |
|---|---|
| Discovery | UDP broadcast on port **19032**, 30-byte payload, magic `XJXQ` |
| Control | TCP port **12304**, frame `[type][len][payload][chk2][CRLF]` |
| Status | 34-byte frames pushed by the treadmill every 1–2 s (speed / duration / distance / calories / heart rate / steps) |

So the vendor server is not required at all. This repository rebuilds the local protocol from the APK's static analysis plus live traffic capture, verifies it with PC scripts, and ships an Android app.

## What it does

- **Auto discovery and connection** — UDP device discovery, a single TCP connection, backoff reconnect (3 s → 10 s → 30 s)
- **Speed control** — 0.5 km/h steps, hold-to-adjust, safe stop (decelerate to 0 before cutting power)
- **Courses** — 7 built-in courses grouped K1–K4, plus natural-language custom courses ("walk 5 min, jog 10 min, walk 5 min")
- **Immersive workout screen** — large speed readout, segment remaining, total progress, next-stage preview, portrait and landscape
- **Offline voice coaching** — 115 bundled Chinese audio clips, no system TTS, no network
- **Cadence metronome** — follows speed or fixed BPM
- **Workout history and share cards** — calendar heatmap, 1080×1440 share image
- **ESP-Touch v2 provisioning** — put the treadmill back on WiFi without the vendor app

Pure Java + Android SDK, **zero third-party dependencies** (no AndroidX, no Material Components, no Gradle).

## Compatibility (read this)

The official app targets **several** XiaoQiao models, but this project has been **tested end-to-end on exactly one unit**:

`factory=1` / `model=13` / WiFi firmware `30`.

Other models may use different frame lengths, status-field offsets or speed limits. If you own a different model, capture the broadcast and status frames first and compare against [PROTOCOL.md](PROTOCOL.md) before sending any control command.

## Install

Download the APK from [Releases](../../releases/latest) and install on Android 6.0+. No account, no login, no network permission beyond your own LAN.

## FAQ

**Can I still use the official app?**
Depends on whether the vendor server is still running. This project never modifies or impersonates the official app; when the official app can no longer log in or reach the device, this app still works over the local network.

**The official app cannot connect or log in — what now?**
Make sure the phone and treadmill are on the same 2.4 GHz WiFi. If the panel still works but the app hangs at login, the server side is gone — that part cannot be fixed, only replaced with a local controller like this one.

**Do I need to open the machine, flash firmware or modify hardware?**
No. The protocol runs over WiFi. This project deliberately avoids the firmware-upgrade channel — a bad flash is unrecoverable.

**Where is my data stored?**
Locally on the phone only. No account, no cloud sync, no telemetry.

**Why Android only?**
iOS does not allow the low-level WiFi frame injection ESP-Touch needs, and restricts long-lived background sockets.

**Is it safe?**
Commands mirror what the official app sends. Still: verify speed commands with the belt empty, clip the physical safety key during any real workout, and never treat `off` as pause.

## Safety

1. Verify speed commands with **nobody on the belt**.
2. `off` (cmd=1, data=1) **cuts power** — it is not a pause button.
3. Always use the physical safety key.
4. Do not touch the firmware upgrade path.

## License

MIT. See [LICENSE](LICENSE).
