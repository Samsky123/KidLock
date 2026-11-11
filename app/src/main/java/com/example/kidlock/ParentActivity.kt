package com.example.kidlock

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 家长端：配置解锁时长、年级/学科/进度单元、难度上限、优先出错题；并支持现场加时、应用限时设置。
 * 勾选状态以 "年级|学科|单元" 为 key 存入 active_groups；
 * 难度上限、优先出错题、错题本(wrong_questions) 同样存 SharedPreferences。
 */
class ParentActivity : AppCompatActivity() {

    private val PREFS = "kidlock"
    private val KEY_ACTIVE = "active_groups"
    private lateinit var prefs: SharedPreferences

    private lateinit var spGrade: Spinner
    private lateinit var spSubject: Spinner
    private lateinit var spDifficulty: Spinner
    private lateinit var containerUnits: LinearLayout
    private lateinit var tvSelInfo: TextView
    private lateinit var etMinutes: EditText
    private lateinit var etAdd: EditText
    private lateinit var cbWrongFirst: CheckBox
    private val activeKeys = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent)

        // 安全闸门：未登录（如孩子点进来）直接退回登录页，进不了设置
        if (!AuthStore.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        if (QuestionBank.isEmpty()) QuestionBank.loadFromAssets(assets)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        activeKeys.addAll(prefs.getStringSet(KEY_ACTIVE, emptySet()) ?: emptySet())
        findViewById<TextView>(R.id.tvUser).text = "已登录：${AuthStore.currentUser() ?: "家长"}"

        etMinutes = findViewById(R.id.etMinutes)
        etMinutes.setText(prefs.getInt("grant_minutes", 10).toString())
        spGrade = findViewById(R.id.spGrade)
        spSubject = findViewById(R.id.spSubject)
        spDifficulty = findViewById(R.id.spDifficulty)
        containerUnits = findViewById(R.id.containerUnits)
        tvSelInfo = findViewById(R.id.tvSelInfo)
        etAdd = findViewById(R.id.etAdd)
        cbWrongFirst = findViewById(R.id.cbWrongFirst)

        spGrade.adapter = makeAdapter(QuestionBank.grades())
        spGrade.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) = onGradeChanged()
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        spSubject.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) = buildUnits()
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        spDifficulty.adapter = ArrayAdapter.createFromResource(
            this, R.array.difficulty_labels, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spDifficulty.setSelection((prefs.getInt("max_difficulty", 3) - 1).coerceIn(0, 2))
        cbWrongFirst.isChecked = prefs.getBoolean("wrong_first", false)

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnExit).setOnClickListener { logout() }
        findViewById<Button>(R.id.btnAddTime).setOnClickListener { addTime() }
        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnAppLimit).setOnClickListener {
            startActivity(Intent(this, AppLimitActivity::class.java))
        }
        findViewById<Button>(R.id.btnHardLock).setOnClickListener { showHardLockHelp() }

        onGradeChanged()
        updateSelInfo()
        refreshStates()
    }

    override fun onResume() {
        super.onResume()
        // 回到自己的界面，说明授权跳转已经结束，通行证立刻作废
        LockGuard.clearPermPass(this)
        refreshStates()
    }

    override fun onDestroy() {
        if (isFinishing) LockGuard.clearPermPass(this)
        super.onDestroy()
    }

    private fun refreshStates() {
        refreshOverlayState()
        refreshHardLockState()
    }

    /** 「显示在其他应用上层」是到点弹题的必需权限，否则系统会拦掉后台拉起界面。 */
    private fun refreshOverlayState() {
        val tv = findViewById<TextView>(R.id.tvPermState)
        val ok = Settings.canDrawOverlays(this)
        tv.text = if (ok) "状态：已开启 ✓　到点会正常弹出题目"
        else "状态：未开启　时间到了不会弹出题目"
        tv.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.ok else R.color.danger)
        )
        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnOverlayPerm)
            .visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE
    }

    /**
     * 强力锁状态：普通安装 vs 设备所有者。
     * 普通安装下 Home 键拦不住，只能「按了之后零点几秒内弹回来」；
     * 设备所有者下才是系统级硬锁（Home/最近任务/通知栏全部失效）。
     */
    private fun refreshHardLockState() {
        val tv = findViewById<TextView>(R.id.tvHardLockState)
        val btn = findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnHardLock)
        if (isDeviceOwner()) {
            tv.text = "状态：硬锁已生效 ✓\nHome 键、最近任务、通知栏已由系统禁用，答题页退不出去"
            tv.setTextColor(ContextCompat.getColor(this, R.color.ok))
            // 设备所有者一旦设上，adb 的 remove-active-admin 也会被系统拒绝，
            // 家长自己必须有出口，否则只能恢复出厂。所以在 App 内留这个按钮。
            btn.text = "解除强力锁"
            btn.visibility = View.VISIBLE
            btn.setOnClickListener { confirmReleaseHardLock() }
        } else {
            tv.text = "状态：普通模式\n孩子按 Home 后约 0.3 秒会被拉回答题页，能用但会闪一下。\n想彻底禁用 Home 键，需用电脑执行一次 adb 命令，把本应用设为「设备所有者」。"
            tv.setTextColor(ContextCompat.getColor(this, R.color.warn))
            btn.text = "如何开启"
            btn.visibility = View.VISIBLE
            btn.setOnClickListener { showHardLockHelp() }
        }
    }

    private fun confirmReleaseHardLock() {
        AlertDialog.Builder(this)
            .setTitle("解除强力锁？")
            .setMessage(
                "解除后：\n" +
                        "· 孩子按 Home 会回到桌面，只能靠 0.3 秒自动回弹拦住（会闪一下）\n" +
                        "· 本应用可以被卸载，限时设置也会随之失效\n\n" +
                        "如果是换手机或不再需要管控，才建议解除。"
            )
            .setPositiveButton("解除") { _, _ -> releaseHardLock() }
            .setNegativeButton("保留", null)
            .show()
    }

    private fun releaseHardLock() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.clearDeviceOwnerApp(packageName)
            LockGuard.setLocked(this, false)
            refreshHardLockState()
            Toast.makeText(this, "已解除强力锁，重启本应用后恢复普通模式", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "解除失败：${e.message ?: e.javaClass.simpleName}\n只能恢复出厂设置才能解除",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isDeviceOwner(): Boolean = try {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isDeviceOwnerApp(packageName)
    } catch (_: Exception) {
        false
    }

    private fun showHardLockHelp() {
        val cmd = "adb shell dpm set-device-owner com.example.kidlock/.AdminReceiver"
        val msg = """
            在电脑上装好 adb 并连上手机，执行下面这一条命令（只需一次）：

            $cmd

            前提条件（三条都要满足，否则系统会拒绝）：
            1. 手机上不能有任何账号——Google、小米、华为等系统账号都不行，
               抖音、小红书这类 App 自己注册的账号也算。最稳的做法是恢复出厂后
               跳过所有账号登录，先装本应用再执行这条命令。
            2. 开发者选项里的「USB 调试」已打开，电脑已授权本机调试。
            3. 本应用是当前唯一的设备管理器。

            生效后，答题页会进入系统级 Lock Task：Home 键、最近任务、通知栏
            在系统层面直接失效，孩子不是「被弹回来」，而是「按了没反应」。

            取消：回到本页点「解除强力锁」。
            注意设上之后 adb 的 remove-active-admin 也会被系统拒绝——这是 Android
            的保护机制，防止别人用电脑随手把家长设的限制解掉。
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("开启强力锁（可选）")
            .setMessage(msg)
            .setPositiveButton("复制命令") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                Toast.makeText(this, "命令已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "权限已开启", Toast.LENGTH_SHORT).show()
            refreshOverlayState()
            return
        }
        // 跳系统设置时自己就「不在本应用」了，先领一张 3 分钟通行证，避免被守卫弹回
        LockGuard.grantPermPass(this, 3)
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "请到 系统设置 → 应用 → 显示在其他应用上层 中手动开启本应用",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun makeAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun onGradeChanged() {
        val grade = spGrade.selectedItem?.toString() ?: return
        spSubject.adapter = makeAdapter(QuestionBank.subjectsForGrade(grade))
        buildUnits()
    }

    private fun buildUnits() {
        val grade = spGrade.selectedItem?.toString() ?: return
        val subject = spSubject.selectedItem?.toString() ?: return
        val units = QuestionBank.unitsForGradeSubject(grade, subject)
        containerUnits.removeAllViews()
        val accent = ContextCompat.getColor(this, R.color.accent)
        units.forEach { unit ->
            val key = "$grade|$subject|$unit"
            val cb = CheckBox(this).apply {
                text = unit
                isChecked = activeKeys.contains(key)
                setTextColor(ContextCompat.getColor(this@ParentActivity, R.color.text_primary))
                textSize = 14f
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                setPadding(0, dp(9), 0, dp(9))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) activeKeys.add(key) else activeKeys.remove(key)
                    updateSelInfo()
                }
            }
            containerUnits.addView(cb)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun updateSelInfo() {
        val maxD = spDifficulty.selectedItemPosition + 1
        val count = QuestionBank.activePool(activeKeys, maxD).size
        val wrong = prefs.getStringSet("wrong_questions", emptySet())?.size ?: 0
        tvSelInfo.text = "已选进度单元 ${activeKeys.size} 个，题目共 $count 道（含错题 $wrong 道）"
    }

    private fun save() {
        val m = etMinutes.text.toString().toIntOrNull()
        if (m != null && m > 0) prefs.edit().putInt("grant_minutes", m).apply()
        prefs.edit().putStringSet(KEY_ACTIVE, activeKeys).apply()
        prefs.edit().putInt("max_difficulty", spDifficulty.selectedItemPosition + 1).apply()
        prefs.edit().putBoolean("wrong_first", cbWrongFirst.isChecked).apply()
        Toast.makeText(this, "已保存题库配置", Toast.LENGTH_SHORT).show()
    }

    /** 家长现场加时：延长本次解锁窗口，并退出锁屏让孩子继续使用。 */
    private fun addTime() {
        val add = etAdd.text.toString().toIntOrNull()
        if (add == null || add <= 0) {
            Toast.makeText(this, "请输入正整数分钟", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val until = prefs.getLong("unlock_until", 0)
        val base = if (until > now) until else now
        prefs.edit().putLong("unlock_until", base + add * 60_000L).apply()

        try { stopService(Intent(this, TimerService::class.java)) } catch (_: Exception) { }
        ContextCompat.startForegroundService(this, Intent(this, TimerService::class.java))

        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(packageName)) stopLockTask()
        } catch (_: Exception) { }
        Toast.makeText(this, "已加时 $add 分钟", Toast.LENGTH_SHORT).show()
        finishAffinity()
    }

    /** 退出登录：清空内存会话，孩子后续无法进入设置改限时（管控仍持续运行）。 */
    private fun logout() {
        AuthStore.logout()
        Toast.makeText(this, "已退出登录，孩子将无法修改设置", Toast.LENGTH_SHORT).show()
        finish()
    }
}
