package io.morph.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity

/**
 * Invisible trampoline used by the Quick Settings tile: the shade collapses,
 * the receive service starts, and the ready card appears over the current app.
 */
class DropTriggerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, MorphService::class.java))
        MorphEngine.start(this)

        if (Settings.canDrawOverlays(this)) {
            MorphEngine.showReady()
            startService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW)
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ASK_OVERLAY, true)
            )
        }
        finish()
    }
}
