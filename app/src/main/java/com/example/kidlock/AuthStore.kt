package com.example.kidlock

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 家长账号体系（本地，无服务器）。
 *
 * - 账号密码以 SHA-256 + 随机盐 哈希存储，明文密码不落盘，孩子拿到手机也读不出密码。
 * - 登录态为「内存会话」：进程被销毁（如重启/被杀）即失效，下次改设置需重新登录——
 *   这正好满足「家长退出登录后，孩子不知道密码就进不去」。
 * - 管控配置（时长/题库/App 限制）独立存于 kidlock 偏好，不依赖登录态，
 *   因此孩子即使无法登录也改不了限时，只能按家长设的用。
 */
object AuthStore {

    private const val PREFS = "kidlock_auth"
    private const val KEY_ACCOUNTS = "accounts"
    private var currentUser: String? = null   // 内存会话，进程死亡即清空

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun accountsJson(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString(KEY_ACCOUNTS, "{}") ?: "{}"
        return JSONObject(raw)
    }

    /** 是否已有家长账号（决定是否显示「创建」还是「登录」）。 */
    fun accountExists(ctx: Context): Boolean {
        return accountsJson(ctx).keys().hasNext()
    }

    /** 创建账号；账号已存在返回 false。成功后自动登录。 */
    fun createAccount(ctx: Context, username: String, password: String): Boolean {
        val p = prefs(ctx)
        val j = accountsJson(ctx)
        if (j.has(username)) return false
        val salt = randomSalt()
        j.put(username, JSONObject().put("salt", salt).put("hash", sha256(salt + password)))
        p.edit().putString(KEY_ACCOUNTS, j.toString()).apply()
        currentUser = username
        return true
    }

    /** 校验账号密码（不改动会话）。 */
    fun verify(ctx: Context, username: String, password: String): Boolean {
        val j = accountsJson(ctx)
        val u = j.optJSONObject(username) ?: return false
        return u.getString("hash") == sha256(u.getString("salt") + password)
    }

    /** 登录；成功写入内存会话并返回 true。 */
    fun login(ctx: Context, username: String, password: String): Boolean {
        return if (verify(ctx, username, password)) {
            currentUser = username
            true
        } else false
    }

    fun isLoggedIn(): Boolean = currentUser != null
    fun currentUser(): String? = currentUser
    fun logout() { currentUser = null }

    private fun randomSalt(): String {
        val chars = ('a'..'z') + ('0'..'9')
        return (1..16).map { chars.random() }.joinToString("")
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray())
            .joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    }
}
