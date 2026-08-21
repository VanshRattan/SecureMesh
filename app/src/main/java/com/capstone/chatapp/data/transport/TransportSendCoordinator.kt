package com.capstone.chatapp.data.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The send pipeline every 1:1 chat message and Emergency broadcast goes through, replacing
 * the ad-hoc "call BLE, also call Firestore if online" that used to live directly in
 * ChatRepository/EmergencyViewModel. Asks [TransportArbiter] which tiers are worth trying,
 * attempts them, and on total failure hands the packet to [StoreCarryForwardQueue] instead
 * of surfacing an error -- it is retried automatically once a bearer returns.
 */
class TransportSendCoordinator(
    private val arbiter: TransportArbiter,
    private val transports: Map<Tier, Transport>,
    private val storeCarryForwardQueue: StoreCarryForwardQueue,
) {

    private val _activeTier = MutableStateFlow<Tier?>(null)

    /** Best tier available right now -- the status UI's source of truth for which bearer
     * traffic is actually moving over, not just a raw "online" vs "offline" flag. */
    val activeTier: StateFlow<Tier?> = _activeTier.asStateFlow()

    /** How many packets are stuck in store-carry-forward right now -- the status UI's
     * "buffering" signal, alongside [activeTier]. */
    val bufferedCount: StateFlow<Int> = storeCarryForwardQueue.pendingCount

    /** Starts the background retry tick + active-tier tracking. Call once, from a scope
     * that outlives individual screens (see `di/AppContainer.kt`). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            arbiter.availabilityChanges.collect {
                _activeTier.value = arbiter.bestAvailableTier()
                retryPending()
            }
        }
    }

    /**
     * Sends [packet] now if any tier will take it, otherwise queues it for later. Always
     * returns SENT or QUEUED -- a send that can't reach anyone right now is not an
     * application error, it's exactly what store-carry-forward exists for.
     */
    suspend fun send(packet: Packet): SendResult {
        val result = attemptSend(packet)
        if (result != SendResult.SENT) storeCarryForwardQueue.enqueue(packet)
        return if (result == SendResult.SENT) SendResult.SENT else SendResult.QUEUED
    }

    private suspend fun retryPending() {
        storeCarryForwardQueue.pending().forEach { queued ->
            if (attemptSend(queued.packet) == SendResult.SENT) {
                storeCarryForwardQueue.remove(queued.packet.msgId)
            }
        }
    }

    private suspend fun attemptSend(packet: Packet): SendResult {
        val decision = arbiter.selectBearer(packet)
        val candidates = (decision as? ArbiterDecision.Candidates)?.ordered ?: return SendResult.FAILED
        return if (packet.destId == null) sendBroadcast(packet, candidates) else sendTargeted(packet, candidates)
    }

    /** Targeted (1:1) packets stop at the first tier that accepts them -- there is exactly
     * one intended recipient, so nothing is gained by also trying a lower-ranked tier. */
    private suspend fun sendTargeted(packet: Packet, candidates: List<TierCandidate>): SendResult {
        for (candidate in candidates) {
            val transport = transports[candidate.tier] ?: continue
            val result = transport.sendToNextHop(packet, nextHop = packet.destId, tier = candidate.tier)
            if (result == SendResult.SENT) {
                _activeTier.value = candidate.tier
                return SendResult.SENT
            }
        }
        return SendResult.FAILED
    }

    /** A broadcast is fanned out to every reachable tier rather than stopping at the first:
     * each bearer (internet, a Wi-Fi Direct group, the BLE mesh) physically reaches a
     * different audience, so sending on only the top-ranked one would silently shrink an
     * SOS's reach compared to the pre-arbiter behaviour of always sending on every bearer. */
    private suspend fun sendBroadcast(packet: Packet, candidates: List<TierCandidate>): SendResult {
        var anySent = false
        for (candidate in candidates) {
            val transport = transports[candidate.tier] ?: continue
            val result = transport.sendToNextHop(packet, nextHop = null, tier = candidate.tier)
            if (result == SendResult.SENT) {
                anySent = true
                _activeTier.value = candidate.tier
            }
        }
        return if (anySent) SendResult.SENT else SendResult.FAILED
    }
}
