package io.morph.core

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.SecretKey

/**
 * A DROP/1 conversation over a byte stream (TCP in practice).
 *
 * Flow: HELLO (X25519 public keys) → CAPS → OFFER → ACCEPT → CHUNK* → DONE → ACK.
 * Everything after HELLO is encrypted with AES-256-GCM using the derived key.
 */
class DropLink(
    private val input: InputStream,
    private val output: OutputStream,
    val role: Role,
) {
    private var key: SecretKey? = null
    private var sendSeq: UInt = 1u
    private var recvSeq: UInt = 1u

    private val directionOut = if (role == Role.SENDER) 0 else 1
    private val directionIn = if (role == Role.SENDER) 1 else 0

    var peerCapabilities: Capabilities? = null
        private set

    /** HELLO + key agreement + CAPS. Returns the peer's capabilities. */
    fun handshake(local: Capabilities): Capabilities {
        val privateKey = X25519.generatePrivateKey()
        val raw = X25519.publicKey(privateKey)
        val peerRaw: ByteArray

        if (role == Role.SENDER) {
            write(MessageType.HELLO, raw)
            peerRaw = read(MessageType.HELLO).payload
        } else {
            peerRaw = read(MessageType.HELLO).payload
            write(MessageType.HELLO, raw)
        }
        require(peerRaw.size == 32) { "peer key must be 32 bytes" }

        val sessionId = Digest.sha256(
            if (role == Role.SENDER) raw + peerRaw else peerRaw + raw
        ).copyOf(16)
        val shared = X25519.sharedSecret(privateKey, peerRaw)
        key = Crypto.deriveSessionKey(shared, sessionId)

        write(MessageType.CAPS, local.encode())
        val peer = Capabilities.decode(read(MessageType.CAPS).payload)
        peerCapabilities = peer
        return peer
    }

    fun sendOffer(manifest: Manifest) = write(MessageType.OFFER, manifest.encode())

    fun receiveOffer(): Manifest = Manifest.decode(read(MessageType.OFFER).payload)

    fun accept() = write(MessageType.ACCEPT)

    fun decline() = write(MessageType.ABORT)

    /** Waits for ACCEPT; returns false when the receiver declines. */
    fun awaitAccept(): Boolean = when (readFrame().type) {
        MessageType.ACCEPT -> true
        MessageType.ABORT -> false
        else -> throw IllegalStateException("expected ACCEPT")
    }

    /** Streams one file with CHUNK frames. */
    fun sendFile(
        file: FileEntry,
        source: InputStream,
        chunkSize: Int = DEFAULT_CHUNK,
        onProgress: (Long) -> Unit = {},
    ) {
        require(chunkSize in 1..(Protocol.MAX_PAYLOAD - 12)) { "bad chunk size" }
        val buffer = ByteArray(chunkSize)
        var offset = 0L
        while (offset < file.size) {
            val want = minOf(chunkSize.toLong(), file.size - offset).toInt()
            val read = readFully(source, buffer, want)
            if (read <= 0) throw EOFException("source ended at $offset of ${file.size}")
            write(MessageType.CHUNK, Chunk(file.id, offset, buffer.copyOf(read)).encode())
            offset += read
            onProgress(offset)
        }
    }

    /** Receives one file into [sink], returns SHA-256 of the written bytes. */
    fun receiveFile(
        file: FileEntry,
        sink: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        while (written < file.size) {
            val frame = read(MessageType.CHUNK)
            val chunk = Chunk.decode(frame.payload)
            require(chunk.fileId == file.id) { "unexpected file id" }
            require(chunk.offset == written) { "unexpected offset ${chunk.offset}, want $written" }
            require(written + chunk.data.size <= file.size) { "chunk overflows file" }
            sink.write(chunk.data)
            digest.update(chunk.data)
            written += chunk.data.size
            onProgress(written)
        }
        sink.flush()
        return digest.digest()
    }

    fun sendDone(manifestHash: ByteArray) = write(MessageType.DONE, manifestHash)

    /** Returns the receiver's verdict: payload empty means verified. */
    fun awaitAck(): Boolean = when (readFrame().type) {
        MessageType.ACK -> true
        MessageType.ABORT -> false
        else -> false
    }

    fun verifyDone(expected: ByteArray): Boolean {
        val frame = read(MessageType.DONE)
        return frame.payload.contentEquals(expected)
    }

    fun ack() = write(MessageType.ACK)

    fun abort() {
        runCatching { write(MessageType.ABORT) }
    }

    private fun write(type: MessageType, payload: ByteArray = ByteArray(0)) {
        val frame = Frame(type, sendSeq, payload)
        val bytes = key?.let { Crypto.seal(frame, it, directionOut) } ?: FrameCodec.encode(frame)
        output.write(bytes)
        output.flush()
        sendSeq++
    }

    private fun read(expected: MessageType): Frame {
        val frame = readFrame()
        require(frame.type == expected) {
            "expected $expected, got ${frame.type}"
        }
        return frame
    }

    private fun readFrame(): Frame {
        val header = ByteArray(Protocol.HEADER_SIZE)
        if (readFully(input, header, header.size) != header.size) {
            throw EOFException("connection closed")
        }
        require(header[0] == Protocol.MAGIC[0] && header[1] == Protocol.MAGIC[1] &&
            header[2] == Protocol.MAGIC[2] && header[3] == Protocol.MAGIC[3]
        ) { "bad MAGIC" }
        require(header[4] == Protocol.VERSION) { "unsupported version ${header[4]}" }

        val length = ((header[10].toInt() and 0xFF) shl 8) or (header[11].toInt() and 0xFF)
        val rest = ByteArray(length + Protocol.CRC_SIZE)
        if (readFully(input, rest, rest.size) != rest.size) {
            throw EOFException("truncated frame")
        }
        val bytes = header + rest
        val frame = key?.let { Crypto.open(bytes, it, directionIn) } ?: FrameCodec.decode(bytes)
        require(frame.seq == recvSeq) { "unexpected seq ${frame.seq}, want $recvSeq" }
        recvSeq++
        return frame
    }

    private fun readFully(stream: InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = stream.read(buffer, total, length - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    companion object {
        const val DEFAULT_CHUNK = 32 * 1024

        fun frameLength(header: ByteArray): Int {
            require(header.size >= Protocol.HEADER_SIZE)
            val length = ((header[10].toInt() and 0xFF) shl 8) or (header[11].toInt() and 0xFF)
            return Protocol.HEADER_SIZE + length + Protocol.CRC_SIZE
        }
    }
}
