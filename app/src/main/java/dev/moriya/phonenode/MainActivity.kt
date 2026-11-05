package dev.moriya.phonenode

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Minimal control screen: service start, battery-exemption guidance, and a
 * shortcut to MIUI's own autostart settings. MIUI's "No restrictions" and
 * autostart toggles cannot be switched programmatically, so this screen
 * guides the user to them instead of pretending to set them.
 */
class MainActivity : Activity() {

    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusLabel = TextView(this)
        layout.addView(statusLabel)

        layout.addView(Button(this).apply {
            text = "ノードサービスを開始"
            setOnClickListener { NodeService.start(this@MainActivity) }
        })

        layout.addView(Button(this).apply {
            text = "電池最適化の除外を申請"
            setOnClickListener { requestBatteryExemption() }
        })

        layout.addView(Button(this).apply {
            text = "MIUIの自動起動設定を開く"
            setOnClickListener { openMiuiAutostartSettings() }
        })

        layout.addView(Button(this).apply {
            text = "アプリ情報（電池の「制限なし」確認）"
            setOnClickListener { openAppDetails() }
        })

        layout.addView(Button(this).apply {
            text = "ログの保存先フォルダを選ぶ（Drive の phone-node/logs）"
            setOnClickListener {
                startActivityForResult(LogExport.pickerIntent(), REQ_PICK_LOG_FOLDER)
            }
        })

        layout.addView(Button(this).apply {
            text = "今すぐログを書き出す"
            setOnClickListener {
                thread(name = "log-export-manual") {
                    LogExport.export(this@MainActivity)
                    runOnUiThread { refreshStatus() }
                }
            }
        })

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    @Deprecated("startActivityForResult is sufficient for this single picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_LOG_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            LogExport.setTreeUri(this, uri)
            thread(name = "log-export-first") { LogExport.export(this@MainActivity) }
        } catch (e: Exception) {
            CrashLog.record(this, "log-folder-pick", e)
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = powerManager.isIgnoringBatteryOptimizations(packageName)
        val started = NodeState.serviceStartedAtMs
        statusLabel.text = buildString {
            append("電池最適化の除外: ").append(if (exempt) "済み" else "未設定").append("\n")
            append("サービス: ").append(if (started == 0L) "未起動" else "起動中").append("\n")
            append("状態エンドポイント: http://127.0.0.1:").append(StatusServer.PORT).append("/\n")
            append("proxy: ").append(if (NodeState.proxyListening) "待受中" else "停止")
                .append("（ポート ").append(ProxyServer.PORT).append("）\n")
            append("proot: ").append(if (NodeState.prootRunning) "実行中" else "停止").append("\n")
            append("ログ保存先: ")
                .append(if (LogExport.hasDestination(this@MainActivity)) "設定済み" else "未設定")
                .append("\n")
            NodeState.lastError?.let { append("最後の失敗: ").append(it).append("\n") }
            val diagnostics = CrashLog.readDiagnostics(this@MainActivity)
            if (diagnostics.isNotBlank()) {
                append("\n").append(diagnostics)
            }
        }
    }

    companion object {
        private const val REQ_PICK_LOG_FOLDER = 42
    }

    private fun requestBatteryExemption() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openMiuiAutostartSettings() {
        val miuiIntent = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        }
        try {
            startActivity(miuiIntent)
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}
