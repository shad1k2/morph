package io.morph.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/** RFC 7748 §5.2 / §6.1 vectors. */
class X25519Test {

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun publicKeyMatchesRfcVector() {
        val scalar = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val expected = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        assertEquals(expected, toHex(X25519.publicKey(scalar)))
    }

    @Test
    fun sharedSecretMatchesRfcVector() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(expected, toHex(X25519.sharedSecret(alice, bobPublic)))
    }

    @Test
    fun agreementIsSymmetric() {
        val a = X25519.generatePrivateKey()
        val b = X25519.generatePrivateKey()
        assert(
            X25519.sharedSecret(a, X25519.publicKey(b))
                .contentEquals(X25519.sharedSecret(b, X25519.publicKey(a)))
        )
    }
}
