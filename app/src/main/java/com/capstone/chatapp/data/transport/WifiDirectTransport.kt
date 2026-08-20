package com.capstone.chatapp.data.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * TODO(Session 6): Wi-Fi Direct tier — group formation/discovery, socket send + chunking,
 * and wiring into the per-hop transport arbiter alongside [Tier.INTERNET] and [Tier.BLE_MESH].
 * Registered in [com.capstone.chatapp.di.AppContainer] now so the arbiter can already be
 * written against the full three-tier [Tier] set; it just has nothing to arbitrate yet.
 */
class WifiDirectTransport : Transport {

    override val incoming: Flow<Packet> = emptyFlow()

    override fun isAvailable(): Boolean = false

    override suspend fun sendToNextHop(packet: Packet, nextHop: String?, tier: Tier): SendResult =
        SendResult.UNAVAILABLE
}
