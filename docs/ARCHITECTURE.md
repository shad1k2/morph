# MORPH architecture and language choices

Status: draft v0.1

## 1. Modules

```
morph-core       protocol, framing, session engine, capabilities   (Kotlin)
morph-app        Android UI + NFC/BLE/Wi-Fi transports             (Kotlin + Compose)
morph-xposed     optional system-level bridge                      (Kotlin/Java)
morph-relay      OPTIONAL internet rendezvous / status service     (Go)
```

The transfer never depends on root. `morph-xposed` only forwards intents and
shows overlays; all business logic stays in `morph-app` / `morph-core`.

## 2. Language decisions

### Kotlin — Android, core, Xposed module

- First-class on Android; `morph-core` can be a plain Kotlin JVM library so it is
  unit-testable without an emulator.
- Jetpack Compose for the shell-aware UI; one set of screens, multiple theme
  packs selected by `shell_id`.
- The Xposed bridge is tiny and follows the same pattern as our LSPosed module:
  hook a system event, send a broadcast with a URI/session id, let the app do
  the work.
- If iOS/desktop becomes a goal later, `morph-core` can move to Kotlin
  Multiplatform without changing the protocol.

### Go — optional relay backend

Needed only for an internet rendezvous service or a small status/telemetry
endpoint. Local P2P needs **no server at all**.

Why Go: fast to write, excellent networking primitives, static single binary,
cheap hosting, and it keeps the server language apart from client logic so a
bug in the server can never break local transfers. Alternative: Rust if we want
zero-GC and stronger memory guarantees at the cost of development speed.

### TypeScript — only if we add a web dashboard

A minimal status/admin page would use TypeScript + a lightweight frontend. Not
part of MVP.

### Not chosen

- Java: Kotlin is more concise and interoperable; no reason to start new Java code.
- Python: convenient for a prototype, but weak as a production relay for many
  concurrent sockets.
- C/C++: unnecessary risk for a protocol with no performance-critical parsing.

## 3. Repository layout (target)

```
core/               Kotlin, DROP/1 codec + session state machine
app/                Compose UI, transports, permissions
xposed/             optional system bridge
relay/              optional Go rendezvous server
docs/               PROTOCOL.md, ARCHITECTURE.md, THEMES.md
```

## 4. Build and CI

- Gradle + Kotlin for client modules.
- GitHub Actions: unit tests for `core`, Android assemble for `app`, `go test` for
  `relay` once it exists.
- Every protocol change requires a `docs/PROTOCOL.md` update and a test vector in
  `core/src/test`.

## 5. Roadmap

1. ✅ `morph-core`: `FrameCodec`, `Capabilities`, session FSM + tests (Kotlin).
2. `morph-app`: `ShellDetector`, three theme packs, tap-to-connect screen.
3. BLE control channel + text/link transfer (MVP).
4. NFC bootstrap + Wi-Fi bulk transfer + resume.
5. `morph-xposed`: sharesheet device list, floating bubble, QS tile.
6. `morph-relay`: optional internet rendezvous (Go).
