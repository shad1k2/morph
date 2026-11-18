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

> Status: `morph-core` implemented and tested; `morph-app` v0.3 actually moves
> files between two Android phones on the same Wi-Fi (mDNS + TCP + X25519 +
> AES-256-GCM), saves them to `Downloads/MORPH`, and shows the drop card in a
> system overlay from the Quick Settings tile.

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

v0.3 ships a working path that needs nothing but the same Wi-Fi network:

```
mDNS (_morph._tcp) -> peer list          (NSD on Android)
TCP :44771         -> DropLink session
HELLO              -> X25519 public keys
CAPS               -> shell_id, flags, MTU
OFFER / ACCEPT     -> manifest with SHA-256 per file
CHUNK*             -> AES-256-GCM frames, 32 KiB payloads
DONE / ACK         -> manifest hash verified, file finalized
```

Received files land in `Downloads/MORPH` through MediaStore, so they survive
reboots and show up in Files. The card shows real bytes/percent, not a timer.

The NFC + BLE + Wi-Fi Direct bootstrap described in [`PROTOCOL.md`](docs/PROTOCOL.md)
is still the target for v0.4: a physical tap removes the "which device do I
pick?" problem and gives the session a natural MITM-resistant start.

### Self-test

Settings → Transmission → **SELF-TEST** runs a full 512 KB encrypted transfer
over loopback TCP on one phone. If it prints `Loopback OK`, framing, key
agreement, AEAD and file reassembly all work.

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
