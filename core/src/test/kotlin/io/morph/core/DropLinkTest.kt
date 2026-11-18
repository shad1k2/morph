package io.morph.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DropLinkTest {

    @Test
    fun transfersEncryptedFileOverTcp() {
        val payload = ByteArray(512 * 1024).also { Random(7).nextBytes(it) }
        val payloadHash = Digest.sha256(payload)
        val received = ByteArrayOutputStream()
        val error = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(2)

        val server = ServerSocket(0)
        val executor = Executors.newFixedThreadPool(2)

        executor.submit {
            try {
                val socket = server.accept()
                socket.soTimeout = 15_000
                val link = DropLink(socket.getInputStream(), socket.getOutputStream(), Role.RECEIVER)
                val caps = link.handshake(Capabilities(shellId = ShellId.NOTHING_OS))
                assertEquals(ShellId.PIXEL, caps.shellId)

                val manifest = link.receiveOffer()
                assertEquals(1, manifest.files.size)
                assertEquals(payload.size.toLong(), manifest.files[0].size)
                assertArrayEquals(payloadHash, manifest.files[0].sha256)

                link.accept()
                val hash = link.receiveFile(manifest.files[0], received)
                assertArrayEquals(payloadHash, hash)
                assertTrue(link.verifyDone(manifest.manifestHash()))
                link.ack()
                socket.close()
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            } finally {
                finished.countDown()
            }
        }

        executor.submit {
            try {
                val socket = Socket("127.0.0.1", server.localPort)
                socket.soTimeout = 15_000
                val link = DropLink(socket.getInputStream(), socket.getOutputStream(), Role.SENDER)
                link.handshake(Capabilities(shellId = ShellId.PIXEL))

                val entry = FileEntry(
                    id = 1u,
                    size = payload.size.toLong(),
                    mime = "application/octet-stream",
                    name = "test.bin",
                    sha256 = payloadHash,
                )
                val manifest = Manifest(listOf(entry))
                link.sendOffer(manifest)
                assertTrue(link.awaitAccept())
                link.sendFile(entry, ByteArrayInputStream(payload))
                link.sendDone(manifest.manifestHash())
                assertTrue(link.awaitAck())
                socket.close()
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            } finally {
                finished.countDown()
            }
        }

        assertTrue("transfer timed out", finished.await(30, TimeUnit.SECONDS))
        executor.shutdownNow()
        server.close()

        error.get()?.let { throw AssertionError("transfer failed", it) }
        assertArrayEquals(payload, received.toByteArray())
    }

    @Test
    fun receiverCanDecline() {
        val error = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(2)
        val server = ServerSocket(0)
        val executor = Executors.newFixedThreadPool(2)

        executor.submit {
            try {
                val socket = server.accept()
                socket.soTimeout = 10_000
                val link = DropLink(socket.getInputStream(), socket.getOutputStream(), Role.RECEIVER)
                link.handshake(Capabilities())
                link.receiveOffer()
                link.decline()
                socket.close()
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            } finally {
                finished.countDown()
            }
        }

        executor.submit {
            try {
                val socket = Socket("127.0.0.1", server.localPort)
                socket.soTimeout = 10_000
                val link = DropLink(socket.getInputStream(), socket.getOutputStream(), Role.SENDER)
                link.handshake(Capabilities())
                val entry = FileEntry(1u, 0, "text/plain", "empty.txt", Digest.sha256(ByteArray(0)))
                link.sendOffer(Manifest(listOf(entry)))
                assertTrue(!link.awaitAccept())
                socket.close()
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            } finally {
                finished.countDown()
            }
        }

        assertTrue("handshake timed out", finished.await(20, TimeUnit.SECONDS))
        executor.shutdownNow()
        server.close()
        error.get()?.let { throw AssertionError("decline failed", it) }
    }
}
