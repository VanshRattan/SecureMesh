package com.capstone.chatapp.data.transport.wifidirect

/**
 * Fixed identifiers and tuning knobs for the Wi-Fi Direct tier. See [WifiDirectManager] for
 * the group-owner/socket topology these numbers apply to.
 */
object WifiDirectConstants {

    /**
     * Every stock-Android Wi-Fi Direct group owner is assigned this address by the
     * framework — it is not configurable per-app, so it is safe to hard-code as the
     * well-known GO address rather than discover it (we still read
     * [android.net.wifi.p2p.WifiP2pInfo.groupOwnerAddress] at connect time; this constant
     * only documents why that value is always the same one on real devices).
     */
    const val GROUP_OWNER_HOST: String = "192.168.49.1"

    /** TCP port the group owner listens on for the packet-relay socket. */
    const val SOCKET_PORT: Int = 8988

    /** How long a client's connect() to the group owner may block before giving up. */
    const val SOCKET_CONNECT_TIMEOUT_MS: Int = 10_000

    /** Pending-connection queue depth for the group owner's [java.net.ServerSocket]. */
    const val SERVER_BACKLOG: Int = 8

    /** Length-prefix size (bytes) for framing [com.capstone.chatapp.data.transport.Packet]
     * bytes over the TCP stream — TCP has no message boundaries of its own. */
    const val LENGTH_PREFIX_SIZE: Int = 4

    /** Sanity cap so a corrupt length prefix can't make us allocate an enormous buffer. */
    const val MAX_FRAME_SIZE: Int = 1 shl 20 // 1 MiB

    /** Peer discovery auto-stops after ~2 minutes on most OEMs; re-issued on this cadence
     * so the peer list keeps refreshing while the tier is running and not yet grouped. */
    const val DISCOVERY_REISSUE_MS: Long = 20_000

    /** Backoff before a client reconnects to the group owner after a socket drop. */
    const val RECONNECT_BACKOFF_MS: Long = 3_000
}
