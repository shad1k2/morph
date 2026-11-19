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

        // Keep the legacy MIME record so older MORPH builds can still read the
        // tag; the morph:// URI works on stacks that ignore custom MIME types;
        // AAR forces Android to start MORPH even when no filter matches.
        val legacy = listOf(MARKER, name, host, port, shell).joinToString("\n")
        return NdefMessage(
            arrayOf(
                NdefRecord.createUri(uri),
                NdefRecord.createMime(MIME, legacy.toByteArray(Charsets.UTF_8)),
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
        return decodeMessage(messages?.firstOrNull() as? NdefMessage)
    }

    /** Legacy path: MORPH/1 MIME record from older HCE builds. */
    fun decodeMessage(message: NdefMessage?): Peer? {
        val record = message?.records?.firstOrNull { candidate ->
            val type = candidate.type ?: return@firstOrNull false
            String(type, Charsets.US_ASCII).equals(MIME, ignoreCase = true)
        } ?: return null

        val parts = String(record.payload, Charsets.UTF_8).split('\n')
        if (parts.size < 5 || parts[0] != MARKER) return null

        val port = parts[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
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

    fun decodeUri(uri: Uri?): Peer? {
        if (uri == null || uri.scheme != "morph" || uri.authority != "drop") return null
        val host = uri.getQueryParameter("h")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
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
