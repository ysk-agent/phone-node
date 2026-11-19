package dev.moriya.phonenode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a self-update, Android kills the app's processes and does not
 * restart the foreground service on its own. MY_PACKAGE_REPLACED is
 * delivered to the *new* build right after its installation and is
 * one of the broadcasts exempted from background-start restrictions,
 * so it is the sanctioned place to bring the node back up hands-free.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        CrashLog.note(context, "update", "package replaced; restarting node service")
        try {
            NodeService.start(context)
        } catch (t: Throwable) {
            CrashLog.record(context, "package-replaced", t)
        }
        LogExport.export(context)
    }
}
