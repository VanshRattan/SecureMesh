package com.capstone.chatapp.data.transport.ble

import java.nio.charset.StandardCharsets

/**
 * A single emergency message travelling across the mesh.
 *
 * Wire format (UTF-8, '|'-separated so it stays human-debuggable):
 *   msgId | ttl | hopCount | reachCount | senderId | senderName | timestamp | text
 *
 * `text` is always the LAST field, so any '|' inside it is preserved by rejoining the
 * trailing parts. `senderName` is an interior field, so '|' is stripped from it on build.
 * `ttl`, `hopCount` and `reachCount` are mutated as the packet is relayed; `msgId` is the
 * stable identity used for de-duplication (seen-set) so a message is never shown or
 * relayed twice.
 */
data class BlePacket(
    val msgId: String,
    val ttl: Int,
    val hopCount: Int,
    val reachCount: Int,
    val senderId: String,
    val senderName: String,
    val timestamp: Long,
    val text: String,
) {
    fun toBytes(): ByteArray {
        val payload = listOf(
            msgId,
            ttl.toString(),
            hopCount.toString(),
            reachCount.toString(),
            senderId,
            senderName.replace('|', '/'),
            timestamp.toString(),
            text,
        ).joinToString("|")
        return payload.toByteArray(StandardCharsets.UTF_8)
    }

    /** A copy advanced by one hop: ttl-1, hopCount+1, reachCount+1. */
    fun relayed(): BlePacket = copy(ttl = ttl - 1, hopCount = hopCount + 1, reachCount = reachCount + 1)

    companion object {
        fun fromBytes(bytes: ByteArray): BlePacket? {
            return try {
                val parts = String(bytes, StandardCharsets.UTF_8).split("|")
                if (parts.size < 8) return null
                BlePacket(
                    msgId = parts[0],
                    ttl = parts[1].toInt(),
                    hopCount = parts[2].toInt(),
                    reachCount = parts[3].toInt(),
                    senderId = parts[4],
                    senderName = parts[5],
                    timestamp = parts[6].toLong(),
                    // text is the last field; rejoin any '|' that belonged to it
                    text = parts.subList(7, parts.size).joinToString("|"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
