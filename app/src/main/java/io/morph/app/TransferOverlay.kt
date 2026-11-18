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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.morph.core.ShellId

/**
 * AirDrop-style card that hangs at the top of the screen over whatever the user
 * is doing. In the real build this lives in an overlay window; here it is an
 * in-app layer so the design can be evaluated without the overlay permission.
 */
@Composable
fun TransferOverlay(
    theme: ShellTheme,
    transfer: TransferUi,
    phase: TransferPhase,
    progress: Float,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDone: () -> Unit,
) {
    val shape = RoundedCornerShape(theme.radius)
    val peer = ThemeRegistry.get(transfer.device.shell)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(enabled = phase == TransferPhase.DONE) { onDone() }
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .shellSurface(theme, shape, elevated = true)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        MonoLabel(
                            when (phase) {
                                TransferPhase.PROMPT -> "INCOMING DROP"
                                TransferPhase.RUNNING -> if (transfer is TransferUi.Incoming) "RECEIVING" else "SENDING"
                                TransferPhase.DONE -> "TRANSFER COMPLETE"
                            },
                            theme.accent,
                            size = 8,
                            tracking = 1.4f,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (phase) {
                                TransferPhase.PROMPT -> if (theme.uppercaseHeadline) "DROP READY" else "Drop ready"
                                TransferPhase.RUNNING -> "In flight"
                                TransferPhase.DONE -> "All yours"
                            },
                            color = if (theme.cardStyle == CardStyle.FLAT && theme.shell != ShellId.NOTHING_OS) theme.accent else theme.text,
                            fontFamily = theme.headline ?: FontFamily.Default,
                            fontWeight = FontWeight.Bold,
                            fontSize = 25.sp,
                            letterSpacing = theme.headlineTracking.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 76.dp),
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(peer.brandBg, RoundedCornerShape(50))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${transfer.device.name} · ${peer.name}",
                        color = theme.dim,
                        fontFamily = theme.body ?: FontFamily.Default,
                        fontSize = 11.sp,
                    )
                }

                Spacer(Modifier.height(16.dp))
                FileCard(theme, transfer)

                if (phase != TransferPhase.PROMPT) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (phase == TransferPhase.DONE) "100%" else "${(progress * 100).toInt()}%",
                            color = theme.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        MonoLabel(
                            if (phase == TransferPhase.DONE) "AES-256-GCM · VERIFIED" else "AES-256-GCM",
                            theme.dim,
                            size = 8,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(theme, if (phase == TransferPhase.DONE) 1f else progress)
                }

                Spacer(Modifier.height(18.dp))

                when (phase) {
                    TransferPhase.PROMPT -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryAction("ACCEPT", theme, Modifier.weight(1f)) { onAccept() }
                        SecondaryAction("DECLINE", theme, Modifier.weight(1f)) { onDecline() }
                    }

                    TransferPhase.RUNNING -> SecondaryAction(
                        "CANCEL",
                        theme,
                        Modifier.fillMaxWidth(),
                    ) { onDecline() }

                    TransferPhase.DONE -> PrimaryAction(
                        "DONE",
                        theme,
                        Modifier.fillMaxWidth(),
                    ) { onDone() }
                }
            }

            BrandOval(
                theme,
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-12).dp, y = (-12).dp),
            )
        }
    }
}

@Composable
private fun FileCard(theme: ShellTheme, transfer: TransferUi) {
    val shape = RoundedCornerShape(theme.radius * 0.5f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (theme.isDark) theme.surfaceAlt.copy(alpha = 0.55f) else theme.surfaceAlt)
            .border(1.dp, theme.border.copy(alpha = 0.6f), shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(if (theme.shell == ShellId.NOTHING_OS) 3.dp else 12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            theme.accent.copy(alpha = 0.9f),
                            theme.accentAlt.copy(alpha = 0.75f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "JPG",
                color = if (theme.shell == ShellId.NOTHING_OS) Color(0xFF101012) else theme.onAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                transfer.fileName,
                color = theme.text,
                fontFamily = theme.body ?: FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (transfer) {
                    is TransferUi.Incoming -> "${transfer.totalFiles} files · ${transfer.sizeLabel}"
                    is TransferUi.Outgoing -> transfer.sizeLabel
                },
                color = theme.dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Text(
            "JPG",
            color = theme.dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun ProgressBar(theme: ShellTheme, progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(50))
            .background(theme.surfaceAlt)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(listOf(theme.accent, theme.accentAlt))
                )
        )
    }
}
