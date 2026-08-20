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
        }
        return out.toByteArray()
    }

    companion object {
        const val CURRENT_VERSION = 1

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
                Packet(version, msgId, destId, srcId, ttl, priority, tierTag, nonce, payload)
            }
        } catch (e: Exception) {
            null
        }
    }
}
