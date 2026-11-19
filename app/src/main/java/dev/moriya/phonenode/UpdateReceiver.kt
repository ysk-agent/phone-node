package dev.moriya.phonenode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the PackageInstaller session result for a self-update.
 * Every outcome is written to the diagnostics log (and thus exported
 * to Drive), so a remote update attempt is fully observable.
 *
 * When the platform asks for user confirmation after all, three routes
 * carry the confirmation to the user, because the first one alone is
 * not reliable: since Android 10, an activity start issued from a
 * broadcast receiver while the app sits unattended in the background
 * is silently dropped by the background-activity-launch restrictions
 * (no exception, nothing in the log). That is exactly the node's
 * normal state, so the direct launch only ever worked while the user
 * happened to be holding the phone.
 *
 *  1. Direct launch of the provided intent (works when the user has
 *     interacted with the app recently; kept because it is free).
 *  2. A notification whose tap launches the intent. Notifications are
 *     posted by the system service, so the PendingIntent survives
 *     process death and the tap carries user-interaction privileges.
 *     This is the primary route for an unattended node.
 *  3. An install button on the main screen, launching the intent kept
 *     in [NodeState] from the activity (always permitted).
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                CrashLog.note(context, "update", "user confirmation requested")
                NodeState.updateStatus = "端末でのインストール確認待ち"
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    NodeState.pendingConfirmIntent = confirm
                    NodeState.pendingConfirmBuild =
                        UpdateManager.pendingBuild(context)
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        CrashLog.record(context, "update-confirm", e)
                    }
                    postConfirmNotification(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                CrashLog.note(context, "update", "install succeeded")
                UpdateManager.clearPending(context)
                NodeState.pendingConfirmIntent = null
                NodeState.pendingConfirmBuild = 0L
                cancelConfirmNotification(context)
                NodeState.updateStatus = "インストール完了"
                // Belt and suspenders alongside MY_PACKAGE_REPLACED:
                // bring the service back in this (new) process too.
                try {
                    NodeService.start(context)
                } catch (t: Throwable) {
                    CrashLog.record(context, "update-restart", t)
                }
            }
            else -> {
                CrashLog.note(context, "update", "install status=$status message=$message")
                NodeState.updateStatus = "インストール結果 status=$status"
                // A terminal result invalidates any stored confirmation:
                // its session no longer exists, so the notification and
                // the main-screen button would only lead to an error.
                NodeState.pendingConfirmIntent = null
                NodeState.pendingConfirmBuild = 0L
                cancelConfirmNotification(context)
            }
        }
        LogExport.export(context)
    }

    private fun postConfirmNotification(context: Context, confirm: Intent) {
        try {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "アップデートの確認",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
            val tap = PendingIntent.getActivity(
                context, REQUEST_CONFIRM, confirm,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val build = NodeState.pendingConfirmBuild
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Phone Node のアップデート")
                .setContentText(
                    if (build > 0L) {
                        "build $build のインストール確認が必要です。タップして進めてください。"
                    } else {
                        "インストール確認が必要です。タップして進めてください。"
                    }
                )
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            CrashLog.record(context, "update-notify", t)
        }
    }

    private fun cancelConfirmNotification(context: Context) {
        try {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            // Nothing meaningful to do if even cancelling fails.
        }
    }

    companion object {
        private const val CHANNEL_ID = "phonenode-update"
        private const val NOTIFICATION_ID = 2001
        private const val REQUEST_CONFIRM = 2001
    }
}
