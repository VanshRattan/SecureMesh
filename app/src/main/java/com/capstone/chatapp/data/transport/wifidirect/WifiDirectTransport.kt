package com.capstone.chatapp.data.transport.wifidirect

import com.capstone.chatapp.data.transport.Packet
import com.capstone.chatapp.data.transport.SendResult
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.Transport
import kotlinx.coroutines.flow.Flow

/**
 * [Transport] over Wi-Fi Direct. Like [com.capstone.chatapp.data.transport.ble.BleTransport],
 * the underlying group is a star (everyone reaches everyone through the Group Owner), so
 * [nextHop] is ignored — [WifiDirectManager.send] already fans a packet out to every
 * connected socket (all clients if we're the GO, or the one upstream socket to the GO
 * otherwise, which then relays it onward).
 */
class WifiDirectTransport(private val manager: WifiDirectManager) : Transport {

    override val incoming: Flow<Packet> = manager.incoming

    override fun isAvailable(): Boolean = manager.status.value.groupFormed

    override suspend fun sendToNextHop(packet: Packet, nextHop: String?, tier: Tier): SendResult {
        if (!isAvailable()) return SendResult.UNAVAILABLE
        return if (manager.send(packet)) SendResult.SENT else SendResult.FAILED
    }
}
