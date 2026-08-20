package com.capstone.chatapp.navigation

import android.net.Uri

/**
 * Type-safe-ish route definitions for the NavHost.
 * Chat carries the peer's uid + display name as encoded path/query args.
 */
sealed class Routes(val route: String) {
    data object Login : Routes("login")
    data object Signup : Routes("signup")
    data object Home : Routes("home")
    data object Discover : Routes("discover")
    data object Profile : Routes("profile")
    data object Emergency : Routes("emergency")

    data object Chat : Routes("chat/{peerUid}?name={peerName}") {
        const val ARG_PEER_UID = "peerUid"
        const val ARG_PEER_NAME = "peerName"
        fun build(peerUid: String, peerName: String): String =
            "chat/$peerUid?name=${Uri.encode(peerName)}"
    }

    /** QR pairing / safety-number verification. [peerUid]/[peerName] are optional: opened from
     *  Profile with neither (just shows my own QR + a scanner), or from a chat with both
     *  (also shows that contact's verified state + safety number). */
    data object Pairing : Routes("pairing?peerUid={peerUid}&peerName={peerName}") {
        const val ARG_PEER_UID = "peerUid"
        const val ARG_PEER_NAME = "peerName"
        fun build(peerUid: String? = null, peerName: String? = null): String {
            val uidPart = peerUid?.let { Uri.encode(it) }.orEmpty()
            val namePart = peerName?.let { Uri.encode(it) }.orEmpty()
            return "pairing?peerUid=$uidPart&peerName=$namePart"
        }
    }
}
