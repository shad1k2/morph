package io.morph.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The phone emulating the HCE tag also sees its own field and Android looks for
 * a TAG_DISCOVERED handler. This silently absorbs that intent so the system does
 * not show "no application found for this tag". The HCE service already raised
 * the RECEIVE card, so no peer/role is decided here.
 */
class NfcTagOwnerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startService(Intent(this, MorphService::class.java)) }
        finish()
    }
}
