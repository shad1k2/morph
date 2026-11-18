# MORPH

**One drop. Every skin.**

MORPH is an Android peer-to-peer file transfer app whose interface adapts to the
device manufacturer's shell: Material 3 on Pixel, Liquid Glass on HyperOS, the
dot-matrix NDot/Geist language on Nothing OS, and so on.

The transport is designed as a hybrid: an **NFC tap** creates a trusted session,
**BLE** carries discovery / proximity / control messages, and **Wi‑Fi Aware or
Wi‑Fi Direct** moves the actual payload. The wire protocol is our own —
[`DROP/1`](docs/PROTOCOL.md).

> Status: early design. Architecture and protocol spec first, code next.

## Design principle

The shell is not just a theme pack — it is part of the protocol. Devices exchange
a `shell_id` in their capabilities, so both sides can render the confirmation UI
in the receiver's visual language.

| Shell | Visual language |
|---|---|
| Pixel / AOSP | Material 3 Expressive, dynamic color |
| Xiaomi HyperOS | Liquid Glass: translucency, backdrop blur, specular highlights |
| Nothing OS | NDot dot-matrix, Geist body, `#1B1B1D` + `#D71921` |
| Samsung OneUI | Dense cards, large touch targets |

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
