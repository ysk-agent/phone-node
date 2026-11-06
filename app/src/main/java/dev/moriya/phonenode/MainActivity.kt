package dev.moriya.phonenode

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
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
    private lateinit var installButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        requestNotificationPermissionIfNeeded()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusLabel = TextView(this)
        layout.addView(statusLabel)

        // Fallback route for an install the platform would not apply
        // without confirmation: launching the stored intent from this
        // activity is always permitted, unlike the receiver's launch,
        // which background-activity-launch restrictions silently drop
        // while the node sits unattended. Shown only while a
        // confirmation is outstanding (refreshStatus toggles it).
        installButton = Button(this).apply {
            text = "アップデートをインストール"
            visibility = View.GONE
            setOnClickListener {
                val confirm = NodeState.pendingConfirmIntent
                if (confirm != null) {
                    try {
                        startActivity(confirm)
                    } catch (e: Exception) {
                        CrashLog.record(this@MainActivity, "update-confirm-ui", e)
                    }
                }
                refreshStatus()
            }
        }
        layout.addView(installButton)

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

        layout.addView(Button(this).apply {
            text = "更新元フォルダを選ぶ（Drive の phone-node）"
            setOnClickListener {
                startActivityForResult(UpdateManager.pickerIntent(), REQ_PICK_UPDATE_FOLDER)
            }
        })

        layout.addView(Button(this).apply {
            text = "今すぐ更新を確認"
            setOnClickListener {
                UpdateManager.checkAsync(this@MainActivity)
                refreshStatus()
            }
        })

        layout.addView(Button(this).apply {
            text = "「不明なアプリのインストール」許可を開く"
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        })

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    @Deprecated("startActivityForResult is sufficient for this single picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            when (requestCode) {
                REQ_PICK_LOG_FOLDER -> {
                    LogExport.setTreeUri(this, uri)
                    thread(name = "log-export-first") { LogExport.export(this@MainActivity) }
                }
                REQ_PICK_UPDATE_FOLDER -> {
                    UpdateManager.setTreeUri(this, uri)
                    UpdateManager.checkAsync(this)
                }
            }
        } catch (e: Exception) {
            CrashLog.record(this, "folder-pick", e)
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        NodeState.uiOpen = true
        refreshStatus()
    }

    override fun onPause() {
        NodeState.uiOpen = false
        super.onPause()
    }

    private fun refreshStatus() {
        val pendingBuild = NodeState.pendingConfirmBuild
        if (NodeState.pendingConfirmIntent != null) {
            installButton.visibility = View.VISIBLE
            installButton.text =
                if (pendingBuild > 0L) {
                    "アップデートをインストール（build $pendingBuild）"
                } else {
                    "アップデートをインストール"
                }
        } else {
            installButton.visibility = View.GONE
        }
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
            append("更新元: ")
                .append(if (UpdateManager.hasSource(this@MainActivity)) "設定済み" else "未設定")
                .append("（この端末の build ").append(UpdateManager.ownBuildId(this@MainActivity))
                .append("）\n")
            append("更新状態: ").append(NodeState.updateStatus).append("\n")
            NodeState.lastError?.let { append("最後の失敗: ").append(it).append("\n") }
            val diagnostics = CrashLog.readDiagnostics(this@MainActivity)
            if (diagnostics.isNotBlank()) {
                append("\n").append(diagnostics)
            }
        }
    }

    companion object {
        private const val REQ_PICK_LOG_FOLDER = 42
        private const val REQ_PICK_UPDATE_FOLDER = 43
        private const val REQ_NOTIFICATIONS = 44
    }

    /** The update-confirmation notification is the primary way an
     * unattended node reaches the user, and on Android 13+ it needs
     * this runtime permission. Ask once from the screen; if declined,
     * the in-app install button above still works. */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
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
