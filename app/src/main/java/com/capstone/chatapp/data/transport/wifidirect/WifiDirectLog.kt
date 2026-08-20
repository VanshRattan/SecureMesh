package com.capstone.chatapp.data.transport.wifidirect

import android.util.Log

/**
 * Every line the Wi-Fi Direct tier emits goes through here, under one logcat tag, mirroring
 * [com.capstone.chatapp.data.transport.ble.BleLog]'s `STEP | key=value` format so both
 * transports can be followed the same way:
 *
 *     adb logcat -s SafeSphereWifiDirect
 */
object WifiDirectLog {

    const val TAG = "SafeSphereWifiDirect"

    object Step {
        const val START = "WD_START"
        const val STOP = "WD_STOP"
        const val PRECHECK = "WD_PRECHECK"
        const val PERMISSION = "WD_PERMISSION"
        const val CHANNEL_LOST = "WD_CHANNEL_LOST"
        const val DISCOVER_START = "WD_DISCOVER_START"
        const val DISCOVER_FAIL = "WD_DISCOVER_FAIL"
        const val PEERS_CHANGED = "WD_PEERS_CHANGED"
        const val CONNECT_REQUEST = "WD_CONNECT_REQUEST"
        const val CONNECT_FAIL = "WD_CONNECT_FAIL"
        const val CONNECTION_CHANGED = "WD_CONNECTION_CHANGED"
        const val GROUP_FORMED = "WD_GROUP_FORMED"
        const val GROUP_LOST = "WD_GROUP_LOST"
        const val SERVER_START = "WD_SERVER_START"
        const val SERVER_FAIL = "WD_SERVER_FAIL"
        const val SERVER_STOP = "WD_SERVER_STOP"
        const val CLIENT_ACCEPTED = "WD_CLIENT_ACCEPTED"
        const val CLIENT_CONNECT = "WD_CLIENT_CONNECT"
        const val CLIENT_CONNECT_OK = "WD_CLIENT_CONNECT_OK"
        const val CLIENT_CONNECT_FAIL = "WD_CLIENT_CONNECT_FAIL"
        const val SOCKET_CLOSED = "WD_SOCKET_CLOSED"
        const val TX_OK = "WD_TX_OK"
        const val TX_FAIL = "WD_TX_FAIL"
        const val RX_PACKET = "WD_RX_PACKET"
        const val RX_BAD = "WD_RX_BAD"
        const val RELAY = "WD_RELAY"
    }

    fun d(step: String, vararg pairs: Pair<String, Any?>) = Log.d(TAG, line(step, pairs))
    fun i(step: String, vararg pairs: Pair<String, Any?>) = Log.i(TAG, line(step, pairs))
    fun w(step: String, vararg pairs: Pair<String, Any?>) = Log.w(TAG, line(step, pairs))
    fun e(step: String, t: Throwable?, vararg pairs: Pair<String, Any?>) =
        Log.e(TAG, line(step, pairs), t)

    private fun line(step: String, pairs: Array<out Pair<String, Any?>>): String =
        if (pairs.isEmpty()) step
        else step + " | " + pairs.joinToString(" ") { (k, v) -> "$k=${v ?: "-"}" }

    /** MAC addresses are personal data; show enough to tell two phones apart. */
    fun shortAddr(address: String?): String {
        if (address.isNullOrBlank()) return "-"
        return address.takeLast(5)
    }

    fun shortId(msgId: String?): String {
        if (msgId.isNullOrBlank()) return "-"
        return msgId.take(8)
    }
}
