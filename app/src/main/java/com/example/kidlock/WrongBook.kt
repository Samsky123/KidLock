package com.example.kidlock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 错题本。
 *
 * 规则（对应产品需求「孩子反复答错的题目可以再次随机入库，直到完全掌握答对」）：
 *  - 答错一次 → 记入错题本，wrongCount +1，连对次数清零；
 *  - 之后答对 → 连对次数 +1，连续答对 [MASTER_STREAK] 次才算「掌握」，从错题本移除；
 *  - 未掌握期间，只要开启了「优先出错题」，这道题会持续参与组卷，反复出现。
 *
 * 存储：SharedPreferences 里存一段 JSON 数组（题目含选项，保证下次能原样复原）。
 */
object WrongBook {

    private const val PREFS = "kidlock"
    private const val KEY = "wrong_book_v2"

    /** 连续答对多少次算掌握。设成 2 是为了防止「蒙对一次」就被清掉。 */
    const val MASTER_STREAK = 2

    data class Item(
        val key: String,
        val title: String,
        val options: List<String>,
        val answerIndex: Int,
        val subject: String,
        val grade: String,
        val unit: String,
        val scopes: List<String> = emptyList(),
        val wrongCount: Int,
        val streak: Int,
        val lastTime: Long
    ) {
        /** 转回 Question，供组卷复用。 */
        fun toQuestion(): Question = Question(
            title = title,
            options = options,
            answerIndex = answerIndex,
            difficulty = 1,
            subject = subject,
            grade = grade,
            unit = unit,
            scopes = scopes
        )

        /** 是否已经掌握（连对达标）。 */
        val mastered: Boolean get() = streak >= MASTER_STREAK
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAll(c: Context): MutableList<Item> {
        val raw = prefs(c).getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<Item>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val opts = o.optJSONArray("options") ?: JSONArray()
                out.add(
                    Item(
                        key = o.optString("key"),
                        title = o.optString("title"),
                        options = List(opts.length()) { k -> opts.getString(k) },
                        answerIndex = o.optInt("answer"),
                        subject = o.optString("subject"),
                        grade = o.optString("grade"),
                        unit = o.optString("unit"),
                        scopes = o.optJSONArray("scopes")?.let { a ->
                            List(a.length()) { k -> a.getString(k) }
                        } ?: emptyList(),
                        wrongCount = o.optInt("wrong", 1),
                        streak = o.optInt("streak", 0),
                        lastTime = o.optLong("time", 0)
                    )
                )
            }
        } catch (_: Exception) {
            // 数据损坏就当作空错题本，不能让答题流程起不来
        }
        return out
    }

    private fun writeAll(c: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { it ->
            val o = JSONObject()
            o.put("key", it.key)
            o.put("title", it.title)
            o.put("options", JSONArray(it.options))
            o.put("answer", it.answerIndex)
            o.put("subject", it.subject)
            o.put("grade", it.grade)
            o.put("unit", it.unit)
            o.put("scopes", JSONArray(it.scopes))
            o.put("wrong", it.wrongCount)
            o.put("streak", it.streak)
            o.put("time", it.lastTime)
            arr.put(o)
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    fun all(c: Context): List<Item> = readAll(c)

    fun size(c: Context): Int = readAll(c).size

    fun ofSubject(c: Context, subject: String): List<Item> =
        readAll(c).filter { it.subject == subject }

    fun ofGrade(c: Context, grade: String): List<Item> =
        readAll(c).filter { it.grade == grade }

    /** 答错：入本或累加错误次数，连对清零。 */
    fun recordWrong(c: Context, q: Question) {
        val items = readAll(c)
        val idx = items.indexOfFirst { it.key == q.key }
        val now = System.currentTimeMillis()
        if (idx >= 0) {
            val old = items[idx]
            items[idx] = old.copy(
                wrongCount = old.wrongCount + 1,
                streak = 0,
                lastTime = now
            )
        } else {
            items.add(
                Item(
                    key = q.key,
                    title = q.title,
                    options = q.options,
                    answerIndex = q.answerIndex,
                    subject = q.subject,
                    grade = q.grade,
                    unit = q.unit,
                    scopes = q.scopes,
                    wrongCount = 1,
                    streak = 0,
                    lastTime = now
                )
            )
        }
        writeAll(c, items)
    }

    /**
     * 答对：若在错题本里，连对次数 +1；达到 [MASTER_STREAK] 次则移出（表示已掌握）。
     * @return true 表示这道题本次被移出错题本。
     */
    fun recordCorrect(c: Context, q: Question): Boolean {
        val items = readAll(c)
        val idx = items.indexOfFirst { it.key == q.key }
        if (idx < 0) return false
        val old = items[idx]
        val newStreak = old.streak + 1
        val now = System.currentTimeMillis()
        return if (newStreak >= MASTER_STREAK) {
            items.removeAt(idx)
            writeAll(c, items)
            true
        } else {
            items[idx] = old.copy(streak = newStreak, lastTime = now)
            writeAll(c, items)
            false
        }
    }

    fun remove(c: Context, key: String) {
        val items = readAll(c).filterNot { it.key == key }
        writeAll(c, items)
    }

    fun clear(c: Context) {
        prefs(c).edit().remove(KEY).apply()
    }
}
