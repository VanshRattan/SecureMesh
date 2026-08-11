package com.capstone.chatapp.data.transport.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.capstone.chatapp.ChatApp
import com.capstone.chatapp.R

/**
 * Foreground service that keeps the BLE mesh (advertising + scanning + relaying) alive
 * while the app is backgrounded or the screen is off. It doesn't own the mesh state —
 * it drives the singleton [BleMeshManager] held in the app's DI container so the UI can
 * observe the same instance.
 */
class BleMeshService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private val mesh get() = (application as ChatApp).container.bleMeshManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                mesh.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val selfId = intent?.getStringExtra(EXTRA_SELF_ID).orEmpty()
                val selfName = intent?.getStringExtra(EXTRA_SELF_NAME).orEmpty()
                startForegroundNotification()
                mesh.start(selfId, selfName)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mesh.stop()
    }

    private fun startForegroundNotification() {
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Emergency mesh active")
            .setContentText("Listening for and relaying nearby emergency messages over Bluetooth.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleMeshService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
