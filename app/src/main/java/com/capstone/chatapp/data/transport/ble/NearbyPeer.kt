package com.capstone.chatapp.data.transport.ble

/**
 * A person discovered physically nearby over the BLE mesh. Identity ([uid]/[name]) is
 * read from the peer's identity characteristic after its advertisement is scanned;
 * [address] is the BLE MAC used to key the connection.
 */
data class NearbyPeer(
    val uid: String,
    val name: String,
    val address: String,
    val lastSeen: Long,
)
