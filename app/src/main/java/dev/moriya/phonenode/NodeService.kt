package dev.moriya.phonenode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlin.concurrent.thread

/**
 * Resident foreground service: the process MIUI must not kill.
 *
 * Holds a partial wake lock, advances [NodeState.heartbeat] every 10
 * seconds, and hosts the loopback status endpoint. Heavy children (proot,
 * sshd, opencode) will be supervised from here in a later step, kept
 * separate from this light watchdog logic on purpose: memory pressure
 * should kill the heavy side first, leaving this code able to restart it.
 */
class NodeService : Service() {

    private var statusServer: StatusServer? = null
    private var proxyServer: ProxyServer? = null
    private var prootManager: ProotManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var heartbeatRunning = false

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        NodeState.serviceStartedAtMs = System.currentTimeMillis()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Each subsystem starts behind its own guard: one failing part
        // must degrade (and record why) instead of crash-looping the
        // whole service, which the helper would keep resurrecting.
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "phonenode:node"
            ).apply { acquire() }
        } catch (t: Throwable) {
            CrashLog.record(this, "wakelock", t)
        }
        try {
            statusServer = StatusServer().also { it.start() }
        } catch (t: Throwable) {
            CrashLog.record(this, "status-server", t)
        }
        try {
            proxyServer = ProxyServer().also { it.start() }
        } catch (t: Throwable) {
            CrashLog.record(this, "proxy", t)
        }
        try {
            prootManager = ProotManager(this).also { it.startSupervision() }
        } catch (t: Throwable) {
            CrashLog.record(this, "proot", t)
        }

        heartbeatRunning = true
        thread(name = "heartbeat", isDaemon = true) {
            var ticks = 0L
            while (heartbeatRunning) {
                try {
                    Thread.sleep(10_000)
                } catch (e: InterruptedException) {
                    break
                }
                NodeState.heartbeat.incrementAndGet()
                ticks++
                // Debug-log export: once shortly after start, then
                // every 5 minutes, to the user-picked folder (Drive).
                if (ticks == 1L || ticks % 30L == 0L) {
                    LogExport.export(this@NodeService)
                }
                // Mutual watchdog: every 10 minutes, make sure the
                // helper's service is started. Starting an already
                // running service is a harmless onStartCommand.
                if (ticks % 60L == 0L) {
                    ensureHelperStarted()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatRunning = false
        statusServer?.stop()
        statusServer = null
        proxyServer?.stop()
        proxyServer = null
        prootManager?.stop()
        prootManager = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureHelperStarted() {
        val intent = Intent().apply {
            component = android.content.ComponentName(
                "dev.moriya.phonenode.helper",
                "dev.moriya.phonenode.helper.HelperService"
            )
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            // Helper not installed or background start restricted; the
            // helper also resurrects itself via its boot receiver.
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phone Node",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Node")
            .setContentText("Node service is running")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "phonenode"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, NodeService::class.java)
            context.startForegroundService(intent)
        }
    }
}
