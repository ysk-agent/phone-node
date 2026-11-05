package dev.moriya.phonenode.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Watchdog for the main app: polls its loopback status endpoint every
 * minute and resurrects its node service when it is dead or wedged.
 *
 * Judgement is by continuity, not by "something answered": the poll
 * itself cannot wake the main process (a dead process refuses the
 * connection), and a process that restarted on its own is detected by
 * a changed serviceStartedAtMs and only recorded, not acted on.
 *
 *   - two consecutive poll failures      -> dead: start the service
 *   - heartbeat unchanged for two polls  -> wedged: start the service
 *     again (re-triggers onStartCommand; a truly wedged process may
 *     still need a force stop, which one app cannot do to another)
 *   - serviceStartedAtMs changed         -> it came back by itself;
 *     reset the baseline and count the event
 *
 * The helper is a separate package on purpose: MIUI's killer judges
 * the two apps separately, so they are unlikely to die at once, and
 * whichever survives can bring the other back.
 */
class HelperService : Service() {

    @Volatile
    private var running = false

    private var lastStartedAtMs: Long = -1L
    private var lastHeartbeat: Long = -1L
    private var stalePolls = 0
    private var failedPolls = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        running = true
        thread(name = "helper-watchdog", isDaemon = true) {
            while (running) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
                if (running) checkMainApp()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkMainApp() {
        val status = pollStatus()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (status == null) {
            failedPolls++
            stalePolls = 0
            if (failedPolls >= 2) {
                resurrectMainApp()
                prefs.edit()
                    .putInt("resurrectCount", prefs.getInt("resurrectCount", 0) + 1)
                    .putLong("lastResurrectAtMs", System.currentTimeMillis())
                    .apply()
                failedPolls = 0
            }
            prefs.edit().putString("mainState", "dead").apply()
            return
        }
        failedPolls = 0
        val (startedAt, heartbeat) = status
        when {
            lastStartedAtMs == -1L -> {
                lastStartedAtMs = startedAt
                lastHeartbeat = heartbeat
                prefs.edit().putString("mainState", "alive").apply()
            }
            startedAt != lastStartedAtMs -> {
                // The main process restarted without our help (system
                // sticky restart or the main app's own supervision).
                lastStartedAtMs = startedAt
                lastHeartbeat = heartbeat
                stalePolls = 0
                prefs.edit()
                    .putString("mainState", "restarted-by-itself")
                    .putInt(
                        "selfRestartCount",
                        prefs.getInt("selfRestartCount", 0) + 1
                    )
                    .apply()
            }
            heartbeat == lastHeartbeat -> {
                stalePolls++
                if (stalePolls >= 2) {
                    resurrectMainApp()
                    prefs.edit()
                        .putString("mainState", "wedged")
                        .putInt("resurrectCount", prefs.getInt("resurrectCount", 0) + 1)
                        .putLong("lastResurrectAtMs", System.currentTimeMillis())
                        .apply()
                    stalePolls = 0
                }
            }
            else -> {
                lastHeartbeat = heartbeat
                stalePolls = 0
                prefs.edit().putString("mainState", "alive").apply()
            }
        }
        prefs.edit()
            .putLong("lastCheckAtMs", System.currentTimeMillis())
            .apply()
    }

    /** Returns (serviceStartedAtMs, heartbeat) or null when unreachable. */
    private fun pollStatus(): Pair<Long, Long>? {
        return try {
            val conn = URL("http://127.0.0.1:18090/").openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val startedAt = extractLong(body, "serviceStartedAtMs") ?: return null
            val heartbeat = extractLong(body, "heartbeat") ?: return null
            startedAt to heartbeat
        } catch (e: Exception) {
            null
        }
    }

    private fun extractLong(json: String, key: String): Long? {
        val marker = "\"$key\":"
        val start = json.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = json.indexOfAny(charArrayOf(',', '}'), from)
        if (end < 0) return null
        return json.substring(from, end).trim().toLongOrNull()
    }

    private fun resurrectMainApp() {
        val intent = Intent().apply {
            component = ComponentName("dev.moriya.phonenode", "dev.moriya.phonenode.NodeService")
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            // Background start restrictions vary by vendor; the next
            // poll retries, and the state stays visible in the UI.
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phone Node Helper", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Node Helper")
            .setContentText("Watching the main node app")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "phonenode-helper"
        private const val NOTIFICATION_ID = 2
        private const val PREFS = "helper-state"
        private const val POLL_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, HelperService::class.java)
            context.startForegroundService(intent)
        }
    }
}
