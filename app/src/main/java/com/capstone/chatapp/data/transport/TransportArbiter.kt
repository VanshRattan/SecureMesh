package com.capstone.chatapp.data.transport

import com.capstone.chatapp.data.transport.ble.BleConstants
import com.capstone.chatapp.data.transport.ble.BleMeshManager
import com.capstone.chatapp.data.transport.wifidirect.WifiDirectManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Tunable weights for [TransportArbiter]'s scorer. Every field is roughly [0,1]; only
 * relative magnitude matters, they don't need to sum to 1. */
data class ArbiterWeights(
    val reachability: Double = 0.45,
    val congestion: Double = 0.20,
    val energy: Double = 0.15,
    val preference: Double = 0.20,
)

/** One tier scored against a specific packet. Higher is better. */
data class TierCandidate(val tier: Tier, val score: Double)

/** What [TransportArbiter.selectBearer] recommends: try these tiers in order, or nothing
 * is reachable right now and the packet belongs in store-carry-forward. */
sealed class ArbiterDecision {
    data class Candidates(val ordered: List<TierCandidate>) : ArbiterDecision()
    object StoreCarry : ArbiterDecision()
}

private val DEFAULT_PREFERENCE = mapOf(Tier.INTERNET to 1.0, Tier.WIFI_DIRECT to 0.6, Tier.BLE_MESH to 0.3)
private const val WIFI_DIRECT_SOCKET_SOFT_CAP = 6.0

/**
 * Per-hop bearer selection: ranks the three [Tier]s with a weighted score over reachability,
 * congestion, energy budget and message priority, so repositories stop deciding "BLE or
 * internet?" for themselves. [TransportSendCoordinator] is the only caller of this class --
 * it only scores, it never sends anything.
 *
 * TODO(ml-arbiter): once evaluation instrumentation (CLAUDE.md §5.5) has logged enough real
 * (reachability, congestion, energy, priority) -> delivery-outcome samples, [scoreTier] is
 * the seam to swap for a small on-device TF-Lite model. Keep [selectBearer]'s signature and
 * [ArbiterDecision]'s shape stable so [TransportSendCoordinator] doesn't need to change.
 */
class TransportArbiter(
    private val bleMeshManager: BleMeshManager,
    private val wifiDirectManager: WifiDirectManager,
    private val networkMonitor: NetworkMonitor,
    private val energyMonitor: EnergyMonitor,
    private val weights: ArbiterWeights = ArbiterWeights(),
    private val preference: Map<Tier, Double> = DEFAULT_PREFERENCE,
) {

    /** Fires whenever a tier's reachability could have changed, so
     * [TransportSendCoordinator] knows when to re-attempt the store-carry-forward queue
     * and refresh the status UI's active-tier reading. */
    val availabilityChanges: Flow<Unit> = merge(
        networkMonitor.isOnline.map {},
        bleMeshManager.status.map {},
        wifiDirectManager.status.map {},
    )

    /**
     * Ranks every reachable tier for [packet]. Targeted (1:1) packets are free to use any
     * tier now that `OfflineMessageRouter` consumes them off BLE/Wi-Fi Direct too (offline
     * 1:1 messaging) -- [BleTransport] carries a targeted packet as an opaque flood payload
     * ([com.capstone.chatapp.data.transport.ble.BleTransport.sendToNextHop]) and
     * [com.capstone.chatapp.data.transport.wifidirect.WifiDirectTransport] already carries a
     * [Packet] generically. Broadcasts ([Packet.destId] == null, i.e. the SOS) are likewise
     * free to use any reachable tier, and [TransportSendCoordinator] fans a broadcast out to
     * every one of them rather than stopping at the first, since each bearer physically
     * reaches a different audience; a targeted packet still stops at the first tier that
     * accepts it, since there is exactly one intended recipient.
     */
    fun selectBearer(packet: Packet): ArbiterDecision {
        val candidates = Tier.entries.mapNotNull { scoreTier(it, packet.priority) }
            .sortedByDescending { it.score }
        return if (candidates.isEmpty()) ArbiterDecision.StoreCarry else ArbiterDecision.Candidates(candidates)
    }

    /** Best tier right now, ignoring any specific packet -- for the status UI only. */
    fun bestAvailableTier(): Tier? =
        Tier.entries.mapNotNull { scoreTier(it, Priority.NORMAL) }.maxByOrNull { it.score }?.tier

    private fun scoreTier(tier: Tier, priority: Priority): TierCandidate? {
        val reachability = reachabilityScore(tier)
        if (reachability <= 0.0) return null
        val w = effectiveWeights(priority)
        val score = w.reachability * reachability +
            w.congestion * congestionScore(tier) +
            w.energy * energyScore(tier) +
            w.preference * preference.getValue(tier)
        return TierCandidate(tier, score)
    }

    /** An emergency shouldn't be held back by efficiency concerns: congestion/energy stop
     * mattering and everything they would have weighed goes to reachability, so an EMERGENCY
     * packet just chases whatever path is most likely to actually deliver. */
    private fun effectiveWeights(priority: Priority): ArbiterWeights =
        if (priority == Priority.EMERGENCY) {
            weights.copy(
                reachability = weights.reachability + weights.congestion + weights.energy,
                congestion = 0.0,
                energy = 0.0,
            )
        } else weights

    /** 0 = unreachable (excluded outright), up to 1 = actively connected. A tier that is
     * technically on but has nobody to talk to yet (mesh running with no neighbours, a
     * formed Wi-Fi Direct group with no sockets) still scores above zero: sending into it
     * still lands in that bearer's own outbox/relay and can be delivered the moment a peer
     * appears -- it just ranks below a tier that's already actively connected. */
    private fun reachabilityScore(tier: Tier): Double = when (tier) {
        Tier.INTERNET -> if (networkMonitor.currentlyOnline()) 1.0 else 0.0
        Tier.WIFI_DIRECT -> {
            val status = wifiDirectManager.status.value
            when {
                !status.groupFormed -> 0.0
                status.connectedSockets > 0 -> 1.0
                else -> 0.6
            }
        }
        Tier.BLE_MESH -> {
            val status = bleMeshManager.status.value
            when {
                !status.running -> 0.0
                status.neighborCount > 0 -> 1.0
                else -> 0.5
            }
        }
    }

    /** 1 = clear, 0 = saturated. A rough proxy from each bearer's own concurrency ceiling --
     * neither bearer exposes real queue-depth telemetry yet, so this is deliberately coarse. */
    private fun congestionScore(tier: Tier): Double = when (tier) {
        Tier.INTERNET -> 1.0
        Tier.WIFI_DIRECT -> {
            val sockets = wifiDirectManager.status.value.connectedSockets
            (1.0 - sockets / WIFI_DIRECT_SOCKET_SOFT_CAP).coerceIn(0.1, 1.0)
        }
        Tier.BLE_MESH -> {
            val neighbors = bleMeshManager.status.value.neighborCount
            (1.0 - neighbors.toDouble() / (BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS * 2)).coerceIn(0.1, 1.0)
        }
    }

    /** 1 = spend freely, 0 = spend nothing extra. Wi-Fi Direct's discovery + always-open
     * socket draws noticeably more than BLE's duty-cycled radio, which draws more than
     * reusing an already-open data connection -- so low battery penalises them in that
     * order. Charging removes the concern entirely. */
    private fun energyScore(tier: Tier): Double {
        if (energyMonitor.isCharging()) return 1.0
        val battery = energyMonitor.batteryFraction().toDouble()
        return when (tier) {
            Tier.INTERNET -> 1.0
            Tier.BLE_MESH -> battery.coerceIn(0.2, 1.0)
            Tier.WIFI_DIRECT -> (battery * battery).coerceIn(0.05, 1.0)
        }
    }
}
