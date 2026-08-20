package com.capstone.chatapp.data.transport.ble

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
 */
class BleTransport(private val mesh: BleMeshManager) : Transport {

    override val incoming: Flow<Packet> = mesh.incoming.map { it.toPacket() }

    override fun isAvailable(): Boolean = mesh.status.value.running

    override suspend fun sendToNextHop(packet: Packet, nextHop: String?, tier: Tier): SendResult {
        val ble = BlePacket.fromPacket(packet) ?: return SendResult.FAILED
        mesh.send(ble)
        return SendResult.SENT
    }
}
