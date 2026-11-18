package io.morph.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One file in an OFFER manifest (§7.1). */
data class FileEntry(
    val id: UInt,
    val size: Long,
    val mime: String,
    val name: String,
    val sha256: ByteArray,
)

/** OFFER payload. */
data class Manifest(val files: List<FileEntry>) {
    fun encode(): ByteArray {
        require(files.size <= 65535) { "too many files" }
        val size = files.sumOf { 2 + 4 + 8 + 1 + it.mime.toByteArray().size + 1 + it.name.toByteArray().size + 32 } + 2
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(files.size.toShort())
        for (file in files) {
            val mime = file.mime.toByteArray()
            val name = file.name.toByteArray(Charsets.UTF_8)
            require(mime.size <= 255 && name.size <= 255) { "name or mime too long" }
            require(file.sha256.size == 32) { "sha256 must be 32 bytes" }
            buffer.putInt(file.id.toInt())
            buffer.putLong(file.size)
            buffer.put(mime.size.toByte())
            buffer.put(mime)
            buffer.put(name.size.toByte())
            buffer.put(name)
            buffer.put(file.sha256)
        }
        return buffer.array()
    }

    /** Aggregate hash of all file hashes, used in DONE. */
    fun manifestHash(): ByteArray = Digest.sha256(files.flatMap { it.sha256.toList() }.toByteArray())

    companion object {
        fun decode(bytes: ByteArray): Manifest {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val count = buffer.short.toInt() and 0xFFFF
            val files = ArrayList<FileEntry>(count)
            repeat(count) {
                val id = buffer.int.toUInt()
                val size = buffer.long
                val mime = ByteArray(buffer.get().toInt() and 0xFF).also { buffer.get(it) }
                val name = ByteArray(buffer.get().toInt() and 0xFF).also { buffer.get(it) }
                val hash = ByteArray(32).also { buffer.get(it) }
                files += FileEntry(id, size, String(mime), String(name, Charsets.UTF_8), hash)
            }
            return Manifest(files)
        }
    }
}

/** CHUNK payload (§7.2). */
data class Chunk(val fileId: UInt, val offset: Long, val data: ByteArray) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(12 + data.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(fileId.toInt())
        buffer.putLong(offset)
        buffer.put(data)
        return buffer.array()
    }

    companion object {
        fun decode(bytes: ByteArray): Chunk {
            require(bytes.size >= 12) { "CHUNK payload too short" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val id = buffer.int.toUInt()
            val offset = buffer.long
            val data = ByteArray(bytes.size - 12).also { buffer.get(it) }
            return Chunk(id, offset, data)
        }
    }
}
