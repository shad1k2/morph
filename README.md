# MORPH

**One drop. Every skin.**

MORPH is an Android peer-to-peer file transfer app whose interface adapts to the
device manufacturer's shell: Material 3 on Pixel, Liquid Glass on HyperOS, the
dot-matrix NDot/Geist language on Nothing OS, and so on.

The transport is designed as a hybrid: an **NFC tap** creates a trusted session,
**BLE** carries discovery / proximity / control messages, and **Wi‑Fi Aware or
Wi‑Fi Direct** moves the actual payload. The wire protocol is our own —
[`DROP/1`](docs/PROTOCOL.md). Architecture and language choices are in
[`ARCHITECTURE.md`](docs/ARCHITECTURE.md); shell design packs in
[`THEMES.md`](docs/THEMES.md).

> Status: **v1.1.0** — `morph-core` is implemented and covered by regression
> tests; `morph-app` taps two phones over NFC, opens a **Bluetooth + Wi-Fi
> Direct** session, and lets either side become the sender from the same
> Receive card. Files are saved to `Downloads/MORPH` with X25519 +
> AES-256-GCM and shown in a system overlay.

## Install

Grab the signed APK from
[Releases](https://github.com/shad1k2/morph/releases/latest). Requires Android
10 (API 29) or newer; NFC tap-to-send needs NFC hardware.

## Build

```bash
# JDK 17 and Android SDK 35 are required.
./gradlew :core:check          # unit tests
./gradlew :app:assembleDebug   # debug APK
./gradlew :app:assembleRelease # signed release (see below)
```

Release builds are signed from `keystore.properties` or the equivalent
environment variables (`MORPH_KEYSTORE`, `MORPH_KEYSTORE_PASSWORD`,
`MORPH_KEY_ALIAS`, `MORPH_KEY_PASSWORD`). The file is git-ignored. CI runs on
every push/PR, and pushing a `v*` tag publishes a signed APK with a SHA-256
checksum through GitHub Actions.

## Design principle

The shell is not just a theme pack — it is part of the protocol. Devices exchange
a `shell_id` in their capabilities, so both sides can render the confirmation UI
in the receiver's visual language.

| Shell | Visual language |
|---|---|
| Pixel / AOSP | Material 3 Expressive, dynamic color |
| Xiaomi HyperOS | Liquid Glass: translucency, backdrop blur, specular highlights |
| Samsung One UI | Soft layered pill cards, rounded containers |
| OPPO ColorOS | Natural light, textures, layered shadows |
| vivo OriginOS | Frosted gradients, gentle motion, vivo Sans |
| Huawei HarmonyOS | Starry-gray, layered translucent surfaces |
| Nothing OS | NDot dot-matrix, Geist body, `#1B1B1D` + `#D71921` |

`app/` is the Android client. It detects the local shell and re-skins the whole
flow: hero + live radar fed by mDNS, discovered phones, a real SAF file picker,
an AirDrop-style drop card with accept/decline and real progress, and a settings
screen with per-shell preview, engine status, loopback self-test and protocol
info. Every pack carries its own silhouette and brand oval, not just a different
accent: light One UI/ColorOS, glass HyperOS/OriginOS, layered HarmonyOS, flat
Nothing OS and Pixel.

## Quick Settings tile

Add **MORPH drop** to the shade (swipe down twice → pencil → drag the tile in).
Tapping it collapses the shade and shows the drop card over the current app.
That needs the "Display over other apps" permission, granted once from the
banner on the home screen or from Settings → Pop-up over apps.

The overlay is a real `TYPE_APPLICATION_OVERLAY` window hosting Compose, not a
full-screen activity, so the app underneath keeps running.

## Transport

v1.1.0 ships the full tap flow. Touch two phones: MORPH emulates a Type-4
NDEF tag over HCE (AID `D2760000850101`) containing `MORPH/1`, model, IP, TCP
port, shell_id and a per-tap session id. Both phones then:

- open a **Bluetooth LE** control channel — the tag owner advertises a GATT
  service whose UUID is derived from the NFC session id, the reader scans and
  connects;
- start **Wi-Fi Direct** with DNS-SD, keyed by the same session id; the group
  owner runs the TCP listener and the P2P client dials it;
- show the same **Receive** card with a **SEND FILES** button.

The first side to press SEND FILES becomes the sender; the other side
automatically switches to receiving. A simultaneous claim is resolved
deterministically (the NFC reader wins). If Bluetooth or Wi-Fi Direct
permissions/hardware are unavailable, the session falls back to direct TCP on
the local network, but the role choice stays the same.

The data path itself:

```
NFC tap            -> session id + IP/port/shell       (HCE Type-4 NDEF)
BLE GATT           -> presence + role claim             (session UUID)
Wi-Fi Direct       -> DNS-SD + P2P group               (group owner dials)
MORPH/1 HELLO      -> session id, device id, role hint  (line protocol)
SEND / ROLE R      -> who sends
FILE               -> picker returned, then DROP/1 starts
mDNS (_morph._tcp) -> LAN fallback peer list            (NSD on Android)
TCP :44771         -> DropLink session
HELLO              -> X25519 public keys
CAPS               -> shell_id, flags, MTU
OFFER / ACCEPT     -> manifest with SHA-256 per file
CHUNK*             -> AES-256-GCM frames, 32 KiB payloads
DONE / ACK         -> manifest hash verified, file finalized
```

Received files land in `Downloads/MORPH` through MediaStore, so they survive
reboots and show up in Files. The card shows real bytes/percent, not a timer.

The old mDNS + direct TCP path remains as the fallback when BLE/Wi-Fi Direct
are unavailable, and is still used for manually selected nearby devices.

### Self-test

Settings → Transmission → **SELF-TEST** runs a full 512 KB encrypted transfer
over loopback TCP on one phone. If it prints `Loopback OK`, framing, key
agreement, AEAD and file reassembly all work.

**E2E RECEIVE TEST** goes further: it advertises `_morph._tcp`, resolves this
phone over mDNS, then sends a 256 KB file to its own receiver through TCP,
X25519 + AES-GCM and MediaStore. It reports the saved file name, and the file
lands in `Downloads/MORPH`. This checks the whole app-level receive path on one
device; sending between two phones uses the identical code.

## Repository layout (planned)

```
app/                  Android application (Jetpack Compose)
core/                 session engine, FrameCodec, capabilities
transport-nfc/        NFC handshake
transport-ble/        GATT control channel
transport-wifi/       Wi-Fi Aware / Direct bulk channel
shells/               ShellDetector + theme registry
docs/PROTOCOL.md      DROP/1 specification
```

## License

GPL-3.0 — see [LICENSE](LICENSE).
