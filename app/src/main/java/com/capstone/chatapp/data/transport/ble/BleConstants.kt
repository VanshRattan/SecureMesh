package com.capstone.chatapp.data.transport.ble

import java.util.UUID

/**
 * Fixed identifiers for the app's BLE mesh. Every device advertises [SERVICE_UUID]
 * and hosts [MESSAGE_CHARACTERISTIC_UUID] as a writable characteristic that peers
 * write emergency packets into.
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
}
