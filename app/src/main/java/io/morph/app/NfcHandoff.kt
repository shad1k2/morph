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
import java.security.SecureRandom
import java.util.Locale

/** A decoded NFC handoff. [sessionId] is null for tags written by pre-1.1 builds. */
data class NfcTap(
    val peer: Peer,
    val sessionId: String?,
)

/**
 * NFC bootstrap for DROP/1. One phone emulates a Type-4 NDEF tag via HCE
 * ([MorphNdefService]); the other reads it and gets the host address, shell and
 * a per-tap session id. The session id is what binds the Bluetooth control
 * channel and the Wi-Fi Direct group to this physical tap.
 *
 * The tag carries a morph:// URI record (plus an AAR). Older builds used a
 * MORPH/1 MIME record, which is still understood on the read side.
 */
object NfcHandoff {

    const val MIME = "application/vnd.morph.drop"
    private const val MARKER = "MORPH/1"

    private val random = SecureRandom()
    private val lock = Any()
    private var hostSession: String = newSessionId()

    /** Stable session id written into the emulated tag during this tap. */
    fun hostSessionId(): String = synchronized(lock) { hostSession }

    /** Start a fresh session after the previous NFC field has gone away. */
    fun rotateHostSession(): String = synchronized(lock) {
        hostSession = newSessionId()
        hostSession
    }

    private fun newSessionId(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    fun message(context: Context): NdefMessage {
        val name = Build.MODEL ?: "Android"
        val host = localAddress(context)
        val port = MorphEngine.port.toString()
        val shell = ShellDetector.detect().code.toString()
        val session = hostSessionId()

        val uri = Uri.Builder()
            .scheme("morph")
            .authority("drop")
            .appendQueryParameter("n", name)
            .appendQueryParameter("h", host)
            .appendQueryParameter("p", port)
            .appendQueryParameter("s", shell)
            .appendQueryParameter("i", session)
            .appendQueryParameter("r", "h")
            .build()
            .toString()

        // Keep the legacy MIME record so pre-1.1 builds can still read the tag.
        // Extra trailing fields are ignored by the old parser.
        val legacy = listOf(MARKER, name, host, port, shell, session, "h")
            .joinToString("\n")
        return NdefMessage(
            arrayOf(
                NdefRecord.createUri(uri),
                NdefRecord.createMime(MIME, legacy.toByteArray(Charsets.UTF_8)),
                NdefRecord.createApplicationRecord("io.morph.app"),
            )
        )
    }

    /** Reads either the legacy MIME record or the morph:// URI record. */
    fun decode(intent: Intent): NfcTap? {
        val fromUri = decodeUri(intent.data)
        if (fromUri != null) return fromUri

        if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return null
        @Suppress("DEPRECATION")
        val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        return decodeMessage(messages?.firstOrNull() as? NdefMessage)
    }

    /** Legacy path: MORPH/1 MIME record from older HCE builds. */
    fun decodeMessage(message: NdefMessage?): NfcTap? {
        val record = message?.records?.firstOrNull { candidate ->
            val type = candidate.type ?: return@firstOrNull false
            String(type, Charsets.US_ASCII).equals(MIME, ignoreCase = true)
        } ?: return null

        val parts = String(record.payload, Charsets.UTF_8).split('\n')
        if (parts.size < 5 || parts[0] != MARKER) return null

        val port = parts[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val shellCode = parts[4].toIntOrNull() ?: return null
        if (parts[1].isBlank() || parts[2].isBlank()) return null

        val session = parts.getOrNull(5)?.trim()?.takeIf { it.isSessionId() }
        return NfcTap(
            peer = Peer(
                id = "nfc:${parts[2]}:$port",
                name = parts[1],
                host = parts[2],
                port = port,
                shell = io.morph.core.ShellId.from(shellCode),
            ),
            sessionId = session,
        )
    }

    fun decodeUri(uri: Uri?): NfcTap? {
        if (uri == null || uri.scheme != "morph" || uri.authority != "drop") return null
        val host = uri.getQueryParameter("h")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val name = uri.getQueryParameter("n")?.let { decode(it) } ?: "Nearby device"
        val shell = uri.getQueryParameter("s")?.toIntOrNull()
            ?: io.morph.core.ShellId.UNKNOWN.code
        val session = uri.getQueryParameter("i")?.trim()?.takeIf { it.isSessionId() }
        return NfcTap(
            peer = Peer(
                id = "nfc:$host:$port",
                name = name,
                host = host,
                port = port,
                shell = io.morph.core.ShellId.from(shell),
            ),
            sessionId = session,
        )
    }

    private fun String.isSessionId(): Boolean =
        length in 8..64 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun localAddress(context: Context): String {
        @Suppress("DEPRECATION")
        val legacyIp = runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java)
                ?.connectionInfo?.ipAddress ?: 0
        }.getOrDefault(0)
        if (legacyIp != 0) return formatIpv4(legacyIp)

        // Prefer a site-local IPv4 address on a Wi-Fi-like interface. Picking
        // the first non-loopback address can hand out mobile data or VPN.
        val addresses = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filterIsInstance<java.net.Inet4Address>()
                        .map { iface.name.lowercase(Locale.US) to it }
                }
        }.getOrDefault(emptyList())

        val chosen = addresses.firstOrNull { (ifName, addr) ->
            addr.isSiteLocalAddress && WIFI_PREFIXES.any { ifName.startsWith(it) }
        } ?: addresses.firstOrNull { (_, addr) -> addr.isSiteLocalAddress }
            ?: addresses.firstOrNull()

        return chosen?.second?.hostAddress ?: "127.0.0.1"
    }

    private val WIFI_PREFIXES = listOf("wlan", "ap", "wifi", "swlan")

    private fun formatIpv4(ip: Int): String = String.format(
        Locale.US,
        "%d.%d.%d.%d",
        ip and 0xFF,
        (ip shr 8) and 0xFF,
        (ip shr 16) and 0xFF,
        (ip shr 24) and 0xFF,
    )
}
