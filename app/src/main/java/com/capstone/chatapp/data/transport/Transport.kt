package com.capstone.chatapp.data.transport

import kotlinx.coroutines.flow.Flow

enum class SendResult { SENT, QUEUED, FAILED, UNAVAILABLE }

/**
 * A bearer capable of moving a [Packet] to (or toward) a peer. Higher layers — repositories,
 * ViewModels — talk only to this interface, never to BluetoothGatt, a Wi-Fi Direct socket, or
 * Firestore directly, so a new tier (or swapping a plaintext payload for ciphertext) slots in
 * without touching them.
 */
interface Transport {
    /**
     * Send [packet] toward [nextHop] — a tier-specific peer id/address, or null when the tier
     * has no notion of a single next hop (e.g. the BLE flood always broadcasts to everyone in
     * range). [tier] is redundant with the packet's own [Packet.tierTag] for a single-tier
     * transport, but is passed explicitly so one Transport implementation could in principle
     * serve more than one tier.
     */
    suspend fun sendToNextHop(packet: Packet, nextHop: String?, tier: Tier): SendResult

    /** Packets arriving on this bearer, already reassembled/decoded off the wire. */
    val incoming: Flow<Packet>

    fun isAvailable(): Boolean
}
