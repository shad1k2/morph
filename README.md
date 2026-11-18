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

> Status: `morph-core` implemented and tested; `morph-app` is a complete,
> shell-aware UI (radar, file picker, drop overlay, settings). Transfer
> transports are the next step.

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
flow: hero + radar on the home screen, nearby device list, a real SAF file
picker, an AirDrop-style drop card with accept/decline and progress, and a
settings screen with per-shell preview, transport toggles and protocol info.
Every pack carries its own silhouette and brand oval, not just a different
accent: light One UI/ColorOS, glass HyperOS/OriginOS, layered HarmonyOS, flat
Nothing OS and Pixel.

## Transport

```
NFC tap      -> session_id, BLE service UUID, optional Wi-Fi credentials
BLE GATT     -> HELLO / CAPS / OFFER / ACK / PROGRESS / ABORT
Wi-Fi Direct -> CHUNK stream, RESUME, DONE (SHA-256 manifest verification)
```

Why NFC: a physical tap removes the "which device do I pick?" problem and gives
the session a natural MITM-resistant bootstrap. Why not BLE-only: its throughput
is fine for links and contacts, not for photos and video.

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
