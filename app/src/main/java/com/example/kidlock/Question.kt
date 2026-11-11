package com.example.kidlock

import android.content.res.AssetManager
import org.json.JSONArray

/**
 * 单道题。
 * @param answerIndex 正确选项下标（从 0 开始）。
 * @param difficulty 难度：1 易 / 2 中 / 3 难。
 */
data class Question(
    val title: String,
    val options: List<String>,
    val answerIndex: Int,
    val difficulty: Int = 1
)

/**
 * 题库分组：年级 + 学科 + 进度单元，下挂若干题。
 * key 用于家长勾选状态的持久化（"年级|学科|单元"）。
 */
data class QuestionGroup(
    val grade: String,
    val subject: String,
    val unit: String,
    val questions: List<Question>
) {
    val key: String get() = "$grade|$subject|$unit"
}

/**
 * 结构化题库：按年级 / 学科 / 进度单元组织。
 * 家长勾选若干单元 → 孩子答题只从这些单元的题里随机抽。
 */
object QuestionBank {
    private val groups = mutableListOf<QuestionGroup>()

    fun loadFromAssets(assets: AssetManager) {
        if (groups.isNotEmpty()) return
        val json = assets.open("questions.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val qArr = o.getJSONArray("questions")
            val qs = List(qArr.length()) { j ->
                val q = qArr.getJSONObject(j)
                val optArr = q.getJSONArray("options")
                Question(
                    title = q.getString("title"),
                    options = List(optArr.length()) { k -> optArr.getString(k) },
                    answerIndex = q.getInt("answer"),
                    difficulty = if (q.has("difficulty")) q.getInt("difficulty") else 1
                )
            }
            groups.add(
                QuestionGroup(
                    grade = o.getString("grade"),
                    subject = o.getString("subject"),
                    unit = o.getString("unit"),
                    questions = qs
                )
            )
        }
    }

    fun grades(): List<String> = groups.map { it.grade }.distinct()
    fun subjectsForGrade(grade: String): List<String> =
        groups.filter { it.grade == grade }.map { it.subject }.distinct()
    fun unitsForGradeSubject(grade: String, subject: String): List<String> =
        groups.filter { it.grade == grade && it.subject == subject }.map { it.unit }.distinct()

    /** 已勾选单元、且难度 <= maxDifficulty 的题目（孩子抽题池）。 */
    fun activePool(activeKeys: Set<String>, maxDifficulty: Int): List<Question> =
        groups.filter { activeKeys.contains(it.key) }
            .flatMap { it.questions }
            .filter { it.difficulty <= maxDifficulty }

    fun allGroups(): List<QuestionGroup> = groups
    fun isEmpty(): Boolean = groups.isEmpty()
}
