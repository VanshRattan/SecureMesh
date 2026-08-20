package com.capstone.chatapp.data.transport.wifidirect

/**
 * A device discovered nearby via [android.net.wifi.p2p.WifiP2pManager] peer discovery,
 * before any socket/group connection exists. Not to be confused with
 * [com.capstone.chatapp.data.transport.ble.NearbyPeer] — this is app-identity-free (Wi-Fi
 * Direct only exposes the device name/address at this stage; the app-level uid/name would
 * need its own exchange once a socket is up, same as the BLE mesh's identity characteristic).
 */
data class WifiDirectPeer(
    val deviceName: String,
    val deviceAddress: String,
    /** Raw [android.net.wifi.p2p.WifiP2pDevice.status] — kept for debugging/UI, not
     * re-interpreted here (AVAILABLE / INVITED / CONNECTED / FAILED / UNAVAILABLE). */
    val status: Int,
)
