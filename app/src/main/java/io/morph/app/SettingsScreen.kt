package io.morph.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.morph.core.ShellId

@Composable
fun SettingsScreen(
    theme: ShellTheme,
    detected: ShellId,
    shell: ShellId,
    onSelectShell: (ShellId) -> Unit,
    onBack: () -> Unit,
    overlayEnabled: Boolean,
    onGrantOverlay: () -> Unit,
    onTestOverlay: () -> Unit,
    engineStatus: String,
    engineRunning: Boolean,
    localPort: Int,
    selfTest: String?,
    onSelfTest: () -> Unit,
    onRestartDiscovery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLabel(
                "← BACK",
                theme.dim,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, theme.border, RoundedCornerShape(50))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                size = 9,
                tracking = 0.8f,
            )
            Spacer(Modifier.weight(1f))
            BrandOval(theme, compact = true)
        }

        Spacer(Modifier.height(26.dp))
        Text(
            if (theme.uppercaseHeadline) "SETTINGS" else "Settings",
            color = theme.text,
            fontFamily = theme.headline ?: FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            letterSpacing = theme.headlineTracking.sp,
        )
        Spacer(Modifier.height(18.dp))

        // Detected shell
        Column(
            Modifier
                .fillMaxWidth()
                .shellSurface(theme, RoundedCornerShape(theme.radius))
                .padding(18.dp)
        ) {
            MonoLabel("DETECTED SHELL", theme.dim, size = 8, tracking = 1.3f)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    theme.name,
                    color = theme.text,
                    fontFamily = theme.headline ?: FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    letterSpacing = theme.headlineTracking.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "0x" + "%02X".format(theme.shell.code),
                    color = theme.accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (theme.shell == detected) {
                    "Matches this device. The pop-up is drawn in this style."
                } else {
                    "Preview override. The real pop-up still uses ${ThemeRegistry.get(detected).name}."
                },
                color = theme.dim,
                fontFamily = theme.body ?: FontFamily.Default,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(22.dp))
        MonoLabel("PREVIEW AS", theme.dim, size = 9, tracking = 1.4f)
        Spacer(Modifier.height(10.dp))

        val packs = ThemeRegistry.all
        packs.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { pack ->
                    ThemeTile(pack, pack.shell == theme.shell, Modifier.weight(1f)) {
                        onSelectShell(pack.shell)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(18.dp))
        MonoLabel("TRANSPORT", theme.dim, size = 9, tracking = 1.4f)
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .shellSurface(theme, RoundedCornerShape(theme.radius))
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(
                            if (engineRunning) theme.accent else theme.dim,
                            RoundedCornerShape(50),
                        )
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    if (engineRunning) "Receiver running" else "Receiver stopped",
                    color = theme.text,
                    fontFamily = theme.body ?: FontFamily.Default,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                MonoLabel(
                    "TCP $localPort",
                    theme.dim,
                    size = 9,
                    tracking = 0.8f,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                engineStatus,
                color = theme.dim,
                fontFamily = theme.body ?: FontFamily.Default,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            Spacer(Modifier.height(12.dp))
            Hairline(theme)
            Spacer(Modifier.height(12.dp))

            ProtocolLine("Discovery", "mDNS · _morph._tcp", theme)
            ProtocolLine("Data", "TCP $localPort", theme)
            ProtocolLine("Handshake", "X25519 → HKDF-SHA256", theme)
            ProtocolLine("Payload", "AES-256-GCM", theme)
            ProtocolLine("Storage", "Downloads/MORPH", theme)

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryAction("SELF-TEST", theme, Modifier.weight(1f)) { onSelfTest() }
                SecondaryAction("RESCAN", theme, Modifier.weight(1f)) { onRestartDiscovery() }
            }

            selfTest?.let { result ->
                Spacer(Modifier.height(10.dp))
                Text(
                    result,
                    color = if (result.contains("OK")) theme.accent else theme.dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Self-test runs a full encrypted transfer over loopback TCP on this phone — " +
                    "no second device needed.",
                color = theme.dim,
                fontFamily = theme.body ?: FontFamily.Default,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }

        Spacer(Modifier.height(18.dp))
        MonoLabel("POP-UP & QUICK SETTINGS", theme.dim, size = 9, tracking = 1.4f)
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .shellSurface(theme, RoundedCornerShape(theme.radius))
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pop-up over apps",
                        color = theme.text,
                        fontFamily = theme.body ?: FontFamily.Default,
                        fontSize = 13.sp,
                    )
                    Text(
                        if (overlayEnabled) {
                            "Allowed. The drop card can appear above other apps."
                        } else {
                            "Not allowed yet. Without it the tile opens MORPH instead."
                        },
                        color = theme.dim,
                        fontFamily = theme.body ?: FontFamily.Default,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                MonoLabel(
                    if (overlayEnabled) "ON" else "OFF",
                    if (overlayEnabled) theme.accent else theme.dim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(
                            1.dp,
                            if (overlayEnabled) theme.accent.copy(alpha = 0.45f) else theme.border,
                            RoundedCornerShape(50),
                        )
                        .clickable { if (!overlayEnabled) onGrantOverlay() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    size = 9,
                    tracking = 1f,
                )
            }

            Spacer(Modifier.height(12.dp))
            Hairline(theme)
            Spacer(Modifier.height(12.dp))

            Text(
                "Add the tile: swipe down twice → pencil → drag \"MORPH drop\" into the shade. " +
                    "Tap it to drop the pop-up over whatever you are doing.",
                color = theme.dim,
                fontFamily = theme.body ?: FontFamily.Default,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            Spacer(Modifier.height(14.dp))
            PrimaryAction(
                if (overlayEnabled) "TEST POP-UP" else "ALLOW POP-UP",
                theme,
                Modifier.fillMaxWidth(),
            ) {
                if (overlayEnabled) onTestOverlay() else onGrantOverlay()
            }
        }

        Spacer(Modifier.height(18.dp))
        MonoLabel("PROTOCOL", theme.dim, size = 9, tracking = 1.4f)
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .shellSurface(theme, RoundedCornerShape(theme.radius))
                .padding(18.dp)
        ) {
            ProtocolLine("Spec", "DROP/1 · v0.3", theme)
            ProtocolLine("Handshake", "X25519 → HKDF-SHA256", theme)
            ProtocolLine("Payload", "AES-256-GCM", theme)
            ProtocolLine("Relay", "none · direct only", theme)
            Spacer(Modifier.height(4.dp))
            Text(
                "No accounts, no cloud, no telemetry.",
                color = theme.dim,
                fontFamily = theme.body ?: FontFamily.Default,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(22.dp))
        MonoLabel("MORPH APP v0.3 · WORKING BUILD", theme.dim, size = 8, tracking = 1.1f)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ThemeTile(
    pack: ShellTheme,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(pack.radius * 0.55f)
    Column(
        modifier
            .clip(shape)
            .background(pack.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) pack.accent else pack.border,
                shape = shape,
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(pack.background.first(), pack.accent, pack.surfaceAlt).forEach { color ->
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color)
                        .border(1.dp, pack.border, RoundedCornerShape(50))
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            pack.name,
            color = pack.text,
            fontFamily = pack.headline ?: FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = if (pack.shell == ShellId.NOTHING_OS) 0.6.sp else 0.sp,
        )
        Text(
            "0x" + "%02X".format(pack.shell.code),
            color = pack.dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun ProtocolLine(label: String, value: String, theme: ShellTheme) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            color = theme.dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            color = theme.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}
