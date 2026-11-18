package io.morph.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilitiesTest {

    @Test
    fun `roundtrip with flags and blob`() {
        val caps = Capabilities(
            protocolVersion = 1,
            shellId = ShellId.NOTHING_OS,
            flags = CapsFlag.WIFI_AWARE or CapsFlag.RESUME or CapsFlag.ENCRYPTED,
            maxMtu = 512,
            maxFileSize = 4L * 1024 * 1024 * 1024,
            blob = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        )
        val decoded = Capabilities.decode(caps.encode())
        assertEquals(caps.protocolVersion, decoded.protocolVersion)
        assertEquals(ShellId.NOTHING_OS, decoded.shellId)
        assertEquals(caps.flags, decoded.flags)
        assertEquals(512, decoded.maxMtu)
        assertEquals(caps.maxFileSize, decoded.maxFileSize)
        assertContentEquals(caps.blob, decoded.blob)
        assertTrue(decoded.has(CapsFlag.RESUME))
    }

    @Test
    fun `unknown shell falls back to unknown`() {
        val bytes = Capabilities(shellId = ShellId.UNKNOWN).encode()
        assertEquals(ShellId.UNKNOWN, Capabilities.decode(bytes).shellId)
    }
}
