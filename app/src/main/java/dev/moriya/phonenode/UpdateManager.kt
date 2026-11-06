package dev.moriya.phonenode

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Self-update from the user-picked Drive folder (the same SAF grant
 * pattern as the log export; the app holds no Google credentials).
 *
 * The folder holds two files published by the developer:
 *   update.json        {"buildId": <number>, "file": "phonenode-app.apk"}
 *   phonenode-app.apk  the new build, signed with the same key
 *
 * The running build's own id is baked into assets/build-info.json at
 * build time. When the folder's buildId is newer, the APK is copied
 * into the cache and handed to PackageInstaller as a self-update,
 * asking for no user action (Android 12+ allows that for updates of
 * the same package by the same installer in many configurations; if
 * the platform still wants a confirmation, UpdateReceiver launches
 * the provided intent so the user taps once). Either way, progress
 * and outcomes are recorded in the diagnostics log, which is itself
 * exported to Drive, so the whole loop is observable remotely.
 */
object UpdateManager {

    private const val PREFS = "phonenode-prefs"
    private const val KEY_TREE_URI = "update_tree_uri"
    private const val KEY_PENDING_BUILD = "pending_build"
    private const val KEY_PENDING_SINCE = "pending_since"
    private const val KEY_PENDING_SESSION = "pending_session"

    /** How long a committed-but-unconfirmed install suppresses retries. */
    private const val PENDING_WINDOW_MS = 30 * 60 * 1000L

    const val ACTION_INSTALL_STATUS = "dev.moriya.phonenode.INSTALL_STATUS"

    private val checking = AtomicBoolean(false)

    fun pickerIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

    fun setTreeUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun treeUri(context: Context): Uri? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
        return stored?.let { Uri.parse(it) }
    }

    fun hasSource(context: Context): Boolean = treeUri(context) != null

    /** This build's id from assets/build-info.json; 0 when unstamped. */
    fun ownBuildId(context: Context): Long =
        try {
            val text = context.assets.open("build-info.json")
                .bufferedReader().use { it.readText() }
            Regex("\"buildId\"\\s*:\\s*(\\d+)").find(text)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }

    fun checkAsync(context: Context) {
        val appContext = context.applicationContext
        thread(name = "update-check", isDaemon = true) { check(appContext) }
    }

    fun check(context: Context) {
        if (!checking.compareAndSet(false, true)) return
        try {
            abandonStaleSessions(context)
            val tree = treeUri(context) ?: run {
                NodeState.updateStatus = "更新元フォルダ未設定"
                return
            }
            val updateDoc = findChild(context, tree, "update.json") ?: run {
                NodeState.updateStatus = "update.json が見つからない"
                return
            }
            val text = readText(context, updateDoc) ?: return
            val remoteId = Regex("\"buildId\"\\s*:\\s*(\\d+)").find(text)
                ?.groupValues?.get(1)?.toLongOrNull() ?: run {
                NodeState.updateStatus = "update.json の形式が不正"
                return
            }
            val own = ownBuildId(context)
            if (remoteId <= own) {
                clearPending(context)
                NodeState.updateStatus = "最新です (build $own)"
                return
            }
            // While the main screen is open, the platform tends to
            // demand an install confirmation (the app is "in use").
            // Defer to a later background check, which usually passes
            // silently.
            if (NodeState.uiOpen) {
                NodeState.updateStatus = "build $remoteId があります（アプリ表示中のため保留）"
                return
            }
            // One outstanding confirmation per build is enough: the
            // 30-second development cadence otherwise re-downloads
            // the whole APK and re-prompts every cycle.
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pendingBuild = prefs.getLong(KEY_PENDING_BUILD, 0L)
            val pendingSince = prefs.getLong(KEY_PENDING_SINCE, 0L)
            if (pendingBuild == remoteId &&
                System.currentTimeMillis() - pendingSince < PENDING_WINDOW_MS
            ) {
                NodeState.updateStatus = "build $remoteId のインストール確認待ち（再試行は保留中）"
                return
            }
            if (!context.packageManager.canRequestPackageInstalls()) {
                NodeState.updateStatus =
                    "build $remoteId がありますが「不明なアプリのインストール」許可が必要です"
                CrashLog.note(context, "update", "new build $remoteId; install permission missing")
                return
            }
            val fileName = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(text)
                ?.groupValues?.get(1) ?: "phonenode-app.apk"
            val apkDoc = findChild(context, tree, fileName) ?: run {
                NodeState.updateStatus = "$fileName が見つからない"
                return
            }
            NodeState.updateStatus = "build $remoteId をダウンロード中"
            CrashLog.note(context, "update", "downloading build $remoteId from $fileName")
            val apkFile = File(context.cacheDir, "update.apk")
            context.contentResolver.openInputStream(apkDoc)?.use { input ->
                apkFile.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                NodeState.updateStatus = "APK を開けない"
                return
            }
            NodeState.updateStatus = "build $remoteId をインストール中"
            prefs.edit()
                .putLong(KEY_PENDING_BUILD, remoteId)
                .putLong(KEY_PENDING_SINCE, System.currentTimeMillis())
                .apply()
            install(context, apkFile)
            CrashLog.note(context, "update", "install session committed for build $remoteId")
        } catch (t: Throwable) {
            NodeState.updateStatus = "更新確認に失敗: ${t.javaClass.simpleName}: ${t.message}"
            CrashLog.record(context, "update", t)
        } finally {
            checking.set(false)
        }
    }

    /**
     * Abandon this app's own stale installer sessions. A session that
     * asked for user confirmation and was never answered stays active
     * and counts against the platform's per-UID cap; enough of them
     * (the 30-second cadence produced one per retry) make every later
     * createSession throw "Too many active sessions". Sessions younger
     * than 10 minutes are kept: one may be a prompt on screen now.
     *
     * The session recorded as [KEY_PENDING_SESSION] is exempt no matter
     * its age: it is the one currently awaiting the user's install
     * confirmation, and abandoning it after 10 minutes (as this sweep
     * originally did) guarantees the update can never land even when
     * the user does respond. The exemption ends when the pending
     * record is cleared on success, or when a newer build commits a
     * newer session and overwrites the id.
     */
    private fun abandonStaleSessions(context: Context) {
        try {
            val installer = context.packageManager.packageInstaller
            val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
            val pendingSession = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PENDING_SESSION, -1)
            for (info in installer.mySessions) {
                if (info.sessionId == pendingSession) continue
                val created = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    info.createdMillis
                } else {
                    0L
                }
                if (created < cutoff) {
                    try {
                        installer.abandonSession(info.sessionId)
                    } catch (t: Throwable) {
                        // Already finalized; nothing to abandon.
                    }
                }
            }
        } catch (t: Throwable) {
            CrashLog.note(context, "update", "session sweep failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Build id with an install currently outstanding; 0 when none. */
    fun pendingBuild(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PENDING_BUILD, 0L)

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING_BUILD).remove(KEY_PENDING_SINCE)
            .remove(KEY_PENDING_SESSION).apply()
    }

    private fun install(context: Context, apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                )
            } catch (e: Exception) {
                // Older/quirky platform: the confirmation prompt is
                // the fallback, launched by UpdateReceiver.
            }
        }
        val sessionId = installer.createSession(params)
        // Exempt this session from the stale-session sweep for as long
        // as it awaits the user's confirmation (see abandonStaleSessions).
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PENDING_SESSION, sessionId).apply()
        val session = installer.openSession(sessionId)
        session.use {
            it.openWrite("app.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                it.fsync(out)
            }
            val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            it.commit(pending.intentSender)
        }
    }

    // -- SAF helpers -------------------------------------------------------

    private fun findChild(context: Context, tree: Uri, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(
                            tree, cursor.getString(0)
                        )
                    }
                }
            }
        return null
    }

    private fun readText(context: Context, doc: Uri): String? =
        context.contentResolver.openInputStream(doc)?.bufferedReader()?.use {
            it.readText()
        }
}
