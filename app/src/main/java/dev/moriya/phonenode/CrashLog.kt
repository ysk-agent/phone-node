package dev.moriya.phonenode

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device diagnostics: the app has no logcat channel back to the
 * developer, so crashes and subsystem failures are persisted under the
 * app's files directory and shown on the main screen.
 *
 * - last-crash.txt: the most recent uncaught exception (any thread),
 *   written by the default-exception handler before it rethrows to the
 *   previous handler, so Android's own crash behaviour is unchanged.
 * - errors.log: caught failures recorded by subsystems, newest last.
 * - proot-setup.log / proot-run.log are written by ProotManager and
 *   tailed here so a failing guest leaves evidence.
 */
object CrashLog {

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildString {
                    append(now()).append(" thread=").append(thread.name).append('\n')
                    append(stackOf(error))
                }
                File(appContext.filesDir, "last-crash.txt").writeText(text)
            } catch (e: Exception) {
                // Diagnostics must never mask the original failure.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Append a plain note (no stack) to the same diagnostics log. */
    fun note(context: Context, where: String, message: String) {
        try {
            val file = File(context.applicationContext.filesDir, "errors.log")
            if (file.length() > 64 * 1024) file.writeText("")
            file.appendText("${now()} [$where] $message\n")
        } catch (e: Exception) {
            // Diagnostics must never mask the original failure.
        }
    }

    fun record(context: Context, where: String, error: Throwable) {
        try {
            val file = File(context.applicationContext.filesDir, "errors.log")
            if (file.length() > 64 * 1024) file.writeText("")
            file.appendText(buildString {
                append(now()).append(" [").append(where).append("] ")
                append(error.javaClass.name).append(": ").append(error.message).append('\n')
                append(stackOf(error)).append('\n')
            })
        } catch (e: Exception) {
            // Diagnostics must never mask the original failure.
        }
        // The timestamp is part of the one-line summary: without it a
        // reader cannot tell a fresh failure from a stale one.
        NodeState.lastError =
            "${now()} [$where] ${error.javaClass.simpleName}: ${error.message}"
    }

    /** Everything the main screen shows under the live status. */
    fun readDiagnostics(context: Context): String {
        val dir = context.applicationContext.filesDir
        return buildString {
            val crash = File(dir, "last-crash.txt")
            if (crash.exists()) {
                append("--- 最後の未捕捉例外 ---\n")
                append(tail(crash, 2500)).append('\n')
            }
            val errors = File(dir, "errors.log")
            if (errors.exists()) {
                append("--- 記録された失敗 ---\n")
                append(tail(errors, 2500)).append('\n')
            }
            val setup = File(dir, "proot-setup.log")
            if (setup.exists()) {
                append("--- proot セットアップ出力 (末尾) ---\n")
                append(tail(setup, 2500)).append('\n')
            }
            val run = File(dir, "proot-run.log")
            if (run.exists()) {
                append("--- proot 実行出力 (末尾) ---\n")
                append(tail(run, 1500)).append('\n')
            }
        }
    }

    private fun tail(file: File, maxChars: Int): String {
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return "(読み取り失敗: ${e.message})"
        }
        return if (text.length <= maxChars) text else "…" + text.takeLast(maxChars)
    }

    private fun stackOf(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
