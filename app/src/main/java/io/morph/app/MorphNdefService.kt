package io.morph.app

import android.nfc.cardemulation.HostApduService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Emulates a NFC Forum Type 4 tag carrying our NDEF handoff record. Tapping a
 * second phone running MORPH wakes its reader and hands it our IP/port, so no
 * device picking and no QR code is needed.
 */
class MorphNdefService : HostApduService() {

    override fun onCreate() {
        super.onCreate()
        // Make sure the advertised port is actually listening before we are read,
        // and keep it alive after the NFC session ends (a cached process can be
        // killed before the sender finishes picking a file).
        MorphEngine.start(applicationContext)
        MorphEngine.showReady()
        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, MorphService::class.java),
            )
        }
        Log.d("MORPH", "NFC tag ready: ${NfcHandoff.message(applicationContext)}")
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d("MORPH", "APDU IN: ${hex(commandApdu)}")
        val response = try {
            if (commandApdu.size < 4) {
                status(0x67, 0x00)
            } else {
                when (commandApdu[1].toInt() and 0xFF) {
                    SELECT -> select(commandApdu)
                    READ_BINARY -> read(commandApdu)
                    else -> status(0x6D, 0x00)
                }
            }
        } catch (t: Throwable) {
            Log.w("MORPH", "APDU handler failed", t)
            status(0x6F, 0x00)
        }
        Log.d("MORPH", "APDU OUT: ${hex(response)}")
        return response
    }

    override fun onDeactivated(reason: Int) {
        selected = Selected.NONE
    }

    private fun select(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return ok()
        val p1 = apdu[2].toInt() and 0xFF
        val dataSize = apdu[4].toInt() and 0xFF
        val data = if (apdu.size >= 5 + dataSize) {
            apdu.copyOfRange(5, 5 + dataSize)
        } else {
            ByteArray(0)
        }

        // SELECT by AID (DF name) — accept the full NDEF tag AID.
        if (p1 == 0x04) {
            selected = if (data.size >= 7 &&
                data.copyOfRange(data.size - 7, data.size).contentEquals(NDEF_AID)
            ) {
                Selected.AID
            } else {
                Selected.NONE
            }
            return if (selected == Selected.AID) ok() else status(0x6A, 0x82)
        }

        // SELECT by file identifier: E103 = capability container, E104 = NDEF.
        if (data.size == 2) {
            val fileId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            selected = when (fileId) {
                0xE103 -> Selected.CC
                0xE104 -> Selected.NDEF
                else -> Selected.NONE
            }
            return if (selected == Selected.NONE) status(0x6A, 0x82) else ok()
        }

        return ok()
    }

    private fun read(apdu: ByteArray): ByteArray {
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        val offset = ((p1 and 0x7F) shl 8) or p2

        val file = when (selected) {
            Selected.CC -> ccFile()
            Selected.NDEF -> ndefFile()
            else -> return status(0x6A, 0x82)
        }
        if (offset >= file.size) return status(0x6B, 0x00)

        val wanted = if (apdu.size > 4) {
            val le = apdu.last().toInt() and 0xFF
            if (le == 0) 256 else le
        } else {
            file.size - offset
        }
        val count = minOf(wanted, file.size - offset)
        if (count <= 0) return status(0x6B, 0x00)

        val response = ByteBuffer.allocate(count + 2)
            .put(file, offset, count)
            .put(0x90.toByte())
            .put(0x00.toByte())
        return response.array()
    }

    private fun ccFile(): ByteArray {
        val max = minOf(ndefFile().size, 0xFFFF)
        return byteArrayOf(
            0x00, 0x0F,             // CC length (15 bytes)
            0x10, 0x00,             // major 1.0
            (max shr 8).toByte(), max.toByte(),
            0x00, 0x00,             // NDEF file always readable / writable
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
    }

    private fun ndefFile(): ByteArray {
        val message = NfcHandoff.message(applicationContext).toByteArray()
        return ByteBuffer.allocate(2 + message.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(message.size.toShort())
            .put(message)
            .array()
    }

    private fun ok(): ByteArray = status(0x90, 0x00)
    private fun fail(): ByteArray = status(0x67, 0x00)

    private fun status(sw1: Int, sw2: Int): ByteArray =
        byteArrayOf(sw1.toByte(), sw2.toByte())

    private var selected: Selected = Selected.NONE

    private enum class Selected { NONE, AID, CC, NDEF }

    companion object {
        private const val SELECT = 0xA4
        private const val READ_BINARY = 0xB0

        private val NDEF_AID = hex("D2760000850101")

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString(" ") { "%02X".format(it) }

        private fun hex(value: String): ByteArray {
            val out = ByteArray(value.length / 2)
            for (i in out.indices) {
                out[i] = value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }
    }
}
