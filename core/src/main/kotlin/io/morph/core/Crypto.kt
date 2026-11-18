package io.morph.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Hashing helpers. */
object Digest {
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** Key agreement / derivation / AEAD — standard primitives only (spec §10). */
object Crypto {

    private val X25519_SPKI_PREFIX =
        byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()

    /** X.509 SPKI → raw 32-byte X25519 public key (HELLO payload). */
    fun rawPublicKey(key: PublicKey): ByteArray {
        val encoded = key.encoded
        require(encoded.size == 44) { "unexpected X25519 SPKI size: ${encoded.size}" }
        return encoded.copyOfRange(12, 44)
    }

    fun publicKeyFromRaw(raw: ByteArray): PublicKey {
        require(raw.size == 32) { "raw X25519 key must be 32 bytes" }
        return KeyFactory.getInstance("X25519")
            .generatePublic(X509EncodedKeySpec(X25519_SPKI_PREFIX + raw))
    }

    /** HKDF-SHA256(shared, salt=session_id, info="DROP/1"). */
    fun deriveSessionKey(privateKey: java.security.PrivateKey, peerPublic: PublicKey, sessionId: ByteArray): SecretKey {
        val agreement = javax.crypto.KeyAgreement.getInstance("X25519")
        agreement.init(privateKey)
        agreement.doPhase(peerPublic, true)
        val shared = agreement.generateSecret()
        val prk = hmacSha256(sessionId, shared)
        val okm = hmacSha256(prk, byteArrayOf(0x01) + "DROP/1".toByteArray())
        return SecretKeySpec(okm.copyOf(32), "AES")
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Encrypt a frame, returning a fully encoded encrypted frame. */
    fun seal(frame: Frame, key: SecretKey, direction: Int): ByteArray {
        val nonce = nonce(frame.seq, direction)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(Protocol.MAGIC + byteArrayOf(Protocol.VERSION, frame.type.code.toByte()) +
                frame.seqBytes() + frame.lengthBytes(frame.payload.size + 16))
        val ciphertext = cipher.doFinal(frame.payload)
        return FrameCodec.encode(Frame(frame.type, frame.seq, ciphertext))
    }

    /** Decode, verify CRC, verify GCM tag, decrypt. */
    fun open(bytes: ByteArray, key: SecretKey, direction: Int): Frame {
        val encoded = FrameCodec.decode(bytes)
        val nonce = nonce(encoded.seq, direction)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(bytes.copyOfRange(0, Protocol.HEADER_SIZE))
        val plain = cipher.doFinal(encoded.payload)
        return Frame(encoded.type, encoded.seq, plain)
    }

    private fun nonce(seq: UInt, direction: Int): ByteArray {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(seq.toInt())
        buffer.put(direction.toByte())
        buffer.put(ByteArray(7))
        return buffer.array()
    }

    private fun Frame.seqBytes(): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(seq.toInt()).array()

    private fun Frame.lengthBytes(length: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(length.toShort()).array()
}
