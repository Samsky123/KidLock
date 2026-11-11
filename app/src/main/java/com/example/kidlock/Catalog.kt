package com.example.kidlock

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * 教材目录：册 → 单元 → 课。
 *
 * 节点 id 规则（和 tools/build_catalog.py 保持一致）：
 *   册   三年级|数学|上册
 *   单元 三年级|数学|上册|4
 *   课   三年级|数学|上册|4|2
 *
 * 抽题只用到「单元」这一级：题库本身是按知识点组织的，一道计算题很难说
 * 属于单元里的第几课。所以课级选择在 [ScopePickerActivity] 里就被折算成
 * 所属单元，进到数据层只剩单元 id，过滤逻辑简单且不会出错。
 */
data class CatalogLesson(val no: Int, val name: String, val id: String)

data class CatalogUnit(
    val no: Int,
    val name: String,
    val id: String,
    val lessons: List<CatalogLesson>
)

data class CatalogBook(
    val grade: String,
    val subject: String,
    val term: String,
    val year: String,
    val id: String,
    val units: List<CatalogUnit>
) {
    /** 「三年级上册」 */
    val label: String get() = grade + term

    /** 「三年级上册 · 2025 秋」 */
    val labelWithYear: String get() = "$label · $year"
}

object Catalog {

    /** 未进教材目录的知识点归到这里，按学科一个。 */
    const val MISC_PREFIX = "综合|"
    const val MISC_LABEL = "综合练习"

    private val books = mutableListOf<CatalogBook>()
    private var loaded = false

    @Synchronized
    fun load(assets: AssetManager) {
        if (loaded) return
        val text = try {
            assets.open("catalog.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            loaded = true
            return
        }
        val root = JSONObject(text)
        val arr = root.optJSONArray("books") ?: return
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val unitsArr = b.optJSONArray("units")
            val units = ArrayList<CatalogUnit>()
            if (unitsArr != null) {
                for (j in 0 until unitsArr.length()) {
                    val u = unitsArr.getJSONObject(j)
                    val lessonsArr = u.optJSONArray("lessons")
                    val lessons = ArrayList<CatalogLesson>()
                    if (lessonsArr != null) {
                        for (k in 0 until lessonsArr.length()) {
                            val l = lessonsArr.getJSONObject(k)
                            lessons.add(
                                CatalogLesson(
                                    no = l.optInt("no"),
                                    name = l.optString("name"),
                                    id = l.optString("id")
                                )
                            )
                        }
                    }
                    units.add(
                        CatalogUnit(
                            no = u.optInt("no"),
                            name = u.optString("name"),
                            id = u.optString("id"),
                            lessons = lessons
                        )
                    )
                }
            }
            books.add(
                CatalogBook(
                    grade = b.optString("grade"),
                    subject = b.optString("subject"),
                    term = b.optString("term"),
                    year = b.optString("year"),
                    id = b.optString("id"),
                    units = units
                )
            )
        }
        loaded = true
    }

    fun isEmpty(): Boolean = books.isEmpty()

    /** 某年级某学科的所有册（上册在前）。 */
    fun booksOf(grade: String, subject: String): List<CatalogBook> =
        books.filter { it.grade == grade && it.subject == subject }
            .sortedBy { if (it.term == "上册") 0 else 1 }

    fun bookOf(grade: String, subject: String, term: String): CatalogBook? =
        books.firstOrNull { it.grade == grade && it.subject == subject && it.term == term }

    /** 单元 id -> 单元，用于把选中的 id 显示成中文摘要。 */
    fun unitById(id: String): CatalogUnit? {
        for (b in books) for (u in b.units) if (u.id == id) return u
        return null
    }

    fun bookByUnitId(id: String): CatalogBook? {
        val parts = id.split("|")
        if (parts.size < 3) return null
        return bookOf(parts[0], parts[1], parts[2])
    }

    /** 某一册下所有单元 id。 */
    fun unitIdsOf(book: CatalogBook): List<String> = book.units.map { it.id }

    /**
     * 已选范围的文字摘要，例如「三上 第4单元 · 三下 第2、3单元」。
     * 未选择任何范围时返回「全部单元」。
     */
    fun describeScope(grade: String, subject: String, selected: Set<String>): String {
        if (selected.isEmpty()) return "全部单元"
        val misc = selected.count { it.startsWith(MISC_PREFIX) }
        if (misc == selected.size) return MISC_LABEL

        val wholeBooks = mutableListOf<String>()
        val byBook = LinkedHashMap<String, MutableList<Int>>()
        for (id in selected) {
            if (id.startsWith(MISC_PREFIX)) continue
            val parts = id.split("|")
            // 年级或学科对不上的脏数据（例如家长先改了年级、范围还是旧的）直接跳过
            if (parts.size < 3 || parts[0] != grade || parts[1] != subject) continue
            if (parts.size == 3) {
                wholeBooks.add(if (parts[2] == "上册") "上册整册" else "下册整册")
                continue
            }
            val key = if (parts[2] == "上册") "上" else "下"
            byBook.getOrPut(key) { mutableListOf() }.add(parts[3].toIntOrNull() ?: 0)
        }
        val pieces = mutableListOf<String>()
        pieces.addAll(wholeBooks)
        byBook.entries.forEach { e ->
            pieces.add("第" + e.value.distinct().sorted().joinToString("、") + "单元(" + e.key + ")")
        }
        if (pieces.isEmpty()) return MISC_LABEL
        val head = pieces.joinToString(" · ")
        return if (misc > 0) "$head + $MISC_LABEL" else head
    }
}
