package io.morph.core

/** Constants and enumerations shared by every DROP/1 implementation. */
object Protocol {
    val MAGIC: ByteArray = byteArrayOf(0x44, 0x52, 0x4F, 0x50) // "DROP"
    const val VERSION: Byte = 1
    const val HEADER_SIZE = 12
    const val CRC_SIZE = 4
    const val MAX_PAYLOAD = 65535
    const val BLE_MIN_MTU = 20
    const val BLE_MAX_MTU = 517
    const val WIFI_PORT = 44771

    const val HELLO_TIMEOUT_MS = 5_000L
    const val ACCEPT_TIMEOUT_MS = 15_000L
    const val ACK_TIMEOUT_MS = 30_000L
}

/** DROP/1 message types (wire codes from §5 of the spec). */
enum class MessageType(val code: Int) {
    HELLO(0x01),
    CAPS(0x02),
    OFFER(0x03),
    ACCEPT(0x04),
    CHUNK(0x05),
    ACK(0x06),
    RESUME(0x07),
    PROGRESS(0x08),
    DONE(0x09),
    ABORT(0xFF);

    companion object {
        fun from(code: Int): MessageType? = entries.firstOrNull { it.code == code }
    }
}

/** Manufacturer UI identity. Advisory only — never changes transport behaviour. */
enum class ShellId(val code: Int) {
    PIXEL(0x01),
    HYPEROS(0x02),
    NOTHING_OS(0x03),
    ONE_UI(0x04),
    UNKNOWN(0xFF);

    companion object {
        fun from(code: Int): ShellId = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Capability flags (§6.2). */
object CapsFlag {
    const val WIFI_AWARE = 1 shl 0
    const val WIFI_DIRECT = 1 shl 1
    const val NFC_HANDSHAKE = 1 shl 2
    const val RESUME = 1 shl 3
    const val ENCRYPTED = 1 shl 4
    const val CONTACTS_ONLY = 1 shl 5
    const val QR_FALLBACK = 1 shl 6
}

/** Session states (§8). */
enum class SessionState {
    IDLE,
    HANDSHAKE,
    NEGOTIATED,
    OFFERED,
    TRANSFERRING,
    RESUMING,
    VERIFYING,
    COMPLETE,
    ABORTED,
}

/** Events that drive the session state machine. */
enum class SessionEvent {
    NFC_TAP,
    HELLO_OK,
    CAPS_OK,
    OFFER,
    ACCEPT,
    LINK_LOST,
    RESUME,
    DONE,
    HASH_OK,
    ABORT,
    TIMEOUT,
}
