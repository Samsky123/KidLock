package com.example.kidlock

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单道题。
 *
 * @param answerIndex 正确选项下标（从 0 开始）
 * @param difficulty 难度：1 易 / 2 中 / 3 难
 * @param subject 学科：语文 / 数学 / 英语
 * @param edition 教材版本：通用 / 人教版 / 苏教版 / 北师大版 / 外研版 / 译林版
 */
data class Question(
    val title: String,
    val options: List<String>,
    val answerIndex: Int,
    val difficulty: Int = 1,
    val subject: String = "",
    val grade: String = "",
    val unit: String = "",
    val edition: String = EDITION_COMMON
) {
    /** 全局唯一标识：同一道题在错题本里靠它定位。 */
    val key: String get() = "$subject|$grade|$title"

    companion object {
        const val EDITION_COMMON = "通用"
    }
}

/**
 * 题库分组：年级 + 学科 + 版本 + 单元，下挂若干题。
 */
data class QuestionGroup(
    val grade: String,
    val subject: String,
    val edition: String,
    val unit: String,
    val questions: List<Question>
)

/**
 * 题库：从 assets/questions.json 载入。
 *
 * 数据结构见 tools/gen_bank.py：
 *   { "version":2, "editions":[...], "grades":[...], "groups":[{grade,subject,edition,unit,questions}] }
 * 同时兼容旧版「顶层直接是数组」的格式（一律按「通用」版本处理）。
 */
object QuestionBank {

    /** 家长可选的「不分版本」：命中全部版本。 */
    const val EDITION_ANY = "不分版本"

    val SUBJECTS = listOf("语文", "数学", "英语")

    private val groups = mutableListOf<QuestionGroup>()
    private var editions = mutableListOf<String>()
    private var gradesList = mutableListOf<String>()
    private var loaded = false

    @Synchronized
    fun load(assets: AssetManager) {
        if (loaded) return
        val text = assets.open("questions.json").bufferedReader().use { it.readText() }
        val root = text.trimStart().let {
            if (it.startsWith("[")) null else JSONObject(it)
        }

        val groupArray: JSONArray = if (root != null) root.getJSONArray("groups") else JSONArray(text)

        if (root != null) {
            root.optJSONArray("editions")?.let { arr ->
                for (i in 0 until arr.length()) editions.add(arr.getString(i))
            }
            root.optJSONArray("grades")?.let { arr ->
                for (i in 0 until arr.length()) gradesList.add(arr.getString(i))
            }
        }

        for (i in 0 until groupArray.length()) {
            val g = groupArray.getJSONObject(i)
            val grade = g.getString("grade")
            val subject = g.getString("subject")
            val edition = if (g.has("edition")) g.getString("edition") else Question.EDITION_COMMON
            val unit = if (g.has("unit")) g.getString("unit") else ""
            val qArr = g.getJSONArray("questions")
            val list = ArrayList<Question>(qArr.length())
            for (j in 0 until qArr.length()) {
                val q = qArr.getJSONObject(j)
                val optArr = q.getJSONArray("options")
                list.add(
                    Question(
                        title = q.getString("title"),
                        options = List(optArr.length()) { k -> optArr.getString(k) },
                        answerIndex = q.getInt("answer"),
                        difficulty = if (q.has("difficulty")) q.getInt("difficulty") else 1,
                        subject = subject,
                        grade = grade,
                        unit = unit,
                        edition = edition
                    )
                )
            }
            groups.add(QuestionGroup(grade, subject, edition, unit, list))
        }

        if (gradesList.isEmpty()) gradesList.addAll(groups.map { it.grade }.distinct())
        if (editions.isEmpty()) editions.addAll(groups.map { it.edition }.distinct())
        // 固定顺序：一~六年级
        val order = listOf("一年级", "二年级", "三年级", "四年级", "五年级", "六年级")
        gradesList.sortBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
        loaded = true
    }

    /** 版本下拉项：「不分版本」+ 题库中出现的具体版本。 */
    fun editions(): List<String> = listOf(EDITION_ANY) + editions

    fun grades(): List<String> = gradesList

    fun subjects(): List<String> = SUBJECTS

    fun isEmpty(): Boolean = groups.isEmpty()

    /** 版本匹配：不分版本 → 全命中；否则命中同版本或「通用」。 */
    private fun editionMatches(groupEdition: String, selected: String): Boolean =
        selected == EDITION_ANY || groupEdition == selected || groupEdition == Question.EDITION_COMMON

    /**
     * 某年级 + 版本 + 学科下的抽题池。
     * 版本为「通用」的题在任何版本下都会出现；标具体版本的题只在该版本下出现。
     */
    fun pool(
        grade: String,
        edition: String,
        subject: String,
        maxDifficulty: Int = 3
    ): List<Question> =
        groups.asSequence()
            .filter { it.grade == grade && it.subject == subject }
            .filter { editionMatches(it.edition, edition) }
            .flatMap { it.questions.asSequence() }
            .filter { it.difficulty <= maxDifficulty }
            .toList()

    fun count(grade: String, edition: String, subject: String, maxDifficulty: Int = 3): Int =
        pool(grade, edition, subject, maxDifficulty).size

    /** 某年级下各学科可用题量，用于设置页实时提示。 */
    fun countsBySubject(grade: String, edition: String, maxDifficulty: Int = 3): Map<String, Int> =
        SUBJECTS.associateWith { count(grade, edition, it, maxDifficulty) }

    fun allGroups(): List<QuestionGroup> = groups
}
