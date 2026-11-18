package io.morph.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.morph.app.R
import io.morph.core.ShellId

/** Visual tokens for one shell. Never affects transport semantics. */
data class ShellTheme(
    val shell: ShellId,
    val name: String,
    val brand: String,
    val background: Color,
    val surface: Color,
    val border: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val dim: Color,
    val radius: Dp,
    val headline: FontFamily?,
    val body: FontFamily?,
    val glass: Boolean,
)

object ThemeRegistry {
    private val geist = FontFamily(R.font.geist_regular)
    private val geistMono = FontFamily(R.font.geist_mono_regular)
    private val ndot = FontFamily(R.font.ndot_57)

    fun get(shell: ShellId): ShellTheme = when (shell) {
        ShellId.PIXEL -> ShellTheme(
            shell, "Pixel", "Google", Color(0xFF111113), Color(0xFF1E1E21),
            Color(0xFF3A3A3F), Color(0xFFA8C7FA), Color(0xFF001D35),
            Color(0xFFE6E1E5), Color(0xFFA0A0A5), 28.dp, geist, geist, false
        )
        ShellId.HYPEROS -> ShellTheme(
            shell, "HyperOS", "MI", Color(0xFF0E0E10), Color(0x33262628),
            Color(0x66FFFFFF), Color(0xFFFF6900), Color(0xFF1A0F00),
            Color(0xFFFFFFFF), Color(0xFFB7B7BC), 32.dp, null, null, true
        )
        ShellId.NOTHING_OS -> ShellTheme(
            shell, "Nothing OS", "nothing", Color(0xFF1B1B1D), Color(0xFF242426),
            Color(0xFF3A3A3D), Color(0xFFD71921), Color(0xFFFFFFFF),
            Color(0xFFF2F2F2), Color(0xFFA3A3A6), 20.dp, ndot, geist, false
        )
        ShellId.ONE_UI -> ShellTheme(
            shell, "One UI", "SAMSUNG", Color(0xFF0F1216), Color(0xFF1C2027),
            Color(0xFF343A44), Color(0xFF7AA2FF), Color(0xFF00143B),
            Color(0xFFF2F5F9), Color(0xFF9AA3B2), 34.dp, null, null, false
        )
        ShellId.COLOROS -> ShellTheme(
            shell, "ColorOS", "OPPO", Color(0xFF0B0D0F), Color(0xFF181B1D),
            Color(0xFF2C3134), Color(0xFF00C853), Color(0xFF002910),
            Color(0xFFF2F5F3), Color(0xFF98A29D), 26.dp, null, null, false
        )
        ShellId.ORIGINOS -> ShellTheme(
            shell, "OriginOS", "vivo", Color(0xFF101215), Color(0x331A2230),
            Color(0x55FFFFFF), Color(0xFF5B8CFF), Color(0xFF001438),
            Color(0xFFF2F5FF), Color(0xFF9FA8BF), 30.dp, null, null, true
        )
        ShellId.HARMONYOS -> ShellTheme(
            shell, "HarmonyOS", "HUAWEI", Color(0xFF1A1A1C), Color(0xFF242428),
            Color(0xFF3C3C42), Color(0xFFFF4D4F), Color(0xFF2E0000),
            Color(0xFFF5F5F7), Color(0xFFA6A6AC), 18.dp, null, null, false
        )
        ShellId.UNKNOWN -> ShellTheme(
            shell, "AOSP", "MORPH", Color(0xFF121212), Color(0xFF1E1E1E),
            Color(0xFF373737), Color(0xFF6750A4), Color(0xFFFFFFFF),
            Color(0xFFE6E1E5), Color(0xFFA0A0A5), 16.dp, geistMono, geist, false
        )
    }

    val all: List<ShellTheme> = ShellId.entries.map(::get)
}
