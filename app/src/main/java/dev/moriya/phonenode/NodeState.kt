package dev.moriya.phonenode

import android.content.Intent
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide liveness state shared by the service and the status server.
 *
 * The helper app judges continuity from these values: if [serviceStartedAtMs]
 * changes, the process was restarted; if [heartbeat] stops advancing, the
 * service is stuck or dead even if something still answers on the port.
 */
object NodeState {
    @Volatile
    var serviceStartedAtMs: Long = 0L

    val heartbeat = AtomicLong(0L)

    @Volatile
    var proxyListening: Boolean = false

    @Volatile
    var prootRunning: Boolean = false

    /** Most recent caught subsystem failure, one line, for the UI. */
    @Volatile
    var lastError: String? = null

    /** Human-readable state of the Drive self-update check. */
    @Volatile
    var updateStatus: String = "-"

    /** True while the main screen is in the foreground. The platform
     * tends to demand an install confirmation when the app being
     * updated is on screen, so the updater defers while this is set. */
    @Volatile
    var uiOpen: Boolean = false

    /** Installer confirmation intent for an update that the platform
     * refused to apply without user action, kept in memory so the main
     * screen can offer an install button. The notification posted by
     * UpdateReceiver is the durable copy (it survives process death);
     * this field is only a convenience while the process lives. */
    @Volatile
    var pendingConfirmIntent: Intent? = null

    /** Build id that [pendingConfirmIntent] belongs to; 0 when none. */
    @Volatile
    var pendingConfirmBuild: Long = 0L

    fun uptimeMs(nowMs: Long): Long =
        if (serviceStartedAtMs == 0L) 0L else nowMs - serviceStartedAtMs
}
