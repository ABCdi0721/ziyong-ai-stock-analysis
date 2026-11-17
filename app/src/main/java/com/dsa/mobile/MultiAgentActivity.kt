package com.dsa.mobile

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dsa.mobile.databinding.ActivityMultiAgentBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 多智能体分析页（TradingAgents 全链路）
 * 进度实时展示各阶段，完成后自动保存历史报告并跳转报告页。
 */
class MultiAgentActivity : BaseActivity() {

    private lateinit var binding: ActivityMultiAgentBinding
    private var code = ""
    private var name = ""
    private var done = false

    private val stages = listOf(
        "基本面分析师", "所属板块", "情绪分析师", "新闻分析师", "技术分析师",
        "多头研究员", "空头研究员", "交易员", "风险管理", "组合经理"
    )
    private val stageRows = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiAgentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "多智能体分析"

        code = intent.getStringExtra("code") ?: ""
        name = intent.getStringExtra("name") ?: code
        binding.maTitle.text = "$name（$code）"

        stages.forEach { label ->
            val row = TextView(this).apply {
                text = "$label　等待中"
                setTextColor(0xFF9E9E9E.toInt())
                textSize = 14f
                setPadding(0, 12, 0, 12)
                typeface = Typeface.DEFAULT
            }
            binding.maStages.addView(row)
            stageRows[label] = row
        }
        setupMascot()
        start()
    }

    // ---- v2.12.11 吉祥物（通用挂件，交互见 MascotHelper） ----

    private fun setupMascot() {
        MascotHelper.attach(this, binding.root)
    }

    private fun start() {
        Thread {
            try {
                val q = try {
                    TencentApi.fetchQuote(code)
                } catch (e: Exception) {
                    null
                }
                val bars = try {
                    fetchKline()
                } catch (e: Exception) {
                    emptyList()
                }
                if (bars.isEmpty()) {
                    runOnUiThread {
                        binding.maStatus.text = "行情数据暂不可用，请稍后重新分析"
                        Notifier.cancel(this@MultiAgentActivity, code, "multi")
                    }
                    return@Thread
                }
                val r = Analyzer.analyze(bars, code)
                val summary = Analyzer.buildAnalysisSummary(code, name, q, r)
                // v2.9.4：带诊断信息的检索，失败原因要能显示出来（原来被静默吞掉）
                val outcome = try {
                    NewsApi.searchDetailed(
                        this,
                        listOf("$name $code", "$name 最新消息", "$name 公告")
                    )
                } catch (e: Exception) {
                    NewsApi.Outcome(error = "检索异常：${e.message?.take(80) ?: "未知错误"}")
                }
                val news = outcome.items
                val newsStatus = outcome.statusText()
                runOnUiThread {
                    binding.maNewsStatus.text = newsStatus
                    binding.maNewsStatus.setTextColor(
                        if (outcome.ok) 0xFF34A853.toInt() else 0xFFE8710A.toInt()
                    )
                }
                val result = MultiAgent.run(this, code, name, summary, news, newsStatus) { label, status, detail ->
                    runOnUiThread { updateStage(label, status, detail) }
                }
                runOnUiThread {
                    // v2.9.5：显示本次真实 token 消耗与缓存命中率，便于直观看到省流效果
                    val u = LlmApi.stats()
                    val calls = LlmApi.callCount()
                    val hit = if (u.prompt > 0) (u.cached * 100 / u.prompt) else 0
                    val tokenLine = "本次分析 Token：输入 ${u.prompt}（缓存命中 ${u.cached} · $hit%）" +
                        " / 输出 ${u.completion}，共 ${u.total}，${calls} 次调用\n\n"
                    val reportText = tokenLine + result.toReport()
                    val degradeNote = if (result.degraded > 0) {
                        "；${result.degraded} 个环节由本地规则依据真实数据补全"
                    } else ""
                    binding.maStatus.text =
                        "全部阶段完成，已保存历史报告\n" +
                            "Token：输入 ${u.prompt}（缓存命中 ${u.cached} · $hit%）/ 输出 ${u.completion}，共 ${u.total}，${calls} 次调用$degradeNote"
                    done = true
                    Prefs.saveHistory(
                        this, HistoryReport(
                            code = code, name = name,
                            date = LocalDate.now().toString(),
                            time = Prefs.clock(),
                            score = result.finalScore,
                            signal = result.finalSignal,
                            report = reportText
                        )
                    )
                    Notifier.showDone(
                        this, code, "multi",
                        "「$name」多智能体分析完成",
                        "综合评分 ${result.finalScore} · ${result.finalSignal}，点击查看报告",
                        Intent(this, ReportActivity::class.java)
                            .putExtra("code", code)
                            .putExtra("name", name)
                            .putExtra("score", result.finalScore)
                            .putExtra("signal", result.finalSignal)
                            .putExtra("date", result.date)
                            .putExtra("report", reportText)
                            .putExtra("readonly", true)
                    )
                    startActivity(
                        Intent(this, ReportActivity::class.java)
                            .putExtra("code", code)
                            .putExtra("name", name)
                            .putExtra("score", result.finalScore)
                            .putExtra("signal", result.finalSignal)
                            .putExtra("date", result.date)
                            .putExtra("report", reportText)
                            .putExtra("readonly", true)
                    )
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.maStatus.text = "行情或网络暂不可用，请稍后重新分析"
                    Notifier.cancel(this@MultiAgentActivity, code, "multi")
                }
            }
        }.start()
    }

    private fun updateStage(label: String, status: String, detail: String) {
        val row = stageRows[label] ?: return
        when (status) {
            "分析中", "辩论中", "决策中", "评估中", "终审中" -> {
                row.text = "$label　⏳ $status…"
                row.setTextColor(0xFF1565C0.toInt())
                row.typeface = Typeface.DEFAULT_BOLD
            }
            "完成" -> {
                row.text = "$label　✅ 完成"
                row.setTextColor(0xFF2E7D32.toInt())
                row.typeface = Typeface.DEFAULT_BOLD
            }
            else -> {
                row.text = "$label　$status"
                row.setTextColor(0xFF9E9E9E.toInt())
            }
        }
        binding.maStatus.text = "当前阶段：$label — $status"
    }

    private fun fetchKline(): List<KLineBar> {
        val token = Prefs.tushareToken(this)
        if (token.isNotBlank()) {
            try {
                val end = LocalDate.now()
                val start = end.minusDays(500)
                val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
                val bars = TushareApi.fetchDailyKline(
                    token, code, start.format(fmt), end.format(fmt)
                )
                if (bars.size >= 20) return bars
            } catch (e: Exception) { /* 回退腾讯 */ }
        }
        return TencentApi.fetchDailyKline(code, 320)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        // 未完成（成功跳转/失败已 cancel）时清除运行中通知
        if (!done) Notifier.cancel(this, code, "multi")
    }
}
