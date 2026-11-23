package com.dsa.mobile

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dsa.mobile.databinding.ActivityStockDetailBinding
import com.dsa.mobile.ui.KLineView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class StockDetailActivity : BaseActivity() {

    /** 周期类型 */
    private enum class Period(val label: String) {
        MINUTE("分时"), DAY("日K"), WEEK("周K"), MONTH("月K"), FIVE_DAY("5日线")
    }

    private lateinit var binding: ActivityStockDetailBinding
    private var code: String = ""
    private var quote: Quote? = null
    private var result: AnalysisResult? = null
    private var analyzing = false
    private var dailyBars: List<KLineBar> = emptyList()
    private var currentPeriod: Period = Period.DAY
    private val periodButtons = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStockDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyTagAlphaTree(binding.root, this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        code = intent.getStringExtra("code") ?: ""
        binding.detailCode.text = code
        binding.detailLoading.text = "加载中…"

        binding.btnAiAnalyze.setOnClickListener { startAiAnalyze() }
        binding.btnMultiAgent.setOnClickListener { openMultiAgent() }
        binding.btnBacktest.setOnClickListener { openBacktest() }
        binding.btnNewsAnalyze.setOnClickListener { startNewsAnalyze() }
        binding.btnBoardRate.setOnClickListener { openBoardRate() }
        binding.btnHistoryReport.setOnClickListener { openHistory() }
        // v2.12.0 快捷选择 AI 模型（与设置页共用同一份配置）
        binding.llmModelRow.setOnClickListener { showLlmPicker() }
        refreshLlmLabel()
        // v2.12.43 持仓录入回填 + 失焦保存 + 清空
        setupHoldingHorizonSpinner()
        fillHolding()
        binding.holdingSave.setOnClickListener {
            persistHolding()
            val h = Prefs.loadHolding(this, code)
            Toast.makeText(
                this,
                if (h != null) "持仓已保存" else "成本价、持股、总资金未填全，按无持仓视角",
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.holdingClear.setOnClickListener {
            binding.etHoldCost.setText("")
            binding.etHoldShares.setText("")
            binding.etHoldCash.setText("")
            binding.etHoldDays.setText("")
            binding.holdingHorizonSpinner.setSelection(0)
            Prefs.saveHolding(this, code, null)
            Toast.makeText(this, "已清空持仓记录", Toast.LENGTH_SHORT).show()
        }
        listOf(binding.etHoldCost, binding.etHoldShares, binding.etHoldCash, binding.etHoldDays).forEach { et ->
            et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persistHolding() }
        }
        // v2.6.0：快捷添加自选
        binding.btnWatch.text = if (Prefs.inWatchlist(this, code)) "★ 已自选" else "☆ 自选"
        binding.btnWatch.setOnClickListener { toggleWatch() }

        // v2.12.6：热股榜排名（头部卡与周期卡之间，点按看各榜名次）
        binding.cardHotRank.setOnClickListener { showHotRankDialog() }
        loadHotRank()

        // v2.12.8：严重异动提醒（点按查看计算口径）
        binding.cardAbnormal.setOnClickListener { showAbnormalDialog() }

        // 动态限制 K 线高度：确保底部日期轴在首屏可见（用屏幕坐标计算，含 Toolbar 偏移）
        binding.klineView.post {
            val lp = binding.klineView.layoutParams
            val loc = IntArray(2)
            binding.klineView.getLocationOnScreen(loc)
            val top = if (loc[1] > 0) loc[1] else 560
            val maxH = resources.displayMetrics.heightPixels - top - 24
            if (lp.height > maxH) {
                lp.height = maxH
                binding.klineView.layoutParams = lp
            }
        }

        setupIndexSpinners()
        setupPeriodButtons()
        setupVolSwitch()

        loadData()
    }

    /** 持仓周期下拉：占位项 + 短线/中长线/长线，透明背景复用指标下拉样式 */
    private fun setupHoldingHorizonSpinner() {
        binding.holdingHorizonSpinner.adapter =
            indexAdapter(arrayOf(HOLDING_HORIZON_PROMPT) + Holding.HORIZONS.toTypedArray())
        binding.holdingHorizonSpinner.setSelection(0)
    }

    /** 主图/附图指标切换（同花顺风格），默认主图 MA、附图 VOL */
    private fun setupIndexSpinners() {
        val mainNames = arrayOf("MA", "EMA", "BOLL", "BBI")
        val subNames = arrayOf("VOL", "MACD", "KDJ", "RSI", "无")
        binding.mainIndexSpinner.adapter = indexAdapter(mainNames)
        binding.subIndexSpinner.adapter = indexAdapter(subNames)
        binding.mainIndexSpinner.setSelection(0)
        binding.subIndexSpinner.setSelection(0)

        binding.mainIndexSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                applyIndices()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.subIndexSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                applyIndices()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * v2.12.4：指标下拉自绘 item。
     * 原来的 spinner_item 布局在深色玻璃卡上会出现「背景可见、文字不可见」的空块，
     * 这里改为代码构建 TextView 并显式指定文字色；position 非法时回落第 0 项，
     * 保证收起态一定显示当前指标名。
     */
    private fun indexAdapter(labels: Array<String>) = object : BaseAdapter() {
        private fun safe(position: Int) = position.coerceIn(0, labels.size - 1)

        override fun getCount(): Int = labels.size
        override fun getItem(position: Int): String = labels[safe(position)]
        override fun getItemId(position: Int): Long = safe(position).toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = convertView as? TextView ?: TextView(this@StockDetailActivity).apply {
                val d = resources.displayMetrics.density
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = null
                setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@StockDetailActivity,
                        if (ThemeManager.isDark(this@StockDetailActivity)) R.color.dark_text
                        else R.color.light_text
                    )
                )
            }
            tv.text = labels[safe(position)]
            return tv
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            getView(position, convertView, parent)
    }

    private fun applyIndices() {
        val main = KLineView.MainIndex.values()[
            binding.mainIndexSpinner.selectedItemPosition.coerceIn(0, KLineView.MainIndex.values().size - 1)
        ]
        val sub = KLineView.SubIndex.values()[
            binding.subIndexSpinner.selectedItemPosition.coerceIn(0, KLineView.SubIndex.values().size - 1)
        ]
        binding.klineView.setIndices(main, sub)
    }

    /** 周期切换按钮（分时/日K/周K/月K/5日线） */
    private fun setupPeriodButtons() {
        periodButtons.clear()
        periodButtons.addAll(listOf(
            binding.periodMinute, binding.periodDay, binding.periodWeek,
            binding.periodMonth, binding.period5d
        ))
        val periods = listOf(
            Period.MINUTE, Period.DAY, Period.WEEK, Period.MONTH, Period.FIVE_DAY
        )
        for (i in periods.indices) {
            val p = periods[i]
            periodButtons[i].setOnClickListener {
                if (p == currentPeriod) return@setOnClickListener
                currentPeriod = p
                highlightPeriod(i)
                when (p) {
                    Period.MINUTE -> loadMinute()
                    else -> loadAggregated(p)
                }
            }
        }
        highlightPeriod(1) // 默认日K
    }

    private fun highlightPeriod(sel: Int) {
        periodButtons.forEachIndexed { i, tv ->
            tv.isSelected = (i == sel)
            tv.setTextColor(if (i == sel) 0xFFC62828.toInt() else 0xFF757575.toInt())
        }
    }

    /** 拉取分时数据 */
    private fun loadMinute() {
        binding.detailLoading.text = "加载分时…"
        Thread {
            try {
                val minute = TencentApi.fetchMinute(code)
                runOnUiThread {
                    binding.detailLoading.text = ""
                    binding.klineView.setChartMode(KLineView.Mode.MINUTE)
                    binding.klineView.setData(minute)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.detailLoading.text = ""
                    Toast.makeText(this, "分时加载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 用已加载的日K聚合周K/月K/5日线 */
    private fun loadAggregated(p: Period) {
        if (dailyBars.isEmpty()) {
            Toast.makeText(this, "日K数据未就绪，请稍后切换", Toast.LENGTH_SHORT).show()
            return
        }
        val agg = aggregate(dailyBars, p)
        binding.klineView.setChartMode(KLineView.Mode.CANDLE)
        binding.klineView.setData(agg)
    }

    /** 日K → 周K/月K/5日线聚合（OHLC 取区间，成交量求和，日期取组内最后一天） */
    private fun aggregate(bars: List<KLineBar>, p: Period): List<KLineBar> {
        if (p == Period.DAY) return bars
        val valid = bars.filter { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it.date) }
        if (valid.isEmpty()) return bars
        val out = mutableListOf<KLineBar>()
        var g = Long.MIN_VALUE
        var lastDate = ""
        var open = 0.0
        var high = 0.0
        var low = Double.MAX_VALUE
        var close = 0.0
        var vol = 0L
        var idx = 0
        for (b in valid) {
            val key = when (p) {
                Period.WEEK -> {
                    val d = LocalDate.parse(b.date)
                    val week = d.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
                    d.year.toLong() * 100 + week
                }
                Period.MONTH -> b.date.substring(0, 7).replace("-", "").toLong()
                Period.FIVE_DAY -> (idx / 5).toLong()
                else -> Long.MIN_VALUE
            }
            idx++
            if (key != g) {
                if (g != Long.MIN_VALUE && lastDate.isNotEmpty()) {
                    out.add(KLineBar(lastDate, open, high, low, close, vol))
                }
                g = key
                lastDate = b.date
                open = b.open; high = b.high; low = b.low; close = b.close; vol = b.volume
            } else {
                lastDate = b.date
                high = maxOf(high, b.high)
                low = minOf(low, b.low)
                close = b.close
                vol += b.volume
            }
        }
        if (lastDate.isNotEmpty()) out.add(KLineBar(lastDate, open, high, low, close, vol))
        return out
    }

    /** 量柱开关（关闭时 VOL 附图隐藏） */
    private fun setupVolSwitch() {
        binding.volSwitch.setOnCheckedChangeListener { _, on ->
            binding.klineView.setVolumeVisible(on)
        }
    }

    private fun loadData() {
        Thread {
            try {
                val q = TencentApi.fetchQuote(code)
                val bars = fetchKline()
                val idxBars = fetchIndexBars()
                val r = Analyzer.analyze(bars, code)
                runOnUiThread {
                    render(q, r, bars)
                    renderAbnormal(q, bars, idxBars)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.detailLoading.text = "加载失败：${e.message}"
                    Toast.makeText(this, "加载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** v2.12.8：严重异动偏离值的基准指数日K（取不到返回空列表，卡片降级为「暂不可用」） */
    private fun fetchIndexBars(): List<KLineBar> {
        val sym = AbnormalAlert.indexSymbol(code) ?: return emptyList()
        return try {
            TencentApi.fetchIndexDailyKline(sym, 20)
        } catch (e: Exception) {
            emptyList()
        }
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
            } catch (e: Exception) {
                // 回退腾讯
            }
        }
        // 腾讯备用域 → 东方财富 → 腾讯主域 → 新浪，任一可用即返回（主域在部分网络被 WAF 拦截）
        return try {
            TencentApi.fetchDailyKlineAlt(code, 320)
        } catch (e1: Exception) {
            try {
                EastmoneyApi.fetchDailyKline(code, 320)
            } catch (e2: Exception) {
                try {
                    TencentApi.fetchDailyKline(code, 320)
                } catch (e3: Exception) {
                    SinaApi.fetchDailyKline(code, 320)
                }
            }
        }
    }

    private fun render(q: Quote, r: AnalysisResult, bars: List<KLineBar>) {
        quote = q
        result = r
        binding.detailLoading.text = ""
        // v2.12.6：行情返回后补全热股榜标题里的股票名称
        applyHotRank(hotRank)

        binding.detailName.text = q.name + boardSuffix(code)
        supportActionBar?.title = q.name + boardSuffix(code)
        val color = if (q.changePct >= 0) 0xFFE53935.toInt() else 0xFF43A047.toInt()
        binding.detailPrice.text = String.format("%.2f", q.price)
        binding.detailPrice.setTextColor(color)
        binding.detailChange.text = String.format("%+.2f%%  %+.2f", q.changePct, q.change)
        binding.detailChange.setTextColor(color)

        binding.klineView.setData(bars)
        dailyBars = bars
        currentPeriod = Period.DAY
        highlightPeriod(1)

        binding.indTrend.text =
            "趋势：${Analyzer.TREND_ZH[r.trendStatus]}（${r.maAlignment}）强度 ${r.trendStrength.toInt()}"
        binding.indMa.text = buildString {
            append("现价 ${String.format("%.2f", r.currentPrice)}")
            append("\nMA5=${String.format("%.2f", r.ma5)}(${String.format("%+.2f", r.biasMa5)}%)  ")
            append("MA10=${String.format("%.2f", r.ma10)}(${String.format("%+.2f", r.biasMa10)}%)  ")
            append("MA20=${String.format("%.2f", r.ma20)}(${String.format("%+.2f", r.biasMa20)}%)")
        }
        binding.indVolume.text =
            "量能：${Analyzer.VOLUME_ZH[r.volumeStatus]}（${r.volumeTrend}）  量比(5日) ${String.format("%.2f", r.volumeRatio5d)}"
        binding.indMacd.text =
            "MACD：${Analyzer.MACD_ZH[r.macdStatus]}  DIF=${String.format("%.3f", r.macdDif)}  DEA=${String.format("%.3f", r.macdDea)}  柱=${String.format("%.3f", r.macdBar)}"
        binding.indRsi.text =
            "RSI：6日=${String.format("%.1f", r.rsi6)}  12日=${String.format("%.1f", r.rsi12)}  24日=${String.format("%.1f", r.rsi24)}（${Analyzer.RSI_ZH[r.rsiStatus]}）"

        binding.scoreValue.text = r.signalScore.toString()
        val signal = Analyzer.SIGNAL_ZH[r.buySignal] ?: r.buySignal
        binding.signalValue.text = "操作信号：$signal"
        binding.signalValue.setTextColor(color)
        binding.reasonsValue.text = buildString {
            append("看多理由：\n")
            r.signalReasons.forEach { append("  $it\n") }
            if (r.riskFactors.isNotEmpty()) {
                append("风险因素：\n")
                r.riskFactors.forEach { append("  $it\n") }
            }
        }
    }

    // ---- v2.12.6 热股榜 ----
    private var hotRank: HotRankApi.RankInfo? = null

    /** 后台读取今日热榜（多源合并，单源不可用不影响其他源） */
    private fun loadHotRank() {
        Thread {
            val info = try {
                HotRankApi.query(code)
            } catch (e: Exception) {
                null
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) applyHotRank(info) }
        }.start()
    }

    private fun applyHotRank(info: HotRankApi.RankInfo?) {
        hotRank = info
        val name = quote?.name ?: code
        if (info == null || info.slots.isEmpty()) {
            binding.tvHotRank.text = "$name 今日热股榜未收录"
            binding.tvHotSub.text = "东财人气榜 / 关注榜、同花顺热榜当前均无该股"
            binding.tvHotBadge.text = "暂未上榜"
            return
        }
        val best = info.best
        binding.tvHotRank.text = "$name 热股榜第 ${best?.rank ?: 0} 名"
        binding.tvHotSub.text = info.slots.joinToString(" · ") { s ->
            if (s.total > 0) "${s.name} ${s.rank}/${s.total}" else "${s.name} 第${s.rank}"
        }
        binding.tvHotBadge.text = best?.name ?: "热榜"
    }

    private fun showHotRankDialog() {
        val info = hotRank
        val title = "热股榜排名 · ${quote?.name ?: code}"
        val body = if (info == null || info.slots.isEmpty()) {
            "该股当前未出现在今日热股榜。\n\n" +
                "热股榜汇总东方财富人气榜、东方财富关注榜、同花顺 24 小时榜与今日榜；" +
                "各公开榜单接口仅开放前 100 名，四榜合并去重后约 300 余只，" +
                "热度排名只覆盖上榜的头部股票，未上榜只代表关注度暂未进入头部热度，" +
                "不代表个股没有交易机会。"
        } else {
            info.slots.joinToString("\n\n") { s ->
                "· ${s.name}：第 ${s.rank} 名" + if (s.total > 0) "（收录 ${s.total} 只）" else ""
            } + "\n\n数据来源：东方财富人气榜 / 关注榜、同花顺热榜公开接口，约每 5 分钟更新；" +
                "多榜同时靠前的股票在「热股榜·短线」选股中热度分更高。"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---- v2.12.8 严重异动提醒 ----
    private var abnormal: AbnormalAlert.Result? = null

    /**
     * 渲染异动提醒卡：10 日累计偏离值 + 当日触发所需涨幅 + 是否达到条件 + 规则。
     * 数据不足/指数暂不可用时只降级提示，不出现「失败」字样。
     */
    private fun renderAbnormal(q: Quote, bars: List<KLineBar>, idxBars: List<KLineBar>) {
        if (isFinishing || isDestroyed) return
        val sym = AbnormalAlert.indexSymbol(code)
        val idxName = AbnormalAlert.indexName(sym)
        val red = 0xFFD93025.toInt()
        val green = 0xFF188038.toInt()
        val gray = themeColor(R.attr.dsaText2)

        binding.tvAbnormalRule.text =
            "规则：连续十个交易日内日收盘价涨跌幅偏离值累计达到 +100%（负向 -50%），将触发严重异常波动。基准：$idxName"

        if (sym == null) {
            abnormal = null
            binding.tvAbnormalDev.text = "10日偏离 --"
            binding.tvAbnormalDev.setTextColor(gray)
            binding.tvAbnormalTrigger.text = "该板块暂无对应基准指数，异动条件不适用"
            binding.tvAbnormalState.text = "未达到严重异动条件（不适用）"
            binding.tvAbnormalState.setTextColor(gray)
            binding.tvAbnormalDot.setTextColor(green)
            return
        }
        if (bars.size < 3 || idxBars.size < 3) {
            abnormal = null
            binding.tvAbnormalDev.text = "10日偏离 --"
            binding.tvAbnormalDev.setTextColor(gray)
            binding.tvAbnormalTrigger.text = "基准指数数据暂不可用，稍后重试"
            binding.tvAbnormalState.text = "未达到严重异动条件（暂无法计算）"
            binding.tvAbnormalState.setTextColor(gray)
            binding.tvAbnormalDot.setTextColor(gray)
            return
        }

        val r = AbnormalAlert.compute(bars, idxBars, q.changePct)
        abnormal = r
        binding.tvAbnormalDev.text = String.format(Locale.US, "10日偏离 %+.2f%%", r.dev10)
        binding.tvAbnormalDev.setTextColor(if (r.triggered) red else gray)

        when {
            r.triggeredUp || r.triggeredDown -> {
                binding.tvAbnormalTrigger.text = "已触发严重异动条件"
                binding.tvAbnormalState.text =
                    String.format(Locale.US, "10 日累计偏离 %+.2f%%（已达阈值）", r.dev10)
                binding.tvAbnormalState.setTextColor(red)
                binding.tvAbnormalDot.setTextColor(red)
            }
            !r.dataOk -> {
                binding.tvAbnormalTrigger.text = "基准指数数据不完整，暂按现有数据估算"
                binding.tvAbnormalState.text = "未达到严重异动条件 · 基准 $idxName"
                binding.tvAbnormalState.setTextColor(gray)
                binding.tvAbnormalDot.setTextColor(gray)
            }
            else -> {
                // 距 +100% 与 -50% 两档，取更近的一档提示
                val gapUp = 100.0 - r.dev10
                val gapDown = r.dev10 + 50.0
                if (gapDown < gapUp) {
                    binding.tvAbnormalTrigger.text =
                        triggerText("收盘跌幅达到", kotlin.math.abs(r.needDownPct), "将触发严重异动")
                } else {
                    binding.tvAbnormalTrigger.text = when {
                        r.needTodayPct <= 0.0 -> "前 9 日累计偏离已超 +100%，当日收盘保持红盘即触发"
                        r.needTodayPct > 21.0 -> String.format(
                            Locale.US, "当日收盘需涨 %.2f%%（超过单日涨停幅度，需多日累计）", r.needTodayPct
                        )
                        else -> triggerText("收盘涨幅达到", r.needTodayPct, "将触发严重异动")
                    }
                }
                binding.tvAbnormalState.text = "未达到严重异动条件 · 基准 $idxName"
                binding.tvAbnormalState.setTextColor(gray)
                binding.tvAbnormalDot.setTextColor(green)
            }
        }
    }

    /** 把百分比数字染成红色（与图1 的「1.53%」同风格） */
    private fun triggerText(prefix: String, pct: Double, suffix: String): CharSequence {
        val num = String.format(Locale.US, "%.2f%%", kotlin.math.abs(pct))
        val sp = android.text.SpannableString("$prefix$num$suffix")
        sp.setSpan(
            android.text.style.ForegroundColorSpan(0xFFD93025.toInt()),
            prefix.length, prefix.length + num.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sp
    }

    private fun themeColor(attr: Int): Int {
        val a = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, 0xFF888888.toInt())
        a.recycle()
        return c
    }

    private fun showAbnormalDialog() {
        val r = abnormal
        val idxName = AbnormalAlert.indexName(AbnormalAlert.indexSymbol(code))
        val name = quote?.name ?: code
        val body = if (r == null) {
            "当前暂无法计算该股的 10 日偏离值（基准指数数据暂不可用，稍后重试即可）。\n\n" +
                "规则：连续十个交易日内日收盘价涨跌幅偏离值累计达到 +100%（负向 -50%），即构成严重异常波动。"
        } else {
            buildString {
                append(String.format(Locale.US, "10 日累计偏离值：%+.2f%%\n", r.dev10))
                append(String.format(Locale.US, "前 9 个交易日累计：%+.2f%%\n", r.pre9))
                append(String.format(Locale.US, "当日涨跌幅：%+.2f%%（基准 %s）\n", r.todayPct, idxName))
                append(String.format(Locale.US, "触发 +100%% 所需当日收盘涨幅：%.2f%%\n", r.needTodayPct))
                append(String.format(Locale.US, "触发 -50%% 所需当日收盘涨幅：%.2f%%\n", r.needDownPct))
                append(if (r.triggered) "\n当前状态：已触发严重异动条件。" else "\n当前状态：未达到严重异动条件。")
                append("\n\n口径：偏离值 = 个股当日涨跌幅 − 基准指数当日涨跌幅，按最近 10 个交易日累计；")
                append("当日涨幅取实时行情。触发后交易所可要求公司披露核查，并视情况实施停牌核查等措施。")
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("严重异动提醒 · $name")
            .setMessage(body)
            .setPositiveButton("知道了", null)
            .show()
    }

    /** v2.6.0：详情页快捷添加 / 移除自选 */
    private fun toggleWatch() {
        val nm = quote?.name ?: code
        if (Prefs.inWatchlist(this, code)) {
            Prefs.removeWatch(this, code)
            binding.btnWatch.text = "☆ 自选"
            Toast.makeText(this, "已移除自选：$nm", Toast.LENGTH_SHORT).show()
        } else {
            Prefs.addWatch(this, code)
            binding.btnWatch.text = "★ 已自选"
            Toast.makeText(this, "已添加自选：$nm", Toast.LENGTH_SHORT).show()
        }
        fillHolding()
    }

    private fun startAiAnalyze() {
        if (analyzing) return
        val r = result ?: run {
            Toast.makeText(this, "请先等待行情加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        val q = quote
        if (!Prefs.hasLlmConfig(this)) {
            Toast.makeText(this, "请先在大模型设置中填写 API Key 和模型名", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        analyzing = true
        binding.btnAiAnalyze.isEnabled = false
        binding.detailLoading.text = "AI 分析中，可能需要几十秒…"
        val name = q?.name ?: code
        Notifier.showRunning(this, code, "ai", "「$name」AI 报告分析中")
        val summary = Analyzer.buildAnalysisSummary(code, name, q, r)
        var system = SYSTEM_PROMPT
        Thread {
            try {
                // 参考近期新闻（失败/未配置时静默跳过，不阻塞报告）
                if (NewsApi.hasAnySource(this)) {
                    val news = NewsApi.search(this, "$name $code 股票")
                    if (news.isNotEmpty()) {
                        val days = Prefs.newsDays(this)
                        val newsBlock = buildString {
                            append("\n\n【近期相关新闻（最近 $days 天，仅供参考，需自行甄别）】\n")
                            news.take(5).forEachIndexed { i, n ->
                                append("${i + 1}. [${n.source}] ${n.title}（${n.date}）\n")
                                if (n.snippet.isNotBlank()) append("   ${n.snippet.take(120)}\n")
                            }
                        }
                        system = SYSTEM_PROMPT + newsBlock
                    }
                }
                val report = LlmApi.chat(
                    Prefs.llmBaseUrl(this), Prefs.llmApiKey(this), Prefs.llmModel(this),
                    system, summary,
                    Prefs.llmTemperature(this), Prefs.llmFallbackModel(this).ifBlank { null }
                )
                runOnUiThread {
                    analyzing = false
                    binding.btnAiAnalyze.isEnabled = true
                    binding.detailLoading.text = ""
                    // 自动保存到历史报告（每次独立留存，不覆盖）；评分 ≤50 仅展示不落库
                    val kept = Prefs.keepHistory(r.signalScore)
                    if (kept) {
                        Prefs.saveHistory(
                            this, HistoryReport(
                                code = code, name = name,
                                date = LocalDate.now().toString(),
                                time = Prefs.clock(),
                                score = r.signalScore,
                                signal = Analyzer.SIGNAL_ZH[r.buySignal] ?: r.buySignal,
                                report = report
                            )
                        )
                    }
                    Toast.makeText(
                        this,
                        if (kept) "AI 分析完成，已保存到历史报告" else "评分 ≤50，未存入历史报告",
                        Toast.LENGTH_SHORT
                    ).show()
                    Notifier.showDone(
                        this, code, "ai",
                        "「$name」AI 分析完成",
                        "综合评分 ${r.signalScore} · ${Analyzer.SIGNAL_ZH[r.buySignal] ?: r.buySignal}，点击查看报告",
                        Intent(this, ReportActivity::class.java)
                            .putExtra("code", code)
                            .putExtra("name", name)
                            .putExtra("score", r.signalScore)
                            .putExtra("signal", Analyzer.SIGNAL_ZH[r.buySignal] ?: r.buySignal)
                            .putExtra("date", LocalDate.now().toString())
                            .putExtra("report", report)
                            .putExtra("readonly", true)
                    )
                    val intent = Intent(this, ReportActivity::class.java)
                        .putExtra("code", code)
                        .putExtra("name", name)
                        .putExtra("score", r.signalScore)
                        .putExtra("signal", Analyzer.SIGNAL_ZH[r.buySignal] ?: r.buySignal)
                        .putExtra("date", LocalDate.now().toString())
                        .putExtra("report", report)
                        .putExtra("readonly", true)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    analyzing = false
                    binding.btnAiAnalyze.isEnabled = true
                    binding.detailLoading.text = "AI 分析失败：${e.message}"
                    Notifier.cancel(this, code, "ai")
                    Toast.makeText(this, "AI 分析失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * v2.5.0 新闻分析：检索近期新闻 → 关键词情绪评分 → （有 LLM 时）LLM 精评新闻情绪/影响/风险
     * 结果自动保存到历史报告并打开报告页
     */
    private fun startNewsAnalyze() {
        if (analyzing) return
        val name = quote?.name ?: code
        analyzing = true
        binding.btnNewsAnalyze.isEnabled = false
        binding.detailLoading.text = "检索新闻并分析中…"
        Notifier.showRunning(this, code, "news", "「$name」新闻分析中")
        Thread {
            try {
                if (!NewsApi.hasAnySource(this)) {
                    runOnUiThread {
                        analyzing = false
                        binding.btnNewsAnalyze.isEnabled = true
                        binding.detailLoading.text = ""
                        Notifier.cancel(this, code, "news")
                        Toast.makeText(this, "未配置新闻检索 API（设置 → 新闻检索 API）", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val news = NewsApi.search(this, "$name $code 股票")
                if (news.isEmpty()) {
                    runOnUiThread {
                        analyzing = false
                        binding.btnNewsAnalyze.isEnabled = true
                        binding.detailLoading.text = ""
                        Notifier.cancel(this, code, "news")
                        Toast.makeText(this, "未检索到相关新闻，请更换新闻源或稍后再试", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val sc = NewsSentiment.score(news)
                val days = Prefs.newsDays(this)
                val newsText = buildString {
                    append("股票：$name（$code）\n")
                    append("最近 $days 天检索到 ${news.size} 条相关新闻：\n")
                    news.take(6).forEachIndexed { i, n ->
                        append("${i + 1}. [${n.source}] ${n.title}（${n.date}）\n")
                        if (n.snippet.isNotBlank()) append("   ${n.snippet.take(150)}\n")
                    }
                }
                val report = if (Prefs.hasLlmConfig(this)) {
                    LlmApi.chat(
                        Prefs.llmBaseUrl(this), Prefs.llmApiKey(this), Prefs.llmModel(this),
                        NEWS_SYSTEM_PROMPT, newsText,
                        Prefs.llmTemperature(this), Prefs.llmFallbackModel(this).ifBlank { null }
                    )
                } else {
                    buildString {
                        append("【新闻情绪评分（关键词规则，未配置 LLM）】\n")
                        append("情绪：${NewsSentiment.label(sc.score)}（$sc.score 分）\n")
                        if (sc.positive.isNotEmpty()) append("利好关键词：${sc.positive.joinToString("、")}\n")
                        if (sc.negative.isNotEmpty()) append("利空关键词：${sc.negative.joinToString("、")}\n")
                        if (sc.positive.isEmpty() && sc.negative.isEmpty()) append("未命中明确利好/利空关键词，需人工判断\n")
                        append("\n—— 配置 LLM 后可生成完整新闻情绪/影响/风险分析 ——\n")
                    }
                }
                runOnUiThread {
                    analyzing = false
                    binding.btnNewsAnalyze.isEnabled = true
                    binding.detailLoading.text = ""
                    val signal = NewsSentiment.label(sc.score)
                    val newsScore = (sc.score + 50).coerceIn(0, 100)
                    val kept = Prefs.keepHistory(newsScore)
                    if (kept) {
                        Prefs.saveHistory(
                            this, HistoryReport(
                                code = code, name = name,
                                date = LocalDate.now().toString(),
                                time = Prefs.clock(),
                                score = newsScore,
                                signal = "新闻·$signal",
                                report = report
                            )
                        )
                    }
                    Toast.makeText(
                        this,
                        if (kept) "新闻分析完成，已保存到历史报告" else "评分 ≤50，未存入历史报告",
                        Toast.LENGTH_SHORT
                    ).show()
                    Notifier.showDone(
                        this, code, "news",
                        "「$name」新闻分析完成",
                        "情绪 ${NewsSentiment.label(sc.score)}（${sc.score} 分），点击查看报告",
                        Intent(this, ReportActivity::class.java)
                            .putExtra("code", code)
                            .putExtra("name", name)
                            .putExtra("score", (sc.score + 50).coerceIn(0, 100))
                            .putExtra("signal", "新闻·${NewsSentiment.label(sc.score)}")
                            .putExtra("date", LocalDate.now().toString())
                            .putExtra("report", report)
                            .putExtra("readonly", true)
                    )
                    startActivity(
                        Intent(this, ReportActivity::class.java)
                            .putExtra("code", code)
                            .putExtra("name", name)
                            .putExtra("score", (sc.score + 50).coerceIn(0, 100))
                            .putExtra("signal", "新闻·${NewsSentiment.label(sc.score)}")
                            .putExtra("date", LocalDate.now().toString())
                            .putExtra("report", report)
                            .putExtra("readonly", true)
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    analyzing = false
                    binding.btnNewsAnalyze.isEnabled = true
                    binding.detailLoading.text = ""
                    Notifier.cancel(this, code, "news")
                    Toast.makeText(this, "新闻分析失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** v2.10.0 封板率预测：跳转独立页面，数据在页面内拉取 */
    private fun openHistory() {
        startActivity(
            Intent(this, HistoryActivity::class.java)
                .putExtra("code", code)
                .putExtra("name", quote?.name ?: code)
        )
    }

    private fun openBoardRate() {
        val nm = quote?.name ?: code
        startActivity(
            Intent(this, BoardRateActivity::class.java)
                .putExtra("code", code)
                .putExtra("name", nm)
        )
    }

    /** 回填已录入持仓；无记录则留空（按无持仓视角） */
    private fun fillHolding() {
        val h = Prefs.loadHolding(this, code)
        binding.etHoldCost.setText(if (h != null && h.valid) fmt2(h.cost) else "")
        binding.etHoldShares.setText(
            if (h != null && h.valid) sharesText(h.shares) else ""
        )
        binding.etHoldCash.setText(
            if (h != null && h.valid && h.cash > 0) fmt2(h.cash) else ""
        )
        // 持有天数：优先手填基准累计，否则回填加入自选起算的天数，无记录留空
        val autoDays = Prefs.holdingDays(this, code)
        val daysText = Prefs.effectiveDays(h, autoDays)?.toString() ?: ""
        binding.etHoldDays.setText(daysText)
        // 周期下拉：有有效持仓才选中具体周期，否则回到占位项
        val horizonIdx = if (h != null && h.valid) {
            1 + Holding.HORIZONS.indexOf(h.horizon).coerceAtLeast(0)
        } else 0
        binding.holdingHorizonSpinner.setSelection(horizonIdx)
    }

    /** 解析输入并保存持仓；成本、持股、总资金三项齐全才视为有持仓，否则清除记录 */
    private fun persistHolding() {
        val cost = binding.etHoldCost.text.toString().trim().replace(",", "").toDoubleOrNull() ?: 0.0
        val shares = binding.etHoldShares.text.toString().trim().replace(",", "").toDoubleOrNull() ?: 0.0
        val cash = binding.etHoldCash.text.toString().trim().replace(",", "").toDoubleOrNull() ?: 0.0
        val sel = binding.holdingHorizonSpinner.selectedItem?.toString()
        val horizon = if (sel in Holding.HORIZONS) sel!! else Holding.HORIZON_SHORT
        val daysInput = binding.etHoldDays.text.toString().trim().toIntOrNull()
        val daysBase = if (daysInput != null && daysInput >= 0) daysInput else null
        val daysBaseAt = if (daysBase != null) System.currentTimeMillis() else null
        val h = if (cost > 0 && shares > 0 && cash > 0) Holding(cost, shares, cash, horizon, daysBase, daysBaseAt) else null
        Prefs.saveHolding(this, code, h)
    }

    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun sharesText(v: Double) =
        if (v == v.toLong().toDouble()) v.toLong().toString() else fmt2(v)

    private fun openMultiAgent() {
        persistHolding()
        if (!Prefs.hasLlmConfig(this)) {
            Toast.makeText(this, "请先在大模型设置中填写 API 地址、Key 和模型名", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val nm = quote?.name ?: code
        Notifier.showRunning(this, code, "multi", "「$nm」多智能体分析中")
        startActivity(
            Intent(this, MultiAgentActivity::class.java)
                .putExtra("code", code)
                .putExtra("name", nm)
        )
    }

    /** 刷新详情页顶部显示的当前 AI 模型 */
    private fun refreshLlmLabel() {
        val profiles = Prefs.llmProfiles(this)
        val idx = Prefs.llmActiveIndex(this).coerceIn(0, (profiles.size - 1).coerceAtLeast(0))
        val p = profiles.getOrNull(idx)
        binding.llmModelValue.text = when {
            p == null -> "未配置（点此去设置）"
            p.model.isBlank() -> "${p.name} · 模型未填"
            else -> "${p.name} · ${p.model}"
        }
    }

    /** 快捷切换当前使用的 AI 模型配置（写入与设置页同一份存储） */
    private fun showLlmPicker() {
        val profiles = Prefs.llmProfiles(this)
        if (profiles.isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val active = Prefs.llmActiveIndex(this).coerceIn(0, profiles.size - 1)
        val labels = profiles.mapIndexed { i, p ->
            val model = p.model.ifBlank { "模型未填" }
            (if (i == active) "✓ " else "") + p.name + " · " + model
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择 AI 模型（与设置页同步）")
            .setSingleChoiceItems(labels, active) { d, which ->
                Prefs.saveLlmProfiles(this, profiles, which)
                d.dismiss()
                refreshLlmLabel()
                Toast.makeText(
                    this, "已切换到「${profiles[which].name}」", Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("管理配置") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            ThemeManager.applyTagAlphaTree(binding.root, this)
            refreshLlmLabel()
        }
    }

    private fun openBacktest() {
        val nm = quote?.name ?: code
        Notifier.showRunning(this, code, "backtest", "「$nm」A股回测中")
        startActivity(
            Intent(this, BacktestActivity::class.java)
                .putExtra("code", code)
                .putExtra("name", nm)
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        /** 持仓周期下拉占位项（未选择周期） */
        private const val HOLDING_HORIZON_PROMPT = "周期"
        private const val SYSTEM_PROMPT = "你是一名专业、审慎的A股技术分析师。用户会提供一只股票的实时行情与技术指标数据。请基于数据输出中文分析报告，严格按以下结构（用 Markdown 标题）：\n" +
            "## 核心洞察\n一段120-180字的综合判断：现价与涨跌、均线排列与趋势强度、量价关系、基本面与估值关键点、当前所处市场阶段，最后给出明确倾向。\n" +
            "## 操作建议\n给出明确建议（买入/持有/观望/回避）并一句话说明理由。\n" +
            "## 趋势预测\n给出趋势判断（上升/震荡/下降/反弹），结合均线与MACD说明依据。\n" +
            "## 市场情绪\n给出恐惧贪婪指数（0-100整数）并标注状态（极度恐惧/恐惧/中性/贪婪/极度贪婪），说明依据（如量比、换手率、K线所处位置）。\n" +
            "## 关联板块\n列出该股所属行业与概念板块标签（3-6个，用顿号分隔）。\n" +
            "## 策略点位\n给出以下四个点位，每个点位必须给出具体价位（基于用户数据推算）与触发条件：\n" +
            "- 理想买入点：XX.XX-XX.XX元（触发条件，如回踩MA5企稳并缩量收阳确认后）\n" +
            "- 二次买入点：XX.XX元（触发条件，如有效站稳MA10并转为支撑后）\n" +
            "- 止损价位：XX.XX元（触发条件，如跌破近期低点且不再收复即离场）\n" +
            "- 止盈目标：目标一XX.XX元（说明压力依据），目标二XX.XX元（说明压力依据）\n" +
            "要求：所有价位必须基于用户提供的数据推算并说明依据；用语审慎，注明技术面参考、非投资建议，不要承诺收益。"

        /** v2.5.0：新闻分析专用系统提示 */
        private const val NEWS_SYSTEM_PROMPT = "你是一名审慎的财经新闻分析师。用户会提供一只A股股票及其近期检索到的新闻标题与摘要。请基于这些新闻输出中文分析报告，严格按以下结构（用 Markdown 标题）：\n" +
            "## 新闻情绪\n给出情绪判断（强利好/利好/中性/利空/强利空），并给出 0-100 的情绪评分（50 为中性），说明依据（命中哪些关键词/事件）。\n" +
            "## 核心事件\n归纳 2-4 条最重要的新闻事件，每条一句话说明对股价的可能影响方向。\n" +
            "## 利好因素\n列出新闻中支持股价的正面因素（无则写“无”）。\n" +
            "## 利空因素\n列出新闻中的负面因素与风险点（无则写“无”）。\n" +
            "## 操作参考\n给出审慎建议（短线关注/回避/观望），并提示需结合技术面，注明非投资建议。\n" +
            "要求：只基于用户提供的新闻内容分析，不得编造新闻中不存在的事件；用语审慎，注明新闻面参考、非投资建议。"
    }
}
