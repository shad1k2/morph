package io.morph.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import io.morph.core.ShellId
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ASK_OVERLAY = "io.morph.app.extra.ASK_OVERLAY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        setContent { MorphApp() }

        if (intent?.getBooleanExtra(EXTRA_ASK_OVERLAY, false) == true) {
            openOverlaySettings(this)
        }
    }
}

private enum class Screen { HOME, SETTINGS }

@Composable
private fun MorphApp() {
    val context = LocalContext.current
    val detected: ShellId = remember { ShellDetector.detect() }
    var shell by remember { mutableStateOf(detected) }
    val theme = ThemeRegistry.get(shell)

    var screen by remember { mutableStateOf(Screen.HOME) }
    var transfer by remember { mutableStateOf<TransferUi?>(null) }
    var phase by remember { mutableStateOf(TransferPhase.PROMPT) }
    var progress by remember { mutableStateOf(0f) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestOverlay: () -> Unit = {
        openOverlaySettings(context)
    }
    val testOverlay: () -> Unit = {
        if (Settings.canDrawOverlays(context)) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW)
            )
        } else {
            openOverlaySettings(context)
        }
    }

    val activity = context as? Activity
    SideEffect {
        activity?.let {
            val controller = WindowCompat.getInsetsController(it.window, it.window.decorView)
            controller.isAppearanceLightStatusBars = !theme.isDark
            controller.isAppearanceLightNavigationBars = !theme.isDark
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = queryDisplayName(context, uri)
            val target = demoDevices.firstOrNull() ?: return@rememberLauncherForActivityResult
            progress = 0f
            phase = TransferPhase.RUNNING
            transfer = TransferUi.Outgoing(target, name, "2.4 MB")
        }
    }

    // Fake progress until the DROP/1 data channel is wired.
    LaunchedEffect(transfer, phase) {
        if (phase == TransferPhase.RUNNING) {
            progress = 0f
            repeat(100) { step ->
                delay(22)
                progress = (step + 1) / 100f
            }
            phase = TransferPhase.DONE
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(theme.background))) {
        GlassBackdrop(theme)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    theme = theme,
                    devices = demoDevices,
                    onDeviceTap = { device ->
                        progress = 0f
                        phase = TransferPhase.PROMPT
                        transfer = TransferUi.Incoming(
                            device = device,
                            fileName = "photo.jpg",
                            totalFiles = 2,
                            sizeLabel = "3.8 MB",
                        )
                    },
                    onSendFiles = { picker.launch(arrayOf("*/*")) },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    overlayEnabled = overlayGranted,
                    onEnableOverlay = requestOverlay,
                    onTestOverlay = testOverlay,
                )

                Screen.SETTINGS -> SettingsScreen(
                    theme = theme,
                    detected = detected,
                    onSelectShell = { shell = it },
                    onBack = { screen = Screen.HOME },
                    overlayEnabled = overlayGranted,
                    onGrantOverlay = requestOverlay,
                    onTestOverlay = testOverlay,
                )
            }
        }

        AnimatedVisibility(
            visible = transfer != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        ) {
            transfer?.let { current ->
                TransferOverlay(
                    theme = theme,
                    transfer = current,
                    phase = phase,
                    progress = progress,
                    onAccept = {
                        progress = 0f
                        phase = TransferPhase.RUNNING
                    },
                    onDecline = {
                        transfer = null
                        phase = TransferPhase.PROMPT
                        progress = 0f
                    },
                    onDone = {
                        transfer = null
                        phase = TransferPhase.PROMPT
                        progress = 0f
                    },
                )
            }
        }
    }
}

/** Soft coloured light under translucent packs so glass reads as glass. */
@Composable
private fun BoxScope.GlassBackdrop(theme: ShellTheme) {
    if (theme.cardStyle != CardStyle.GLASS) return

    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(x = (-110).dp, y = 30.dp)
            .size(300.dp)
            .background(
                Brush.radialGradient(
                    listOf(theme.accent.copy(alpha = 0.26f), Color.Transparent)
                ),
                RoundedCornerShape(50),
            )
    )
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 120.dp, y = (-140).dp)
            .size(340.dp)
            .background(
                Brush.radialGradient(
                    listOf(theme.accentAlt.copy(alpha = 0.22f), Color.Transparent)
                ),
                RoundedCornerShape(50),
            )
    )
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .offset(x = 40.dp, y = 160.dp)
            .width(160.dp)
            .height(160.dp)
            .background(
                Brush.radialGradient(
                    listOf(theme.brandBg.copy(alpha = 0.16f), Color.Transparent)
                ),
                RoundedCornerShape(50),
            )
    )
}

private fun queryDisplayName(context: Context, uri: Uri): String = try {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        ?: "file"
} catch (t: Throwable) {
    "file"
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
