package io.morph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class CryptoTest {

    @Test
    fun `both peers derive the same key`() {
        val alice = Crypto.generateKeyPair()
        val bob = Crypto.generateKeyPair()
        val sessionId = DropSession.newSessionId()
        val aliceKey = Crypto.deriveSessionKey(alice.private, bob.public, sessionId)
        val bobKey = Crypto.deriveSessionKey(bob.private, alice.public, sessionId)
        assertContentEquals(aliceKey.encoded, bobKey.encoded)
    }

    @Test
    fun `raw public key conversion is symmetric`() {
        val pair = Crypto.generateKeyPair()
        val raw = Crypto.rawPublicKey(pair.public)
        assertEquals(32, raw.size)
        assertContentEquals(pair.public.encoded, Crypto.publicKeyFromRaw(raw).encoded)
    }

    @Test
    fun `seal and open roundtrip`() {
        val alice = Crypto.generateKeyPair()
        val bob = Crypto.generateKeyPair()
        val sessionId = DropSession.newSessionId()
        val key = Crypto.deriveSessionKey(alice.private, bob.public, sessionId)
        val frame = Frame(MessageType.OFFER, 7u, byteArrayOf(1, 2, 3, 4))
        val wire = Crypto.seal(frame, key, direction = 0)
        val opened = Crypto.open(wire, key, direction = 0)
        assertEquals(frame.type, opened.type)
        assertEquals(frame.seq, opened.seq)
        assertContentEquals(frame.payload, opened.payload)
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val alice = Crypto.generateKeyPair()
        val bob = Crypto.generateKeyPair()
        val key = Crypto.deriveSessionKey(alice.private, bob.public, DropSession.newSessionId())
        val wire = Crypto.seal(Frame(MessageType.CHUNK, 1u, byteArrayOf(1, 2, 3)), key, 0)
        wire[wire.lastIndex - 1] = (wire[wire.lastIndex - 1].toInt() xor 0xFF).toByte()
        assertFailsWith<Exception> { Crypto.open(wire, key, 0) }
    }
}
