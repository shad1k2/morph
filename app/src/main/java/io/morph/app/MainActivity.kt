package io.morph.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.morph.core.ShellId

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ASK_OVERLAY = "io.morph.app.extra.ASK_OVERLAY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        setContent { MorphRoot() }

        if (intent?.getBooleanExtra(EXTRA_ASK_OVERLAY, false) == true) {
            openOverlaySettings(this)
        }
        startReceiving(this)
    }
}

private enum class Screen { HOME, SETTINGS }

@Composable
private fun MorphRoot() {
    val context = LocalContext.current
    val detected: ShellId = remember { ShellDetector.detect() }
    var shell by remember { mutableStateOf(ThemePrefs.selected(context)) }
    val theme = ThemeRegistry.get(shell)

    var screen by remember { mutableStateOf(Screen.HOME) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var pendingPeer by remember { mutableStateOf<Peer?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                // The in-app card takes over; drop the system window if it is up.
                runCatching {
                    context.startService(
                        Intent(context, OverlayService::class.java)
                            .setAction(OverlayService.ACTION_HIDE)
                    )
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The receive side stays alive while the user is in other apps.
    LaunchedEffect(Unit) {
        MorphEngine.start(context)
        runCatching { startReceiving(context) }
    }

    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val requestOverlay: () -> Unit = { openOverlaySettings(context) }
    val testOverlay: () -> Unit = {
        if (Settings.canDrawOverlays(context)) {
            MorphEngine.start(context)
            MorphEngine.showReady()
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

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val peer = pendingPeer
        if (uri != null && peer != null) {
            MorphEngine.send(context, peer, uri)
        }
        pendingPeer = null
    }

    val chooseFileFor: (Peer) -> Unit = { peer ->
        pendingPeer = peer
        picker.launch(arrayOf("*/*"))
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
                    peers = MorphEngine.peers,
                    status = MorphEngine.status,
                    running = MorphEngine.running,
                    onPeerTap = chooseFileFor,
                    onSendFiles = {
                        val peer = MorphEngine.peers.firstOrNull()
                        if (peer != null) {
                            chooseFileFor(peer)
                        } else {
                            MorphEngine.note("No phones nearby. Open MORPH on the other phone and keep both on the same Wi-Fi.")
                        }
                    },
                    onRefresh = { MorphEngine.refreshDiscovery() },
                    onReceive = {
                        startReceiving(context)
                        MorphEngine.showReady()
                    },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    overlayEnabled = overlayGranted,
                    onEnableOverlay = requestOverlay,
                    onTestOverlay = testOverlay,
                )

                Screen.SETTINGS -> SettingsScreen(
                    theme = theme,
                    detected = detected,
                    shell = shell,
                    onSelectShell = {
                        shell = it
                        ThemePrefs.save(context, it)
                    },
                    onBack = { screen = Screen.HOME },
                    overlayEnabled = overlayGranted,
                    onGrantOverlay = requestOverlay,
                    onTestOverlay = testOverlay,
                    engineStatus = MorphEngine.status,
                    engineRunning = MorphEngine.running,
                    localPort = MorphEngine.port,
                    selfTest = MorphEngine.selfTest,
                    onSelfTest = { MorphEngine.runSelfTest(context) },
                    onRestartDiscovery = {
                        startReceiving(context)
                        MorphEngine.refreshDiscovery()
                    },
                )
            }
        }

        val drop = MorphEngine.drop
        AnimatedVisibility(
            visible = drop != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        ) {
            drop?.let { current ->
                TransferOverlay(
                    theme = theme,
                    drop = current,
                    onAccept = { MorphEngine.acceptIncoming() },
                    onDecline = { MorphEngine.declineIncoming() },
                    onDone = { MorphEngine.dismiss() },
                )
            }
        }
    }
}

private fun startReceiving(context: Context) {
    runCatching {
        ContextCompat.startForegroundService(
            context,
            Intent(context, MorphService::class.java),
        )
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
