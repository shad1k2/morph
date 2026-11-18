package io.morph.app

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Build
import java.net.NetworkInterface
import java.util.Locale

/**
 * NFC bootstrap for DROP/1. The receiver emulates a Type-4 NDEF tag via HCE
 * ([MorphNdefService]); the sender reads it and gets a ready-to-use [Peer].
 *
 * Payload is deliberately tiny and dependency-free:
 *   MORPH/1\n<name>\n<host>\n<port>\n<shell code>
 */
object NfcHandoff {

    const val MIME = "application/vnd.morph.drop"
    private const val MARKER = "MORPH/1"
    private val MIME_BYTES = MIME.toByteArray(Charsets.US_ASCII)

    fun message(context: Context): NdefMessage {
        val payload = listOf(
            MARKER,
            Build.MODEL ?: "Android",
            localAddress(context),
            MorphEngine.port.toString(),
            ShellDetector.detect().code.toString(),
        ).joinToString("\n")

        return NdefMessage(
            arrayOf(NdefRecord.createMime(MIME, payload.toByteArray(Charsets.UTF_8)))
        )
    }

    fun decode(message: NdefMessage?): Peer? {
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
