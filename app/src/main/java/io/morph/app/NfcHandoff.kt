package io.morph.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.Locale

/**
 * NFC bootstrap for DROP/1. The receiver emulates a Type-4 NDEF tag via HCE
 * ([MorphNdefService]); the sender reads it and gets a ready-to-use [Peer].
 *
 * The tag carries a morph:// URI record (plus an AAR). Older builds used a
 * MORPH/1 MIME record, which is still understood on the read side.
 */
object NfcHandoff {

    const val MIME = "application/vnd.morph.drop"
    private const val MARKER = "MORPH/1"

    fun message(context: Context): NdefMessage {
        val name = Build.MODEL ?: "Android"
        val host = localAddress(context)
        val port = MorphEngine.port.toString()
        val shell = ShellDetector.detect().code.toString()

        val uri = Uri.Builder()
            .scheme("morph")
            .authority("drop")
            .appendQueryParameter("n", name)
            .appendQueryParameter("h", host)
            .appendQueryParameter("p", port)
            .appendQueryParameter("s", shell)
            .build()
            .toString()

        // URI record is understood by every NFC stack, including MIUI; AAR
        // forces Android to start MORPH even when no mime filter matches.
        return NdefMessage(
            arrayOf(
                NdefRecord.createUri(uri),
                NdefRecord.createApplicationRecord("io.morph.app"),
            )
        )
    }

    /** Reads either the legacy MIME record or the morph:// URI record. */
    fun decode(intent: Intent): Peer? {
        val fromUri = decodeUri(intent.data)
        if (fromUri != null) return fromUri

        if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return null
        @Suppress("DEPRECATION")
        val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        return decode(messages?.firstOrNull() as? NdefMessage)
    }

    /** Legacy path: MORPH/1 MIME record from older HCE builds. */
    private fun decode(message: NdefMessage?): Peer? {
        val record = message?.records?.firstOrNull { candidate ->
            val type = candidate.type ?: return@firstOrNull false
            String(type, Charsets.US_ASCII).equals(MIME, ignoreCase = true)
        } ?: return null

        val parts = String(record.payload, Charsets.UTF_8).split('\n')
        if (parts.size < 5 || parts[0] != MARKER) return null

        val port = parts[3].toIntOrNull() ?: return null
        val shellCode = parts[4].toIntOrNull() ?: return null
        if (parts[1].isBlank() || parts[2].isBlank()) return null

        return Peer(
            id = "nfc:${parts[2]}:$port",
            name = parts[1],
            host = parts[2],
            port = port,
            shell = io.morph.core.ShellId.from(shellCode),
        )
    }

    private fun decodeUri(uri: Uri?): Peer? {
        if (uri == null || uri.scheme != "morph") return null
        val host = uri.getQueryParameter("h") ?: return null
        val port = uri.getQueryParameter("p")?.toIntOrNull() ?: return null
        val name = uri.getQueryParameter("n")?.let { decode(it) } ?: "Nearby device"
        val shell = uri.getQueryParameter("s")?.toIntOrNull()
            ?: io.morph.core.ShellId.UNKNOWN.code
        return Peer(
            id = "nfc:$host:$port",
            name = name,
            host = host,
            port = port,
            shell = io.morph.core.ShellId.from(shell),
        )
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun localAddress(context: Context): String {
        @Suppress("DEPRECATION")
        val ip = try {
            val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
            wifi?.connectionInfo?.ipAddress ?: 0
        } catch (t: Throwable) {
            0
        }
        if (ip != 0) {
            return String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ip and 0xFF,
                (ip shr 8) and 0xFF,
                (ip shr 16) and 0xFF,
                (ip shr 24) and 0xFF,
            )
        }
        return NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress.contains('.') }
            ?.hostAddress
            ?: "127.0.0.1"
    }
}
