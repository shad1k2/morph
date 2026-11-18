package io.morph.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameCodecTest {

    @Test
    fun `matches spec test vector`() {
        val vector = hex(FrameCodec.TEST_VECTOR_HEX)
        val frame = FrameCodec.decode(vector)
        assertEquals(MessageType.DONE, frame.type)
        assertEquals(1u, frame.seq)
        assertEquals(0, frame.payload.size)
        assertContentEquals(vector, FrameCodec.encode(frame))
    }

    @Test
    fun `roundtrip with payload`() {
        val frame = Frame(MessageType.OFFER, 42u, byteArrayOf(1, 2, 3, 4))
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertEquals(frame.type, decoded.type)
        assertEquals(frame.seq, decoded.seq)
        assertContentEquals(frame.payload, decoded.payload)
    }

    @Test
    fun `detects corrupted crc`() {
        val bytes = FrameCodec.encode(Frame(MessageType.CHUNK, 1u, byteArrayOf(9, 9, 9)))
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0xFF).toByte()
        assertFailsWith<IllegalArgumentException> { FrameCodec.decode(bytes) }
    }

    @Test
    fun `rejects bad magic`() {
        val bytes = FrameCodec.encode(Frame(MessageType.HELLO, 0u, ByteArray(0)))
        bytes[0] = 0
        assertFailsWith<IllegalArgumentException> { FrameCodec.decode(bytes) }
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
