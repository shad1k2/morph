package io.morph.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * In-app presentation of the same card: a dimmed layer with the drop card at
 * the top, so the look matches the system overlay exactly.
 */
@Composable
fun TransferOverlay(
    theme: ShellTheme,
    drop: DropUi,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDone: () -> Unit,
    onAction: () -> Unit = onDone,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .clickable(enabled = drop.phase == TransferPhase.DONE) { onDone() }
            .statusBarsPadding()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.fillMaxWidth()) {
            OverlayPopup(
                theme = theme,
                drop = drop,
                onAccept = onAccept,
                onDecline = onDecline,
                onDone = onDone,
                onAction = onAction,
            )
        }
    }
}
