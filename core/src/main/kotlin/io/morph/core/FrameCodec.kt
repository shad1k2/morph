package io.morph.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** One framed DROP/1 message. */
data class Frame(
    val type: MessageType,
    val seq: UInt,
    val payload: ByteArray,
)

/** Encodes and decodes the framing envelope from §4 of the spec. */
object FrameCodec {

    /** A frame with an empty DONE payload and SEQ=1 (spec §4.1). */
    val TEST_VECTOR_HEX = "44524F5001090000000100002d711c24"

    fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }

    fun encode(frame: Frame): ByteArray {
        require(frame.payload.size <= Protocol.MAX_PAYLOAD) {
            "payload too large: ${frame.payload.size}"
        }
        val buffer = ByteBuffer.allocate(Protocol.HEADER_SIZE + frame.payload.size + Protocol.CRC_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(Protocol.MAGIC)
        buffer.put(Protocol.VERSION)
        buffer.put(frame.type.code.toByte())
        buffer.putInt(frame.seq.toInt())
        buffer.putShort(frame.payload.size.toShort())
        buffer.put(frame.payload)
        val crc = crc32(buffer.array(), 0, Protocol.HEADER_SIZE + frame.payload.size)
        buffer.putInt(crc.toInt())
        return buffer.array()
    }

    fun decode(bytes: ByteArray): Frame {
        require(bytes.size >= Protocol.HEADER_SIZE + Protocol.CRC_SIZE) { "frame too short" }
        require(Protocol.MAGIC.contentEquals(bytes.copyOfRange(0, 4))) { "bad MAGIC" }
        require(bytes[4] == Protocol.VERSION) { "unsupported VERSION" }

        val type = MessageType.from(bytes[5].toInt() and 0xFF)
            ?: throw IllegalArgumentException("unknown message type: ${bytes[5].toInt() and 0xFF}")
        val seq = ByteBuffer.wrap(bytes, 6, 4).order(ByteOrder.BIG_ENDIAN).int.toUInt()
        val length = ByteBuffer.wrap(bytes, 10, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        val expectedSize = Protocol.HEADER_SIZE + length + Protocol.CRC_SIZE
        require(bytes.size == expectedSize) { "size mismatch: ${bytes.size} != $expectedSize" }

        val stored = ByteBuffer.wrap(bytes, Protocol.HEADER_SIZE + length, 4)
            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val actual = crc32(bytes, 0, Protocol.HEADER_SIZE + length)
        require(stored == actual) { "CRC mismatch: $stored != $actual" }

        return Frame(type, seq, bytes.copyOfRange(Protocol.HEADER_SIZE, Protocol.HEADER_SIZE + length))
    }
}
