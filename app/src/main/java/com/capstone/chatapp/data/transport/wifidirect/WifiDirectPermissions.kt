package com.capstone.chatapp.data.transport.wifidirect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.capstone.chatapp.data.transport.ble.BlePermissions

/**
 * The runtime permissions Wi-Fi Direct peer discovery/connect needs. Structured the same
 * way as [BlePermissions] (which this deliberately does not duplicate the location-toggle
 * check from — see [locationServiceRequired] below).
 *
 *  - Android 13+ (API 33): [Manifest.permission.NEARBY_WIFI_DEVICES], declared with
 *    `neverForLocation` in the manifest since we never derive physical location from scan
 *    results — same reasoning the BLE tier uses for `BLUETOOTH_SCAN`.
 *  - Android 12 and below (API <= 32): [Manifest.permission.ACCESS_FINE_LOCATION].
 *    Unlike BLE (which is exempt via `neverForLocation` starting at API 31), Wi-Fi Direct
 *    peer discovery is tied to the platform's Wi-Fi scan subsystem and requires location
 *    up through API 32 regardless.
 */
object WifiDirectPermissions {

    fun essential(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun essentialsGranted(context: Context): Boolean = essential().all { isGranted(context, it) }

    fun missingEssential(context: Context): List<String> =
        essential().filterNot { isGranted(context, it) }

    /**
     * Same platform quirk BLE has below API 31: the location *toggle*, not just the
     * permission, must be on for a Wi-Fi scan (which peer discovery is built on) to return
     * results below API 33. Reuses [BlePermissions]'s check rather than duplicating it —
     * the logic isn't actually BLE-specific, it's a general pre-13 Android scan requirement.
     */
    fun locationServiceRequired(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    fun isLocationServiceOn(context: Context): Boolean =
        !locationServiceRequired() || BlePermissions.isLocationServiceOn(context)

    fun describe(permissions: List<String>): String =
        permissions.joinToString(", ") { it.substringAfterLast('.') }
}
