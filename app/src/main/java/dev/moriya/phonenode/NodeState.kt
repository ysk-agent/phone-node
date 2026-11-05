package dev.moriya.phonenode

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

    fun uptimeMs(nowMs: Long): Long =
        if (serviceStartedAtMs == 0L) 0L else nowMs - serviceStartedAtMs
}
