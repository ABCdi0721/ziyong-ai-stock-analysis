package com.dsa.mobile

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dsa.mobile.databinding.ActivityScreeningBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreeningActivity : BaseActivity() {

    private lateinit var binding: ActivityScreeningBinding
    private var running = false

    // v2.12.10：策略顺序可由用户长按调整，下拉项与运行入口都以这里为准
    private var strategies: List<ScreenStrategy> = Screening.STRATEGIES
    private var strategyNames: List<String> = Screening.STRATEGIES.map { it.name }

    // v2.5.0：结果筛选状态（板块 / 信号）
    private var resultItems: List<Triple<ScreenStock, Double, Map<String, Double>>> = emptyList()
    private var resultNews: Map<String, String> = emptyMap()
    // v2.6.1：所有策略按评分换算的信号标签（强烈买入/买入），信号筛选与列表徽标共用
    private var resultSignals: Map<String, String> = emptyMap()

    // v2.12.26：碗口反弹新因子引擎（独立结果列表，置于原引擎下方）
    private var bowlItems: List<Triple<ScreenStock, Double, Map<String, Double>>> = emptyList()
    private var bowlSignals: Map<String, String> = emptyMap()
    private var bowlRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreeningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "选股"

        // v2.12.10：先按用户保存的顺序还原策略列表，再填充下拉
        strategies = Screening.ordered(Prefs.screenStrategyOrder(this))
        strategyNames = strategies.map { it.name }
        binding.strategySpinner.adapter =
            ArrayAdapter(this, R.layout.spinner_item, strategyNames)

        binding.screenRecycler.layoutManager = LinearLayoutManager(this)
        binding.bowlRecycler.layoutManager = LinearLayoutManager(this)

        // v2.12.26：碗口反弹新因子引擎独立触发按钮
        binding.btnRunBowl.setOnClickListener {
            if (bowlRunning) return@setOnClickListener
            runBowlScreening()
        }

        // v2.5.0：结果筛选（板块：沪深主板 / 科创板688 / 创业板300-301；信号：强烈买入/买入）
        binding.spFilterBoard.adapter = ArrayAdapter(
            this, R.layout.spinner_item, listOf("全部板块", "仅沪深主板", "仅科创板", "仅创业板")
        )
        binding.spFilterSignal.adapter = ArrayAdapter(
            this, R.layout.spinner_item, listOf("全部信号", "仅强烈买入", "仅买入")
        )
        binding.spFilterBoard.onItemSelectedListener = simpleSelected { applyFilter() }
        binding.spFilterSignal.onItemSelectedListener = simpleSelected {
            // 记住信号筛选选择（全部信号/仅强烈买入/仅买入）
            Prefs.saveScreenSignalFilter(this, binding.spFilterSignal.selectedItemPosition)
            applyFilter()
        }
        // v2.6.3：所有策略均支持信号筛选——信号为技术面快照信号（与详情页"操作信号"同源）
        binding.strategySpinner.onItemSelectedListener = simpleSelected {
            val pos = binding.strategySpinner.selectedItemPosition
            Prefs.saveScreenStrategyIdx(this, pos)
            strategies.getOrNull(pos)?.let { Prefs.saveScreenStrategyName(this, it.name) }
            refreshSignalOptions()
        }
        // v2.12.10：长按策略栏（或下拉本身）弹出排序面板
        val orderLongClick = View.OnLongClickListener {
            showStrategyOrderDialog()
            true
        }
        binding.strategyCard.setOnLongClickListener(orderLongClick)
        binding.strategySpinner.setOnLongClickListener(orderLongClick)
        // 恢复上次策略：排序后下标会变，优先按名称匹配，名称找不到再退回下标
        val savedName = Prefs.screenStrategyName(this)
        val savedIdx = strategyNames.indexOf(savedName).takeIf { it >= 0 }
            ?: Prefs.screenStrategyIdx(this).coerceIn(0, strategyNames.size - 1)
        binding.strategySpinner.setSelection(savedIdx)
        refreshSignalOptions()

        binding.btnRunScreen.setOnClickListener {
            if (running) return@setOnClickListener
            val idx = binding.strategySpinner.selectedItemPosition
            // v2.6.2：记住当前策略选择，下次进入选股页保持不变
            Prefs.saveScreenStrategyIdx(this, idx)
            val strategy = strategies.getOrNull(idx) ?: return@setOnClickListener
            Prefs.saveScreenStrategyName(this, strategy.name)
            if (strategy.name == Screening.CUSTOM_NAME) {
                // 自定义策略...：先编辑参数再运行
                editCustomStrategy { s ->
                    runScreening(s)
                }
                return@setOnClickListener
            }
            runScreening(strategy)
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, ScreenHistoryActivity::class.java))
        }
    }

    private fun simpleSelected(onSel: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onSel()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

    /**
     * v2.12.11：长按策略栏 → 策略排序面板（拖拽排序）。
     * 列表为 RecyclerView，长按行或拖动手柄上下拖动换位；保存后写进 Prefs 并刷新下拉。
     * 「自定义策略...」不参与排序，始终固定在下拉最后一项。
     */
    private fun showStrategyOrderDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val textColor = com.google.android.material.color.MaterialColors.getColor(
            this, R.attr.dsaText, android.graphics.Color.DKGRAY
        )
        val subColor = com.google.android.material.color.MaterialColors.getColor(
            this, R.attr.dsaText2, android.graphics.Color.GRAY
        )

        val work = strategies.filter { it.name != Screening.CUSTOM_NAME }
            .map { it.name }.toMutableList()

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(12), dp(16), dp(6))

        val hint = TextView(this)
        hint.text = "长按行或拖动手柄调整顺序，保存后下拉列表按新顺序显示；「自定义策略...」固定在最后一项。"
        hint.textSize = 12f
        hint.setTextColor(subColor)
        hint.setPadding(0, 0, 0, dp(10))
        box.addView(hint)

        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        box.addView(rv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(400)))

        var touchHelper: ItemTouchHelper? = null

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                }
                val seq = TextView(row.context).apply {
                    minWidth = dp(24); textSize = 12f; setTextColor(subColor)
                }
                val label = TextView(row.context).apply {
                    textSize = 14f; setTextColor(textColor)
                }
                val grip = TextView(row.context).apply {
                    text = "⠿"; textSize = 18f; setTextColor(subColor)
                    setPadding(dp(12), 0, 0, 0)
                    contentDescription = "拖拽手柄"
                }
                row.addView(seq)
                row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(grip)
                row.tag = arrayOf(seq, label, grip)
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val arr = holder.itemView.tag as Array<TextView>
                arr[0].text = "${position + 1}"
                arr[1].text = work[position]
                arr[2].setOnLongClickListener {
                    touchHelper?.startDrag(holder)
                    true
                }
            }

            override fun getItemCount(): Int = work.size
        }
        rv.adapter = adapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder) =
                ItemTouchHelper.Callback.makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

            override fun onMove(
                recyclerView: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                val f = from.bindingAdapterPosition
                val t = to.bindingAdapterPosition
                if (f < 0 || t < 0) return false
                val tmp = work[f]
                work[f] = work[t]
                work[t] = tmp
                adapter.notifyItemMoved(f, t)
                adapter.notifyItemRangeChanged(0, work.size)
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.85f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
            }
        })
        touchHelper?.attachToRecyclerView(rv)

        AlertDialog.Builder(this)
            .setTitle("策略排序")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                Prefs.saveScreenStrategyOrder(this, work.toList())
                applyStrategyOrder()
                Toast.makeText(this, "策略顺序已保存", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                Prefs.saveScreenStrategyOrder(this, emptyList())
                applyStrategyOrder()
                Toast.makeText(this, "已恢复默认顺序", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 应用已保存的策略顺序，并尽量保持当前选中的策略不变（按名称匹配） */
    private fun applyStrategyOrder() {
        val keep = strategyNames.getOrNull(binding.strategySpinner.selectedItemPosition)
            ?: Prefs.screenStrategyName(this)
        strategies = Screening.ordered(Prefs.screenStrategyOrder(this))
        strategyNames = strategies.map { it.name }
        binding.strategySpinner.adapter =
            ArrayAdapter(this, R.layout.spinner_item, strategyNames)
        val pos = strategyNames.indexOf(keep).takeIf { it >= 0 } ?: 0
        binding.strategySpinner.setSelection(pos)
        Prefs.saveScreenStrategyIdx(this, pos)
        Prefs.saveScreenStrategyName(this, strategyNames.getOrNull(pos) ?: "")
        refreshSignalOptions()
    }

    /** v2.6.3：所有策略均保留三档信号筛选（信号=快照信号，与详情页一致） */
    private fun refreshSignalOptions() {
        binding.spFilterSignal.adapter = ArrayAdapter(
            this, R.layout.spinner_item, listOf("全部信号", "仅强烈买入", "仅买入")
        )
        binding.spFilterSignal.setSelection(Prefs.screenSignalFilter(this).coerceIn(0, 2))
    }

    /** v2.5.0：按板块 + 信号筛选结果并刷新列表 */
    private fun applyFilter() {
        var list = resultItems
        when (binding.spFilterBoard.selectedItemPosition) {
            // 1=仅沪深主板：沪市 600/601/603/605 + 深市 000/001/002/003（排除 688/300/301/北交所8xx/4xx）
            1 -> list = list.filter {
                it.first.code.startsWith("60") || it.first.code.startsWith("00")
            }
            2 -> list = list.filter { it.first.code.startsWith("688") }
            3 -> list = list.filter {
                it.first.code.startsWith("300") || it.first.code.startsWith("301")
            }
        }
        // v2.6.3：信号筛选对所有策略生效——按快照信号词过滤（与详情页"操作信号"完全同源）
        when (binding.spFilterSignal.selectedItemPosition) {
            1 -> list = list.filter { resultSignals[it.first.code] == "强烈买入" }
            2 -> list = list.filter { resultSignals[it.first.code] == "买入" }
        }
        val newsSub = resultNews.filterKeys { code -> list.any { it.first.code == code } }
        val signalSub = resultSignals.filterKeys { code -> list.any { it.first.code == code } }
        binding.screenRecycler.adapter = ScreenAdapter(list, newsSub, signalSub) { code ->
            startActivity(
                Intent(this, StockDetailActivity::class.java).putExtra("code", code)
            )
        }
    }

    /** 自定义策略参数编辑对话框（结果存 Prefs，回调返回最新策略） */
    private fun editCustomStrategy(onSaved: (ScreenStrategy) -> Unit) {
        val ctx = this
        val saved = Prefs.customStrategy(ctx)
        val fields = linkedMapOf(
            "price_min" to "价格下限（元）",
            "price_max" to "价格上限（元）",
            "mv_min" to "总市值下限（亿）",
            "mv_max" to "总市值上限（亿）",
            "pe_max" to "PE(TTM) 上限",
            "pb_max" to "PB 上限",
            "vr_min" to "量比下限",
            "vr_max" to "量比上限",
            "turnover_min" to "换手率下限（%）",
            "turnover_max" to "换手率上限（%）",
            "change_min" to "涨跌幅下限（%）",
            "change_max" to "涨跌幅上限（%）",
            "amount_min" to "成交额下限（亿元）"
        )
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }
        val views = HashMap<String, EditText>()
        fields.forEach { (key, label) ->
            val et = EditText(ctx).apply {
                hint = label
                inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(
                    saved?.let { s ->
                        when (key) {
                            "price_min" -> s.priceMin; "price_max" -> s.priceMax
                            "mv_min" -> s.mvMin; "mv_max" -> s.mvMax
                            "pe_max" -> s.peMax; "pe_min" -> s.peMin; "pb_max" -> s.pbMax
                            "vr_min" -> s.vrMin; "vr_max" -> s.vrMax
                            "turnover_min" -> s.turnoverMin; "turnover_max" -> s.turnoverMax
                            "change_min" -> s.changeMin; "change_max" -> s.changeMax
                            "amount_min" -> s.amountMin
                            else -> null
                        }?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
                            ?: ""
                    } ?: ""
                )
            }
            views[key] = et
            container.addView(et, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 })
        }
        AlertDialog.Builder(ctx)
            .setTitle("自定义策略参数")
            .setMessage("留空表示不限制。成交额下限单位为亿元。")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存并运行") { _, _ ->
                fun d(k: String): Double? = views[k]?.text?.toString()?.trim()?.toDoubleOrNull()
                val strategy = ScreenStrategy(
                    name = "自定义策略...",
                    techWeight = 0.35,
                    priceMin = d("price_min"), priceMax = d("price_max"),
                    mvMin = d("mv_min"), mvMax = d("mv_max"),
                    peMax = d("pe_max"), peMin = d("pe_min"), pbMax = d("pb_max"),
                    vrMin = d("vr_min"), vrMax = d("vr_max"),
                    turnoverMin = d("turnover_min"), turnoverMax = d("turnover_max"),
                    changeMin = d("change_min"), changeMax = d("change_max"),
                    amountMin = d("amount_min")?.let { it * 1e8 }
                )
                Prefs.saveCustomStrategy(ctx, strategy)
                Toast.makeText(ctx, "自定义策略已保存", Toast.LENGTH_SHORT).show()
                onSaved(strategy)
            }
            .show()
    }

    /**
     * v2.6.0 自动数据源：按优先级依次尝试，全部失败抛最后异常。
     * 顺序：腾讯（免费、无需 Key）→ 东方财富 → Tushare → TickFlow
     */
    private fun fetchAuto(strategy: ScreenStrategy, onProgress: ((Int, Int) -> Unit)? = null): Pair<List<ScreenStock>, String> {
        val attempts = listOf(
            Pair("tencent", "腾讯"),
            Pair("eastmoney", "东方财富"),
            Pair("tushare", "Tushare"),
            Pair("tickflow", "TickFlow")
        )
        var last: Exception = RuntimeException("所有数据源均失败")
        for ((id, label) in attempts) {
            try {
                val list = when (id) {
                    "tencent" -> fetchTencent(onProgress)
                    "eastmoney" -> fetchEastmoney(onProgress)
                    "tushare" -> fetchTushare(onProgress)
                    else -> fetchTickflow(strategy, onProgress)
                }
                return Pair(list, label)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last
    }

    private fun runScreening(strategy: ScreenStrategy) {
        running = true
        binding.btnRunScreen.isEnabled = false
        binding.screenStatus.text = "正在获取全市场数据…"
        Thread {
            // v2.4.0：短线·今日买入走专用流程（拉日K → 快照分析 → 只保留"买入"信号）
            if (strategy.name == "短线·今日买入") {
                runShortScreening(strategy)
                return@Thread
            }
            // v2.5.0：新闻利好选股走新闻情绪打分流程
            if (strategy.name == "新闻利好选股") {
                runNewsScreening(strategy)
                return@Thread
            }
            // v2.12.6：热股榜·短线走热榜股票池 + 当日可买因子流程
            if (strategy.name == "热股榜·短线") {
                runHotScreening(strategy)
                return@Thread
            }
            // v2.12.9：中小市值·热门题材走「实时热门题材成分股 + 短线资金博弈」专用流程
            if (strategy.name == "中小市值·热门题材") {
                runThemeScreening(strategy)
                return@Thread
            }
            // 手动数据源选择：auto / eastmoney / tushare / tickflow / tencent / miaoxiang / futu
            val chosen = Prefs.screenSource(this)
            var source = when (chosen) {
                "eastmoney" -> "东方财富"
                "tushare" -> "Tushare"
                "tickflow" -> "TickFlow"
                "tencent" -> "腾讯"
                "miaoxiang" -> "MiaoXiang"
                "futu" -> "Futu"
                else -> "自动"
            }
            if (chosen == "miaoxiang" || chosen == "futu") {
                runOnUiThread {
                    binding.screenStatus.text = "「$source」暂未接入实时选股接口，已回退自动（腾讯→东财→Tushare→TickFlow）"
                    Toast.makeText(this, "$source 暂未接入选股，已回退自动", Toast.LENGTH_LONG).show()
                }
            }
            val all: List<ScreenStock>
            try {
                all = when (chosen) {
                    "eastmoney" -> fetchEastmoney()
                    "tushare" -> fetchTushare()
                    "tickflow" -> fetchTickflow(strategy)
                    "tencent" -> fetchTencent()
                    else -> {
                        // v2.6.0 自动：所有源按序尝试，腾讯优先
                        val (list, label) = fetchAuto(strategy)
                        source = label
                        list
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.screenStatus.text = "获取失败：${e.message}"
                    Toast.makeText(this, "获取失败：${e.message}", Toast.LENGTH_LONG).show()
                    running = false
                    binding.btnRunScreen.isEnabled = true
                }
                return@Thread
            }
            val filtered = Screening.applyFilters(all, strategy)
            val scored = Screening.score(filtered, strategy).take(60)

            // 保存历史选股（时间 + 策略 + 数据源 + 股票列表）
            Prefs.saveScreenHistory(
                this,
                ScreenHistory(
                    time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                    strategy = strategy.name,
                    source = source,
                    total = all.size,
                    matched = filtered.size,
                    stocks = scored.take(30).map { ScreenHistoryStock(it.first.code, it.first.name, it.second) }
                )
            )

            // 前 10 只候选检索相关新闻标题（未配置新闻源时跳过）
            val newsMap = HashMap<String, String>()
            val newsCount = minOf(10, scored.size)
            if (newsCount > 0 && NewsApi.hasAnySource(this)) {                for (i in 0 until newsCount) {
                    val st = scored[i].first
                    runOnUiThread { binding.screenStatus.text = "检索新闻 ${i + 1}/$newsCount…" }
                    try {
                        val n = NewsApi.search(this, "${st.name} ${st.code} 股票")
                        if (n.isNotEmpty()) newsMap[st.code] = n.first().title
                    } catch (e: Exception) { /* 单只失败跳过 */ }
                }
            }

            // v2.6.3：快照信号在工作线程批量生成（与详情页"操作信号"同源）；
            // 腾讯日K并发优先，全部失败（WAF 拦截等）自动回退 TickFlow 批量日K
            val signalMap = if (scored.isNotEmpty()) buildSnapshotSignals(scored) else HashMap<String, String>()

            runOnUiThread {
                binding.btnRunScreen.isEnabled = true
                running = false
                if (scored.isEmpty()) {
                    binding.screenStatus.text = "共 ${all.size} 只（$source），无符合条件股票"
                } else {
                    val newsHint = if (newsMap.isNotEmpty()) "，已参考新闻 ${newsMap.size} 只" else ""
                    binding.screenStatus.text =
                        "全市场 ${all.size} 只（$source）→ 符合条件 ${filtered.size} 只 → 显示评分前 ${scored.size}$newsHint"
                }
                resultItems = scored
                resultNews = newsMap
                resultSignals = signalMap
                applyFilter()
            }
        }.start()
    }

    /**
     * v2.4.0 短线·今日买入引擎：
     * 全市场快照 → 预筛+多因子评分 Top100 → 逐只拉日K做快照分析（Analyzer）→
     * 只保留 buySignal=买入/强烈买入 的股票，按快照综合评分排序（保证详情页快照显示"买入"）
     */
    private fun runShortScreening(strategy: ScreenStrategy) {
        val chosen = Prefs.screenSource(this)
        var source = when (chosen) {
            "eastmoney" -> "东方财富"
            "tushare" -> "Tushare"
            "tickflow" -> "TickFlow"
            "tencent" -> "腾讯"
            "miaoxiang" -> "MiaoXiang"
            "futu" -> "Futu"
            else -> "自动"
        }
        if (chosen == "miaoxiang" || chosen == "futu") {
            runOnUiThread {
                binding.screenStatus.text = "「$source」暂未接入实时选股接口，已回退自动（腾讯→东财→Tushare→TickFlow）"
                Toast.makeText(this, "$source 暂未接入选股，已回退自动", Toast.LENGTH_LONG).show()
            }
        }
        val all: List<ScreenStock>
        try {
            all = when (chosen) {
                "eastmoney" -> fetchEastmoney()
                "tushare" -> fetchTushare()
                "tickflow" -> fetchTickflow(strategy)
                "tencent" -> fetchTencent()
                else -> {
                    // v2.6.0 自动：所有源按序尝试，腾讯优先
                    val (list, label) = fetchAuto(strategy)
                    source = label
                    list
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                binding.screenStatus.text = "获取失败：${e.message}"
                Toast.makeText(this, "获取失败：${e.message}", Toast.LENGTH_LONG).show()
                running = false
                binding.btnRunScreen.isEnabled = true
            }
            return
        }

        // 预筛 + 多因子评分 → Top 100 候选（并发拉日K）
        val filtered = Screening.applyFilters(all, strategy)
        val preCand = Screening.score(filtered, strategy).take(100)
        if (preCand.isEmpty()) {
            runOnUiThread {
                binding.screenStatus.text = "全市场 ${all.size} 只（$source），预筛后无候选"
                binding.btnRunScreen.isEnabled = true
                running = false
            }
            return
        }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val futures = preCand.map { (st, _, _) ->
                executor.submit<Pair<ScreenStock, Double>> {
                    st to analyzeShortSignal(st)
                }
            }
            val shortList = mutableListOf<Triple<ScreenStock, Double, Map<String, Double>>>()
            futures.forEachIndexed { i, f ->
                try {
                    val (st, signalScore) = f.get()
                    if (signalScore > 0) {
                        shortList.add(Triple(st, signalScore, emptyMap()))
                    }
                } catch (e: Exception) {
                    // 单只分析失败跳过
                }
                val done = i + 1
                runOnUiThread { binding.screenStatus.text = "短线快照分析 $done/${futures.size}…" }
            }
            // 按快照综合评分降序，取前 30
            val scored = shortList.sortedByDescending { it.second }.take(30)

            // v2.6.1：信号标签移入专用徽标位（sSignal），sNews 位保留给新闻；文本不再带"信号："前缀
            val signalMap = HashMap<String, String>()
            for ((st, sc, _) in scored) {
                val sig = if (sc >= 80) "强烈买入" else "买入"
                signalMap[st.code] = sig
            }

            // 保存历史选股（策略=短线·今日买入，matched=快照买入数）
            Prefs.saveScreenHistory(
                this,
                ScreenHistory(
                    time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                    strategy = strategy.name,
                    source = source,
                    total = all.size,
                    matched = scored.size,
                    stocks = scored.take(30).map { ScreenHistoryStock(it.first.code, it.first.name, it.second) }
                )
            )

            runOnUiThread {
                binding.btnRunScreen.isEnabled = true
                running = false
                binding.screenStatus.text =
                    "全市场 ${all.size} 只（$source）→ 预筛 ${filtered.size} 只 → 快照信号买入 ${scored.size} 只"
                resultItems = scored
                resultNews = emptyMap()
                resultSignals = signalMap
                applyFilter()
            }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * v2.12.6 热股榜·短线引擎：
     * 热股榜多源合并池（前 500，实测约 300 余只）→ 东方财富全市场快照取对应行情 →
     * 当日可买过滤（剔除停牌/无成交/涨跌停/ST）+ 策略因子过滤 →
     * 因子分 × 0.65 + 热度分 × 0.35 排序，取前 30
     */
    private fun runHotScreening(strategy: ScreenStrategy) {
        runOnUiThread { binding.screenStatus.text = "正在读取今日热股榜…" }
        val pool: List<HotRankApi.HotStock> = try {
            HotRankApi.fetchPool()
        } catch (e: Exception) {
            emptyList()
        }
        if (pool.isEmpty()) {
            runOnUiThread {
                binding.screenStatus.text = "热股榜暂不可用，请稍后重试"
                binding.btnRunScreen.isEnabled = true
                running = false
            }
            return
        }
        runOnUiThread { binding.screenStatus.text = "热股榜 ${pool.size} 只，正在获取行情快照…" }
        // v2.12.7：行情快照双源兜底——东财不可用时自动切腾讯全市场快照，避免直接提示无数据
        var all: List<ScreenStock> = emptyList()
        var srcLabel = "东方财富"
        try {
            all = fetchEastmoney()
        } catch (e: Exception) {
            all = emptyList()
        }
        if (all.isEmpty()) {
            runOnUiThread { binding.screenStatus.text = "东财快照暂不可用，切换腾讯源重试…" }
            try {
                all = fetchTencent()
                srcLabel = "腾讯"
            } catch (e: Exception) {
                all = emptyList()
            }
        }
        if (all.isEmpty()) {
            runOnUiThread {
                binding.screenStatus.text = "行情快照暂不可用，请稍后重试"
                binding.btnRunScreen.isEnabled = true
                running = false
            }
            return
        }

        // 热榜股票 → 快照（按代码对齐，北交所等未收录的自动跳过）
        val byCode = all.associateBy { it.code }
        val universe = pool.mapNotNull { h -> byCode[h.code]?.let { h to it } }

        // 当日可买过滤 + 策略因子过滤
        val filtered = mutableListOf<Pair<HotRankApi.HotStock, ScreenStock>>()
        universe.forEach { (h, st) ->
            if (st.price <= 0.0 || st.amount <= 0.0) return@forEach          // 停牌 / 当日无成交
            if (st.changePct >= 9.5 || st.changePct <= -9.5) return@forEach  // 已封涨停买不进 / 跌停不接
            if (st.name.contains("ST") || st.name.contains("退")) return@forEach
            if (Screening.applyFilters(listOf(st), strategy).isEmpty()) return@forEach
            filtered.add(h to st)
        }

        // 因子评分（与其它策略同一套 6 因子模型）
        val scored = Screening.score(filtered.map { it.second }, strategy)
        val hotByCode = filtered.associate { it.second.code to it.first }
        val poolSize = pool.size.coerceAtLeast(1)

        // 热度分：榜单越靠前越高（第 1 名 100 分，末位 0 分）
        val combined = scored.map { (st, factorScore, parts) ->
            val h = hotByCode[st.code]
            val heat = if (h == null) 0.0
            else 100.0 - (h.rank - 1).toDouble() / poolSize * 100.0
            Triple(st, factorScore * 0.65 + heat * 0.35, parts)
        }.sortedByDescending { it.second }.take(30)

        val signalMap = HashMap<String, String>()
        for ((st, sc, _) in combined) signalMap[st.code] = if (sc >= 80) "强烈买入" else "买入"

        Prefs.saveScreenHistory(
            this,
            ScreenHistory(
                time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                strategy = strategy.name,
                source = "热股榜+$srcLabel",
                total = pool.size,
                matched = filtered.size,
                stocks = combined.take(30).map { ScreenHistoryStock(it.first.code, it.first.name, it.second) }
            )
        )

        runOnUiThread {
            binding.btnRunScreen.isEnabled = true
            running = false
            binding.screenStatus.text = if (combined.isEmpty()) {
                "热股榜 ${pool.size} 只 → 今日无满足短线条件的标的"
            } else {
                "热股榜 ${pool.size} 只 → 当天可买 ${filtered.size} 只 → 显示前 ${combined.size}"
            }
            resultItems = combined
            resultNews = emptyMap()
            resultSignals = signalMap
            applyFilter()
        }
    }

    /**
     * v2.12.9 中小市值·热门题材引擎（v2.12.10 换口径）：
     * 近 30 日热门题材（市场共识 + 交易活跃度 + 话题度，运行时现取，非固定名单）→ 该题材成分股 →
     * 全市场快照对齐 → 中小市值 + 短线资金博弈过滤 →
     * 因子分 × 0.78 + 题材热度分 × 0.22 排序，取前 30。
     * 题材取数不可用时退化为「中小市值 + 资金博弈」全市场筛选，并在状态栏说明口径。
     */
    private fun runThemeScreening(strategy: ScreenStrategy) {
        runOnUiThread { binding.screenStatus.text = "正在评估近 30 日热门题材（共识 · 活跃度 · 话题度）…" }
        val uni: ThemeHotApi.Universe? = try {
            ThemeHotApi.buildUniverse(applicationContext, 6)
        } catch (e: Exception) {
            null
        }

        runOnUiThread { binding.screenStatus.text = "正在获取全市场行情快照…" }
        var all: List<ScreenStock> = emptyList()
        var srcLabel = "东方财富"
        try {
            all = fetchEastmoney()
        } catch (e: Exception) {
            all = emptyList()
        }
        if (all.isEmpty()) {
            runOnUiThread { binding.screenStatus.text = "东财快照暂不可用，切换腾讯源重试…" }
            try {
                all = fetchTencent()
                srcLabel = "腾讯"
            } catch (e: Exception) {
                all = emptyList()
            }
        }
        if (all.isEmpty()) {
            runOnUiThread {
                binding.screenStatus.text = "行情快照暂不可用，请稍后重试"
                binding.btnRunScreen.isEnabled = true
                running = false
            }
            return
        }

        // 题材成分股 → 行情快照对齐（题材榜暂不可用时退化为全市场，只按中小市值 + 资金博弈筛选）
        val themeByCode = uni?.themeByCode ?: emptyMap()
        val themeHeat = uni?.themeHeat ?: emptyMap()
        val universe: List<ScreenStock> = if (themeByCode.isEmpty()) {
            all
        } else {
            all.filter { themeByCode.containsKey(ThemeHotApi.normalize(it.code)) }
        }

        val filtered = Screening.applyFilters(universe, strategy)
        val scored = Screening.score(filtered, strategy)

        // 题材热度分：直接用题材综合分（共识 45% + 活跃 30% + 话题 25%），个股分 × 0.78 + 热度 × 0.22
        val combined = scored.map { (st, factorScore, parts) ->
            val themeName = themeByCode[ThemeHotApi.normalize(st.code)] ?: ""
            val heat = themeHeat[themeName] ?: 40.0
            Triple(st, factorScore * 0.78 + heat * 0.22, parts)
        }.sortedByDescending { it.second }.take(30)

        val signalMap = HashMap<String, String>()
        for ((st, sc, _) in combined) signalMap[st.code] = if (sc >= 80) "强烈买入" else "买入"

        Prefs.saveScreenHistory(
            this,
            ScreenHistory(
                time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                strategy = strategy.name,
                source = "热门题材+$srcLabel",
                total = universe.size,
                matched = filtered.size,
                stocks = combined.take(30).map { ScreenHistoryStock(it.first.code, it.first.name, it.second) }
            )
        )

        val themeText = uni?.themes?.take(3)?.joinToString(" · ") { it.name }.orEmpty()
        val themeCount = uni?.themes?.size ?: 0
        val topicNote = uni?.topicNote.orEmpty()
        val topicUnavailable = uni == null
        runOnUiThread {
            binding.btnRunScreen.isEnabled = true
            running = false
            val head = when {
                topicUnavailable && combined.isEmpty() ->
                    "题材取数暂不可用 → 按中小市值 + 资金博弈筛选：暂无符合条件的标的"
                topicUnavailable ->
                    "题材取数暂不可用 → 按中小市值 + 资金博弈筛选：${universe.size} 只 → 显示前 ${combined.size}"
                combined.isEmpty() ->
                    "近30日热门题材 $themeCount 个（$themeText）→ 成分股 ${universe.size} 只 → 暂无符合条件的标的"
                else ->
                    "近30日热门题材 $themeCount 个（$themeText）→ 成分股 ${universe.size} 只 → 符合条件 ${filtered.size} 只 → 显示前 ${combined.size}"
            }
            binding.screenStatus.text = if (topicUnavailable) {
                head
            } else {
                "$head\n口径：30日共识 45% · 活跃 30% · 话题 25%（$topicNote）"
            }
            resultItems = combined
            resultNews = emptyMap()
            resultSignals = signalMap
            applyFilter()
        }
    }

    /**
     * v2.5.0 新闻利好选股引擎：
     * 全市场快照 → 预筛+评分 Top60 → 逐只检索新闻 → 关键词情绪打分 →
     * 只保留利好/强利好（情绪分>0），按情绪分排序；结果行显示新闻情绪标签
     */
    private fun runNewsScreening(strategy: ScreenStrategy) {
        if (!NewsApi.hasAnySource(this)) {
            runOnUiThread {
                binding.btnRunScreen.isEnabled = true
                running = false
                binding.screenStatus.text = "未配置新闻检索 API，请在 设置 → 新闻检索 API 中填写（如 Bocha/Tavily）"
                Toast.makeText(this, "未配置新闻检索 API", Toast.LENGTH_LONG).show()
            }
            return
        }
        val chosen = Prefs.screenSource(this)
        var source = when (chosen) {
            "eastmoney" -> "东方财富"; "tushare" -> "Tushare"; "tickflow" -> "TickFlow"
            "tencent" -> "腾讯"; "miaoxiang" -> "MiaoXiang"; "futu" -> "Futu"
            else -> "自动"
        }
        if (chosen == "miaoxiang" || chosen == "futu") {
            runOnUiThread {
                binding.screenStatus.text = "「$source」暂未接入实时选股接口，已回退自动（腾讯→东财→Tushare→TickFlow）"
                Toast.makeText(this, "$source 暂未接入选股，已回退自动", Toast.LENGTH_LONG).show()
            }
        }
        val all: List<ScreenStock>
        try {
            all = when (chosen) {
                "eastmoney" -> fetchEastmoney()
                "tushare" -> fetchTushare()
                "tickflow" -> fetchTickflow(strategy)
                "tencent" -> fetchTencent()
                else -> {
                    // v2.6.0 自动：所有源按序尝试，腾讯优先
                    val (list, label) = fetchAuto(strategy)
                    source = label
                    list
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                binding.screenStatus.text = "获取失败：${e.message}"
                Toast.makeText(this, "获取失败：${e.message}", Toast.LENGTH_LONG).show()
                running = false
                binding.btnRunScreen.isEnabled = true
            }
            return
        }

        val filtered = Screening.applyFilters(all, strategy)
        val preCand = Screening.score(filtered, strategy).take(60)
        if (preCand.isEmpty()) {
            runOnUiThread {
                binding.screenStatus.text = "全市场 ${all.size} 只（$source），预筛后无候选"
                binding.btnRunScreen.isEnabled = true
                running = false
            }
            return
        }

        val executor = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val futures = preCand.map { (st, score, parts) ->
                executor.submit<Pair<Triple<ScreenStock, Double, Map<String, Double>>, Int>> {
                    val sc = try {
                        val news = NewsApi.search(this, "${st.name} ${st.code} 股票")
                        NewsSentiment.score(news).score
                    } catch (e: Exception) {
                        0
                    }
                    Triple(st, score, parts) to sc
                }
            }
            val newsList = mutableListOf<Pair<Triple<ScreenStock, Double, Map<String, Double>>, Int>>()
            val newsMap = HashMap<String, String>()
            futures.forEachIndexed { i, f ->
                try {
                    val (item, sc) = f.get()
                    if (sc > 0) {
                        newsList.add(item to sc)
                        newsMap[item.first.code] =
                            if (sc >= 3) "新闻情绪：强利好(+$sc)" else "新闻情绪：利好(+$sc)"
                    }
                } catch (e: Exception) {
                    // 单只检索失败跳过
                }
                val done = i + 1
                runOnUiThread { binding.screenStatus.text = "新闻情绪分析 $done/${futures.size}…" }
            }
            val scored = newsList
                .sortedByDescending { it.second }
                .take(60)
                .map { it.first }

            Prefs.saveScreenHistory(
                this,
                ScreenHistory(
                    time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                    strategy = strategy.name,
                    source = source,
                    total = all.size,
                    matched = scored.size,
                    stocks = scored.take(30).map { ScreenHistoryStock(it.first.code, it.first.name, it.second) }
                )
            )

            // v2.6.3：新闻利好选股同样用快照信号（与详情页一致），工作线程批量生成，
            // 腾讯日K失败自动回退 TickFlow 批量日K；所有策略信号筛选生效
            val signalMap = if (scored.isNotEmpty()) buildSnapshotSignals(scored) else HashMap<String, String>()

            runOnUiThread {
                binding.btnRunScreen.isEnabled = true
                running = false
                binding.screenStatus.text =
                    "全市场 ${all.size} 只（$source）→ 预筛 ${filtered.size} 只 → 新闻利好 ${scored.size} 只"
                resultItems = scored
                resultNews = newsMap
                resultSignals = signalMap
                applyFilter()
            }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * 单只短线信号：拉日K → 快照分析 → 仅 BUY/STRONG_BUY 返回快照分，其余返回 0（剔除）
     */
    /**
     * v2.6.3：批量生成快照信号（与详情页"操作信号"完全同源）。
     * 6 线程并发，每只股票内部按 腾讯主域 → 腾讯备用域 → 东方财富 三级回退拉日K。
     * 返回 Map<code, 中文信号>，单只失败跳过（该股无信号徽标）。
     */
    private fun buildSnapshotSignals(scored: List<Triple<ScreenStock, Double, Map<String, Double>>>): Map<String, String> {
        val signalMap = HashMap<String, String>()
        if (scored.isEmpty()) return signalMap

        val exec = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val futures = scored.map { (st, _, _) ->
                exec.submit<Pair<ScreenStock, String?>> { st to analyzeSnapshotSignal(st) }
            }
            futures.forEachIndexed { i, f ->
                try {
                    val (st, sig) = f.get()
                    if (!sig.isNullOrBlank()) signalMap[st.code] = sig
                } catch (e: Exception) { /* 单只失败跳过 */ }
                val done = i + 1
                runOnUiThread { binding.screenStatus.text = "快照信号分析 $done/${futures.size}…" }
            }
        } finally {
            exec.shutdown()
        }
        return signalMap
    }

    /**
     * v2.6.3：拉日K做快照分析，返回与详情页"操作信号"完全同源的中文信号
     * （强烈买入/买入/观望/卖出/强烈卖出）；失败返回 null（该股不显示信号徽标）
     */
    private fun analyzeSnapshotSignal(st: ScreenStock): String? {
        return try {
            val bars = fetchSnapshotKline(st.code)
            val r = Analyzer.analyze(bars, st.code)
            Analyzer.SIGNAL_ZH[r.buySignal] ?: r.buySignal
        } catch (e: Exception) { null }
    }

    /** v2.6.3：快照日K回退链：腾讯主域 → 腾讯备用域 → 新浪（东财接口在部分网络不可达已剔除） */
    private fun fetchSnapshotKline(code: String): List<KLineBar> {
        return try {
            TencentApi.fetchDailyKline(code, 120)
        } catch (e: Exception) {
            try {
                TencentApi.fetchDailyKlineAlt(code, 120)
            } catch (e2: Exception) {
                SinaApi.fetchDailyKline(code, 120)
            }
        }
    }

    private fun analyzeShortSignal(st: ScreenStock): Double {
        try {
            val bars = TencentApi.fetchDailyKline(st.code, 120)
            val r = Analyzer.analyze(bars, st.code)
            return if (r.buySignal == "BUY" || r.buySignal == "STRONG_BUY") r.signalScore.toDouble() else 0.0
        } catch (e: Exception) {
            return 0.0
        }
    }

    private fun fetchEastmoney(onProgress: ((Int, Int) -> Unit)? = null): List<ScreenStock> =
        EastmoneyApi.fetchAllStocks { done, total ->
            if (onProgress != null) onProgress(done, total)
            else runOnUiThread {
                binding.screenStatus.text = "获取市场数据 $done/$total…"
            }
        }

    private fun fetchTushare(onProgress: ((Int, Int) -> Unit)? = null): List<ScreenStock> {
        val token = Prefs.tushareToken(this)
        if (token.isBlank()) throw RuntimeException("未配置 Tushare Token（设置 → Tushare Token）")
        return TushareApi.fetchAllStocks(this, token) { done, total ->
            if (onProgress != null) onProgress(done, total)
            else runOnUiThread {
                binding.screenStatus.text = "获取 Tushare 数据 $done/$total…"
            }
        }
    }

    /**
     * 腾讯自选股选股（v2.3.0，免费，无需 Key）：rank 接口翻页拉全市场，字段全就绪
     */
    private fun fetchTencent(onProgress: ((Int, Int) -> Unit)? = null): List<ScreenStock> {
        return TencentScreenApi.fetchAllStocks { done, total ->
            if (onProgress != null) onProgress(done, total)
            else runOnUiThread { binding.screenStatus.text = "获取腾讯数据 $done/$total…" }
        }
    }

    /**
     * TickFlow 选股（v2.2.0 接入）：
     * 全市场实时行情 + 股本/财务分批 → 粗筛候选（量比暂不判断）→
     * 候选 klines 补算真实量比与 60 日动量 → 返回增强后的候选集
     */
    private fun fetchTickflow(strategy: ScreenStrategy, onProgress: ((Int, Int) -> Unit)? = null): List<ScreenStock> {
        val key = Prefs.tickflowKey(this)
        if (key.isBlank()) throw RuntimeException("未配置 TickFlow API Key（设置 → TickFlow API Key）")
        val full = TickFlowApi.fetchAllStocks(key) { done, total ->
            if (onProgress != null) onProgress(done, total)
            else runOnUiThread { binding.screenStatus.text = "获取 TickFlow 数据 $done/$total…" }
        }
        // 粗筛：量比暂不判断（真实量比需候选补算），并限制候选规模
        val pre = Screening.applyFilters(full, strategy.copy(vrMin = null, vrMax = null)).take(600)
        if (pre.isEmpty()) return emptyList()
        return TickFlowApi.enrichMomentum(key, pre) { done, total ->
            if (onProgress != null) onProgress(done, total)
            else runOnUiThread { binding.screenStatus.text = "TickFlow 补算量比/动量 $done/$total…" }
        }
    }

    /**
     * v2.12.26 碗口反弹新因子引擎：
     * 全市场快照 → 当日可买 + 快照预筛 → 因子分 Top100 → 并发拉日K做碗口判定
     * （KDJ 低位超卖 / 放量阳线 / 最大量非阴量 / 趋势未破位）→ 命中按因子分降序取前 30，
     * 条目复用与原引擎相同的图3样式；信号标签 ≥80 强烈买入，其余买入。
     */
    private fun runBowlScreening() {
        bowlRunning = true
        binding.btnRunBowl.isEnabled = false
        binding.bowlStatus.text = "正在获取全市场数据…"
        val strategy = BowlRebound.prefilter()
        Thread {
            val all: List<ScreenStock>
            var source = "自动"
            try {
                val (list, label) = fetchAuto(strategy) { done, total ->
                    runOnUiThread { binding.bowlStatus.text = "获取行情 $done/$total…" }
                }
                all = list
                source = label
            } catch (e: Exception) {
                runOnUiThread {
                    binding.bowlStatus.text = "获取失败：${e.message}"
                    binding.btnRunBowl.isEnabled = true
                    bowlRunning = false
                }
                return@Thread
            }

            // 当日可买过滤（停牌/无成交/ST/退市/涨跌停）+ 快照层预筛
            val tradable = all.filter { BowlRebound.tradable(it) }
            val filtered = Screening.applyFilters(tradable, strategy)
            if (filtered.isEmpty()) {
                runOnUiThread {
                    binding.bowlStatus.text = "全市场 ${all.size} 只（$source），预筛后无候选"
                    binding.btnRunBowl.isEnabled = true
                    bowlRunning = false
                }
                return@Thread
            }

            // 快照多因子分排序取 Top100 进入日K碗口判定
            val preCand = Screening.score(filtered, strategy).take(100)
            if (preCand.isEmpty()) {
                runOnUiThread {
                    binding.bowlStatus.text = "全市场 ${all.size} 只（$source），预筛后无候选"
                    binding.btnRunBowl.isEnabled = true
                    bowlRunning = false
                }
                return@Thread
            }

            val executor = java.util.concurrent.Executors.newFixedThreadPool(6)
            try {
                val futures = preCand.map { (st, _, _) ->
                    executor.submit<Pair<ScreenStock, Double>?> {
                        try {
                            val bars = fetchSnapshotKline(st.code)
                            val hit = BowlRebound.evaluate(bars)
                            if (hit != null) st to hit.score else null
                        } catch (e: Exception) { null }
                    }
                }
                val hitList = mutableListOf<Triple<ScreenStock, Double, Map<String, Double>>>()
                futures.forEachIndexed { i, f ->
                    try {
                        val pair = f.get()
                        if (pair != null) hitList.add(Triple(pair.first, pair.second, emptyMap()))
                    } catch (e: Exception) { /* 单只失败跳过 */ }
                    val done = i + 1
                    runOnUiThread { binding.bowlStatus.text = "碗口反弹日K判定 $done/${futures.size}…" }
                }
                val scored = hitList.sortedByDescending { it.second }.take(30)

                val signalMap = HashMap<String, String>()
                for ((st, sc, _) in scored) {
                    signalMap[st.code] = if (sc >= 80) "强烈买入" else "买入"
                }

                Prefs.saveScreenHistory(
                    this,
                    ScreenHistory(
                        time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                        strategy = "碗口反弹",
                        source = "新因子引擎+$source",
                        total = all.size,
                        matched = scored.size,
                        stocks = scored.take(30).map { ScreenHistoryStock(it.first.code, it.first.name, it.second) }
                    )
                )

                runOnUiThread {
                    binding.btnRunBowl.isEnabled = true
                    bowlRunning = false
                    binding.bowlStatus.text = if (scored.isEmpty()) {
                        "全市场 ${all.size} 只（$source）→ 预筛 ${filtered.size} 只 → 无命中碗口反弹形态"
                    } else {
                        "全市场 ${all.size} 只（$source）→ 预筛 ${filtered.size} 只 → 命中 ${scored.size} 只"
                    }
                    bowlItems = scored
                    bowlSignals = signalMap
                    renderBowl()
                }
            } finally {
                executor.shutdown()
            }
        }.start()
    }

    /** 渲染碗口反弹独立结果列表（条目样式与原引擎图3一致） */
    private fun renderBowl() {
        binding.bowlRecycler.adapter = ScreenAdapter(bowlItems, emptyMap(), bowlSignals) { code ->
            startActivity(Intent(this, StockDetailActivity::class.java).putExtra("code", code))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
