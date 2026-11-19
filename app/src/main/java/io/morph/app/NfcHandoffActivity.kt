package io.morph.app

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * Invisible trampoline for NFC taps. It never shows UI of its own: it turns the
 * NDEF handoff into an AirDrop-like card in the system overlay.
 */
class NfcHandoffActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, MorphService::class.java))
        MorphEngine.start(this)

        @Suppress("DEPRECATION")
        val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val fromExtras = messages?.firstOrNull() as? NdefMessage

        @Suppress("DEPRECATION")
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        val fromTag = runCatching { Ndef.get(tag)?.cachedNdefMessage }.getOrNull()

        val tap = NfcHandoff.decodeUri(intent.data)
            ?: NfcHandoff.decodeMessage(fromExtras ?: fromTag)

        Log.d(
            "MORPH",
            "NFC handoff intent: ${intent.action} data=${intent.data} " +
                "records=${(fromExtras ?: fromTag)?.records?.size ?: 0} peer=${tap?.peer?.name} " +
                "session=${tap?.sessionId}",
        )

        if (tap != null) {
            MorphEngine.onGuestTap(this, tap)
            if (Settings.canDrawOverlays(this)) {
                // Let the trampoline finish before raising the overlay; avoids
                // the HIDE/SHOW race with the engine's publish().
                Handler(Looper.getMainLooper()).postDelayed({
                    startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
                }, 250)
            } else {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_ASK_OVERLAY, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        finish()
    }
}
