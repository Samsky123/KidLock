package com.example.kidlock

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 家长账号体系（纯本地，无服务器、无网络）。
 *
 * 安全设计：
 * - 密码使用 PBKDF2-HMAC-SHA256（10 万次迭代 + 16 字节随机盐）派生，明文不落盘；
 *   旧版本写入的单轮 SHA-256 哈希会在登录成功时自动迁移，老用户无感。
 * - 哈希比较使用 [MessageDigest.isEqual]，避免逐字节比较的时序侧信道。
 * - 连续输错 5 次锁定 60 秒，失败计数落盘，重启 App 不能绕过，防孩子穷举。
 * - 登录态是内存会话：进程被销毁即失效，下次改设置需重新登录。
 * - 管控配置（时长/题库/App 限制）独立存储、不依赖登录态，
 *   孩子即使进不去设置也改不了限时，只能按家长设定的用。
 */
object AuthStore {

    enum class LoginResult { SUCCESS, BAD_CREDENTIALS, LOCKED }

    private const val PREFS = "kidlock_auth"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_FAILS = "login_fails"
    private const val KEY_LOCK_UNTIL = "login_lock_until"

    private const val MAX_FAILS = 5
    private const val LOCK_MS = 60_000L

    private const val PBKDF2_ITERATIONS = 100_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val SCHEME = "pbkdf2"

    private var currentUser: String? = null   // 内存会话，进程死亡即清空

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun accountsJson(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString(KEY_ACCOUNTS, "{}") ?: "{}"
        return JSONObject(raw)
    }

    /** 是否已有家长账号（决定显示「创建」还是「登录」）。 */
    fun accountExists(ctx: Context): Boolean = accountsJson(ctx).keys().hasNext()

    /** 创建账号；账号已存在或密码太短返回 false。成功后自动登录。 */
    fun createAccount(ctx: Context, username: String, password: String): Boolean {
        if (username.isBlank() || password.length < 6) return false
        val p = prefs(ctx)
        val j = accountsJson(ctx)
        if (j.has(username)) return false
        j.put(username, JSONObject().put("hash", hashPassword(password)))
        p.edit().putString(KEY_ACCOUNTS, j.toString()).apply()
        clearFailures(p)
        currentUser = username
        return true
    }

    /** 校验账号密码（不改动会话、不计失败次数）。 */
    fun verify(ctx: Context, username: String, password: String): Boolean {
        val u = accountsJson(ctx).optJSONObject(username) ?: return false
        val stored = u.optString("hash")
        return if (stored.startsWith("$SCHEME$")) {
            verifyPbkdf2(stored, password)
        } else {
            verifyLegacy(ctx, username, stored, password)
        }
    }

    /**
     * 登录：成功写入内存会话；失败累计次数，达到上限后锁定一段时间。
     * 无论账号是否存在都计入失败，避免通过提示差异枚举账号。
     */
    fun login(ctx: Context, username: String, password: String): LoginResult {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now < p.getLong(KEY_LOCK_UNTIL, 0L)) return LoginResult.LOCKED

        val account = accountsJson(ctx).optJSONObject(username)
        val ok = account != null && run {
            val stored = account.optString("hash")
            if (stored.startsWith("$SCHEME$")) {
                verifyPbkdf2(stored, password)
            } else {
                verifyLegacy(ctx, username, stored, password)
            }
        }

        if (!ok) {
            val fails = p.getInt(KEY_FAILS, 0) + 1
            if (fails >= MAX_FAILS) {
                p.edit().putInt(KEY_FAILS, 0)
                    .putLong(KEY_LOCK_UNTIL, now + LOCK_MS).apply()
                return LoginResult.LOCKED
            }
            p.edit().putInt(KEY_FAILS, fails).apply()
            return LoginResult.BAD_CREDENTIALS
        }

        clearFailures(p)
        currentUser = username
        return LoginResult.SUCCESS
    }

    /** 距离解锁还剩多少毫秒（0 表示未锁定）。 */
    fun lockRemainingMs(ctx: Context): Long {
        val left = prefs(ctx).getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()
        return if (left > 0) left else 0L
    }

    fun isLoggedIn(): Boolean = currentUser != null
    fun currentUser(): String? = currentUser
    fun logout() { currentUser = null }

    // ---- 密码派生 ----

    /** 返回 `pbkdf2$迭代次数$盐Base64$哈希Base64`。 */
    private fun hashPassword(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(password, salt, PBKDF2_ITERATIONS)
        return listOf(
            SCHEME,
            PBKDF2_ITERATIONS.toString(),
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(hash, Base64.NO_WRAP)
        ).joinToString("$")
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun verifyPbkdf2(stored: String, password: String): Boolean {
        return try {
            val parts = stored.split("$")
            if (parts.size != 4) return false
            val iterations = parts[1].toInt()
            val salt = Base64.decode(parts[2], Base64.NO_WRAP)
            val expected = Base64.decode(parts[3], Base64.NO_WRAP)
            MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations))
        } catch (e: Exception) {
            false
        }
    }

    /** 兼容 v1.4 及更早版本：随机盐 + 单轮 SHA-256；验证通过后原地升级为 PBKDF2。 */
    private fun verifyLegacy(ctx: Context, username: String, stored: String, password: String): Boolean {
        val j = accountsJson(ctx)
        val u = j.optJSONObject(username) ?: return false
        val salt = u.optString("salt")
        if (salt.isEmpty() || stored.length != 64) return false
        val legacy = sha256(salt + password)
        if (!MessageDigest.isEqual(legacy.toByteArray(), stored.toByteArray())) return false

        u.put("hash", hashPassword(password))
        u.remove("salt")
        prefs(ctx).edit().putString(KEY_ACCOUNTS, j.toString()).apply()
        return true
    }

    private fun clearFailures(p: SharedPreferences) {
        p.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray())
            .joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    }
}
