package io.morph.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `chunk roundtrip keeps absolute offset`() {
        val chunk = Chunk(7u, 4096L, byteArrayOf(1, 2, 3))
        val decoded = Chunk.decode(chunk.encode())
        assertEquals(7u, decoded.fileId)
        assertEquals(4096L, decoded.offset)
        assertEquals(3, decoded.data.size)
    }
}
