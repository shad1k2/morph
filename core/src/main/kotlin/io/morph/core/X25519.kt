package io.morph.core

import java.math.BigInteger
import java.security.SecureRandom

/**
 * RFC 7748 Curve25519 (X25519) in plain Kotlin.
 *
 * We deliberately do not use `KeyPairGenerator.getInstance("X25519")`: it is
 * missing on older Android builds (e.g. MIUI 12 / Android 10), where the app
 * died during the HELLO exchange. Only BigInteger and SecureRandom are needed.
 */
object X25519 {

    private val FIELD: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val U_MASK: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE)
    private val A24 = BigInteger.valueOf(121665)
    private val BASE_U = BigInteger.valueOf(9)
    private val RANDOM = SecureRandom()

    /** Random, clamped 32-byte little-endian scalar. */
    fun generatePrivateKey(): ByteArray = ByteArray(32).also { RANDOM.nextBytes(it) }.also { clamp(it) }

    /** X25519 scalar multiplication of the base point (u = 9). */
    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        return encode(scalarMultiply(privateKey, BASE_U))
    }

    /** Shared secret with a peer's raw 32-byte public key. */
    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        require(peerPublic.size == 32) { "peer public key must be 32 bytes" }
        val u = decode(peerPublic)
        return encode(scalarMultiply(privateKey, u))
    }

    private fun clamp(key: ByteArray) {
        key[0] = (key[0].toInt() and 0xF8).toByte()
        key[31] = ((key[31].toInt() and 0x7F) or 0x40).toByte()
    }

    private fun decode(bytes: ByteArray): BigInteger =
        BigInteger(1, bytes.reversedArray()).and(U_MASK)

    private fun encode(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(32)
        var i = bigEndian.size - 1
        var j = 0
        while (i >= 0 && j < 32) out[j++] = bigEndian[i--]
        return out
    }

    private fun scalarMultiply(privateKey: ByteArray, uCoordinate: BigInteger): BigInteger {
        val clamped = privateKey.copyOf().also { clamp(it) }
        val k = BigInteger(1, clamped.reversedArray())

        var x1 = uCoordinate.mod(FIELD)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = k.testBit(t)
            swap = swap xor if (bit) 1 else 0
            if (swap == 1) {
                val tmp = x2; x2 = x3; x3 = tmp
                val tmpZ = z2; z2 = z3; z3 = tmpZ
            }
            swap = if (bit) 1 else 0

            val a = (x2 + z2).mod(FIELD)
            val aa = (a * a).mod(FIELD)
            val b = (x2 - z2).mod(FIELD)
            val bb = (b * b).mod(FIELD)
            val e = (aa - bb).mod(FIELD)
            val c = (x3 + z3).mod(FIELD)
            val d = (x3 - z3).mod(FIELD)
            val da = (d * a).mod(FIELD)
            val cb = (c * b).mod(FIELD)

            x3 = ((da + cb).pow(2)).mod(FIELD)
            z3 = (x1 * ((da - cb).pow(2))).mod(FIELD)
            x2 = (aa * bb).mod(FIELD)
            z2 = (e * (aa + A24 * e)).mod(FIELD)
        }

        if (swap == 1) {
            val tmp = x2; x2 = x3; x3 = tmp
            val tmpZ = z2; z2 = z3; z3 = tmpZ
        }
        return (x2 * z2.modPow(FIELD.subtract(BigInteger.valueOf(2)), FIELD)).mod(FIELD)
    }
}
