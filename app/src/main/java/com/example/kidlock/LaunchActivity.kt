package com.example.kidlock

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 答题锁屏（也是 App 启动页）。
 *
 * 交互闭环：
 *  时间到 → 按家长配置组卷（语文/数学/英语各 N 题）→ 全部答对 → 发放 N 分钟 → 回到孩子原来在用的 App。
 *  答错 → 记入错题本，并把这道题重新排到队尾，直到答对才离开队列。
 *
 * 「孩子退不出去」在这里做了三层：
 *  1. 全屏沉浸：把底部导航栏（含 Home 键）直接藏起来，要先上滑才能唤出；
 *  2. 返回键/返回手势：onBackPressedDispatcher 直接吞掉；
 *  3. 按 Home 之后：onUserLeaveHint 立刻回弹 + onStop 兜底 + 服务的 0.7 秒轮询，
 *     三道保险保证界面在零点几秒内重新盖回来。
 *  若家长用 adb 把本应用设成「设备所有者」，还会进入 Lock Task 硬锁，
 *  Home / 最近任务 / 通知栏在系统层面直接失效（见 ParentActivity 的强力锁卡片）。
 */
class LaunchActivity : AppCompatActivity() {

    private lateinit var tvQuestion: TextView
    private lateinit var tvSubject: TextView
    private lateinit var layoutOptions: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnParent: Button

    /** 本轮剩余题目队列：答错的题会重新排到队尾，直到答对才真正离开队列。 */
    private val queue = ArrayDeque<Question>()

    /** 本轮计划题量（建卷时的数量，用于显示进度）。 */
    private var plannedTotal = 0
    private var correctCount = 0

    /** 同一道题在一轮内被答错的次数，只用于提示文案。 */
    private val reentryCount = mutableMapOf<String, Int>()

    private var current: Question? = null

    /** 答对后正在主动收尾，此时不允许任何「回弹」逻辑把界面又拉回来。 */
    private var unlocking = false

    /** 点「家长入口」跳到登录页时置位，避免 onStop 把登录页顶掉。 */
    private var navigatingInternally = false

    private val PREFS = "kidlock"
    private val KEY_UNLOCK_UNTIL = "unlock_until"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvQuestion = findViewById(R.id.tvQuestion)
        tvSubject = findViewById(R.id.tvSubject)
        layoutOptions = findViewById(R.id.layoutOptions)
        tvStatus = findViewById(R.id.tvStatus)
        btnParent = findViewById(R.id.btnParent)

        QuestionBank.load(assets)

        // 第 1 层：藏起底部导航栏（Home 键就不在屏幕上了）
        applyImmersiveMode()

        // 第 2 层：返回键 / 返回手势一律吞掉
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (LockGuard.isLocked(this@LaunchActivity)) {
                    Toast.makeText(
                        this@LaunchActivity, "答对题目后才能离开", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        btnParent.setOnClickListener {
            // 家长入口：先登录才能进设置（孩子不知道密码进不去，也就改不了限时）
            navigatingInternally = true
            startActivity(Intent(this, LoginActivity::class.java))
        }

        // 只有「没有剩余使用时长」时才进入强制锁定态；
        // 还在有效时段内主动打开本应用看一眼，不该被锁在里面。
        if (System.currentTimeMillis() >= getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_UNLOCK_UNTIL, 0)
        ) {
            LockGuard.setLocked(this, true)
        }

        // 锁定期间必须有服务在后台做 0.7 秒一次的轮询兜底：
        // 有些机型按「最近任务」只走 onPause，不回调 onUserLeaveHint/onStop，
        // 只靠 Activity 自己是拦不住的。
        if (LockGuard.isLocked(this)) TimerService.start(this)

        buildRound()
        nextQuestion()
    }

    /**
     * 家长可能在设置页改完题库/退出登录后回到这里。
     * 若当前没有题目（例如首次进来时题库还是空的），或题库已被改成空，
     * 都要重新抽题，否则会一直停在「家长尚未配置题库」的旧画面。
     */
    override fun onResume() {
        super.onResume()
        // 回到家门口就说明「内部跳转」结束了，清掉标志，避免下次按 Home 时不回弹
        val returningFromInternal = navigatingInternally
        navigatingInternally = false
        applyImmersiveMode()
        dismissAlarmBanner()
        QuestionBank.load(assets)

        // 从家长设置页回来（改了年级/学科/题量）要重新组卷；
        // 若这道题已经答完解锁了，就不能再组，否则会把孩子重新锁回去。
        if (LockGuard.isLocked(this) &&
            (returningFromInternal || current == null || queue.isEmpty())
        ) {
            buildRound()
            nextQuestion()
        }

        // 设备所有者模式：锁定态进 Lock Task（系统级硬锁，Home 直接失效），
        // 解锁态要主动退出来，否则家长答对后还被钉在这儿。
        // 必须放在界面已经 resumed 之后再调，onCreate 里调 startLockTask() 是不生效的。
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                if (LockGuard.isLocked(this)) maybeLockTask() else releaseLockTask()
            }
        }
    }

    /**
     * 到点那一下是靠「全屏意图通知」把界面顶起来的，
     * 界面起来后横幅还挂在状态栏上会挡住题目标题，这里主动撤掉。
     */
    private fun dismissAlarmBanner() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(TimerService.NOTI_ID_ALARM)
        } catch (_: Exception) { }
    }

    /**
     * 第 3 层（主路径）：用户按了 Home / 切到别的应用，系统会先回调这里。
     * 此时立刻请求把自己顶回前台，过渡动画都还没走完，感知上就是「按了没反应」。
     *
     * 注意：跳到自己家的登录页/设置页时，有的机型也会回调这里，
     * 必须靠 navigatingInternally 放过，否则家长根本进不了设置。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (unlocking) return
        if (navigatingInternally) return
        if (!LockGuard.isLocked(this)) return
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) LockGuard.rebound(this)
        }, 60)
    }

    /** 第 3 层（兜底）：界面真的被切走了（最近任务、被别的窗口盖住等），再补一次。 */
    override fun onStop() {
        super.onStop()
        if (unlocking) return
        if (navigatingInternally) return
        if (!LockGuard.isLocked(this)) return
        if (isChangingConfigurations) return
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) LockGuard.rebound(this)
        }, 220)
    }

    private fun applyImmersiveMode() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.hide(WindowInsetsCompat.Type.systemBars())
            // 上滑可临时唤出导航栏，但几秒后自动隐藏；唤出期间按 Home 由回弹兜住
            c.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (_: Exception) { }
    }

    private fun showSystemBars() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) { }
    }

    /**
     * 组卷：按家长设定的「语文 / 数学 / 英语」题量配额，逐科抽题。
     *
     * 开启「优先出错题」时，每个学科的配额先由该学科的错题填满，
     * 剩余名额再用普通题补齐 —— 这样错题会持续复现，直到连对达标才离开错题本。
     */
    private fun buildRound() {
        queue.clear()
        reentryCount.clear()
        correctCount = 0
        current = null

        val grade = QuizSettings.grade(this)
        val edition = QuizSettings.edition(this)
        val maxD = QuizSettings.difficulty(this)
        val wrongFirst = QuizSettings.wrongFirst(this)

        val picked = mutableListOf<Question>()
        for (subject in QuestionBank.SUBJECTS) {
            val want = QuizSettings.count(this, subject)
            if (want <= 0) continue

            // 家长按教材目录圈定的范围（单元 id 集合）；空集表示全册都抽
            val scope = QuizSettings.scope(this, subject)

            val taken = mutableSetOf<String>()
            if (wrongFirst) {
                for (item in WrongBook.ofSubject(this, subject).filter { it.grade == grade }.shuffled()) {
                    if (taken.size >= want) break
                    var q = item.toQuestion()
                    // 旧版错题快照没有 scopes，回查题库补上，
                    // 否则这些题会无视家长设的教材范围
                    if (q.scopes.isEmpty()) {
                        QuestionBank.findByTitle(q.title)?.let { q = q.copy(scopes = it.scopes) }
                    }
                    // 错题也要服从范围：家长把范围收到第 3 单元，
                    // 却抽出一道第 8 单元的错题，孩子会觉得莫名其妙
                    if (!q.inScope(scope)) continue
                    taken.add(item.key)
                    picked.add(q)
                }
            }
            if (taken.size < want) {
                for (q in QuestionBank.pool(grade, edition, subject, maxD, scope)
                    .filter { it.key !in taken }.shuffled()) {
                    if (taken.size >= want) break
                    taken.add(q.key)
                    picked.add(q)
                }
            }
        }

        // 打散学科顺序，避免连着做同一科
        queue.addAll(picked.shuffled())
        plannedTotal = queue.size
    }

    private fun showNoQuizConfigured() {
        current = null
        tvSubject.text = "未配置题目"
        tvQuestion.text =
            "家长还没有设置题目。\n\n请点击下方「家长入口」，进入「题库与出题」选择年级，并给语文 / 数学 / 英语分别填上题目数量。"
        tvStatus.text = "至少有一个学科的数量要大于 0"
        layoutOptions.removeAllViews()
    }

    private fun nextQuestion() {
        if (queue.isEmpty()) {
            if (plannedTotal == 0) showNoQuizConfigured() else unlock()
            return
        }

        val q = queue.removeFirst()
        current = q

        tvSubject.text = "${q.subject} · ${q.grade}"
        tvQuestion.text = q.title
        tvStatus.text = "已答对 $correctCount 题 · 还剩 ${queue.size + 1} 题"

        layoutOptions.removeAllViews()
        q.options.forEachIndexed { idx, text ->
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
            ).apply { bottomMargin = dp(12) }
            val b = Button(this).apply {
                // 选项做成科技蓝卡片：左侧 A/B/C/D 序号 + 居中选项文字
                this.text = "${"ABCD"[idx % 4]}   $text"
                textSize = 18f
                isAllCaps = false
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@LaunchActivity, R.color.text_primary))
                background = ContextCompat.getDrawable(this@LaunchActivity, R.drawable.bg_option)
                stateListAnimator = null
                layoutParams = lp
                setOnClickListener { onAnswer(idx) }
            }
            layoutOptions.addView(b)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun onAnswer(idx: Int) {
        val q = current ?: return

        if (idx == q.answerIndex) {
            correctCount++
            val mastered = WrongBook.recordCorrect(this, q)
            val msg = if (mastered) "答对！这道错题已掌握，移出错题本"
            else "答对！继续下一题"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            nextQuestion()
        } else {
            // 答错：记入错题本，并把这道题重新排到队尾 ——
            // 队列没清空就不会解锁，所以孩子必须把这题真正答对才能出去。
            WrongBook.recordWrong(this, q)
            val times = (reentryCount[q.key] ?: 0) + 1
            reentryCount[q.key] = times
            queue.addLast(q)
            Toast.makeText(
                this,
                "答错了（这道题已错 $times 次），稍后还会出现",
                Toast.LENGTH_SHORT
            ).show()
            nextQuestion()
        }
    }

    private fun unlock() {
        unlocking = true

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val minutes = QuizSettings.minutes(this)
        val until = System.currentTimeMillis() + minutes * 60_000L
        prefs.edit().putLong(KEY_UNLOCK_UNTIL, until).apply()

        // 解除锁定 + 放行系统栏 + 退出 Lock Task
        LockGuard.setLocked(this, false)
        showSystemBars()
        try { stopLockTask() } catch (_: Exception) { }

        // 通知服务：回到倒计时模式
        TimerService.unlocked(this)

        Toast.makeText(this, "解锁成功，可用 $minutes 分钟", Toast.LENGTH_SHORT).show()

        // 送孩子回到「时间到之前正在用的那个 App」，而不是直接掉回桌面
        //（旧版本 finish() 后任务栈空了就落桌面，这就是「答对就跳出应用」的来源）
        val back = LockGuard.takeResumePackage(this)
        var resumed = false
        if (back != null) {
            val li = packageManager.getLaunchIntentForPackage(back)
            if (li != null) {
                try {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(li)
                    resumed = true
                } catch (_: Exception) { }
            }
        }

        if (resumed) {
            finishAndRemoveTask()
        } else {
            // 没有记录到「原应用」（例如锁屏前就在桌面，或系统没给出使用记录）：
            // 先把答题任务让到后台，系统会自然露出上一个界面，再把自己清掉。
            // 这比直接 finish() 更能回到孩子刚才的位置。
            val moved = try { moveTaskToBack(true) } catch (_: Exception) { false }
            if (moved) {
                window.decorView.postDelayed({
                    try { finishAndRemoveTask() } catch (_: Exception) { }
                }, 600)
            } else {
                finishAndRemoveTask()
            }
        }
    }

    // ---------- 设备所有者硬锁 ----------

    private fun isDeviceOwner(): Boolean = try {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isDeviceOwnerApp(packageName)
    } catch (_: Exception) {
        false
    }

    /**
     * 设备所有者时进入 Lock Task：Home / 最近任务 / 通知栏在系统层面被禁用，
     * 孩子连「退」这个动作都做不到，而不是靠事后回弹。
     * 普通安装（非设备所有者）下不调用，否则会弹「固定到屏幕」的系统确认框，孩子点取消即可绕过。
     */
    private fun maybeLockTask() {
        if (!isDeviceOwner()) return
        try {
            val admin = ComponentName(this, AdminReceiver::class.java)
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            // 顺序有讲究：先把本应用列为唯一允许锁定的包、再关掉全部 Lock Task 功能，
            // 最后才 startLockTask()。反过来的话，界面会先带着导航栏进入锁定态再闪烁一次。
            dpm.setLockTaskPackages(admin, arrayOf(packageName))
            if (Build.VERSION.SDK_INT >= 28) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask()
                Log.d("KidLock", "lockTask started, state=${am.lockTaskModeState}")
            }
        } catch (e: Exception) {
            Log.e("KidLock", "lockTask failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 解锁（答对）或非锁定态时退出 Lock Task，否则家长会被钉在答题页里出不去。 */
    private fun releaseLockTask() {
        if (!isDeviceOwner()) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
                stopLockTask()
                Log.d("KidLock", "lockTask released")
            }
        } catch (e: Exception) {
            Log.e("KidLock", "release lockTask failed: ${e.javaClass.simpleName}")
        }
    }
}
