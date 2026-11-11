package com.example.kidlock

import android.content.Context

/**
 * 每轮出题的配置，统一读写 SharedPreferences，避免各处散落魔法字符串。
 *
 * 一轮 = 家长设定的「语文 N 题 + 数学 N 题 + 英语 N 题」，
 * 数量填 0 表示该学科不参与本轮；全部为 0 时答题页会提示家长去配置。
 */
object QuizSettings {

    private const val PREFS = "kidlock"

    const val KEY_MINUTES = "grant_minutes"
    const val KEY_GRADE = "quiz_grade"
    const val KEY_EDITION = "quiz_edition"
    const val KEY_DIFFICULTY = "max_difficulty"
    const val KEY_WRONG_FIRST = "wrong_first"
    const val KEY_COUNT_PREFIX = "q_count_"

    const val DEFAULT_GRADE = "三年级"
    const val DEFAULT_EDITION = QuestionBank.EDITION_ANY
    const val DEFAULT_MINUTES = 10
    const val DEFAULT_DIFFICULTY = 3

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun minutes(c: Context): Int = prefs(c).getInt(KEY_MINUTES, DEFAULT_MINUTES)

    fun grade(c: Context): String = prefs(c).getString(KEY_GRADE, DEFAULT_GRADE) ?: DEFAULT_GRADE

    fun edition(c: Context): String =
        prefs(c).getString(KEY_EDITION, DEFAULT_EDITION) ?: DEFAULT_EDITION

    fun difficulty(c: Context): Int =
        prefs(c).getInt(KEY_DIFFICULTY, DEFAULT_DIFFICULTY).coerceIn(1, 3)

    fun wrongFirst(c: Context): Boolean = prefs(c).getBoolean(KEY_WRONG_FIRST, true)

    /** 某学科本轮题量（0 表示不抽）。默认三科各 1 题，家长设完即覆盖。 */
    fun count(c: Context, subject: String): Int =
        prefs(c).getInt(KEY_COUNT_PREFIX + subject, 1).coerceIn(0, 50)

    fun setCount(c: Context, subject: String, n: Int) {
        prefs(c).edit().putInt(KEY_COUNT_PREFIX + subject, n.coerceIn(0, 50)).apply()
    }

    /** 本轮总题量。 */
    fun totalCount(c: Context): Int = QuestionBank.SUBJECTS.sumOf { count(c, it) }

    /** 本轮配置的文字摘要，家长页与答题页共用。 */
    fun summary(c: Context): String {
        val parts = QuestionBank.SUBJECTS
            .map { it to count(c, it) }
            .filter { it.second > 0 }
            .map { "${it.first} ${it.second}" }
        val detail = if (parts.isEmpty()) "未配置题目" else parts.joinToString(" · ")
        return "${grade(c)} · ${edition(c)} · $detail"
    }

    fun save(
        c: Context,
        minutes: Int,
        grade: String,
        edition: String,
        counts: Map<String, Int>,
        difficulty: Int,
        wrongFirst: Boolean
    ) {
        val e = prefs(c).edit()
        e.putInt(KEY_MINUTES, minutes.coerceIn(1, 600))
        e.putString(KEY_GRADE, grade)
        e.putString(KEY_EDITION, edition)
        e.putInt(KEY_DIFFICULTY, difficulty.coerceIn(1, 3))
        e.putBoolean(KEY_WRONG_FIRST, wrongFirst)
        QuestionBank.SUBJECTS.forEach { s ->
            e.putInt(KEY_COUNT_PREFIX + s, (counts[s] ?: 0).coerceIn(0, 50))
        }
        e.apply()
    }
}
