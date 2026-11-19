package io.morph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManifestTest {

    @Test
    fun `manifest roundtrip`() {
        val manifest = Manifest(
            listOf(
                FileEntry(1u, 1234L, "image/jpeg", "photo.jpg", ByteArray(32) { it.toByte() }),
                FileEntry(2u, 99L, "text/plain", "note.txt", ByteArray(32) { (255 - it).toByte() }),
            )
        )
        val decoded = Manifest.decode(manifest.encode())
        assertEquals(2, decoded.files.size)
        assertEquals(1u, decoded.files[0].id)
        assertEquals(1234L, decoded.files[0].size)
        assertEquals("image/jpeg", decoded.files[0].mime)
        assertEquals("photo.jpg", decoded.files[0].name)
        assertEquals(32, decoded.files[0].sha256.size)
        assertEquals("note.txt", decoded.files[1].name)
        assertEquals(32, manifest.manifestHash().size)
    }

    @Test
    fun `encoded manifest has no padding`() {
        val manifest = Manifest(
            listOf(FileEntry(1u, 1234L, "image/jpeg", "a.jpg", ByteArray(32)))
        )
        val mime = "image/jpeg".toByteArray().size
        val name = "a.jpg".toByteArray().size
        assertEquals(2 + 4 + 8 + 1 + mime + 1 + name + 32, manifest.encode().size)
    }

    @Test
    fun `decode rejects truncated data and tolerates legacy padding`() {
        val bytes = Manifest(
            listOf(FileEntry(1u, 1L, "text/plain", "a.txt", ByteArray(32)))
        ).encode()
        assertEquals(1, Manifest.decode(bytes).files.size)
        // <= 0.6.5 encoded two spare zero bytes per file.
        assertEquals(1, Manifest.decode(bytes + byteArrayOf(0, 0)).files.size)
        assertFailsWith<IllegalArgumentException> {
            Manifest.decode(bytes.copyOf(bytes.size - 1))
        }
    }

    @Test
    fun `empty manifest roundtrips`() {
        assertEquals(2, Manifest(emptyList()).encode().size)
        assertEquals(0, Manifest.decode(Manifest(emptyList()).encode()).files.size)
    }

    @Test
    fun `chunk roundtrip keeps absolute offset`() {
        val chunk = Chunk(7u, 4096L, byteArrayOf(1, 2, 3))
        val decoded = Chunk.decode(chunk.encode())
        assertEquals(7u, decoded.fileId)
        assertEquals(4096L, decoded.offset)
        assertEquals(3, decoded.data.size)
    }
}
