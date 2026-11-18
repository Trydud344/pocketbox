package dev.pocketbox.app.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.pocketbox.app.PocketboxApp
import dev.pocketbox.app.MainActivity
import dev.pocketbox.app.R
import dev.pocketbox.runtime.supervisor.ServerState
import kotlinx.coroutines.launch

/**
 * Foreground service (`specialUse`) that owns the Supervisor, so the server
 * survives the UI being swiped away (brief section 5.5 / 8.1).
 */
class ServerService : LifecycleService() {

    companion object {
        const val CHANNEL_STATUS = "pb_status"
        const val CHANNEL_ERRORS = "pb_errors"
        const val ACTION_START = "dev.pocketbox.app.action.START"
        const val ACTION_STOP = "dev.pocketbox.app.action.STOP"
        const val ACTION_RESTART = "dev.pocketbox.app.action.RESTART"
        private const val NOTIF_ID = 42

        fun start(context: Context, action: String = ACTION_START) {
            val intent = Intent(context, ServerService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }

    private val container get() = (application as PocketboxApp).container

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat("Preparing")
        lifecycleScope.launch {
            container.supervisor.status.collect { st ->
                when (st.state) {
                    ServerState.RUNNING -> {
                        updateNotification(
                            "OpenCode server running on 127.0.0.1:${st.port}",
                            running = true,
                        )
                        updateWakeLock(container.settings.keepCpuAwake.value, hold = true)
                    }
                    ServerState.STARTING -> updateNotification("Starting server…", running = false)
                    ServerState.BACKOFF -> updateNotification("Server restarting…", running = false)
                    ServerState.ERROR -> updateNotification(
                        "Server error — open Pocketbox for details",
                        running = false,
                        error = true,
                    )
                    else -> updateNotification("Server stopped", running = false)
                }
                if (st.state != ServerState.RUNNING) updateWakeLock(hold = false)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> lifecycleScope.launch {
                container.supervisor.stop()
                stopSelf()
            }
            ACTION_RESTART -> lifecycleScope.launch { container.supervisor.restart() }
            else -> lifecycleScope.launch { container.supervisor.start() }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text, running = false)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String, running: Boolean, error: Boolean = false) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, running, error))
    }

    private fun buildNotification(text: String, running: Boolean, error: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val restartIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ServerService::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, if (error) CHANNEL_ERRORS else CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Pocketbox")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stopIntent)
            .addAction(0, "Restart", restartIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateWakeLock(hold: Boolean) = updateWakeLock(container.settings.keepCpuAwake.value, hold)

    /** Optional "keep CPU awake" while running (brief 8.4). */
    private fun updateWakeLock(keepAwake: Boolean, hold: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (hold && keepAwake) {
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "pocketbox:server-running",
                ).apply { setReferenceCounted(false) }
            }
            if (!wakeLock!!.isHeld) wakeLock!!.acquire(12 * 60 * 60 * 1000L) // safety cap: 12 h
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    override fun onDestroy() {
        updateWakeLock(hold = false)
        super.onDestroy()
    }
}
