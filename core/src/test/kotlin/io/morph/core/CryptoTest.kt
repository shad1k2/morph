package io.morph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class CryptoTest {

    private fun pair(): Pair<ByteArray, ByteArray> {
        val private = X25519.generatePrivateKey()
        return private to X25519.publicKey(private)
    }

    @Test
    fun `both peers derive the same key`() {
        val (alicePriv, alicePub) = pair()
        val (bobPriv, bobPub) = pair()
        val sessionId = DropSession.newSessionId()
        val aliceKey = Crypto.deriveSessionKey(X25519.sharedSecret(alicePriv, bobPub), sessionId)
        val bobKey = Crypto.deriveSessionKey(X25519.sharedSecret(bobPriv, alicePub), sessionId)
        assertContentEquals(aliceKey.encoded, bobKey.encoded)
    }

    @Test
    fun `public key is 32 bytes and derived from private`() {
        val (private, public) = pair()
        assertEquals(32, public.size)
        assertContentEquals(public, X25519.publicKey(private))
    }

    @Test
    fun `seal and open roundtrip`() {
        val (alicePriv, alicePub) = pair()
        val (bobPriv, bobPub) = pair()
        val sessionId = DropSession.newSessionId()
        val key = Crypto.deriveSessionKey(X25519.sharedSecret(alicePriv, bobPub), sessionId)
        val frame = Frame(MessageType.OFFER, 7u, byteArrayOf(1, 2, 3, 4))
        val wire = Crypto.seal(frame, key, direction = 0)
        val opened = Crypto.open(wire, key, direction = 0)
        assertEquals(frame.type, opened.type)
        assertEquals(frame.seq, opened.seq)
        assertContentEquals(frame.payload, opened.payload)
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val (alicePriv, alicePub) = pair()
        val (_, bobPub) = pair()
        val key = Crypto.deriveSessionKey(X25519.sharedSecret(alicePriv, bobPub), DropSession.newSessionId())
        val wire = Crypto.seal(Frame(MessageType.CHUNK, 1u, byteArrayOf(1, 2, 3)), key, 0)
        wire[wire.lastIndex - 1] = (wire[wire.lastIndex - 1].toInt() xor 0xFF).toByte()
        assertFailsWith<Exception> { Crypto.open(wire, key, 0) }
    }
}
