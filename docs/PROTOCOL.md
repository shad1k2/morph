# DROP/1 — MORPH transfer protocol

Status: **draft v0.2** · Editor: MORPH core team · Applies to: `MORPH >= 0.1`

---

## 1. Scope

DROP/1 defines how two MORPH devices establish a trusted session, exchange
capabilities, negotiate a transport and move files. It deliberately defines
**nothing** about how the radio works: BLE, NFC and Wi-Fi are transports, not
part of the semantics.

### 1.1 Goals

- Work without Google Play Services.
- Bind a session to a physical action (NFC tap) to prevent device confusion and
  MITM.
- Resume after a transport drop.
- Carry the receiver's shell identity so both UIs can render in one design
  language.
- Be extensible: version, capability bits and unknown-message tolerance.

### 1.2 Non-goals

- Internet relay / cloud storage. Peers are assumed to be nearby.
- Custom cryptography. Standard primitives only.
- Contact discovery / social graph.

---

## 2. Terminology

| Term | Meaning |
|---|---|
| **Sender** | device that offers files |
| **Receiver** | device that accepts files |
| **Session** | one tap → one completed (or aborted) transfer |
| **Session ID** | 128-bit value created by the tap initiator |
| **Frame** | the framing envelope defined in §4 |
| **Manifest** | list of offered files with sizes and SHA-256 hashes |
| **Shell** | manufacturer UI identity (Pixel, HyperOS, Nothing OS, …) |

---

## 3. Layer model

```
┌───────────────────────────────────────────┐
│  App layer      manifest, UI, permissions │
├───────────────────────────────────────────┤
│  Session layer  HELLO/CAPS/OFFER/...      │  ← DROP/1 proper
├───────────────────────────────────────────┤
│  Framing        MAGIC/VER/TYPE/SEQ/LEN/…  │  ← DROP/1 proper
├──────────────┬──────────────┬─────────────┤
│ NFC handshake│ BLE control  │ Wi-Fi bulk  │  ← transports
└──────────────┴──────────────┴─────────────┘
```

---

## 4. Frame format

Offsets in bytes, all integers **big-endian**.

```
 0..3    MAGIC     = 0x44 0x52 0x4F 0x50 ("DROP")
 4       VERSION   = 0x01
 5       TYPE      (see §5)
 6..9    SEQ       uint32, monotonically increasing per sender direction
10..11   LENGTH    uint16, payload length
12..N    PAYLOAD   LENGTH bytes
N+1..N+4 CRC32     IEEE 802.3, computed over bytes 0..N
```

`MAX_FRAME` is negotiated: BLE `CAPS.max_mtu` (20–517), Wi-Fi up to 65535.
Frames larger than the negotiated MTU MUST be fragmented at the framing layer
with consecutive SEQ values; the receiver reassembles by SEQ.

### 4.1 Test vector

A `DONE` frame, SEQ = 1, empty payload:

```
44 52 4F 50 01 09 00 00 00 01 00 00
<CRC32 of the 12 bytes above>
```

---

## 5. Message types

| Code | Name | Direction | Payload | Meaning |
|---|---|---|---|---|
| `0x01` | HELLO | both | 32B X25519 pubkey + 16B session_id | key agreement |
| `0x02` | CAPS | both | capability block (§6) | feature negotiation |
| `0x03` | OFFER | sender | manifest (§7.1) | propose files |
| `0x04` | ACCEPT | receiver | accepted file ids + transport | accept/reject |
| `0x05` | CHUNK | sender | file_id + offset + bytes | payload block |
| `0x06` | ACK | receiver | cumulative SEQ | flow control |
| `0x07` | RESUME | receiver | first missing SEQ | gap recovery |
| `0x08` | PROGRESS | both | transferred bytes, total | optional UI |
| `0x09` | DONE | sender | manifest SHA-256 | end of stream |
| `0xFF` | ABORT | both | reason code | terminate |

Unknown TYPE values MUST be ignored; the session continues unless a mandatory
capability was negotiated.

---

## 6. Capability block (CAPS payload)

```
u8   protocol_version      (1)
u8   shell_id              (§6.1)
u16  flags                 (bitfield, §6.2)
u16  max_mtu
u32  max_file_size
u8[] capabilities_blob     (optional, TLV)
```

### 6.1 shell_id registry

| Value | Shell |
|---|---|
| `0x01` | Pixel / AOSP (Material) |
| `0x02` | Xiaomi HyperOS (Liquid Glass) |
| `0x03` | Nothing OS (NDot/Geist) |
| `0x04` | Samsung OneUI |
| `0x05` | OPPO ColorOS |
| `0x06` | vivo OriginOS |
| `0x07` | Huawei HarmonyOS / EMUI |
| `0xFF` | Unknown / fallback theme |

`shell_id` is **advisory**: it changes UI only, never transport behaviour.

### 6.2 Flags

| Bit | Meaning |
|---|---|
| 0 | `WIFI_AWARE` supported |
| 1 | `WIFI_DIRECT` supported |
| 2 | `NFC_HANDSHAKE` supported |
| 3 | `RESUME` supported |
| 4 | `ENCRYPTED` (set after HELLO) |
| 5 | `CONTACTS_ONLY` requested |
| 6 | `QR_FALLBACK` supported |

---

## 7. Manifest and chunks

### 7.1 OFFER manifest

```
u16  file_count
repeat file_count:
  u32  file_id
  u64  size
  u8   mime_len
  u8[] mime
  u8   name_len
  u8[] name (UTF-8)
  u8[32] sha256
```

### 7.2 CHUNK payload

```
u32  file_id
u64  offset
u8[] data
```

`offset` is absolute within the file. Receivers MUST write at `offset`, making
out-of-order and resumed chunks safe.

---

## 8. State machine

| State | Event | Next state | Action |
|---|---|---|---|
| IDLE | NFC tap / QR scan | ROLE_CHOICE | create session_id, start BLE + Wi-Fi Direct |
| ROLE_CHOICE | TCP control channel up | ROLE_CHOICE | exchange `MORPH/1 HELLO` |
| ROLE_CHOICE | SEND FILES claim | SENDING / RECEIVING | send `SEND` on control channel |
| SENDING | file picked | HANDSHAKE | start DROP/1 as SENDER |
| RECEIVING | `SEND` observed | HANDSHAKE | start DROP/1 as RECEIVER |
| HANDSHAKE | HELLO + key derived | NEGOTIATED | send CAPS |
| NEGOTIATED | OFFER | OFFERED | show shell-themed confirm UI |
| OFFERED | ACCEPT | TRANSFERRING | open bulk transport |
| TRANSFERRING | CHUNK/ACK | TRANSFERRING | write + ack |
| TRANSFERRING | link lost | RESUMING | remember last ACK |
| RESUMING | RESUME | TRANSFERRING | retransmit from SEQ |
| TRANSFERRING | DONE | VERIFYING | compare SHA-256 |
| VERIFYING | hashes ok | COMPLETE | play shell-themed animation |
| any | ABORT / timeout / CLOSE | IDLE | stop BLE + P2P, discard session |

Timeouts: 5 s for HELLO/CAPS, 15 s for ACCEPT, 30 s of no ACK during transfer.
The Receive card closes itself after 45 s if no control channel came up, and
the role-choice channel gives up after 90 s without a claim (180 s overall,
which leaves room for the sender to browse the file picker).

### 8.1 Role arbitration

Both phones show the same Receive card. The first phone to press **SEND FILES**
writes `SEND <device_id>` on the control channel and becomes the sender. The
other phone sees the line, drops its SEND button and waits for `FILE`. If both
press within the 450 ms grace window, the NFC reader wins deterministically;
both sides compute the same result from their host/guest roles, so no extra
round-trip is needed. A late duplicate claim is resolved the same way: the tag
owner yields the sender role unless it already sent `FILE`.

The sender opens the file picker only after the role is resolved. If the picker
is dismissed, `CANCEL` ends the session on both sides; the receiver never starts
DROP/1 before it sees `FILE`.

---

## 9. Transports

### 9.1 NFC bootstrap

The tag owner emulates a Type-4 tag over HCE (AID `D2760000850101`). The NDEF
message contains a `morph://drop?...` URI and, for older readers, a legacy
`MORPH/1` MIME record:

```
morph://drop?n=<model>&h=<ip>&p=<port>&s=<shell_id>&i=<session_id>&r=h
MORPH/1 \n <model> \n <ip> \n <port> \n <shell_id> \n <session_id> \n h
```

`session_id` is 16 hex characters, generated with `SecureRandom` and rotated
after the NFC field is gone and no session is active. It binds the BLE service
and the Wi-Fi Direct DNS-SD instance to this physical tap. Tags from pre-1.1
builds have no session id and still use the direct-send flow.

### 9.2 BLE control channel

The tag owner is the GATT server and advertiser; the reader is the GATT client.
The service UUID is derived from the session id:

```
service_uuid = UUID.nameUUIDFromBytes("morph-ble:" + session_id)
```

One characteristic `CTRL` accepts writes and sends notifications. Both sides
exchange newline-delimited ASCII control lines:

```
HELLO <session_id> <device_id> <role> <name>
SEND <device_id>
ROLE R <device_id>
FILE
CANCEL | BYE
```

`role` is `S` (sender or sender claim), `R` (receiver) or `-` (undecided).
After the role is settled, the sender keeps using the line protocol until the
user actually picked a file, then sends `FILE`. Only after `FILE` does either
side switch to DROP/1 on the same socket, so a dismissed file picker can still
send `CANCEL` instead of leaving the receiver waiting on a half-open transfer.
`HELLO` at connect time and `SEND`/`ROLE R` afterwards may repeat; receivers
treat them as idempotent.

The BLE channel is the proximity/control path; the actual payload always uses
Wi-Fi Direct / TCP, so a BLE MTU limit never affects transfer speed.

### 9.3 Wi-Fi bulk channel

Both phones advertise a DNS-SD service instance containing the session id:

```
instance = MORPH-<session_id[0..12]>-H   (tag owner)
instance = MORPH-<session_id[0..12]>-G   (reader)
type     = _morph._tcp
TXT      = sid, role, name, shell, port
```

The tag owner asks for group-owner intent when connecting; the reader becomes
the P2P client and dials `groupOwnerAddress:<port>` from the TXT record. Either
side may be the DROP/1 sender: roles are decided by the `MORPH/1` line protocol,
not by who opened the socket. If Wi-Fi Direct is unavailable, the reader dials
the IP/port from the NFC tag directly on the local network.

The TCP stream starts with one `MORPH/1 HELLO` line per side, followed by
`SEND` / `ROLE R` / `FILE` / `CANCEL` lines until the sender has a file ready.
The same socket is then reused for DROP/1, so there is no second connection and
no lost frames. Legacy senders that start with the binary `DROP` magic bypass
the line protocol and go straight to DROP/1.

---

## 10. Security

### 10.1 Threat model

- Passive eavesdropper on BLE/Wi-Fi → prevented by AES-GCM.
- Active MITM trying to impersonate a peer → prevented by NFC physical binding
  and key confirmation.
- Replay of old frames → prevented by SEQ + session_id in AAD.

### 10.2 Key schedule

```
X25519 ephemeral keys exchanged in HELLO
shared = X25519(sk_local, pk_remote)
session_key = HKDF-SHA256(shared, salt=session_id, info="DROP/1")
```

Each frame: `AES-256-GCM(session_key, nonce = seq || direction_bit,
aad = MAGIC||VER||TYPE||SEQ||LEN, plaintext = PAYLOAD)`.

CRC32 is **not** a security mechanism.

### 10.3 Privacy

No persistent device ID is transmitted. `session_id` is random per tap and
erased on COMPLETE/ABORT. Contacts-only mode is enforced at the app layer.

---

## 11. Versioning

- MAGIC + VERSION permit side-by-side `DROP/1` and future revisions.
- Capability bits are the negotiation mechanism; unknown TLVs are skipped.
- Peers with no common bulk transport fall back to BLE-only transfer limited to
  text/links/contacts (max 64 KiB).
- `ENCRYPTED` must be set before any OFFER is accepted.

---

## 12. Conformance checklist

- [ ] Frames carry correct MAGIC/VERSION/CRC32
- [ ] SEQ monotonic per direction
- [ ] CAPS exchanged before OFFER
- [ ] shell_id used for UI only
- [ ] CHUNK offsets absolute
- [ ] ACK cumulative
- [ ] RESUME from first missing SEQ
- [ ] SHA-256 verified before COMPLETE
- [ ] session_id erased after session
- [ ] Unknown message types ignored gracefully

---

## 13. Glossary

**AAD** — additional authenticated data in GCM · **BLE** — Bluetooth Low Energy ·
**CRC** — cyclic redundancy check · **GATT** — BLE Generic Attribute Profile ·
**HKDF** — HMAC-based key derivation function · **NDEF** — NFC Data Exchange Format ·
**SEQ** — sequence number · **TLV** — tag-length-value.
