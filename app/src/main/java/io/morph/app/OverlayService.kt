package io.morph.app

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay

/**
 * Shows the DROP pop-up in a real overlay window, above whatever app the user
 * is in. Triggered from the Quick Settings tile or from Settings → Test pop-up.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "io.morph.app.action.SHOW_DROP"
        const val ACTION_HIDE = "io.morph.app.action.HIDE_DROP"
    }

    private var windowManager: WindowManager? = null
    private var root: ComposeView? = null
    private var owners: OverlayOwners? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> dismiss()
            else -> {
                if (Settings.canDrawOverlays(this)) show() else stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun show() {
        if (root != null) return

        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = manager

        val overlayOwners = OverlayOwners().also { it.start() }
        owners = overlayOwners

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(overlayOwners)
            setViewTreeSavedStateRegistryOwner(overlayOwners)
            setViewTreeViewModelStoreOwner(overlayOwners)
            setContent {
                val theme = ThemeRegistry.get(ShellDetector.detect())
                val device = demoDevices.first()
                var phase by remember { mutableStateOf(TransferPhase.PROMPT) }
                var progress by remember { mutableStateOf(0f) }

                LaunchedEffect(phase) {
                    when (phase) {
                        TransferPhase.PROMPT -> {
                            // AirDrop-like: the card does not stay forever.
                            delay(15_000)
                            dismiss()
                        }

                        TransferPhase.RUNNING -> {
                            repeat(100) { step ->
                                delay(20)
                                progress = (step + 1) / 100f
                            }
                            phase = TransferPhase.DONE
                            delay(1500)
                            dismiss()
                        }

                        TransferPhase.DONE -> Unit
                    }
                }

                OverlayPopup(
                    theme = theme,
                    device = device,
                    phase = phase,
                    progress = progress,
                    onAccept = {
                        progress = 0f
                        phase = TransferPhase.RUNNING
                    },
                    onDecline = { dismiss() },
                    onDone = { dismiss() },
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = statusBarHeight()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching { manager.addView(view, params) }
            .onFailure {
                overlayOwners.destroy()
                owners = null
                stopSelf()
                return
            }
        root = view
    }

    private fun dismiss() {
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        root = null
        owners?.destroy()
        owners = null
        stopSelf()
    }

    override fun onDestroy() {
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        root = null
        owners?.destroy()
        owners = null
        super.onDestroy()
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
}

/** Minimal owners so Compose can live inside a Service window. */
private class OverlayOwners : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    fun start() {
        savedState.performRestore(Bundle.EMPTY)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
