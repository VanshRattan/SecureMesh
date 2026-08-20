package com.capstone.chatapp.data.transport.ble

import java.nio.ByteBuffer

/**
 * Application-level fragmentation for GATT writes. Android's own "long write" (prepared
 * write) path is inconsistent across API levels and chipsets, so every outbound write is
 * wrapped in this envelope instead: `groupId(4 bytes) + seq(2 bytes) + total(2 bytes) +
 * payload`. A message that fits in one chunk is still wrapped (`total = 1`), so the
 * receiving side has exactly one reassembly code path.
 */
object BleChunk {

    data class Chunk(val groupId: Int, val seq: Int, val total: Int, val payload: ByteArray)

    /** Splits [bytes] into chunks of at most [chunkPayloadSize] payload bytes each. */
    fun split(bytes: ByteArray, chunkPayloadSize: Int, groupId: Int): List<ByteArray> {
        val size = chunkPayloadSize.coerceAtLeast(1)
        val total = ((bytes.size + size - 1) / size).coerceAtLeast(1).coerceAtMost(0xFFFF)
        return (0 until total).map { seq ->
            val start = seq * size
            val end = minOf(start + size, bytes.size)
            ByteBuffer.allocate(BleConstants.CHUNK_HEADER_SIZE + (end - start)).apply {
                putInt(groupId)
                putShort(seq.toShort())
                putShort(total.toShort())
                put(bytes, start, end - start)
            }.array()
        }
    }

    /** Parses one chunk envelope off the wire. Null if malformed. */
    fun parse(bytes: ByteArray): Chunk? {
        if (bytes.size < BleConstants.CHUNK_HEADER_SIZE) return null
        return try {
            val buf = ByteBuffer.wrap(bytes)
            val groupId = buf.int
            val seq = buf.short.toInt() and 0xFFFF
            val total = buf.short.toInt() and 0xFFFF
            if (total <= 0 || seq >= total) return null
            val payload = bytes.copyOfRange(BleConstants.CHUNK_HEADER_SIZE, bytes.size)
            Chunk(groupId, seq, total, payload)
        } catch (e: Exception) {
            null
        }
    }
}
