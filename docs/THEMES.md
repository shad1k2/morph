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
| `Build.MANUFACTURER == "OPPO"` / `"OnePlus"` | `COLOROS` (`0x05`) |
| `Build.MANUFACTURER == "vivo"` | `ORIGINOS` (`0x06`) |
| `Build.MANUFACTURER == "HUAWEI"` | `HARMONYOS` (`0x07`) |
| anything else | `UNKNOWN` (`0xFF`) → fallback pack |

Detection is cached per process; `shell_id` from the peer overrides the local
guess for the **shared** confirmation UI.

## 3. Design tokens

| Token | Pixel | HyperOS | One UI | ColorOS | OriginOS | HarmonyOS | Nothing OS |
|---|---|---|---|---|---|---|---|
| Background | dynamic surface | `#0E0E10` | `#0F1216` | `#0B0D0F` | `#101215` | `#1A1A1C` (starry gray) | `#1B1B1D` |
| Accent | Monet primary | `#FF6900` | `#1428A0` | `#00A651` | `#2D6BFF` | `#C90016` | `#D71921` |
| Surface | tonal, soft shadow | glass, blur 24dp | pill cards, blur | natural light, texture | frosted gradient | layered translucent | flat, 1px outline |
| Radius | 28dp | 32dp + highlight | 34dp pill | 26dp | 30dp | 18dp | 20dp |
| Headline | Google Sans / Geist | MiSans | Samsung One | OPPO Sans | vivo Sans | HarmonyOS Sans | **NDot 57** |
| Body | Roboto / Geist | MiSans | Samsung One | OPPO Sans | vivo Sans | HarmonyOS Sans | **Geist** |
| Meta | Geist Mono | MiSans Mono | system mono | system mono | system mono | system mono | **Geist Mono** |
| Motion | expressive ease | specular sweep | soft layered | light/shadow flow | gentle drift | precise fade | snap, 120 ms |

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

## 5. Research notes (2026)

- **One UI 7** (Samsung): layered pill-shaped cards, soft shadows, blurred panels,
  brighter dimensional icons — «curves and circles» as the official design page puts it.
- **HyperOS 2** (Xiaomi): liquid glass — transparent panels, blur and specular
  highlights, especially in Control Center and Gallery.
- **ColorOS 15** (OPPO): natural light and shadow mapping, subtle textures,
  Aurora/Tidal rendering engine, cross-device visual language.
- **OriginOS 5** (vivo): soft color gradients, frosted glass, gentle motion,
  enhanced vivo Sans typeface, layered lock-screen widgets.
- **HarmonyOS NEXT** (Huawei): starry-gray base instead of pure black, layered
  translucent surfaces, unified multi-language typeface, precise fade motion.
- **Nothing OS 5**: NDot dot-matrix accent, Geist as the new system typeface,
  `#1B1B1D` + `#D71921` palette, intentionally restrained dot-matrix usage.

## 6. Accessibility

- Text contrast must stay ≥ 4.5:1 in every pack; blur never reduces legibility.
- Reduced-motion setting disables specular sweeps and spring overshoot.
- Font packs ship with the app; no network fetch at runtime.
