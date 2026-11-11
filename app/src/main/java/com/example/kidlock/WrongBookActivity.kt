package com.example.kidlock

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 错题本页面。
 *
 * 按学科分组列出所有未掌握的错题，显示错误次数与连对进度。
 * 家长可单条「已掌握」移除，也可整本清空。
 */
class WrongBookActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvSummary: TextView
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrongbook)

        if (!AuthStore.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        QuestionBank.load(assets)

        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }
        container = findViewById(R.id.containerWrong)
        tvSummary = findViewById(R.id.tvSummary)
        tvHint = findViewById(R.id.tvHint)

        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnClearWrong)
            .setOnClickListener { confirmClear() }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val items = WrongBook.all(this)
        container.removeAllViews()

        val empty = findViewById<TextView>(R.id.tvEmpty)
        if (items.isEmpty()) {
            tvSummary.text = "共 0 道错题"
            tvHint.text = "孩子答错题后会自动记进来，下次答题时重新出现。"
            empty.visibility = View.VISIBLE
            findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnClearWrong)
                .visibility = View.GONE
            return
        }

        empty.visibility = View.GONE
        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnClearWrong)
            .visibility = View.VISIBLE

        tvSummary.text = "共 ${items.size} 道错题"
        val totalWrong = items.sumOf { it.wrongCount }
        val almost = items.count { it.streak > 0 }
        tvHint.text = "累计答错 $totalWrong 次" +
                if (almost > 0) "，其中 $almost 道已连对 1 次、再答对 1 次就能移出" else ""

        // 按学科分组，固定语文/数学/英语顺序
        val grouped = items.groupBy { it.subject }
        val order = QuestionBank.SUBJECTS + grouped.keys.filter { it !in QuestionBank.SUBJECTS }
        for (subject in order) {
            val list = grouped[subject] ?: continue
            container.addView(sectionHeader("$subject（${list.size} 道）"))
            list.sortedByDescending { it.wrongCount }.forEach { container.addView(itemCard(it)) }
        }
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@WrongBookActivity, R.color.accent_2))
        setPadding(dp(4), dp(18), 0, dp(6))
    }

    private fun itemCard(item: WrongBook.Item): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@WrongBookActivity, R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val meta = TextView(this).apply {
            text = buildString {
                append(item.grade)
                if (item.unit.isNotEmpty()) append(" · ").append(item.unit)
                append(" · 错 ").append(item.wrongCount).append(" 次")
            }
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@WrongBookActivity, R.color.text_tertiary))
        }
        card.addView(meta)

        val title = TextView(this).apply {
            text = item.title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@WrongBookActivity, R.color.text_primary))
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(title)

        val answerText = item.options.getOrNull(item.answerIndex) ?: "—"
        val answer = TextView(this).apply {
            text = "正确答案：$answerText"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@WrongBookActivity, R.color.ok))
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(answer)

        val status = TextView(this).apply {
            text = if (item.streak > 0) {
                "已连对 ${item.streak} 次，再答对 ${WrongBook.MASTER_STREAK - item.streak} 次即可掌握"
            } else {
                "尚未掌握，下次组卷会再次出现"
            }
            textSize = 11f
            setTextColor(
                ContextCompat.getColor(
                    this@WrongBookActivity,
                    if (item.streak > 0) R.color.warn else R.color.text_tertiary
                )
            )
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(status)

        val btn = androidx.appcompat.widget.AppCompatButton(this).apply {
            text = "已掌握，移出"
            textSize = 12f
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@WrongBookActivity, R.color.text_secondary))
            background = ContextCompat.getDrawable(this@WrongBookActivity, R.drawable.bg_btn_ghost)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                WrongBook.remove(this@WrongBookActivity, item.key)
                Toast.makeText(this@WrongBookActivity, "已移出错题本", Toast.LENGTH_SHORT).show()
                render()
            }
        }
        card.addView(btn)

        return card
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空错题本？")
            .setMessage("清空后这些题不再优先出现，孩子之前的错误记录也会一起删掉。")
            .setPositiveButton("清空") { _, _ ->
                WrongBook.clear(this)
                render()
                Toast.makeText(this, "错题本已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
