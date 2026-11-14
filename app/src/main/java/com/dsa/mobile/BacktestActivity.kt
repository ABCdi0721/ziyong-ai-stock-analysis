package com.dsa.mobile

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dsa.mobile.databinding.ActivityBacktestBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A 股回测页：技术信号策略（MA金叉/MACD金叉/RSI超卖反弹/买入持有）
 * 基于腾讯/Tushare 日K 本地计算，展示收益、胜率、最大回撤与逐笔交易。
 */
class BacktestActivity : BaseActivity() {

    private lateinit var binding: ActivityBacktestBinding
    private var code = ""
    private var name = ""
    private var bars: List<KLineBar> = emptyList()
    private var currentStrategy = "MA金叉"
    private var done = false

    private val strategyButtons = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBacktestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "A股回测"

        code = intent.getStringExtra("code") ?: ""
        name = intent.getStringExtra("name") ?: code
        binding.btTitle.text = "$name（$code）"

        strategyButtons.addAll(listOf(binding.btS1, binding.btS2, binding.btS3, binding.btS4))
        val strategies = Backtest.STRATEGIES
        strategyButtons.forEachIndexed { i, tv ->
            tv.text = strategies[i]
            tv.setOnClickListener {
                currentStrategy = strategies[i]
                highlight(i)
                runBacktest()
            }
        }
        highlight(0)

        Thread {
            try {
                bars = fetchKline()
                runOnUiThread {
                    binding.btStatus.text = "日K ${bars.size} 根就绪"
                    runBacktest()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btStatus.text = "数据加载失败：${e.message}"
                    Notifier.cancel(this@BacktestActivity, code, "backtest")
                }
            }
        }.start()
    }

    private fun highlight(sel: Int) {
        strategyButtons.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == sel) 0xFFC62828.toInt() else 0xFF757575.toInt())
            tv.typeface = if (i == sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun runBacktest() {
        if (bars.size < 30) {
            binding.btStatus.text = "数据不足（<30 根），无法回测"
            return
        }
        val r = Backtest.run(bars, currentStrategy)
        val color = if (r.totalReturn >= 0) 0xFFE53935.toInt() else 0xFF43A047.toInt()
        // v2.6.0：回测完成 → 通知栏推送，点击跳回回测结果页
        done = true
        Notifier.showDone(
            this, code, "backtest",
            "「$name」A股回测完成",
            "策略 $currentStrategy · 总收益 ${String.format("%+.2f%%", r.totalReturn)}，点击查看",
            Intent(this, BacktestActivity::class.java)
                .putExtra("code", code)
                .putExtra("name", name)
        )
        binding.btResult.text = buildString {
            append("策略：${currentStrategy}　（${bars.first().date} ~ ${bars.last().date}，共 ${bars.size} 根日K）\n\n")
            append("总收益率：${String.format("%+.2f%%", r.totalReturn)}")
            append("　　年化：${String.format("%+.2f%%", r.annualReturn)}")
            append("\n")
            append("最大回撤：-${String.format("%.2f%%", r.maxDrawdown)}")
            append("　　胜率：${String.format("%.1f%%", r.winRate)}")
            append("\n交易次数：${r.tradeCount}　　")
            append("同期买入持有：${String.format("%+.2f%%", r.buyHoldReturn)}")
            append("\n\n说明：初始资金 10 万，全仓买卖，不含手续费。仅供技术研究，不构成投资建议。")
        }
        binding.btStatus.text = "回测完成：${currentStrategy}"

        // 交易列表
        binding.btTrades.removeAllViews()
        if (r.trades.isEmpty()) {
            binding.btTrades.addView(TextView(this).apply {
                text = "该策略区间内无完整交易（买入持有除外）"
                setTextColor(0xFF757575.toInt())
                textSize = 13f
                setPadding(0, 8, 0, 8)
            })
        }
        r.trades.forEachIndexed { i, t ->
            val row = TextView(this).apply {
                val c = if (t.pct >= 0) 0xFFE53935.toInt() else 0xFF43A047.toInt()
                text = "${i + 1}. ${t.buyDate} 买 ${String.format("%.2f", t.buyPrice)} → ${t.sellDate} 卖 ${String.format("%.2f", t.sellPrice)}　${String.format("%+.2f%%", t.pct)}"
                setTextColor(0xFF212121.toInt())
                textSize = 13f
                setPadding(0, 6, 0, 6)
            }
            binding.btTrades.addView(row)
        }
    }

    private fun fetchKline(): List<KLineBar> {
        val token = Prefs.tushareToken(this)
        if (token.isNotBlank()) {
            try {
                val end = LocalDate.now()
                val start = end.minusDays(500)
                val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
                val b = TushareApi.fetchDailyKline(
                    token, code, start.format(fmt), end.format(fmt)
                )
                if (b.size >= 20) return b
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
        if (!done) Notifier.cancel(this, code, "backtest")
    }
}
