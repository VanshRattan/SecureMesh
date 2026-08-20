package com.capstone.chatapp.data.transport.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.ScanCallback
import android.util.Log

/**
 * Every line the BLE mesh emits goes through here, under one logcat tag so a whole
 * two-phone test can be followed with a single filter:
 *
 *     adb logcat -s SafeSphereBLE
 *
 * Lines are structured as `STEP | key=value key=value` so they can be eyeballed or
 * grepped for a specific stage (`adb logcat -s SafeSphereBLE | grep RX_PACKET`).
 * See docs/RUNTIME_TEST.md for the expected sequence during a successful SOS.
 */
object BleLog {

    const val TAG = "SafeSphereBLE"

    /** Steps, kept as constants so the log vocabulary can't drift between call sites. */
    object Step {
        const val START = "MESH_START"
        const val STOP = "MESH_STOP"
        const val PRECHECK = "PRECHECK"
        const val ADV_START = "ADV_START"
        const val ADV_OK = "ADV_OK"
        const val ADV_FAIL = "ADV_FAIL"
        const val ADV_STOP = "ADV_STOP"
        const val ADV_RESTART = "ADV_RESTART"
        const val GATT_SERVER_OPEN = "GATT_SERVER_OPEN"
        const val GATT_SERVER_READY = "GATT_SERVER_READY"
        const val GATT_SERVER_FAIL = "GATT_SERVER_FAIL"
        const val GATT_SERVER_CLOSE = "GATT_SERVER_CLOSE"
        const val GATT_SERVER_CONN = "GATT_SERVER_CONN"
        const val SCAN_START = "SCAN_START"
        const val SCAN_FAIL = "SCAN_FAIL"
        const val SCAN_STOP = "SCAN_STOP"
        const val SCAN_RESULT = "SCAN_RESULT"
        const val NEIGHBOR_ADD = "NEIGHBOR_ADD"
        const val NEIGHBOR_DROP = "NEIGHBOR_DROP"
        const val IDENTITY_REQ = "IDENTITY_REQ"
        const val IDENTITY_OK = "IDENTITY_OK"
        const val IDENTITY_FAIL = "IDENTITY_FAIL"
        const val IDENTITY_SERVED = "IDENTITY_SERVED"
        const val TX_QUEUE = "TX_QUEUE"
        const val TX_CONNECT = "TX_CONNECT"
        const val TX_MTU = "TX_MTU"
        const val TX_WRITE = "TX_WRITE"
        const val TX_OK = "TX_OK"
        const val TX_FAIL = "TX_FAIL"
        const val RX_PACKET = "RX_PACKET"
        const val RX_BAD = "RX_BAD"
        const val DEDUP_DROP = "DEDUP_DROP"
        const val RELAY_SEND = "RELAY_SEND"
        const val TTL_DROP = "TTL_DROP"
        const val OUTBOX = "OUTBOX"
        const val SOS_SEND = "SOS_SEND"
        const val BT_STATE = "BT_STATE"
        const val SERVICE = "SERVICE"
        const val PERMISSION = "PERMISSION"
        const val QUEUE_FULL = "QUEUE_FULL"
        const val RETRY_SCHEDULE = "RETRY_SCHEDULE"
        const val REASSEMBLY_DROP = "REASSEMBLY_DROP"
        const val DUTY_CYCLE = "DUTY_CYCLE"
    }

    fun d(step: String, vararg pairs: Pair<String, Any?>) = Log.d(TAG, line(step, pairs))
    fun i(step: String, vararg pairs: Pair<String, Any?>) = Log.i(TAG, line(step, pairs))
    fun w(step: String, vararg pairs: Pair<String, Any?>) = Log.w(TAG, line(step, pairs))
    fun e(step: String, t: Throwable?, vararg pairs: Pair<String, Any?>) =
        Log.e(TAG, line(step, pairs), t)

    private fun line(step: String, pairs: Array<out Pair<String, Any?>>): String =
        if (pairs.isEmpty()) step
        else step + " | " + pairs.joinToString(" ") { (k, v) -> "$k=${v ?: "-"}" }

    /**
     * MAC addresses are personal data and make logs noisy; show enough to tell two
     * phones apart (`..:A1:B2`) without printing the whole address.
     */
    fun shortAddr(address: String?): String {
        if (address.isNullOrBlank()) return "-"
        return address.takeLast(5)
    }

    /** First 8 chars of a msgId — enough to follow one message across both phones. */
    fun shortId(msgId: String?): String {
        if (msgId.isNullOrBlank()) return "-"
        return msgId.take(8)
    }

    /** Human-readable names for the GATT status codes that actually show up in the field. */
    fun gattStatus(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
        BluetoothGatt.GATT_READ_NOT_PERMITTED -> "READ_NOT_PERMITTED"
        BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "WRITE_NOT_PERMITTED"
        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "INSUFFICIENT_AUTHENTICATION"
        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "REQUEST_NOT_SUPPORTED"
        BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "INSUFFICIENT_ENCRYPTION"
        BluetoothGatt.GATT_INVALID_OFFSET -> "INVALID_OFFSET"
        BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "INVALID_ATTRIBUTE_LENGTH"
        BluetoothGatt.GATT_CONNECTION_CONGESTED -> "CONNECTION_CONGESTED"
        BluetoothGatt.GATT_FAILURE -> "FAILURE"
        8 -> "CONN_TIMEOUT(8)"
        19 -> "TERMINATED_BY_PEER(19)"
        22 -> "TERMINATED_LOCAL_HOST(22)"
        // The infamous catch-all: usually too many open GATT clients, or a connect
        // that raced a disconnect. We close and let the next scan retry.
        133 -> "GATT_ERROR(133)"
        else -> "STATUS_$status"
    }

    fun advertiseError(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        // The common one on cheap phones: the chipset has no peripheral role.
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        else -> "ADV_ERROR_$code"
    }

    fun scanError(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        5 -> "OUT_OF_HARDWARE_RESOURCES"
        6 -> "SCANNING_TOO_FREQUENTLY"
        else -> "SCAN_ERROR_$code"
    }
}
