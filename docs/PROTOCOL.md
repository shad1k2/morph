# DROP/1 — MORPH transfer protocol

Status: draft v0.1

## Goals

- Vendor-independent: no Google Play Services requirement (fallback transport exists).
- Session-oriented: a physical NFC tap binds the whole session to one peer.
- Resumable: a dropped Wi-Fi link can continue from the last acknowledged chunk.
- Shell-aware: capability exchange carries the receiver's shell identity so both
  UIs can render in the same visual language.

## Frame format

All frames, regardless of transport:

```
offset  size  field
0       4     MAGIC = "DROP"   (0x44 0x52 0x4F 0x50)
4       1     VERSION = 0x01
5       1     TYPE
6       4     SEQ      (big endian, per direction)
10      2     LENGTH   (big endian, payload bytes)
12      N     PAYLOAD
12+N    4     CRC32    (IEEE 802.3, over bytes 0..11+N)
```

Maximum frame size on BLE is negotiated via `CAPS.max_mtu`; Wi-Fi transport may
use larger frames (up to 64 KiB) with the same header.

## Message types

| Code | Name | Direction | Purpose |
|---|---|---|---|
| 0x01 | HELLO | both | ephemeral public key, session token proof |
| 0x02 | CAPS | both | shell_id, version, MTU, transports, limits |
| 0x03 | OFFER | sender | manifest: files, sizes, MIME, SHA-256 |
| 0x04 | ACCEPT | receiver | accepted file ids, chosen transport |
| 0x05 | CHUNK | sender | numbered payload block |
| 0x06 | ACK | receiver | cumulative acknowledged sequence |
| 0x07 | RESUME | receiver | request retransmission from SEQ |
| 0x08 | PROGRESS | both | optional UI progress |
| 0x09 | DONE | sender | transfer finished, manifest hash |
| 0xFF | ABORT | both | reason code |

## State machine

```
IDLE --NFC tap--> HANDSHAKE --BLE HELLO/CAPS--> NEGOTIATED
   NEGOTIATED --OFFER--> OFFERED --ACCEPT--> TRANSFERRING
   TRANSFERRING --CHUNK/ACK--> TRANSFERRING
   TRANSFERRING --link lost--> RESUMING --RESUME--> TRANSFERRING
   TRANSFERRING --DONE--> VERIFYING --hash ok--> COMPLETE
   any state --ABORT--> IDLE
```

## Phase 1 — NFC bootstrap

NDEF record payload (binary, ~40 bytes):

```
u8   version
u128 session_id
u16  ble_service_uuid
u8   flags (bit0: wifi_aware, bit1: wifi_direct, bit2: requires_contacts)
[optional] u8[32] wifi_psk
```

NFC is foreground-only by platform design; a QR-code fallback carries the same
payload for devices without NFC.

## Phase 2 — BLE control

GATT service with three characteristics:

- `ctrl_tx` — write, frames from sender to receiver
- `ctrl_rx` — notify, frames from receiver
- `presence` — notify, RSSI-based proximity updates

`CAPS.shell_id` values: `0x01` Pixel/AOSP, `0x02` HyperOS, `0x03` Nothing OS,
`0x04` OneUI, `0xFF` unknown. The UI consumes this to switch its design pack.

## Phase 3 — bulk data

Preferred: Wi‑Fi Aware (Neighbor Aware Networking) with a `NetworkSpecifier` and
a local TCP socket. Fallback: Wi‑Fi Direct group, otherwise a plain HTTP server
on the local network for GMS-less/legacy devices.

CHUNK payload:

```
u32 file_id
u64 offset
u8[] data
```

Receiver ACKs cumulative sequence numbers; a gap triggers `RESUME` with the
first missing sequence. `DONE` carries the manifest SHA-256; receiver verifies
each file before `COMPLETE`.

## Security

- Ephemeral ECDH (X25519) in HELLO; HKDF derives the session key.
- AES-256-GCM on every frame after HELLO; the NFC session_id acts as an additional
  authenticated binding (physical contact = MITM resistance).
- No custom cryptographic primitives. Frame CRC32 is for corruption detection,
  not security.
- Capability flags are signed as part of the GCM AAD.

## Versioning

MAGIC + VERSION allow coexistence of `DROP/1` and future revisions. Unknown
message types are ignored; mandatory capability bits are negotiated so an old
peer falls back to BLE-only text transfer instead of failing the session.
