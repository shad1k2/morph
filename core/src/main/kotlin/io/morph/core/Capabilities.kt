package io.morph.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** CAPS payload from §6 of the spec. */
data class Capabilities(
    val protocolVersion: Int = Protocol.VERSION.toInt(),
    val shellId: ShellId = ShellId.UNKNOWN,
    val flags: Int = 0,
    val maxMtu: Int = Protocol.BLE_MAX_MTU,
    val maxFileSize: Long = Long.MAX_VALUE,
    val blob: ByteArray = ByteArray(0),
) {
    fun has(flag: Int): Boolean = flags and flag != 0

    fun with(flag: Int): Capabilities = copy(flags = flags or flag)

    fun encode(): ByteArray {
        require(blob.size <= 255) { "capabilities blob too large" }
        val buffer = ByteBuffer.allocate(15 + blob.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(protocolVersion.toByte())
        buffer.put(shellId.code.toByte())
        buffer.putShort(flags.toShort())
        buffer.putShort(maxMtu.toShort())
        buffer.putLong(maxFileSize)
        buffer.put(blob.size.toByte())
        buffer.put(blob)
        return buffer.array()
    }

    companion object {
        fun decode(bytes: ByteArray): Capabilities {
            require(bytes.size >= 15) { "CAPS payload too short" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val version = buffer.get().toInt() and 0xFF
            val shell = ShellId.from(buffer.get().toInt() and 0xFF)
            val flags = buffer.short.toInt() and 0xFFFF
            val mtu = buffer.short.toInt() and 0xFFFF
            val maxFile = buffer.long
            val blobLen = buffer.get().toInt() and 0xFF
            require(bytes.size == 15 + blobLen) { "CAPS size mismatch" }
            val blob = ByteArray(blobLen).also { buffer.get(it) }
            return Capabilities(version, shell, flags, mtu, maxFile, blob)
        }
    }
}
