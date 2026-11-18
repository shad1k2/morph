package io.morph.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.morph.core.ShellId

/** How surfaces are rendered: flat, glass, light card or layered stack. */
enum class CardStyle { FLAT, GLASS, LIGHT, LAYERED }

/** Button silhouette — shells differ noticeably here. */
enum class ButtonStyle { PILL, ROUNDED, SQUARE, GRADIENT }

/**
 * Visual tokens for one shell. Never affects transport semantics.
 * Only tokens live here; layout quirks are handled by the composables.
 */
data class ShellTheme(
    val shell: ShellId,
    val name: String,
    val isDark: Boolean,
    val background: List<Color>,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val accent: Color,
    val accentAlt: Color,
    val onAccent: Color,
    val text: Color,
    val dim: Color,
    val radius: Dp,
    val cardStyle: CardStyle,
    val buttonStyle: ButtonStyle,
    val headline: FontFamily?,
    val body: FontFamily?,
    val headlineTracking: Float,
    val uppercaseHeadline: Boolean,
    val brandLabel: String,
    val brandBg: Color,
    val brandInk: Color,
)

object ThemeRegistry {
    private val geist = FontFamily(Font(R.font.geist_regular))
    private val geistMono = FontFamily(Font(R.font.geist_mono_regular))
    private val ndot = FontFamily(Font(R.font.ndot_57))

    fun get(shell: ShellId): ShellTheme = when (shell) {
        // Material 3 Expressive: tonal surface, generous radius, no border.
        ShellId.PIXEL -> ShellTheme(
            shell = shell,
            name = "Pixel",
            isDark = true,
            background = listOf(Color(0xFF0D0D10), Color(0xFF191821)),
            surface = Color(0xFF1E1B22),
            surfaceAlt = Color(0xFF2A2731),
            border = Color(0x00000000),
            accent = Color(0xFFD0BCFF),
            accentAlt = Color(0xFFB69DF8),
            onAccent = Color(0xFF241A3D),
            text = Color(0xFFE9E1EC),
            dim = Color(0xFFA9A2AD),
            radius = 30.dp,
            cardStyle = CardStyle.FLAT,
            buttonStyle = ButtonStyle.PILL,
            headline = geist,
            body = geist,
            headlineTracking = (-0.5f),
            uppercaseHeadline = false,
            brandLabel = "Google",
            brandBg = Color(0xFFF1F3F4),
            brandInk = Color(0xFF1F1F1F),
        )

        // Liquid glass: translucent panel, specular sheen, orange accent.
        ShellId.HYPEROS -> ShellTheme(
            shell = shell,
            name = "HyperOS",
            isDark = true,
            background = listOf(Color(0xFF050508), Color(0xFF12101A), Color(0xFF201007)),
            surface = Color(0x1FFFFFFF),
            surfaceAlt = Color(0x14FFFFFF),
            border = Color(0x4DFFFFFF),
            accent = Color(0xFFFF6900),
            accentAlt = Color(0xFFFFA24D),
            onAccent = Color(0xFF1A0C00),
            text = Color(0xFFFFFFFF),
            dim = Color(0xB3FFFFFF),
            radius = 34.dp,
            cardStyle = CardStyle.GLASS,
            buttonStyle = ButtonStyle.PILL,
            headline = geist,
            body = geist,
            headlineTracking = (0f),
            uppercaseHeadline = false,
            brandLabel = "Xiaomi",
            brandBg = Color(0xFFFF6900),
            brandInk = Color.White,
        )

        // Nothing OS: flat monochrome, hairline outline, NDot headline, red dot.
        ShellId.NOTHING_OS -> ShellTheme(
            shell = shell,
            name = "Nothing OS",
            isDark = true,
            background = listOf(Color(0xFF1B1B1D), Color(0xFF121214)),
            surface = Color(0xFF202023),
            surfaceAlt = Color(0xFF2B2B2E),
            border = Color(0x2BFFFFFF),
            accent = Color(0xFFD71921),
            accentAlt = Color(0xFFF2F2F2),
            onAccent = Color(0xFFFFFFFF),
            text = Color(0xFFF2F2F2),
            dim = Color(0xFF8A8A8D),
            radius = 18.dp,
            cardStyle = CardStyle.FLAT,
            buttonStyle = ButtonStyle.SQUARE,
            headline = ndot,
            body = geist,
            headlineTracking = (1.2f),
            uppercaseHeadline = true,
            brandLabel = "nothing",
            brandBg = Color(0xFF141416),
            brandInk = Color(0xFFF2F2F2),
        )

        // One UI 7: light, soft layered pills, thin dividers, blue accent.
        ShellId.ONE_UI -> ShellTheme(
            shell = shell,
            name = "One UI",
            isDark = false,
            background = listOf(Color(0xFFF5F7FB), Color(0xFFE7EDF7)),
            surface = Color(0xFFFFFFFF),
            surfaceAlt = Color(0xFFF0F3F9),
            border = Color(0x14000000),
            accent = Color(0xFF1B6EF3),
            accentAlt = Color(0xFF4D8DFF),
            onAccent = Color(0xFFFFFFFF),
            text = Color(0xFF10131A),
            dim = Color(0xFF6B7280),
            radius = 34.dp,
            cardStyle = CardStyle.LIGHT,
            buttonStyle = ButtonStyle.PILL,
            headline = geist,
            body = geist,
            headlineTracking = (-0.6f),
            uppercaseHeadline = false,
            brandLabel = "SAMSUNG",
            brandBg = Color(0xFF1428A0),
            brandInk = Color.White,
        )

        // ColorOS 15: light natural-light sheet, soft green accent.
        ShellId.COLOROS -> ShellTheme(
            shell = shell,
            name = "ColorOS",
            isDark = false,
            background = listOf(Color(0xFFF2FAF5), Color(0xFFE2F2EA)),
            surface = Color(0xFFFFFFFF),
            surfaceAlt = Color(0xFFEFF8F2),
            border = Color(0x12000000),
            accent = Color(0xFF00A651),
            accentAlt = Color(0xFF44D08A),
            onAccent = Color(0xFF00230F),
            text = Color(0xFF0E1512),
            dim = Color(0xFF67766E),
            radius = 24.dp,
            cardStyle = CardStyle.LIGHT,
            buttonStyle = ButtonStyle.ROUNDED,
            headline = geist,
            body = geist,
            headlineTracking = (-0.2f),
            uppercaseHeadline = false,
            brandLabel = "OPPO",
            brandBg = Color(0xFF00A651),
            brandInk = Color.White,
        )

        // OriginOS 5: frosted gradient widget, blue-violet, gentle motion.
        ShellId.ORIGINOS -> ShellTheme(
            shell = shell,
            name = "OriginOS",
            isDark = true,
            background = listOf(Color(0xFF0A1020), Color(0xFF1B1443), Color(0xFF0A1B33)),
            surface = Color(0x2EFFFFFF),
            surfaceAlt = Color(0x1AFFFFFF),
            border = Color(0x40FFFFFF),
            accent = Color(0xFF5B8CFF),
            accentAlt = Color(0xFF9B7BFF),
            onAccent = Color(0xFF04122E),
            text = Color(0xFFF3F5FF),
            dim = Color(0xBFD5DCF5),
            radius = 28.dp,
            cardStyle = CardStyle.GLASS,
            buttonStyle = ButtonStyle.GRADIENT,
            headline = geist,
            body = geist,
            headlineTracking = (0f),
            uppercaseHeadline = false,
            brandLabel = "vivo",
            brandBg = Color(0xFF2D6BFF),
            brandInk = Color.White,
        )

        // HarmonyOS NEXT: starry gray base, layered translucent cards, red accent.
        ShellId.HARMONYOS -> ShellTheme(
            shell = shell,
            name = "HarmonyOS",
            isDark = true,
            background = listOf(Color(0xFF131317), Color(0xFF1F1F24), Color(0xFF101014)),
            surface = Color(0xFF26262B),
            surfaceAlt = Color(0xFF313137),
            border = Color(0x33FFFFFF),
            accent = Color(0xFFFF4D4F),
            accentAlt = Color(0xFFFF8A8C),
            onAccent = Color(0xFF2B0000),
            text = Color(0xFFF5F5F7),
            dim = Color(0xFFA1A1A8),
            radius = 18.dp,
            cardStyle = CardStyle.LAYERED,
            buttonStyle = ButtonStyle.ROUNDED,
            headline = geist,
            body = geist,
            headlineTracking = (0.2f),
            uppercaseHeadline = false,
            brandLabel = "HUAWEI",
            brandBg = Color(0xFFC90016),
            brandInk = Color.White,
        )

        // Fallback: AOSP / Material defaults.
        ShellId.UNKNOWN -> ShellTheme(
            shell = shell,
            name = "AOSP",
            isDark = true,
            background = listOf(Color(0xFF111113), Color(0xFF1A1A1D)),
            surface = Color(0xFF1E1E21),
            surfaceAlt = Color(0xFF2A2A2E),
            border = Color(0x1FFFFFFF),
            accent = Color(0xFF6750A4),
            accentAlt = Color(0xFF8B72D6),
            onAccent = Color(0xFFFFFFFF),
            text = Color(0xFFE6E1E5),
            dim = Color(0xFFA0A0A5),
            radius = 20.dp,
            cardStyle = CardStyle.FLAT,
            buttonStyle = ButtonStyle.ROUNDED,
            headline = geistMono,
            body = geist,
            headlineTracking = (0f),
            uppercaseHeadline = true,
            brandLabel = "MORPH",
            brandBg = Color(0xFF6750A4),
            brandInk = Color.White,
        )
    }

    val all: List<ShellTheme> = ShellId.entries.map(::get)
}
