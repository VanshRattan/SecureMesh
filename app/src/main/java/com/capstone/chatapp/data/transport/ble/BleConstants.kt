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

    /** Backoff before retrying advertising/scanning after a recoverable failure. */
    const val RESTART_BACKOFF_MS: Long = 3_000
}
