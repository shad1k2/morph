package io.morph.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * One card, used both by the system overlay and by the in-app layer.
 * Everything it renders comes from [DropUi], i.e. from a real session.
 */
@Composable
fun OverlayPopup(
    theme: ShellTheme,
    drop: DropUi,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDone: () -> Unit,
    entrance: Boolean = true,
) {
    var visible by remember { mutableStateOf(!entrance) }
    LaunchedEffect(Unit) { visible = true }

    val shape = RoundedCornerShape(theme.radius)
    val peer = ThemeRegistry.get(drop.shell)
    val phase = drop.phase
    val incoming = drop.kind == DropKind.INCOMING

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .shellSurface(theme, shape, elevated = true)
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                MonoLabel(
                    when {
                        drop.kind == DropKind.READY -> "VISIBLE ON THIS WI-FI"
                        incoming && phase == TransferPhase.PROMPT -> "INCOMING DROP"
                        incoming && phase == TransferPhase.RUNNING -> "RECEIVING"
                        incoming -> "TRANSFER COMPLETE"
                        phase == TransferPhase.RUNNING -> "SENDING"
                        else -> "TRANSFER COMPLETE"
                    },
                    theme.accent,
                    size = 8,
                    tracking = 1.4f,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        drop.kind == DropKind.READY ->
                            if (theme.uppercaseHeadline) "DROP READY" else "Drop ready"
                        incoming && phase == TransferPhase.PROMPT ->
                            if (theme.uppercaseHeadline) "DROP READY" else "Drop ready"
                        incoming && phase == TransferPhase.RUNNING -> "In flight"
                        incoming -> if (drop.ok) "All yours" else "Failed"
                        phase == TransferPhase.RUNNING -> "In flight"
                        else -> if (drop.ok) "Delivered" else "Not delivered"
                    },
                    color = if (theme.cardStyle == CardStyle.FLAT && theme.shell != ShellId.NOTHING_OS) {
                        theme.accent
                    } else {
                        theme.text
                    },
                    fontFamily = theme.headline ?: FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    letterSpacing = theme.headlineTracking.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 70.dp),
                )

                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(peer.brandBg, RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (drop.kind) {
                            DropKind.READY -> "This device · ${peer.name}"
                            DropKind.INCOMING -> "${drop.peerName} · ${peer.name}"
                            DropKind.OUTGOING -> "to ${drop.peerName} · ${peer.name}"
                        },
                        color = theme.dim,
                        fontFamily = theme.body ?: FontFamily.Default,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(12.dp))
                if (drop.kind == DropKind.READY) {
                    ReadyRow(theme)
                } else {
                    OverlayFileRow(theme, drop)
                }

                if (drop.kind != DropKind.READY && phase != TransferPhase.PROMPT) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (phase == TransferPhase.DONE) "100%" else "${(drop.progress * 100).toInt()}%",
                            color = theme.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        MonoLabel(
                            if (phase == TransferPhase.DONE) "AES-256-GCM · VERIFIED" else "AES-256-GCM",
                            theme.dim,
                            size = 8,
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    OverlayProgress(theme, if (phase == TransferPhase.DONE) 1f else drop.progress)
                }

                drop.message?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        message,
                        color = theme.dim,
                        fontFamily = theme.body ?: FontFamily.Default,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }

                Spacer(Modifier.height(14.dp))
                when {
                    drop.kind == DropKind.READY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryAction("CLOSE", theme, Modifier.weight(1f)) { onDone() }
                    }

                    phase == TransferPhase.PROMPT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryAction("ACCEPT", theme, Modifier.weight(1f)) { onAccept() }
                        SecondaryAction("DECLINE", theme, Modifier.weight(1f)) { onDecline() }
                    }

                    phase == TransferPhase.RUNNING -> SecondaryAction(
                        "CANCEL",
                        theme,
                        Modifier.fillMaxWidth(),
                    ) { onDecline() }

                    else -> PrimaryAction("DONE", theme, Modifier.fillMaxWidth()) { onDone() }
                }
            }

            BrandOval(
                theme,
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-10).dp, y = (-11).dp),
            )
        }
    }
}

@Composable
private fun ReadyRow(theme: ShellTheme) {
    val shape = RoundedCornerShape(theme.radius * 0.5f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (theme.isDark) theme.surfaceAlt.copy(alpha = 0.55f) else theme.surfaceAlt)
            .border(1.dp, theme.border.copy(alpha = 0.6f), shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(if (theme.shell == ShellId.NOTHING_OS) 3.dp else 11.dp))
                .background(theme.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "↑↓",
                color = theme.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Listening for drops",
                color = theme.text,
                fontFamily = theme.body ?: FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
            Text(
                "Ask the other phone to tap this one",
                color = theme.dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun OverlayFileRow(theme: ShellTheme, drop: DropUi) {
    val shape = RoundedCornerShape(theme.radius * 0.5f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (theme.isDark) theme.surfaceAlt.copy(alpha = 0.55f) else theme.surfaceAlt)
            .border(1.dp, theme.border.copy(alpha = 0.6f), shape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(if (theme.shell == ShellId.NOTHING_OS) 3.dp else 11.dp))
                .background(
                    Brush.linearGradient(
                        listOf(theme.accent.copy(alpha = 0.9f), theme.accentAlt.copy(alpha = 0.75f))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                fileBadge(drop.fileName),
                color = if (theme.shell == ShellId.NOTHING_OS) Color(0xFF101012) else theme.onAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                drop.fileName,
                color = theme.text,
                fontFamily = theme.body ?: FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                drop.sizeLabel,
                color = theme.dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
        }
        Text(
            when (drop.phase) {
                TransferPhase.PROMPT -> "NEW"
                TransferPhase.RUNNING -> "${(drop.progress * 100).toInt()}%"
                TransferPhase.DONE -> "OK"
            },
            color = theme.dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun OverlayProgress(theme: ShellTheme, progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(theme.surfaceAlt)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(theme.accent, theme.accentAlt)))
        )
    }
}
