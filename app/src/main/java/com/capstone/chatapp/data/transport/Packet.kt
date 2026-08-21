package com.capstone.chatapp.data.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** How urgently a packet should be delivered. Feeds the per-hop arbiter (later session). */
enum class Priority { NORMAL, EMERGENCY }

/**
 * The single wire format every bearer (BLE, Wi-Fi Direct, Internet) ultimately carries.
 * The header travels in the clear so any relay can route without decrypting; [payload] is
 * opaque bytes — plaintext today, ciphertext once end-to-end encryption slots in.
 *
 * [destId] null means "broadcast" (e.g. an Emergency SOS reachable by everyone); [srcId]
 * is nullable purely so a malformed/legacy packet can still round-trip instead of crashing.
 */
data class Packet(
    val version: Int = CURRENT_VERSION,
    val msgId: String,
    val destId: String?,
    val srcId: String?,
    val ttl: Int,
    val priority: Priority,
    val tierTag: Tier,
    val nonce: ByteArray,
    val payload: ByteArray,
    /** True for a delivery receipt sent back to [srcId] once [destId] has received and
     * decrypted the original message with this [msgId] -- see `OfflineMessageRouter`. An
     * ack's own [payload] is empty; it carries no message content, only proof of arrival,
     * so relaying it blind is harmless from a relay-blindness standpoint. */
    val ack: Boolean = false,
) {
    /** Binary wire format — compact enough to fit BLE's negotiated MTU for typical payloads. */
    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeByte(version)
            d.writeUTF(msgId)
            d.writeBoolean(destId != null)
            destId?.let { d.writeUTF(it) }
            d.writeBoolean(srcId != null)
            srcId?.let { d.writeUTF(it) }
            d.writeByte(ttl)
            d.writeByte(priority.ordinal)
            d.writeByte(tierTag.ordinal)
            d.writeShort(nonce.size)
            d.write(nonce)
            d.writeInt(payload.size)
            d.write(payload)
            d.writeBoolean(ack)
        }
        return out.toByteArray()
    }

    companion object {
        // v2 appended a trailing `ack` byte -- deserialize gates reading it on version so a
        // v1 stream (none ever shipped, but keeps the format honestly versioned) still parses.
        const val CURRENT_VERSION = 2

        fun deserialize(bytes: ByteArray): Packet? = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                val version = d.readUnsignedByte()
                val msgId = d.readUTF()
                val destId = if (d.readBoolean()) d.readUTF() else null
                val srcId = if (d.readBoolean()) d.readUTF() else null
                val ttl = d.readUnsignedByte()
                val priority = Priority.entries.getOrElse(d.readUnsignedByte()) { Priority.NORMAL }
                val tierTag = Tier.entries.getOrElse(d.readUnsignedByte()) { Tier.BLE_MESH }
                val nonce = ByteArray(d.readUnsignedShort()).also { d.readFully(it) }
                val payload = ByteArray(d.readInt()).also { d.readFully(it) }
                val ack = if (version >= 2) d.readBoolean() else false
                Packet(version, msgId, destId, srcId, ttl, priority, tierTag, nonce, payload, ack)
            }
        } catch (e: Exception) {
            null
        }
    }
}
