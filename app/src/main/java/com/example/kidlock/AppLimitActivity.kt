package com.example.kidlock

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 家长端：勾选要限制的应用（抖音等）并设每日时长。
 * 依赖"使用情况访问"权限（UsageStatsManager），未授予时 M2 不生效。
 */
class AppLimitActivity : AppCompatActivity() {

    private val PREFS = "kidlock"
    private lateinit var prefs: SharedPreferences
    private lateinit var tvPerm: TextView
    private lateinit var containerApps: LinearLayout

    // 常见儿童高频应用（包名，标签，默认每日分钟，是否启用）
    private val APPS = listOf(
        AppLimit("com.ss.android.ugc.aweme", "抖音", 30, false),
        AppLimit("com.smile.gifmaker", "快手", 30, false),
        AppLimit("com.tencent.mm", "微信", 60, false),
        AppLimit("com.tencent.mobileqq", "QQ", 60, false),
        AppLimit("com.tencent.tmgp.sgame", "王者荣耀", 30, false),
        AppLimit("com.tencent.tmgp.pubgm", "和平精英", 30, false),
        AppLimit("tv.danmaku.bili", "哔哩哔哩", 30, false),
        AppLimit("com.xingin.xhs", "小红书", 30, false),
        AppLimit("com.youku.phone", "优酷视频", 30, false),
        AppLimit("com.qiyi.video", "爱奇艺", 30, false)
    )

    // 保存每行控件引用，便于读取
    private val rows = mutableListOf<RowRef>()

    data class RowRef(
        val base: AppLimit,
        val check: CheckBox,
        val edit: EditText
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_applimit)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        tvPerm = findViewById(R.id.tvPerm)
        containerApps = findViewById(R.id.containerApps)

        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        buildRows()
    }

    override fun onResume() {
        super.onResume()
        updatePermText()
    }

    private fun updatePermText() {
        val ok = hasUsagePermission()
        tvPerm.text = if (ok) {
            "已授予 ✓　可统计各 App 每日使用时长"
        } else {
            "未授予　不开启则按 App 限时不会生效"
        }
        tvPerm.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, if (ok) R.color.ok else R.color.danger)
        )
        // 已授权时没必要再显示授权按钮，避免家长误以为还要操作
        findViewById<Button>(R.id.btnGrant).visibility =
            if (ok) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun buildRows() {
        containerApps.removeAllViews()
        rows.clear()
        val saved = AppLimitStore.load(prefs).associateBy { it.pkg }
        val accent = ContextCompat.getColor(this, R.color.accent)
        val textColor = ContextCompat.getColor(this, R.color.text_primary)
        APPS.forEach { app ->
            val cfg = saved[app.pkg] ?: app
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val check = CheckBox(this).apply {
                text = cfg.label
                isChecked = cfg.enabled
                setTextColor(textColor)
                textSize = 14.5f
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val edit = EditText(this).apply {
                setText(cfg.limitMin.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(textColor)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                background = ContextCompat.getDrawable(this@AppLimitActivity, R.drawable.bg_input)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(76), dp(44)).apply { marginStart = dp(10) }
            }
            row.addView(check)
            row.addView(edit)
            containerApps.addView(row)
            rows.add(RowRef(cfg, check, edit))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun save() {
        val list = rows.map { ref ->
            val min = ref.edit.text.toString().toIntOrNull() ?: 0
            ref.base.copy(enabled = ref.check.isChecked, limitMin = min)
        }
        AppLimitStore.save(prefs, list)
        if (!hasUsagePermission()) {
            Toast.makeText(this, "已保存，但需先授予使用情况访问权限才生效", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "已保存应用限时", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
