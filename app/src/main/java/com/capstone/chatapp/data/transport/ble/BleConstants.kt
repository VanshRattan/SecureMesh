package com.capstone.chatapp.data.transport.ble

import java.util.UUID

/**
 * Fixed identifiers and tuning knobs for the app's BLE mesh. Every device advertises
 * [SERVICE_UUID] and hosts [MESSAGE_CHARACTERISTIC_UUID] as a writable characteristic
 * that peers write emergency packets into.
 */
object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("0000c0de-0000-1000-8000-00805f9b34fb")
    val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000c0d1-0000-1000-8000-00805f9b34fb")

    /** Readable characteristic that returns this device's "uid|name" for the Nearby list. */
    val IDENTITY_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000c0d2-0000-1000-8000-00805f9b34fb")

    /** Hop limit backstop for the flood (in case the reach counter is imperfect). */
    const val DEFAULT_TTL: Int = 10

    /** Approximate cap on how many devices a broadcast should reach. */
    const val REACH_CAP: Int = 100

    /** MTU we request so a short SOS message fits in a single write. */
    const val REQUESTED_MTU: Int = 185

    /**
     * Per-GATT-operation budget. Kept well under the old 8s because every client
     * operation is serialized: one unreachable neighbour used to stall the whole
     * outbound queue (and therefore the SOS) for 8 seconds.
     */
    const val GATT_OP_TIMEOUT_MS: Long = 6_000

    /** Consecutive write failures before a neighbour is dropped from the flood set. */
    const val NEIGHBOR_FAILURE_LIMIT: Int = 3

    /**
     * A neighbour not seen in a scan for this long is considered out of range and
     * evicted, so the outbound queue stops burning [GATT_OP_TIMEOUT_MS] on a phone
     * that walked away.
     */
    const val NEIGHBOR_STALE_MS: Long = 60_000

    /** Nearby-list entries are hidden once their identity read is this old. */
    const val PEER_STALE_MS: Long = 120_000

    /** How often the housekeeping sweep runs (neighbour + peer eviction, outbox expiry). */
    const val SWEEP_INTERVAL_MS: Long = 15_000

    /**
     * How long a packet stays in the outbox and is re-sent to newly discovered
     * neighbours. This is what makes "press SOS, then walk into range" work: without
     * it a broadcast only ever reaches phones that happened to be discovered already.
     */
    const val OUTBOX_TTL_MS: Long = 90_000

    /** Cap on outbox entries so a long session can't grow it without bound. */
    const val OUTBOX_MAX: Int = 32

    /** Bounded de-dup set: ids remembered before the oldest are evicted. */
    const val SEEN_MAX: Int = 500

    /** A seen msgId older than this is evicted even if [SEEN_MAX] hasn't been hit. */
    const val SEEN_TTL_MS: Long = 15 * 60_000

    /** Backoff before retrying advertising/scanning after a recoverable failure. */
    const val RESTART_BACKOFF_MS: Long = 3_000

    /**
     * Max GATT connections (writes + identity reads combined) the client role will run
     * at once. Android's stack tolerates a handful of concurrent connections fine but
     * degrades (status 133) well before double digits; 5 is a conservative default that
     * still lets a dense room make real progress instead of queuing one-at-a-time.
     */
    const val MAX_CONCURRENT_GATT_CONNECTIONS: Int = 5

    /** Outbound job queues (writes / identity reads) are bounded so a very dense room
     * can't grow them without limit; once full, new jobs are dropped politely (logged,
     * and for writes the delivery mark is rolled back so a later opportunity retries). */
    const val JOB_QUEUE_CAPACITY: Int = 256

    /** Failed connects/writes are retried with exponential backoff + jitter before the
     * neighbour is given up on (see [NEIGHBOR_FAILURE_LIMIT]). */
    const val RETRY_BASE_BACKOFF_MS: Long = 500
    const val RETRY_MAX_BACKOFF_MS: Long = 8_000
    const val RETRY_JITTER_MS: Long = 300

    /** Random delay before re-broadcasting a relayed packet, so phones that all just
     * received the same broadcast don't all hammer the same neighbours in the same instant. */
    const val REBROADCAST_JITTER_MAX_MS: Long = 400

    /** Per-chunk envelope: groupId(4) + seq(2) + total(2), see [BleChunk]. */
    const val CHUNK_HEADER_SIZE: Int = 8

    /** Floor for chunk payload size, matching the default (unrequested) ATT MTU of 23
     * minus the 3-byte ATT write header, so chunking still works if MTU negotiation fails. */
    const val CHUNK_MIN_PAYLOAD: Int = 20

    /** An incomplete multi-chunk reassembly older than this is dropped (sender vanished
     * mid-transfer, or a chunk was lost) so it can't leak memory forever. */
    const val REASSEMBLY_TTL_MS: Long = 20_000

    /** Scan duty-cycling: alternate ON/OFF windows to cut the battery cost of continuous
     * SCAN_MODE_LOW_LATENCY scanning. Enabled by default with a generous ON window so
     * discovery latency stays close to unaffected; set [SCAN_DUTY_CYCLE_ENABLED] = false
     * to scan continuously as before. */
    const val SCAN_DUTY_CYCLE_ENABLED: Boolean = true
    const val SCAN_WINDOW_ON_MS: Long = 10_000
    const val SCAN_WINDOW_OFF_MS: Long = 5_000

    /** Advertise duty-cycling is off by default: unlike scanning, an OFF window makes us
     * briefly undiscoverable, which is a bigger reliability risk than the battery it saves.
     * The knobs exist for anyone who wants to trade discoverability for battery life. */
    const val ADVERTISE_DUTY_CYCLE_ENABLED: Boolean = false
    const val ADVERTISE_WINDOW_ON_MS: Long = 10_000
    const val ADVERTISE_WINDOW_OFF_MS: Long = 5_000
}
