# MORPH shell themes

Status: draft v0.1 · Companion to `docs/PROTOCOL.md` §6.1

## 1. Purpose

The receiver's `shell_id` is sent in `CAPS`. Both devices use it to pick a
**design pack** for the confirmation and progress screens. A theme never changes
protocol behaviour — only colors, type, shape and motion.

## 2. Detection (client-side, for local UI)

`ShellDetector` checks, in order:

| Signal | Shell |
|---|---|
| `Build.MANUFACTURER == "Google"` and no OEM props | `PIXEL` (`0x01`) |
| `Build.MANUFACTURER == "Xiaomi"` or `ro.miui.ui.version.name` present | `HYPEROS` (`0x02`) |
| `Build.BRAND == "Nothing"` or `ro.nothing.build.*` present | `NOTHING_OS` (`0x03`) |
| `Build.MANUFACTURER == "samsung"` | `ONE_UI` (`0x04`) |
| anything else | `UNKNOWN` (`0xFF`) → fallback pack |

Detection is cached per process; `shell_id` from the peer overrides the local
guess for the **shared** confirmation UI.

## 3. Design tokens

| Token | Pixel | HyperOS | Nothing OS | OneUI | Fallback |
|---|---|---|---|---|---|
| Background | dynamic surface | `#0E0E10` | `#1B1B1D` | `#131316` | `#121212` |
| Accent | Monet primary | `#FF6900` | `#D71921` | `#42A5F5` | `#6750A4` |
| Surface style | tonal, soft shadow | liquid glass, blur 24dp | flat, 1px outline | elevated card | Material 3 default |
| Corner radius | 28dp | 32dp + highlight | 20dp | 26dp | 16dp |
| Headline font | Google Sans / Geist | MiSans | **NDot 57** | Samsung One | system sans |
| Body font | Roboto / Geist | MiSans | **Geist** | Samsung One | system sans |
| Meta font | Geist Mono | MiSans Mono | **Geist Mono** | system mono | monospace |
| Motion | Material expressive ease | spring + specular sweep | snap, 120 ms | overshoot spring | standard ease |
| Tap feedback | Material ripple | expanding glass ring | NDot pulse grid | circular ripple | ripple |

## 4. Shared confirmation screen

Both peers render the same layout, with tokens taken from the receiver's pack:

```
[ shell wordmark ]        ← NDot / MiSans / Google Sans / One
FILE  photo.jpg  2.4 MB
FROM  <sender name>
[ ACCEPT ]  [ DECLINE ]
```

Phase-specific flourishes:
- **NFC tap**: Pixel ripple from touch point, HyperOS glass droplet, NDot pulse,
  OneUI expanding circle.
- **Transferring**: thin progress line; Nothing OS uses a red segment on a dark
  track, HyperOS uses a blurred translucent bar.
- **Complete**: short animation then auto-dismiss.

## 5. Accessibility

- Text contrast must stay ≥ 4.5:1 in every pack; blur never reduces legibility.
- Reduced-motion setting disables specular sweeps and spring overshoot.
- Font packs ship with the app; no network fetch at runtime.
