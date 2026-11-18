package io.morph.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * In picker-only activity launched from the overlay. Keeps the AirDrop card
 * visible while the system file picker is open, then hands the URI to the
 * engine for a real transfer to the NFC peer.
 */
class FilePickActivity : ComponentActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val peer = MorphEngine.nfcPeer
        if (uri != null && peer != null) {
            MorphEngine.send(this, peer, uri)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        picker.launch(arrayOf("*/*"))
    }
}
