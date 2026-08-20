package com.capstone.chatapp.data.transport.wifidirect

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * TCP is a byte stream with no message boundaries, unlike a single BLE GATT write — so
 * every [com.capstone.chatapp.data.transport.Packet] sent over a Wi-Fi Direct socket is
 * wrapped in a simple 4-byte big-endian length prefix. TCP is already reliable and ordered
 * within one connection, so (unlike [com.capstone.chatapp.data.transport.ble.BleChunk])
 * there is no need for sequencing/reassembly here — just where one message ends.
 */
object WifiDirectFrame {

    fun write(out: OutputStream, bytes: ByteArray) {
        val header = ByteArray(WifiDirectConstants.LENGTH_PREFIX_SIZE)
        header[0] = (bytes.size ushr 24).toByte()
        header[1] = (bytes.size ushr 16).toByte()
        header[2] = (bytes.size ushr 8).toByte()
        header[3] = bytes.size.toByte()
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    /** Returns null on a clean EOF (peer closed the socket); throws on a genuine I/O error
     * or a corrupt/oversized length prefix so the caller can log and tear the socket down. */
    fun read(input: InputStream): ByteArray? {
        val header = readFully(input, WifiDirectConstants.LENGTH_PREFIX_SIZE) ?: return null
        val size = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (size <= 0 || size > WifiDirectConstants.MAX_FRAME_SIZE) {
            throw IOException("Bad frame length: $size")
        }
        return readFully(input, size) ?: throw EOFException("Stream closed mid-frame")
    }

    /** Returns null only if the stream hit EOF before a single byte was read (clean close
     * between frames); throws if it closes partway through a frame. */
    private fun readFully(input: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read == -1) {
                return if (offset == 0) null else throw EOFException("Stream closed mid-frame")
            }
            offset += read
        }
        return buffer
    }
}
