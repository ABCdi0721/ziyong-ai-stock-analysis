package com.dsa.mobile

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dsa.mobile.databinding.ActivityBoardRateBinding
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * v2.10.0 封板率预测页
 * 数据：东财涨停池/炸板池 + 腾讯日K → 本地因子模型 → 次日封板概率
 */
class BoardRateActivity : BaseActivity() {

    private lateinit var binding: ActivityBoardRateBinding
    private var code: String = ""
    private var name: String = ""
    private var last: BoardPrediction? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBoardRateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 本页自带标题栏，隐藏系统 ActionBar，避免顶部出现两层标题
        supportActionBar?.hide()

        code = intent.getStringExtra("code") ?: ""
        name = intent.getStringExtra("name") ?: code
        binding.boardSub.text = "$name（$code）"

        binding.boardBack.setOnClickListener { finish() }
        binding.btnBoardSave.setOnClickListener { saveToHistory() }
        binding.btnBoardRefresh.setOnClickListener { run() }

        run()
    }

    private fun run() {
        if (running) return
        running = true
        binding.boardProb.text = "…"
        binding.boardLevel.text = "计算中"
        binding.boardSummary.text = "正在拉取涨停池与日K数据…"
        binding.boardFactorBox.removeAllViews()
        binding.btnBoardRefresh.isEnabled = false

        Thread {
            try {
                val market = BoardRateApi.fetchMarket()
                val bars = try {
                    TencentApi.fetchDailyKline(code, 60)
                } catch (e: Exception) {
                    emptyList()
                }
                val quote = try {
                    TencentApi.fetchQuote(code)
                } catch (e: Exception) {
                    null
                }
                val pred = BoardRateApi.predict(code, name, bars, quote, market)
                runOnUiThread { render(pred) }
            } catch (e: Exception) {
                runOnUiThread {
                    running = false
                    binding.btnBoardRefresh.isEnabled = true
                    binding.boardProb.text = "—"
                    binding.boardLevel.text = "失败"
                    binding.boardSummary.text = "预测失败：${e.message}\n\n请检查网络后点「重新预测」。"
                }
            }
        }.start()
    }

    private fun render(p: BoardPrediction) {
        last = p
        running = false
        binding.btnBoardRefresh.isEnabled = true

        binding.boardSub.text = "${p.name}（${p.code}）"
        binding.boardProb.text = "%.1f%%".format(p.probability)
        binding.boardLevel.text = "档位：${p.level}"
        binding.boardStateTag.text = if (p.todayLimitUp) {
            if (p.todayBoards > 1) "${p.todayBoards} 连板" else "首板"
        } else "未涨停"

        binding.boardSeal.text = "%.1f%%".format(p.sealProbability)
        binding.boardGene.text = "${p.recentLimitUps} 次"

        // 概率条：按父宽度比例（0~70% 视为满格上限）
        val bar = binding.boardProbBar
        bar.post {
            val parentW = (bar.parent as ViewGroup).width
            val ratio = (p.probability / 70.0).coerceIn(0.02, 1.0)
            val lp = bar.layoutParams
            lp.width = (parentW * ratio).toInt()
            bar.layoutParams = lp
        }
        binding.boardProbHint.text =
            "A股打板实测区间：最强情形约 40%，绝大多数标的低于 25%；低于 10% 不建议参与"

        binding.boardSummary.text = p.summary

        val m = p.market
        binding.boardMktZt.text = "${m.limitUpCount}"
        binding.boardMktZb.text = "%.0f%%".format(m.brokenRate)
        binding.boardMktMax.text = "${m.maxBoards} 板"
        binding.boardMktSent.text = m.sentimentLabel()

        renderFactors(p.factors)
        binding.boardNote.text = p.dataNote
    }

    private fun renderFactors(factors: List<BoardFactor>) {
        binding.boardFactorBox.removeAllViews()
        factors.forEachIndexed { idx, f ->
            if (idx > 0) {
                val div = View(this)
                div.setBackgroundColor(colorAttr(R.attr.dsaDivider))
                binding.boardFactorBox.addView(div, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (1 * resources.displayMetrics.density).toInt()
                ).apply { topMargin = dp(8); bottomMargin = dp(8) })
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameTv = TextView(this).apply {
                text = f.name
                textSize = 13f
                setTextColor(colorAttr(R.attr.dsaText))
                typeface = Typeface.DEFAULT_BOLD
            }
            val valTv = TextView(this).apply {
                text = f.value
                textSize = 11f
                setTextColor(colorAttr(R.attr.dsaText2))
                setPadding(0, dp(2), 0, 0)
            }
            left.addView(nameTv)
            left.addView(valTv)

            val deltaTv = TextView(this).apply {
                text = if (f.delta > 0) "+%.1f".format(f.delta)
                else if (f.delta < 0) "-%.1f".format(-f.delta)
                else "0"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                setTextColor(
                    when {
                        f.delta > 0 -> colorAttr(R.attr.dsaPrimary)
                        f.delta < 0 -> Color.parseColor("#E05A5A")
                        else -> colorAttr(R.attr.dsaText2)
                    }
                )
            }

            row.addView(left)
            row.addView(deltaTv, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
            binding.boardFactorBox.addView(row)

            if (f.note.isNotBlank()) {
                val noteTv = TextView(this).apply {
                    text = f.note
                    textSize = 10f
                    setTextColor(colorAttr(R.attr.dsaText2))
                    setPadding(0, dp(2), 0, 0)
                }
                binding.boardFactorBox.addView(noteTv)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun colorAttr(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun saveToHistory() {
        val p = last ?: return
        val report = buildString {
            append("【封板率预测】${p.name}（${p.code}）\n")
            append("预测日期：${LocalDate.now()}（对应下一交易日）\n")
            append("明日封板概率：${"%.1f".format(p.probability)}%（${p.level}）\n")
            append("若涨停，封住概率：${"%.1f".format(p.sealProbability)}%\n")
            append("今日状态：")
            append(if (p.todayLimitUp) "${p.todayBoards} 连板封板" else "未涨停")
            append("\n\n")
            append("【因子明细】\n")
            p.factors.forEach { f ->
                val sign = when {
                    f.delta > 0 -> "+${"%.1f".format(f.delta)}"
                    f.delta < 0 -> "${"%.1f".format(f.delta)}"
                    else -> "0"
                }
                append("· ${f.name}：${f.value}（$sign）\n")
                if (f.note.isNotBlank()) append("  ${f.note}\n")
            }
            append("\n【市场情绪】\n")
            append("涨停 ${p.market.limitUpCount} 家 / 炸板 ${p.market.brokenCount} 家")
            append(" / 炸板率 ${"%.0f".format(p.market.brokenRate)}%")
            append(" / 最高 ${p.market.maxBoards} 连板 / 情绪 ${p.market.sentimentLabel()}\n")
            append("\n${p.dataNote}")
        }

        Prefs.saveHistory(
            this, HistoryReport(
                code = p.code, name = p.name,
                date = LocalDate.now().toString(),
                time = Prefs.clock(),
                score = p.probability.roundToInt().coerceIn(0, 100),
                signal = "封板·${p.level}",
                report = report
            )
        )
        Toast.makeText(this, "已保存到历史报告", Toast.LENGTH_SHORT).show()
    }
}
