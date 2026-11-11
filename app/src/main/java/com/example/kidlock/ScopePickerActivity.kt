package com.example.kidlock

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 按教材目录选择出题范围。
 *
 * 展示「册 → 单元 → 课」三级，勾选粒度是**单元**：
 * 题库本身按知识点组织，一道「24 × 3 = ?」没法判断属于单元里的第几课，
 * 所以课级在这里只用于「看清孩子学到哪」，勾选它会选中它所在的单元。
 *
 * 不勾任何单元 = 不限范围，全册都出题。
 */
class ScopePickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SUBJECT = "subject"
    }

    private lateinit var subject: String
    private lateinit var grade: String
    private lateinit var layoutTree: LinearLayout
    private lateinit var tvSummary: TextView
    private lateinit var tvSubtitle: TextView

    private val selected = mutableSetOf<String>()
    private val expanded = mutableSetOf<String>()

    private var maxDifficulty = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scope_picker)

        if (!AuthStore.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        QuestionBank.load(assets)
        Catalog.load(assets)

        subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "语文"
        grade = QuizSettings.grade(this)
        maxDifficulty = QuizSettings.difficulty(this)
        selected.addAll(QuizSettings.scope(this, subject))

        layoutTree = findViewById(R.id.layoutTree)
        tvSummary = findViewById(R.id.tvSummary)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        findViewById<TextView>(R.id.tvTitle).text = "选择范围 · $subject"
        tvSubtitle.text = "$grade · 勾选到单元，不勾表示全部单元都出题"

        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            selected.clear(); expanded.clear(); render()
            Toast.makeText(this, "已切回「全部单元」", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnSaveScope).setOnClickListener { save() }

        // 默认展开上册，家长一进来就能看到当前学期的内容
        Catalog.booksOf(grade, subject).firstOrNull { it.term == "上册" }
            ?.units?.forEach { expanded.add(it.id) }

        render()
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        layoutTree.removeAllViews()

        if (Catalog.isEmpty()) {
            val tv = TextView(this).apply {
                text = "题库未附带教材目录，请更新到 v1.4 及以上版本。"
                setTextColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.text_secondary))
                textSize = 15f
                setPadding(0, dp(24), 0, 0)
            }
            layoutTree.addView(tv)
            updateSummary()
            return
        }

        val books = Catalog.booksOf(grade, subject)
        if (books.isEmpty()) {
            val tv = TextView(this).apply {
                text = "人教版目前只收录了语文、数学、英语的小学阶段目录，" +
                        "该年级暂无$subject 教材目录。"
                setTextColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.text_secondary))
                textSize = 15f
                setPadding(0, dp(24), 0, 0)
            }
            layoutTree.addView(tv)
            updateSummary()
            return
        }

        books.forEach { layoutTree.addView(buildBookCard(it)) }

        val miscCount = QuestionBank.countMisc(subject, maxDifficulty)
        if (miscCount > 0) {
            layoutTree.addView(buildMiscCard(miscCount))
        }

        updateSummary()
    }

    /** 一册 = 一张卡片：册标题（整册）+ 单元列表。 */
    private fun buildBookCard(book: CatalogBook): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(12), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val bookQty = QuestionBank.countInBook(book.id, maxDifficulty)
        val unitIds = book.units.map { it.id }
        val pickedUnits = unitIds.count { selected.contains(it) }
        val wholeSelected = selected.contains(book.id)

        // 册标题行：勾选 = 整册（语文这类按整册组织题目的学科，这是唯一有效的粒度）
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(CheckBox(this).apply {
            isChecked = wholeSelected
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(book.id) else selected.remove(book.id)
                render()
            }
        })
        head.addView(TextView(this).apply {
            text = book.labelWithYear
            setTextColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.text_primary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        head.addView(TextView(this).apply {
            val t = when {
                bookQty > 0 -> "整册 $bookQty 道"
                pickedUnits > 0 -> "已选 $pickedUnits/${unitIds.size} 单元"
                else -> "整册"
            }
            text = t
            setTextColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.accent))
            textSize = 12f
        })

        card.addView(head)
        card.addView(divider())

        // 该册的题只挂在册上（没有单元级归属）时，明确告诉家长怎么选
        if (bookQty > 0 && book.units.all { QuestionBank.countInUnit(it.id, maxDifficulty) == 0 }) {
            card.addView(TextView(this).apply {
                text = "本册的题按整册组织（成语、标点、修辞等不属于某一课），请直接勾选上面的书名。"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.text_tertiary))
                setPadding(dp(34), dp(2), 0, dp(6))
            })
        }

        // 单元列表
        for (u in book.units) {
            card.addView(buildUnitRow(u))
            if (expanded.contains(u.id) && u.lessons.isNotEmpty()) {
                for (l in u.lessons) card.addView(buildLessonRow(u, l))
            }
        }
        return card
    }

    private fun buildUnitRow(u: CatalogUnit): View {
        val n = QuestionBank.countInUnit(u.id, maxDifficulty)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val cb = CheckBox(this).apply {
            isChecked = selected.contains(u.id)
            isEnabled = n > 0
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(u.id) else selected.remove(u.id)
                render()
            }
        }
        row.addView(cb)

        // 语文的单元名本身就叫「第1单元」，别再叠一层前缀
        val raw = u.name.trim()
        val label = if (raw.startsWith("第") || raw.startsWith("Unit", true)) raw
        else "第${u.no}单元  $raw"

        val tv = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(
                ContextCompat.getColor(
                    this@ScopePickerActivity,
                    if (n > 0) R.color.text_primary else R.color.text_tertiary
                )
            )
            setOnClickListener {
                if (n == 0) {
                    Toast.makeText(
                        this@ScopePickerActivity,
                        "本单元暂无独立题目，试试勾选上面的整册",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    cb.isChecked = !cb.isChecked
                }
            }
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = if (n > 0) "$n 道" else ""
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.text_tertiary))
            setPadding(0, 0, dp(8), 0)
        })

        if (u.lessons.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = if (expanded.contains(u.id)) "▾" else "▸"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.text_tertiary))
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    if (expanded.contains(u.id)) expanded.remove(u.id) else expanded.add(u.id)
                    render()
                }
            })
        }
        return row
    }

    /** 课：只展示 + 点击折算到所属单元。 */
    private fun buildLessonRow(u: CatalogUnit, lesson: CatalogLesson): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 子级相对父级缩进 1 个字符（约 12dp）
            setPadding(dp(34), dp(3), 0, dp(3))
        }
        val tv = TextView(this).apply {
            text = "${lesson.no}  ${lesson.name}"
            textSize = 13.5f
            setTextColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.text_secondary))
            setOnClickListener {
                selected.add(u.id)
                Toast.makeText(
                    this@ScopePickerActivity,
                    "第${u.no}单元已选中（题库按单元抽题）",
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        }
        row.addView(tv)
        return row
    }

    /** 教材已删掉、只在旧题库里存在的知识点。 */
    private fun buildMiscCard(count: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_soft)
            setPadding(dp(16), dp(10), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val key = Catalog.MISC_PREFIX + subject
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(CheckBox(this).apply {
            isChecked = selected.contains(key)
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(key) else selected.remove(key)
                render()
            }
        })
        row.addView(TextView(this).apply {
            text = "${Catalog.MISC_LABEL}（$count 道）"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        card.addView(TextView(this).apply {
            text = "包含教材新版已调整或删除的知识点（如认识人民币、认识钟表），" +
                    "不影响日常练习，需要时可以单独勾选。"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.text_tertiary))
            setPadding(dp(34), dp(2), 0, 0)
        })
        return card
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setBackgroundColor(ContextCompat.getColor(this@ScopePickerActivity, R.color.stroke))
    }

    // ---------------------------------------------------------------- 状态

    private fun updateSummary() {
        if (selected.isEmpty()) {
            val all = QuestionBank.count(
                grade, QuizSettings.edition(this), subject, maxDifficulty
            )
            tvSummary.text = "全部单元都出题 · 共 $all 道"
            return
        }
        val total = QuestionBank.count(
            grade, QuizSettings.edition(this), subject, maxDifficulty, selected.toSet()
        )
        tvSummary.text = "已选 ${selected.size} 项 · 本次范围内 $total 道题"
    }

    private fun save() {
        QuizSettings.setScope(this, subject, selected.toSet())
        val msg = if (selected.isEmpty()) {
            "$subject：已设为全部单元"
        } else {
            "$subject：已选 ${selected.size} 个范围"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
