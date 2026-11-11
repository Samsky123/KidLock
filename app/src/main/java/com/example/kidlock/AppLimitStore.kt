package com.example.kidlock

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单个 App 的限时配置。
 * @param pkg 应用包名（如抖音 com.ss.android.ugc.aweme）
 * @param limitMin 每日可使用时长（分钟）；0 表示不限
 * @param enabled 是否启用该限制
 */
data class AppLimit(
    val pkg: String,
    val label: String,
    val limitMin: Int,
    val enabled: Boolean
)

/** 受限 App 配置的读写（JSON 存于 SharedPreferences "app_limits"）。 */
object AppLimitStore {
    private const val KEY = "app_limits"

    fun load(prefs: SharedPreferences): List<AppLimit> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        val out = mutableListOf<AppLimit>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    AppLimit(
                        pkg = o.getString("pkg"),
                        label = o.getString("label"),
                        limitMin = o.getInt("limitMin"),
                        enabled = o.getBoolean("enabled")
                    )
                )
            }
        } catch (_: Exception) { }
        return out
    }

    fun save(prefs: SharedPreferences, list: List<AppLimit>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().apply {
                    put("pkg", it.pkg)
                    put("label", it.label)
                    put("limitMin", it.limitMin)
                    put("enabled", it.enabled)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
