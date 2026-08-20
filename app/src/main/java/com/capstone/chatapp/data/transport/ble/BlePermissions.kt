package com.capstone.chatapp.data.transport.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * The runtime permissions the BLE mesh needs, which differ sharply by Android version:
 *  - Android 12+ (API 31): BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT.
 *    We declare `neverForLocation` on SCAN in the manifest, so no location permission
 *    is needed — but that also means we must never derive location from scan results.
 *  - Android 11 and below (API <= 30): BLUETOOTH / BLUETOOTH_ADMIN are install-time,
 *    but scanning additionally requires ACCESS_FINE_LOCATION **and** the system
 *    location toggle to actually be on (see [isLocationServiceOn]).
 *  - Android 13+ (API 33): POST_NOTIFICATIONS, only so the foreground-service
 *    notification is visible. The mesh works fine without it.
 *
 * [essential] and [optional] are deliberately separate: the mesh must still start when
 * the user declines the notification permission. Treating the whole array as
 * all-or-nothing was silently blocking the mesh on Android 13+.
 */
object BlePermissions {

    /** Permissions without which the mesh physically cannot run. */
    fun essential(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Nice-to-have permissions; a denial must not stop the mesh. */
    fun optional(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    /** Everything worth asking for in one prompt sequence. */
    fun required(): Array<String> = essential() + optional()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True when the mesh has what it needs. Notifications are NOT part of this. */
    fun essentialsGranted(context: Context): Boolean = essential().all { isGranted(context, it) }

    /** Kept for callers that want the full picture, including notifications. */
    fun allGranted(context: Context): Boolean = required().all { isGranted(context, it) }

    /** The essential permissions still missing, for logging and user-facing copy. */
    fun missingEssential(context: Context): List<String> =
        essential().filterNot { isGranted(context, it) }

    /**
     * On Android 11 and below the location *toggle* must be on for BLE scans to return
     * any results — the permission alone is not enough, and the scan fails silently
     * (no callback, no error). Android 12+ with `neverForLocation` is exempt.
     */
    fun locationServiceRequired(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    fun isLocationServiceOn(context: Context): Boolean {
        if (!locationServiceRequired()) return true
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /** Short label for logs/snackbars, e.g. "BLUETOOTH_SCAN, BLUETOOTH_CONNECT". */
    fun describe(permissions: List<String>): String =
        permissions.joinToString(", ") { it.substringAfterLast('.') }
}
