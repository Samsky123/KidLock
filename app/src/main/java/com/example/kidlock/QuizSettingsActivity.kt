package com.example.kidlock

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 题库与出题设置页。
 *
 * 家长在这里一次性决定：
 *  · 每轮可玩时长（分钟）
 *  · 教材版本 + 年级
 *  · 语文 / 数学 / 英语 各出几道（0 表示该科不抽）
 *  · 难度上限、是否优先出错题
 *
 * 保存后写入 QuizSettings，答题页下一轮组卷时生效。
 */
class QuizSettingsActivity : AppCompatActivity() {

    private lateinit var spEdition: Spinner
    private lateinit var spGrade: Spinner
    private lateinit var spDifficulty: Spinner
    private lateinit var etMinutes: EditText
    private lateinit var etCn: EditText
    private lateinit var etMa: EditText
    private lateinit var etEn: EditText
    private lateinit var tvBankInfo: TextView
    private lateinit var tvTotalPlan: TextView
    private lateinit var cbWrongFirst: CheckBox

    private val editOf = mutableMapOf<String, EditText>()
    private val availOf = mutableMapOf<String, TextView>()
    private val scopeOf = mutableMapOf<String, TextView>()

    /** 正在按预填值初始化控件，此时不触发校验与重算。 */
    private var initializing = true

    override fun onResume() {
        super.onResume()
        // 从「选择范围」页返回后，题量与范围摘要都要重算
        if (!initializing) {
            refreshAvailability()
            updateTotal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz_settings)

        if (!AuthStore.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        QuestionBank.load(assets)
        Catalog.load(assets)

        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }

        spEdition = findViewById(R.id.spEdition)
        spGrade = findViewById(R.id.spGradeQuiz)
        spDifficulty = findViewById(R.id.spDifficultyQuiz)
        etMinutes = findViewById(R.id.etMinutes)
        etCn = findViewById(R.id.etCn)
        etMa = findViewById(R.id.etMa)
        etEn = findViewById(R.id.etEn)
        tvBankInfo = findViewById(R.id.tvBankInfo)
        tvTotalPlan = findViewById(R.id.tvTotalPlan)
        cbWrongFirst = findViewById(R.id.cbWrongFirstQuiz)

        editOf["语文"] = etCn
        editOf["数学"] = etMa
        editOf["英语"] = etEn
        availOf["语文"] = findViewById(R.id.tvAvailCn)
        availOf["数学"] = findViewById(R.id.tvAvailMa)
        availOf["英语"] = findViewById(R.id.tvAvailEn)
        scopeOf["语文"] = findViewById(R.id.tvScopeCn)
        scopeOf["数学"] = findViewById(R.id.tvScopeMa)
        scopeOf["英语"] = findViewById(R.id.tvScopeEn)

        // 每科的「出题范围」入口：按教材目录选单元
        listOf(
            "语文" to R.id.rowScopeCn,
            "数学" to R.id.rowScopeMa,
            "英语" to R.id.rowScopeEn
        ).forEach { (subject, rowId) ->
            findViewById<android.view.View>(rowId).setOnClickListener {
                startActivity(Intent(this, ScopePickerActivity::class.java)
                    .putExtra(ScopePickerActivity.EXTRA_SUBJECT, subject))
            }
        }

        // 版本
        spEdition.adapter = makeAdapter(QuestionBank.editions())
        val curEdition = QuizSettings.edition(this)
        val eIdx = QuestionBank.editions().indexOf(curEdition)
        if (eIdx >= 0) spEdition.setSelection(eIdx)

        // 年级
        spGrade.adapter = makeAdapter(QuestionBank.grades())
        val curGrade = QuizSettings.grade(this)
        val gIdx = QuestionBank.grades().indexOf(curGrade)
        if (gIdx >= 0) spGrade.setSelection(gIdx)

        // 难度
        spDifficulty.adapter = ArrayAdapter.createFromResource(
            this, R.array.difficulty_labels, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spDifficulty.setSelection((QuizSettings.difficulty(this) - 1).coerceIn(0, 2))

        // 时长与题量
        etMinutes.setText(QuizSettings.minutes(this).toString())
        editOf.forEach { (subject, et) -> et.setText(QuizSettings.count(this, subject).toString()) }
        cbWrongFirst.isChecked = QuizSettings.wrongFirst(this)

        val listener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long
            ) {
                if (!initializing) refreshAvailability()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        spEdition.onItemSelectedListener = listener
        spGrade.onItemSelectedListener = listener
        spDifficulty.onItemSelectedListener = listener

        // 加减步进
        bindStepper(R.id.btnCnMinus, R.id.btnCnPlus, etCn)
        bindStepper(R.id.btnMaMinus, R.id.btnMaPlus, etMa)
        bindStepper(R.id.btnEnMinus, R.id.btnEnPlus, etEn)

        editOf.values.forEach { et ->
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!initializing) updateTotal()
                }
            })
        }

        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnSaveQuiz)
            .setOnClickListener { save() }
        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnWrongBook)
            .setOnClickListener { startActivity(Intent(this, WrongBookActivity::class.java)) }

        initializing = false
        refreshAvailability()
        updateTotal()
    }

    private fun makeAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun bindStepper(minusId: Int, plusId: Int, et: EditText) {
        findViewById<androidx.appcompat.widget.AppCompatButton>(minusId).setOnClickListener {
            val v = et.text.toString().toIntOrNull() ?: 0
            et.setText((v - 1).coerceAtLeast(0).toString())
            et.setSelection(et.text.length)
        }
        findViewById<androidx.appcompat.widget.AppCompatButton>(plusId).setOnClickListener {
            val v = et.text.toString().toIntOrNull() ?: 0
            et.setText((v + 1).coerceAtMost(30).toString())
            et.setSelection(et.text.length)
        }
    }

    private fun currentGrade(): String = spGrade.selectedItem?.toString() ?: QuizSettings.DEFAULT_GRADE

    private fun currentEdition(): String =
        spEdition.selectedItem?.toString() ?: QuizSettings.DEFAULT_EDITION

    private fun currentDifficulty(): Int = spDifficulty.selectedItemPosition + 1

    /** 刷新各科「题库可用多少道」，库存为 0 的学科给出明确提示。 */
    private fun refreshAvailability() {
        val grade = currentGrade()
        val edition = currentEdition()
        val maxD = currentDifficulty()
        var total = 0
        for (subject in QuestionBank.SUBJECTS) {
            val scope = QuizSettings.scope(this, subject)
            // 题量必须按选定范围算：家长把范围圈到两个单元，这里还报全册题量，
            // 保存时就会被静默封顶，看起来像「设了 10 道却只出 3 道」
            val n = QuestionBank.count(grade, edition, subject, maxD, scope)
            total += n
            scopeOf[subject]?.text = Catalog.describeScope(grade, subject, scope)
            val tv = availOf[subject] ?: continue
            if (n == 0) {
                tv.text = "该年级暂无${subject}题，请把数量填 0"
                tv.setTextColor(ContextCompat.getColor(this, R.color.danger))
            } else {
                tv.text = "题库可用 $n 道"
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            }
        }
        tvBankInfo.text = "$grade · $edition · 难度 ${difficultyLabel(maxD)}，题库共 $total 道可用"
        updateTotal()
    }

    private fun difficultyLabel(level: Int): String =
        resources.getStringArray(R.array.difficulty_labels).getOrElse(level - 1) { "全部" }

    private fun countOf(subject: String): Int =
        editOf[subject]?.text?.toString()?.toIntOrNull()?.coerceIn(0, 99) ?: 0

    private fun updateTotal() {
        val counts = QuestionBank.SUBJECTS.map { it to countOf(it) }
        val total = counts.sumOf { it.second }
        val detail = counts.filter { it.second > 0 }
            .joinToString(" + ") { "${it.first} ${it.second}" }
            .ifEmpty { "未选择科目" }
        val minutes = etMinutes.text.toString().toIntOrNull() ?: 0
        tvTotalPlan.text = if (total == 0) {
            "本轮共 0 道题 —— 至少给一科填大于 0 的数量"
        } else {
            "本轮共 $total 道题（$detail）· 答完可用 $minutes 分钟"
        }
        tvTotalPlan.setTextColor(
            ContextCompat.getColor(this, if (total == 0) R.color.warn else R.color.accent)
        )
    }

    private fun save() {
        val minutes = etMinutes.text.toString().toIntOrNull()
        if (minutes == null || minutes <= 0) {
            Toast.makeText(this, "每轮时长要大于 0 分钟", Toast.LENGTH_SHORT).show()
            return
        }

        val grade = currentGrade()
        val edition = currentEdition()
        val maxD = currentDifficulty()

        // 各科数量不能超过题库库存，超出就按库存封顶，避免出现「要 5 道只有 2 道」
        val counts = mutableMapOf<String, Int>()
        var clamped = false
        for (subject in QuestionBank.SUBJECTS) {
            val want = countOf(subject)
            val avail = QuestionBank.count(
                grade, edition, subject, maxD, QuizSettings.scope(this, subject)
            )
            val real = if (want > avail) {
                if (want > 0) clamped = true
                avail
            } else want
            counts[subject] = real
        }

        if (counts.values.sum() == 0) {
            Toast.makeText(
                this,
                "语文 / 数学 / 英语 至少要有一科大于 0，否则孩子没有题可答",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        QuizSettings.save(
            this,
            minutes = minutes,
            grade = grade,
            edition = edition,
            counts = counts,
            difficulty = maxD,
            wrongFirst = cbWrongFirst.isChecked
        )

        val msg = if (clamped) {
            "已保存（部分学科题量超过题库，已按题库上限调整）"
        } else {
            "已保存：每轮 ${counts.values.sum()} 道题，可用 $minutes 分钟"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
