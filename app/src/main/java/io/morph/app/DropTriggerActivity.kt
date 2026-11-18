package io.morph.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity

/**
 * Invisible trampoline used by the Quick Settings tile: the shade collapses,
 * then the overlay service shows the pop-up. If the overlay permission is
 * missing, the main app opens the permission prompt instead.
 */
class DropTriggerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Settings.canDrawOverlays(this)) {
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
