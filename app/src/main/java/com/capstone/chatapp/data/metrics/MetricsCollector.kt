package com.capstone.chatapp.data.metrics

import android.content.Context
import com.capstone.chatapp.BuildConfig
import com.capstone.chatapp.data.transport.EnergyMonitor
import com.capstone.chatapp.data.transport.Packet
import com.capstone.chatapp.data.transport.SendResult
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.ble.BleConstants
import com.capstone.chatapp.data.transport.ble.BleMeshManager
import com.capstone.chatapp.data.transport.wifidirect.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ENERGY_SAMPLE_INTERVAL_MS = 30_000L

/**
 * Evaluation instrumentation for the SafeSphere paper's results section (CLAUDE.md §5.5):
 * one append-only CSV under the app's external files dir, one row per event. This is
 * deliberately an event log, not a live per-message aggregate — delivery ratio, latency,
 * hop count and per-tier usage are all derivable by joining rows on [msgIdHash] afterwards
 * (see docs/EVALUATION.md for the exact recipe), which costs nothing more per event than an
 * append and needs no in-memory bookkeeping that could leak across a long session or get out
 * of sync with retries/relays/multi-device runs.
 *
 * **Debug-only by construction**: gated on [BuildConfig.DEBUG], so it is compiled out of
 * every release build's behavior (a release APK's `enabled` is always false) rather than
 * relying on a runtime toggle someone could forget to flip back off.
 *
 * **No PII**: every id ([Packet.msgId], and transitively [Packet.srcId]/[Packet.destId] via
 * the msgId-keyed join in analysis) is SHA-256-hashed, unsalted, before it touches the CSV —
 * unsalted so the same id still hashes identically across two phones' separate CSVs and can
 * be joined on, but nothing readable (a uid, a name) ever gets written to disk.
 */
class MetricsCollector(
    context: Context,
    private val energyMonitor: EnergyMonitor,
    private val bleMeshManager: BleMeshManager,
    private val wifiDirectManager: WifiDirectManager,
) {
    private val appContext = context.applicationContext
    private val enabled = BuildConfig.DEBUG

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val rows = Channel<String>(capacity = 256)

    private val sessionFile: File by lazy {
        val dir = File(appContext.getExternalFilesDir(null), "metrics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File(dir, "metrics_$stamp.csv")
    }

    @Volatile private var writer: OutputStreamWriter? = null

    /** Starts the row-writer coroutine and the periodic energy/relay-activity sampler. A
     * no-op entirely in a release build. Call once, from the app-lifetime scope. */
    fun start(appScope: CoroutineScope) {
        if (!enabled) return
        ioScope.launch {
            openWriter()
            for (row in rows) writeLine(row)
        }
        appScope.launch {
            while (isActive) {
                delay(ENERGY_SAMPLE_INTERVAL_MS)
                recordEnergySample()
            }
        }
    }

    private fun openWriter() {
        val isNewFile = !sessionFile.exists()
        val opened = runCatching { OutputStreamWriter(FileOutputStream(sessionFile, true)) }.getOrNull()
        writer = opened
        if (isNewFile) runCatching { opened?.appendLine(HEADER); opened?.flush() }
    }

    private fun writeLine(line: String) {
        runCatching { writer?.appendLine(line); writer?.flush() }
    }

    // ---- send-side ----

    /** One row per tier the arbiter actually tried for [packet], in order — this is what
     * "tiers used" is derived from, not just the tier that finally won. */
    fun recordSendAttempt(packet: Packet, tier: Tier, outcome: SendResult) =
        emit(if (packet.ack) "ACK_SEND_ATTEMPT" else "SEND_ATTEMPT", packet, tier, outcome.name)

    /** One row per [com.capstone.chatapp.data.transport.TransportSendCoordinator.send] call
     * — the overall SENT/QUEUED result after every candidate tier was tried. */
    fun recordSendResult(packet: Packet, outcome: SendResult) =
        emit(if (packet.ack) "ACK_SENT" else "SEND_RESULT", packet, tier = null, outcome = outcome.name)

    // ---- receive-side ----

    /** A packet arriving at its intended destination (a real message) or as a delivery
     * receipt ([Packet.ack]) arriving back at the original sender. [tier] is the transport
     * it actually arrived over, so this doubles as the per-tier delivery count. */
    fun recordReceive(packet: Packet, tier: Tier) =
        emit(if (packet.ack) "ACK_RECEIVED" else "RECEIVED", packet, tier, outcome = null)

    // ---- energy / relay-activity sampling ----

    /** Periodic snapshot of battery + each mesh's connection count, the proxy this app has
     * for "how much is this device spending relaying for others" — neither bearer exposes
     * real per-message energy accounting, so this samples the same load signals
     * [com.capstone.chatapp.data.transport.TransportArbiter] already uses for congestion
     * scoring rather than inventing new instrumentation deep inside the mesh. */
    private fun recordEnergySample() {
        val ble = bleMeshManager.status.value
        val wifi = wifiDirectManager.status.value
        enqueue(
            csvRow(
                eventType = "ENERGY_SAMPLE",
                msgIdHash = "",
                tier = "",
                priority = "",
                hopCount = "",
                payloadBytes = "",
                outcome = "",
                batteryPct = (energyMonitor.batteryFraction() * 100).toInt().toString(),
                charging = energyMonitor.isCharging().toString(),
                bleNeighbors = ble.neighborCount.toString(),
                wifiDirectSockets = wifi.connectedSockets.toString(),
            )
        )
    }

    // ---- shared row builder ----

    private fun emit(eventType: String, packet: Packet, tier: Tier?, outcome: String?) {
        if (!enabled) return
        // INTERNET has no relay hops to decrement a TTL over -- a Firestore-delivered
        // packet is always a direct, single-hop arrival regardless of its ttl field.
        val hopCount = if (tier == Tier.INTERNET) 0 else (BleConstants.DEFAULT_TTL - packet.ttl).coerceAtLeast(0)
        enqueue(
            csvRow(
                eventType = eventType,
                msgIdHash = hashId(packet.msgId),
                tier = tier?.name.orEmpty(),
                priority = packet.priority.name,
                hopCount = hopCount.toString(),
                payloadBytes = packet.payload.size.toString(),
                outcome = outcome.orEmpty(),
                batteryPct = "",
                charging = "",
                bleNeighbors = "",
                wifiDirectSockets = "",
            )
        )
    }

    private fun csvRow(
        eventType: String,
        msgIdHash: String,
        tier: String,
        priority: String,
        hopCount: String,
        payloadBytes: String,
        outcome: String,
        batteryPct: String,
        charging: String,
        bleNeighbors: String,
        wifiDirectSockets: String,
    ): String = listOf(
        System.currentTimeMillis().toString(), eventType, msgIdHash, tier, priority,
        hopCount, payloadBytes, outcome, batteryPct, charging, bleNeighbors, wifiDirectSockets,
    ).joinToString(",")

    private fun enqueue(row: String) {
        if (!enabled) return
        rows.trySend(row)
    }

    /** Flushes buffered rows and returns the CSV's absolute path for the debug "Export
     * metrics" action — null in a release build, or if nothing has been recorded yet. */
    fun exportPath(): String? {
        if (!enabled) return null
        writer?.let { runCatching { it.flush() } }
        return sessionFile.absolutePath
    }

    companion object {
        private const val HEADER =
            "timestampMs,eventType,msgIdHash,tier,priority,hopCount,payloadBytes,outcome," +
                "batteryPct,charging,bleNeighbors,wifiDirectSockets"

        /** Unsalted SHA-256, truncated to 16 hex chars: enough to avoid collisions at
         * demo/evaluation scale, short enough to stay readable in the CSV, and deterministic
         * so the same id hashes identically on every phone for the cross-device join
         * described in docs/EVALUATION.md. */
        fun hashId(id: String?): String {
            if (id.isNullOrEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
