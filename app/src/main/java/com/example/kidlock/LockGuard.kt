package com.example.kidlock

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * 「答题中不许溜走」的核心守卫。
 *
 * 先说清 Android 的边界，这是本文件为什么要这么写的原因：
 *  1. 返回键 / 上滑返回手势 —— 普通 App 可以拦（Activity 里 addCallback 吞掉）；
 *  2. Home 键（底部圆键）、最近任务键 —— 系统保留，普通 App **拦不住**，
 *     只能「被按下之后立刻把答题页拉回前台」，用户感知上就是退不出去；
 *  3. 唯一能真正禁用 Home/最近任务的叫 Lock Task（屏幕固定），
 *     非设备所有者时系统会弹确认框并允许取消，所以只有 dpm set-device-owner
 *     之后（见 ParentActivity 的「强力锁」卡片）才是硬锁。
 *
 * 因此这里负责三件事：
 *  · 锁定态存盘（服务被杀重来也能恢复）；
 *  · 判断孩子是不是已经溜出答题页（顶层应用检测）；
 *  · 把答题页拉回来（回弹）。
 */
object LockGuard {

    private const val PREFS = "kidlock"
    private const val KEY_LOCKED = "guard_locked"
    private const val KEY_RESUME_PKG = "resume_pkg"
    private const val KEY_PERM_PASS = "perm_pass_until"

    /** 系统界面本身不算「孩子逃逸」，否则下拉通知栏、锁屏时会自己打架。 */
    private val SYSTEM_SHELL = setOf("com.android.systemui")

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 锁定态 ----------

    fun isLocked(c: Context): Boolean = p(c).getBoolean(KEY_LOCKED, false)

    fun setLocked(c: Context, locked: Boolean) {
        p(c).edit().putBoolean(KEY_LOCKED, locked).apply()
    }

    // ---------- 授权跳转豁免 ----------
    // 守卫的判断是「顶层只要不是本应用就拉回」，这对孩子是对的，但对家长有一个副作用：
    // 家长点「去开启权限」会跳到系统设置，那一瞬间自己就不在本应用了。
    // 所以只有家长主动跳系统设置时才发一张 3 分钟的通行证，回来立刻作废。
    // （早期版本曾用「登录后给 10 分钟宽限期」，一旦家长中途离开/应用被杀，
    //   这 10 分钟会留在本地，孩子正好捡漏，已废弃。）

    fun grantPermPass(c: Context, minutes: Int = 3) {
        p(c).edit().putLong(KEY_PERM_PASS, System.currentTimeMillis() + minutes * 60_000L).apply()
    }

    fun clearPermPass(c: Context) {
        p(c).edit().putLong(KEY_PERM_PASS, 0).apply()
    }

    fun inPermPass(c: Context): Boolean = p(c).getLong(KEY_PERM_PASS, 0) > System.currentTimeMillis()

    // ---------- 回来时送回原应用 ----------

    /**
     * 记住锁屏前孩子正在用的 App。答对后把他送回原处，
     * 而不是像旧版本那样 finish() 之后直接掉到桌面（这就是「答对就跳出应用」的来源）。
     *
     * 注意：这里**不排除桌面**。某些 ROM（AOSP 的 FallbackHome）会把系统设置也注册成 HOME，
     * 一旦按「是不是桌面」来过滤，真正该记住的 App 反而被误杀。
     * 记住桌面本身没有副作用——答对后启动桌面，效果就是回桌面。
     */
    fun rememberForeground(c: Context) {
        var pkg = topPackage(c)
        // 取到的如果是自己（答题页刚起来），退回「最近一小时内用过」的那个 App
        if (pkg == null || pkg == c.packageName) pkg = lastForeignPackage(c)
        android.util.Log.d("KidLock", "rememberForeground top=$pkg")
        if (pkg == null) return
        if (pkg == c.packageName) return
        if (pkg in SYSTEM_SHELL) return
        p(c).edit().putString(KEY_RESUME_PKG, pkg).apply()
        android.util.Log.d("KidLock", "rememberForeground saved=$pkg")
    }

    /** 最近一小时内使用过、且不是自己的 App，用 lastTimeUsed 取最新。 */
    private fun lastForeignPackage(c: Context): String? {
        if (!hasUsagePermission(c)) return null
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        return try {
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 3_600_000L, now
            ) ?: return null
            stats.asSequence()
                .filter {
                    it.packageName != c.packageName &&
                            it.packageName !in SYSTEM_SHELL &&
                            it.lastTimeUsed > 0
                }
                .maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) {
            null
        }
    }

    fun takeResumePackage(c: Context): String? {
        val pkg = p(c).getString(KEY_RESUME_PKG, null) ?: return null
        p(c).edit().remove(KEY_RESUME_PKG).apply()
        return pkg
    }

    // ---------- 逃逸检测 ----------

    /** 顶层应用不是本 App → 孩子已经溜出去了。查不到（无权限）时保守返回 false。 */
    fun escaped(c: Context): Boolean {
        val top = topPackage(c) ?: return false
        if (top == c.packageName) return false
        if (top in SYSTEM_SHELL) return false
        return true
    }

    /** 当前顶层应用包名。用 UsageEvents 精度最高（毫秒级），拿不到时退回 UsageStats。 */
    fun topPackage(c: Context): String? {
        if (!hasUsagePermission(c)) return null
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()

        try {
            val events = usm.queryEvents(now - 600_000L, now)
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                // MOVE_TO_FOREGROUND 与 ACTIVITY_RESUMED 是同一个常量值（API 29 改名）
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = e.packageName
                }
            }
            if (!last.isNullOrEmpty()) return last
        } catch (_: Exception) { }

        return try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
                ?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) {
            null
        }
    }

    fun hasUsagePermission(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ---------- 把答题页拉回来 ----------

    private var lastReboundAt = 0L

    /**
     * 立刻把答题页顶到最前。带 300ms 节流，避免 Home 动画期间反复请求造成抖动。
     * 走 NEW_TASK + CLEAR_TOP + SINGLE_TOP：答题页是 singleTask，
     * 任务已存在时会直接前置并保留当前题目的进度，不会重新 onCreate 抽题。
     */
    fun rebound(c: Context, force: Boolean = false) {
        if (!isLocked(c)) return
        if (inPermPass(c)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastReboundAt < 300) return
        lastReboundAt = now

        val i = Intent(c, LaunchActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            c.startActivity(i)
        } catch (_: Exception) { }
    }

    /** 是否已开启「显示在其他应用上层」。没有它，Android 10+ 会拦掉后台启动界面。 */
    fun canDrawOverlay(c: Context): Boolean = Settings.canDrawOverlays(c)
}
