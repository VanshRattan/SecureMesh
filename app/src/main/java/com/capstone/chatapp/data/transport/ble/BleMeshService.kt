package com.capstone.chatapp.data.transport.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.capstone.chatapp.ChatApp
import com.capstone.chatapp.MainActivity
import com.capstone.chatapp.R

/**
 * Foreground service that keeps the BLE mesh (advertising + scanning + relaying) alive
 * while the app is backgrounded or the screen is off. It does not own the mesh state —
 * it drives the singleton [BleMeshManager] held in the app's DI container so the UI can
 * observe the same instance.
 *
 * Two real-device hazards are handled here:
 *  - START_STICKY redelivers a **null** intent after the process is killed, so the
 *    identity is cached in SharedPreferences instead of read from extras every time.
 *    Without this the mesh came back up advertising an empty uid, and peers labelled it
 *    "Someone" forever.
 *  - Android 14 throws from `startForeground` when a `connectedDevice` service starts
 *    without the Bluetooth permissions actually granted, and Android 12+ throws when a
 *    foreground service is started from the background. Both are caught and logged
 *    rather than crashing the app mid-emergency.
 */
class BleMeshService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private val mesh get() = (application as ChatApp).container.bleMeshManager

    private val prefs: SharedPreferences
        get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BleLog.i(BleLog.Step.SERVICE, "action" to "STOP")
            mesh.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // A null intent means the system restarted us (START_STICKY): fall back to the
        // identity we cached the first time round.
        val selfId = intent?.getStringExtra(EXTRA_SELF_ID)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(EXTRA_SELF_ID, null).orEmpty()
        val selfName = intent?.getStringExtra(EXTRA_SELF_NAME)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(EXTRA_SELF_NAME, null).orEmpty()

        BleLog.i(
            BleLog.Step.SERVICE,
            "action" to "START",
            "restarted" to (intent == null),
            "selfId" to BleLog.shortId(selfId),
            "selfName" to selfName,
        )

        if (selfId.isNotBlank()) {
            prefs.edit().putString(EXTRA_SELF_ID, selfId).putString(EXTRA_SELF_NAME, selfName).apply()
        }

        if (!startForegroundNotification()) {
            // We could not become a foreground service, so we would be killed within
            // seconds. Stop cleanly instead of pretending the mesh is running.
            stopSelf()
            return START_NOT_STICKY
        }

        mesh.start(selfId, selfName)
        return START_STICKY
    }

    override fun onDestroy() {
        BleLog.i(BleLog.Step.SERVICE, "action" to "DESTROY")
        super.onDestroy()
        mesh.stop()
    }

    /** True when the service successfully entered the foreground. */
    private fun startForegroundNotification(): Boolean {
        createChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Emergency mesh active")
            .setContentText("Listening for and relaying nearby emergency messages over Bluetooth.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            BleLog.i(BleLog.Step.SERVICE, "foreground" to true, "type" to "connectedDevice")
            true
        } catch (e: Exception) {
            // API 34: SecurityException when the connectedDevice type is not backed by a
            // granted Bluetooth permission. API 31+: ForegroundServiceStartNotAllowed
            // when started from the background.
            BleLog.e(
                BleLog.Step.SERVICE, e,
                "foreground" to false,
                "reason" to (e.javaClass.simpleName),
                "missing" to BlePermissions.describe(BlePermissions.missingEssential(this)),
            )
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Emergency Mesh", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "emergency_mesh"
        private const val NOTIFICATION_ID = 42
        private const val PREFS = "ble_mesh_service"
        const val ACTION_START = "com.capstone.chatapp.MESH_START"
        const val ACTION_STOP = "com.capstone.chatapp.MESH_STOP"
        const val EXTRA_SELF_ID = "self_id"
        const val EXTRA_SELF_NAME = "self_name"

        fun start(context: Context, selfId: String, selfName: String) {
            val intent = Intent(context, BleMeshService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SELF_ID, selfId)
                putExtra(EXTRA_SELF_NAME, selfName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ refuses foreground-service starts from the background.
                BleLog.e(BleLog.Step.SERVICE, e, "action" to "START", "result" to "rejected")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleMeshService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
                .onFailure { BleLog.w(BleLog.Step.SERVICE, "action" to "STOP", "error" to it.message) }
        }
    }
}
