package com.example.kidlock

import android.app.*
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * 常驻前台服务，承担两个角色：
 *
 * 1. 倒计时：家长发放 N 分钟后，每秒刷新通知；
 * 2. 守卫：时间到 → 弹答题页并进入「锁定态」→ 每 0.7 秒检查一次顶层应用，
 *    孩子按 Home / 从最近任务切走 / 被别的 App 盖住，都会被立刻拉回答题页。
 *
 * 为什么要轮询：Home 键是系统保留键，普通应用收不到也不不了，只能事后回弹。
 * 0.7 秒是「肉眼几乎看不出逃逸成功」和「不烧电」之间的折中。
 */
class TimerService : Service() {

    companion object {
        /** 答对题目 → 解除锁定，回到倒计时 */
        const val ACTION_UNLOCKED = "com.example.kidlock.UNLOCKED"
        /** 家长加时 / 改配置后刷新 */
        const val ACTION_REFRESH = "com.example.kidlock.REFRESH"

        private const val PREFS = "kidlock"
        private const val KEY_UNLOCK_UNTIL = "unlock_until"
        private const val CHANNEL = "kidlock_channel"
        private const val CHANNEL_ALARM = "kidlock_alarm"
        private const val NOTI_ID_RUNNING = 1
        /** 到点提醒（全屏意图）通知；答题页出现后由 Activity 主动撤掉，避免横幅挡住题目 */
        const val NOTI_ID_ALARM = 2

        /** 轮询间隔：决定「按 Home 之后多久被拉回」 */
        private const val TICK_MS = 700L

        /** 答题页刚弹起的一小段时间不判逃逸，等窗口切换动画稳定 */
        private const val SETTLE_MS = 1500L

        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, TimerService::class.java))
            } catch (_: Exception) { }
        }

        fun unlocked(c: Context) {
            try {
                ContextCompat.startForegroundService(
                    c, Intent(c, TimerService::class.java).setAction(ACTION_UNLOCKED)
                )
            } catch (_: Exception) { }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var lockedAt = 0L

    /** 通知文案没变就不重建通知，避免 0.7 秒一次的无谓刷新把状态栏刷闪。 */
    private var lastText: String? = null

    private fun updateNotification(text: String) {
        if (text == lastText) return
        lastText = text
        try {
            startForeground(NOTI_ID_RUNNING, buildNotification(text))
        } catch (_: Exception) { }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTI_ID_RUNNING, buildNotification("管控中…"))

        when (intent?.action) {
            ACTION_UNLOCKED -> {
                LockGuard.setLocked(this, false)
                lockedAt = 0L
                cancelAlarm()
            }
            ACTION_REFRESH -> {
                // 家长刚改完配置，让下一轮 tick 自己读最新值
            }
        }

        if (!ticking) {
            ticking = true
            tick()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ticking = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 任务被从「最近任务」里划掉时，如果还在答题锁定态，立刻弹回来。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (LockGuard.isLocked(this)) {
            handler.postDelayed({ LockGuard.rebound(this, force = true) }, 200)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun tick() {
        handler.postDelayed({
            if (!ticking) return@postDelayed
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            // M2：受限 App（抖音等）当日超时 → 同样进入答题锁定
            if (checkAppLimits(prefs)) {
                tick()
                return@postDelayed
            }

            val until = prefs.getLong(KEY_UNLOCK_UNTIL, 0)
            val remain = until - System.currentTimeMillis()

            if (remain <= 0) {
                // 时间到：清掉时间戳，进入锁定
                if (until != 0L) prefs.edit().putLong(KEY_UNLOCK_UNTIL, 0).apply()
                if (!LockGuard.isLocked(this)) enterLock()
                else guard()
                updateNotification("答题解锁中 · 答对即可继续使用")
            } else {
                // 还有可用时长：若之前处于锁定态（家长加时/答题已过），解除锁定
                if (LockGuard.isLocked(this)) {
                    LockGuard.setLocked(this, false)
                    lockedAt = 0L
                    cancelAlarm()
                }
                updateNotification(format(remain))
            }

            tick()
        }, TICK_MS)
    }

    /** 首次进入锁定：记住孩子原来在用什么 App，然后弹答题页。 */
    private fun enterLock() {
        LockGuard.rememberForeground(this)
        LockGuard.setLocked(this, true)
        lockedAt = System.currentTimeMillis()
        lockNow()
    }

    /** 锁定中每次 tick 的守卫动作。 */
    private fun guard() {
        if (System.currentTimeMillis() - lockedAt < SETTLE_MS) return   // 界面还在弹起
        if (LockGuard.inPermPass(this)) return                          // 家长正在系统设置里开权限
        if (!LockGuard.escaped(this)) return                            // 还在答题页
        LockGuard.rebound(this)
    }

    /**
     * M2 按 App 限时：若当前前台是受限 App 且当日已用时长 >= 上限，则锁屏并返回 true。
     * 需「使用情况访问」权限；未授权或无配置时直接返回 false（不拦截）。
     */
    private fun checkAppLimits(prefs: SharedPreferences): Boolean {
        val limits = AppLimitStore.load(prefs).filter { it.enabled && it.limitMin > 0 }
        if (limits.isEmpty()) return false
        if (!hasUsagePermission()) return false
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false

        val now = System.currentTimeMillis()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val fg = currentForegroundPackage(usm, now) ?: return false
        val limit = limits.firstOrNull { it.pkg == fg } ?: return false
        val used = dailyUsage(usm, fg, startOfDay, now)
        return if (used >= limit.limitMin * 60_000L) {
            prefs.edit().putLong(KEY_UNLOCK_UNTIL, 0).apply()
            if (!LockGuard.isLocked(this)) enterLock() else guard()
            true
        } else false
    }

    private fun hasUsagePermission(): Boolean = LockGuard.hasUsagePermission(this)

    private fun currentForegroundPackage(usm: UsageStatsManager, now: Long): String? {
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10_000, now)
        if (stats.isNullOrEmpty()) return null
        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun dailyUsage(usm: UsageStatsManager, pkg: String, start: Long, end: Long): Long {
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        if (stats.isNullOrEmpty()) return 0L
        return stats.filter { it.packageName == pkg }.sumOf { it.totalTimeInForeground }
    }

    private fun format(ms: Long): String {
        val totalSec = ms / 1000
        return "剩余 ${totalSec / 60}分${totalSec % 60}秒"
    }

    /**
     * 把答题锁屏拉到最前。
     *
     * 关键说明（踩过的坑）：Android 10+ 默认禁止后台进程启动 Activity，
     * 前台服务里直接 startActivity() 会被系统以 `BAL_BLOCK` 拦掉，表现为
     * "时间到了却什么都不弹，管控形同虚设"。
     * 两个可行通道：
     *  1) 应用持有「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW）时，系统豁免后台启动限制；
     *  2) 屏幕锁着时，用「全屏意图通知」也能直接弹到前台。
     * 所以这里两条都走：先尝试启动，再补一条全屏意图通知兜底。
     */
    private fun lockNow() {
        val i = Intent(this, LaunchActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            startActivity(i)
        } catch (_: Exception) { }

        // 兜底：全屏意图通知（屏幕锁定时系统会直接拉起）
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTI_ID_ALARM, buildAlarmNotification())
        } catch (_: Exception) { }
    }

    private fun cancelAlarm() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTI_ID_ALARM)
        } catch (_: Exception) { }
    }

    private fun buildAlarmNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 2,
            Intent(this, LaunchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            if (Build.VERSION.SDK_INT >= 33) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setContentTitle("时间到啦")
            .setContentText("做完一道题就能继续使用手机")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, LaunchActivity::class.java),
            if (Build.VERSION.SDK_INT >= 33) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val parentPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, LoginActivity::class.java),
            if (Build.VERSION.SDK_INT >= 33) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("儿童手机管控")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_preferences, "家长设置", parentPi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "管控通知", NotificationManager.IMPORTANCE_LOW)
            )
            // 全屏意图必须走 IMPORTANCE_HIGH 的渠道，否则系统会降级为普通通知
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARM, "到点提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
