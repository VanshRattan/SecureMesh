package com.capstone.chatapp.data.transport.ble

import com.capstone.chatapp.data.transport.Packet
import com.capstone.chatapp.data.transport.Priority
import com.capstone.chatapp.data.transport.Tier
import java.nio.charset.StandardCharsets

/**
 * A single emergency message travelling across the mesh. Wraps the transport-agnostic
 * [Packet]: `msgId`/`ttl`/`senderId` live in the packet header (so any bearer can route it
 * without understanding SOS semantics); everything the Emergency UI needs on top of that —
 * `senderName`, `timestamp`, the hop/reach counters, `text` — is packed into the opaque
 * payload, '|'-separated so it stays human-debuggable. `text` is always the LAST payload
 * field, so any '|' inside it is preserved by rejoining the trailing parts. `senderName` is
 * an interior field, so '|' is stripped from it on build.
 *
 * `ttl`, `hopCount` and `reachCount` are mutated as the packet is relayed; `msgId` is the
 * stable identity used for de-duplication (seen-set) so a message is never shown or relayed
 * twice.
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
    /** Wraps this SOS as a broadcast (destId = null) transport-agnostic packet. */
    fun toPacket(): Packet = Packet(
        msgId = msgId,
        destId = null,
        srcId = senderId,
        ttl = ttl,
        priority = Priority.EMERGENCY,
        tierTag = Tier.BLE_MESH,
        nonce = ByteArray(0),
        payload = payloadBytes(),
    )

    fun toBytes(): ByteArray = toPacket().serialize()

    /** A copy advanced by one hop: ttl-1, hopCount+1, reachCount+1. */
    fun relayed(): BlePacket = copy(ttl = ttl - 1, hopCount = hopCount + 1, reachCount = reachCount + 1)

    private fun payloadBytes(): ByteArray {
        val payload = listOf(
            hopCount.toString(),
            reachCount.toString(),
            senderName.replace('|', '/'),
            timestamp.toString(),
            text,
        ).joinToString("|")
        return payload.toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): BlePacket? =
            Packet.deserialize(bytes)?.let { fromPacket(it) }

        /** Unwraps a transport-agnostic [Packet] back into SOS fields. Null if malformed. */
        fun fromPacket(packet: Packet): BlePacket? {
            return try {
                val parts = String(packet.payload, StandardCharsets.UTF_8).split("|")
                if (parts.size < 4) return null
                BlePacket(
                    msgId = packet.msgId,
                    ttl = packet.ttl,
                    hopCount = parts[0].toInt(),
                    reachCount = parts[1].toInt(),
                    senderId = packet.srcId.orEmpty(),
                    senderName = parts[2],
                    timestamp = parts[3].toLong(),
                    // text is the last field; rejoin any '|' that belonged to it
                    text = parts.subList(4, parts.size).joinToString("|"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
