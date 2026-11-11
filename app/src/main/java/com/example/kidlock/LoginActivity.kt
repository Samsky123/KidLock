package com.example.kidlock

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * 家长登录 / 首次创建账号。
 * - 无账号时显示「创建」表单（两次密码一致后建账号并进入设置）；
 * - 有账号时显示「登录」表单，密码正确才放行。
 * 登录成功统一跳到 ParentActivity，并给家长一段「宽限期」——这期间守卫不回弹，
 * 家长可以安心改设置；离开设置页时宽限期立刻失效（见 ParentActivity.onDestroy）。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var etUser: EditText
    private lateinit var etPw: EditText
    private lateinit var etPw2: EditText
    private lateinit var btn: Button
    private lateinit var tvHint: TextView
    private var firstRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        tvTitle = findViewById(R.id.tvTitle)
        etUser = findViewById(R.id.etUser)
        etPw = findViewById(R.id.etPw)
        etPw2 = findViewById(R.id.etPw2)
        btn = findViewById(R.id.btnLogin)
        tvHint = findViewById(R.id.tvHint)

        firstRun = !AuthStore.accountExists(this)
        if (firstRun) {
            tvTitle.text = "首次使用 · 创建家长账号"
            etPw2.visibility = View.VISIBLE
            btn.text = "创建并进入"
            tvHint.text = "账号密码只有您知道，密码至少 6 位"
        } else {
            tvTitle.text = "家长登录"
            etPw2.visibility = View.GONE
            btn.text = "登录"
            tvHint.text = ""
        }

        btn.setOnClickListener { onSubmit() }
    }

    private fun onSubmit() {
        val u = etUser.text.toString().trim()
        val p = etPw.text.toString()
        if (u.isEmpty() || p.isEmpty()) { toast("请输入账号和密码"); return }

        if (firstRun) {
            val p2 = etPw2.text.toString()
            if (p != p2) { toast("两次密码不一致"); return }
            if (p.length < 6) { toast("密码至少 6 位"); return }
            if (!AuthStore.createAccount(this, u, p)) {
                toast("该账号已存在，请直接登录"); return
            }
            goParent()
        } else {
            when (AuthStore.login(this, u, p)) {
                AuthStore.LoginResult.SUCCESS -> goParent()
                AuthStore.LoginResult.LOCKED -> {
                    val sec = (AuthStore.lockRemainingMs(this) + 999) / 1000
                    toast("密码错误次数过多，请 $sec 秒后再试")
                }
                AuthStore.LoginResult.BAD_CREDENTIALS -> toast("账号或密码错误")
            }
        }
    }

    private fun goParent() {
        startActivity(Intent(this, ParentActivity::class.java))
        finish()
    }

    /**
     * 孩子点「家长入口」后停在登录页，按 Home 一样会溜走。
     * 这里补上同样的回弹（登录页属于本应用，弹回的是答题页，孩子仍然出不去）。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (AuthStore.isLoggedIn()) return
        if (!LockGuard.isLocked(this)) return
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) LockGuard.rebound(this)
        }, 60)
    }

    override fun onStop() {
        super.onStop()
        if (AuthStore.isLoggedIn()) return
        if (!LockGuard.isLocked(this)) return
        if (isChangingConfigurations) return
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) LockGuard.rebound(this)
        }, 220)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
