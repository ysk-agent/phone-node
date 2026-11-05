package dev.moriya.phonenode.helper

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
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watchdog status screen: what the helper last saw of the main app, how
 * often it had to resurrect it, and the same battery/MIUI guidance as
 * the main app (the helper needs the same exemptions to survive).
 */
class MainActivity : Activity() {

    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusLabel = TextView(this)
        layout.addView(statusLabel)

        layout.addView(Button(this).apply {
            text = "監視サービスを開始"
            setOnClickListener { HelperService.start(this@MainActivity) }
        })
        layout.addView(Button(this).apply {
            text = "電池最適化の除外を申請"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })
        layout.addView(Button(this).apply {
            text = "MIUIの自動起動設定を開く"
            setOnClickListener { openMiuiAutostartSettings() }
        })

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = powerManager.isIgnoringBatteryOptimizations(packageName)
        val prefs = getSharedPreferences("helper-state", Context.MODE_PRIVATE)
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.JAPAN)
        val lastCheck = prefs.getLong("lastCheckAtMs", 0L)
        val lastResurrect = prefs.getLong("lastResurrectAtMs", 0L)
        statusLabel.text = buildString {
            append("電池最適化の除外: ").append(if (exempt) "済み" else "未設定").append("\n")
            append("本体アプリの状態: ").append(prefs.getString("mainState", "未確認")).append("\n")
            append("最終確認: ")
                .append(if (lastCheck == 0L) "なし" else fmt.format(Date(lastCheck))).append("\n")
            append("復活させた回数: ").append(prefs.getInt("resurrectCount", 0)).append("\n")
            append("本体が自力で再起動した回数: ").append(prefs.getInt("selfRestartCount", 0)).append("\n")
            append("最終復活: ")
                .append(if (lastResurrect == 0L) "なし" else fmt.format(Date(lastResurrect))).append("\n")
        }
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
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }
}
