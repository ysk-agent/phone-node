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

    @Volatile
    private var probeRunning = false

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
                // Development cadence (Moriya, 2026-11-06): while the
                // bring-up loop is running, log export and the Drive
                // update check both run every 3 ticks = 30 seconds.
                // Relax both to minutes once the node is stable.
                if (ticks == 1L || ticks % 3L == 0L) {
                    LogExport.export(this@NodeService)
                    UpdateManager.checkAsync(this@NodeService)
                }
                // Mutual watchdog: every 10 minutes, make sure the
                // helper's service is started. Starting an already
                // running service is a harmless onStartCommand.
                if (ticks % 60L == 0L) {
                    ensureHelperStarted()
                }
            }
        }

        // Health probe for the two guest opencode serves. The guest
        // shares this device's network namespace (proot does not
        // isolate it), so the serves are reachable on loopback from
        // here. Any HTTP 200 on /global/health counts as running:
        // v1's health payload is known, v2's shape is not verified
        // on a device yet, so the probe deliberately does not parse
        // the body.
        probeRunning = true
        thread(name = "opencode-health-probe", isDaemon = true) {
            while (probeRunning) {
                NodeState.opencodeV1Running = probeHealth(4096)
                NodeState.opencodeV2Running = probeHealth(4098)
                try {
                    Thread.sleep(5_000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun probeHealth(port: Int): Boolean {
        return try {
            val conn = java.net.URL("http://127.0.0.1:$port/global/health")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            conn.useCaches = false
            try {
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        // Clear the liveness marker FIRST: the main screen's switch
        // reads it to display the actual service state, and the
        // teardown below (proot tree kill) can take a moment.
        NodeState.serviceStartedAtMs = 0L
        heartbeatRunning = false
        probeRunning = false
        NodeState.opencodeV1Running = false
        NodeState.opencodeV2Running = false
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
            // The return value matters: an unresolvable start (no
            // <queries> visibility, Android 11+) returns null instead
            // of throwing. Log both outcomes; a silent no-op here is
            // what hid the broken resurrection edge until 2026-11-14.
            val started = startForegroundService(intent)
            if (started == null) {
                android.util.Log.w("PhoneNode", "HelperService start returned null (not found)")
            }
        } catch (e: Exception) {
            // Helper not installed or background start restricted; the
            // helper also resurrects itself via its boot receiver.
            android.util.Log.w("PhoneNode", "HelperService start failed: $e")
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

        /**
         * Clean stop via the normal service lifecycle: onDestroy runs
         * the full teardown (status server, proxy, proot tree, wake
         * lock). Deliberately NOT a force-stop — the package never
         * enters the stopped state, so external starters (helper,
         * waker, the Moka VPN boot-management extension) can still
         * revive the service afterwards. That difference is the whole
         * reason the main screen's switch exists.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, NodeService::class.java))
        }
    }
}
