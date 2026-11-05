package dev.moriya.phonenode.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the watchdog after a reboot, best effort like the main app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                HelperService.start(context)
            } catch (e: Exception) {
                // Background start restricted; a manual launch or the
                // main app's periodic ensure-start will bring it up.
            }
        }
    }
}
