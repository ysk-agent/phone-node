package dev.moriya.phonenode

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight debug-log export to a folder the user picks once through
 * the system folder picker (Storage Access Framework).
 *
 * The intended target is the Google Drive folder phone-node/logs,
 * chosen through the Drive app's document provider; the app itself
 * holds no Google credentials, only the persisted folder permission.
 * Each export is one small text file with a timestamped name and a
 * hard size budget, containing the live state, the setup markers, and
 * tails of every on-device log. A local mirror is always kept under
 * the app's external files directory as a fallback.
 */
object LogExport {

    private const val PREFS = "phonenode-prefs"
    private const val KEY_TREE_URI = "log_tree_uri"

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

    fun hasDestination(context: Context): Boolean = treeUri(context) != null

    /**
     * Writes one bundle file to the picked folder (if any) and to the
     * local mirror. Safe to call from any thread; never throws.
     */
    fun export(context: Context) {
        val appContext = context.applicationContext
        try {
            val bundle = buildBundle(appContext)
            val name = "phonenode-log-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

            // Local mirror (Android/data/<pkg>/files/logs/).
            try {
                val dir = File(appContext.getExternalFilesDir(null), "logs")
                dir.mkdirs()
                File(dir, name).writeText(bundle)
                File(dir, "latest.txt").writeText(bundle)
            } catch (e: Exception) {
                // Mirror is best effort.
            }

            val tree = treeUri(appContext) ?: return
            val resolver = appContext.contentResolver
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val docUri = DocumentsContract.createDocument(
                resolver, parentUri, "text/plain", name
            ) ?: return
            resolver.openOutputStream(docUri, "w")?.use { out ->
                out.write(bundle.toByteArray(Charsets.UTF_8))
            }
        } catch (t: Throwable) {
            CrashLog.record(appContext, "log-export", t)
        }
    }

    private fun buildBundle(context: Context): String {
        val dir = context.filesDir
        val now = System.currentTimeMillis()
        return buildString {
            append("Phone Node debug log\n")
            append("exported: ").append(stamp(now)).append('\n')
            append("device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            val started = NodeState.serviceStartedAtMs
            append("service: ").append(
                if (started == 0L) "not started"
                else "started ${stamp(started)}, uptime ${NodeState.uptimeMs(now) / 1000}s"
            ).append('\n')
            append("heartbeat: ").append(NodeState.heartbeat.get()).append('\n')
            append("proxyListening: ").append(NodeState.proxyListening).append('\n')
            append("prootRunning: ").append(NodeState.prootRunning).append('\n')
            append("lastError: ").append(NodeState.lastError ?: "-").append('\n')
            append("rootfs/bin/sh: ").append(File(dir, "rootfs/bin/sh").exists()).append('\n')
            append("setup marker: ")
                .append(File(dir, "rootfs/opt/phonenode/.setup-done").exists()).append('\n')
            append('\n')
            appendSection(this, File(dir, "last-crash.txt"), "last crash", 4000)
            appendSection(this, File(dir, "errors.log"), "recorded failures", 4000)
            appendSection(this, File(dir, "proot-setup.log"), "proot setup output (tail)", 6000)
            appendSection(this, File(dir, "proot-run.log"), "proot run output (tail)", 3000)
        }
    }

    private fun appendSection(sb: StringBuilder, file: File, title: String, maxChars: Int) {
        sb.append("===== ").append(title).append(" =====\n")
        if (!file.exists()) {
            sb.append("(なし)\n\n")
            return
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            "(読み取り失敗: ${e.message})"
        }
        sb.append(if (text.length <= maxChars) text else "…" + text.takeLast(maxChars))
        sb.append("\n\n")
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
