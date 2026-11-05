package dev.moriya.phonenode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the node service after a reboot. Whether a foreground service
 * may be started from this receiver depends on the Android version and the
 * vendor's power management, so this is a best-effort path; survival and
 * recovery on the real device are verified in the final step.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                NodeService.start(context)
            } catch (e: Exception) {
                // Background start restricted; the helper app or a manual
                // launch will bring the service up instead.
            }
        }
    }
}
