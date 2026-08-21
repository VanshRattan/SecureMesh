package com.capstone.chatapp.data.transport.ble

import android.util.Base64
import com.capstone.chatapp.data.transport.Packet
import com.capstone.chatapp.data.transport.SendResult
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [Transport] over the BLE mesh. The mesh is a flood, not a point-to-point link, so
 * [nextHop] is ignored — every broadcast reaches whichever neighbours [BleMeshManager]
 * currently knows about (plus anyone it meets afterwards, via its own outbox).
 *
 * **Targeted (1:1) packets ride the same flood as an SOS broadcast.** [BleMeshManager] only
 * knows how to carry a [BlePacket] (its flood/relay/dedup/outbox/chunking machinery is all
 * keyed off that shape), so a targeted [Packet] — whose payload is opaque ciphertext, not the
 * '|'-joined SOS text [BlePacket] expects — is wrapped as the `text` field of a carrier
 * [BlePacket] ([wrapTargeted]) instead of touching any of that machinery. Every phone in range
 * still relays the carrier exactly like an SOS (it cannot tell the difference, which is the
 * point: relay-blindness holds); only [unwrapTargeted] on the intended recipient's device
 * (matched by the inner packet's own [Packet.destId], checked by the caller) ever sees it as
 * anything other than an opaque broadcast. This is what makes offline 1:1 messaging "for
 * free" multi-hop, not just single-hop to a phone already in direct range.
 */
class BleTransport(private val mesh: BleMeshManager) : Transport {

    override val incoming: Flow<Packet> = mesh.incoming.map { ble -> unwrapTargeted(ble) ?: ble.toPacket() }

    override fun isAvailable(): Boolean = mesh.status.value.running

    override suspend fun sendToNextHop(packet: Packet, nextHop: String?, tier: Tier): SendResult {
        val ble = if (packet.destId != null) wrapTargeted(packet) else BlePacket.fromPacket(packet)
        ble ?: return SendResult.FAILED
        mesh.send(ble)
        return SendResult.SENT
    }

    /** Wraps a targeted [Packet] (ciphertext payload, real destId) as an opaque BlePacket so
     * it can travel the existing flood unchanged. Base64 has no '|' in its alphabet, so it
     * can never be mistaken for [BlePacket]'s own '|'-joined SOS field separator. */
    private fun wrapTargeted(packet: Packet): BlePacket = BlePacket(
        msgId = packet.msgId,
        ttl = packet.ttl.takeIf { it > 0 } ?: BleConstants.DEFAULT_TTL,
        hopCount = 0,
        reachCount = 1,
        senderId = packet.srcId.orEmpty(),
        senderName = "",
        timestamp = System.currentTimeMillis(),
        text = Base64.encodeToString(packet.serialize(), Base64.NO_WRAP),
    )

    /** The inverse of [wrapTargeted]: only unwraps a carrier whose `text` actually decodes to
     * a targeted inner [Packet] — anything else (a real SOS) falls through to the normal
     * [BlePacket.toPacket] broadcast path untouched. */
    private fun unwrapTargeted(ble: BlePacket): Packet? {
        val bytes = runCatching { Base64.decode(ble.text, Base64.NO_WRAP) }.getOrNull() ?: return null
        val inner = Packet.deserialize(bytes) ?: return null
        return if (inner.destId != null) inner else null
    }
}
