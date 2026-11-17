package com.dsa.mobile

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.dsa.mobile.databinding.FragmentScreenBinding
import com.dsa.mobile.databinding.ItemRankBinding

/** 选股页签：搜索股票入口 + 跳转选股 Activity + 排行榜 */
class ScreenHolderFragment : Fragment() {

    private var binding: FragmentScreenBinding? = null

    private var currentType = RankBoardApi.Type.HOT
    private var currentSource = RankBoardApi.Source.EM
    private var loading = false

    /** v2.12.25：自动刷新档位（与大盘页同一套六档口径） */
    private val refreshModes = listOf(
        "entry" to "每次进入",
        "30" to "30分钟",
        "60" to "1小时",
        "120" to "2小时",
        "180" to "3小时",
        "never" to "从不"
    )

    private var typeViews: Map<RankBoardApi.Type, TextView> = emptyMap()
    private var rankList: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val b = FragmentScreenBinding.inflate(inflater, container, false)
        binding = b
        b.btnOpenScreen.setOnClickListener {
            startActivity(Intent(requireContext(), ScreeningActivity::class.java))
        }
        // v2.11.0：按名称/代码搜索股票 → 点选进详情页
        b.searchCard.setOnClickListener {
            StockSearchDialog.show(requireContext(), "搜索股票") { code, _ ->
                startActivity(
                    Intent(requireContext(), StockDetailActivity::class.java)
                        .putExtra("code", code)
                )
            }
        }
        setupRank()
        return b.root
    }

    // ------------------------- 排行榜 -------------------------

    private fun setupRank() {
        val b = binding ?: return
        rankList = b.rankList

        typeViews = mapOf(
            RankBoardApi.Type.HOT to b.rankTypeHot,
            RankBoardApi.Type.LONGHU to b.rankTypeLonghu,
            RankBoardApi.Type.SECTOR to b.rankTypeSector,
            RankBoardApi.Type.ETF to b.rankTypeEtf
        )

        typeViews.forEach { (type, v) ->
            v.setOnClickListener {
                if (currentType != type) {
                    currentType = type
                    loadRank(force = true)
                }
            }
        }

        // v2.12.25：恢复上次选择的数据源（持久化，杀进程重进不回退默认）
        currentSource = runCatching {
            RankBoardApi.Source.valueOf(Prefs.rankSource(requireContext()))
        }.getOrDefault(RankBoardApi.Source.EM)

        // 数据源下拉：同花顺 / 东方财富 / 腾讯；收起态用透明背景项，弹出项保留卡片底色
        val sources = RankBoardApi.Source.values()
        val srcAdapter = ArrayAdapter(
            requireContext(), R.layout.spinner_rank_item, sources.map { it.label }
        )
        srcAdapter.setDropDownViewResource(R.layout.spinner_item)
        b.rankSrcSpinner.adapter = srcAdapter
        b.rankSrcSpinner.setSelection(currentSource.ordinal)
        b.rankSrcSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val s = sources.getOrNull(position) ?: return
                if (s != currentSource) {
                    currentSource = s
                    Prefs.saveRankSource(requireContext(), s.name)
                    loadRank(force = true)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 自动刷新档位胶囊 + 手动刷新按钮
        updateRefreshModeLabel()
        b.rankRefreshMode.setOnClickListener { showRefreshModeDialog() }
        b.rankRefreshBtn.setOnClickListener { loadRank(force = true) }

        refreshPills()
        loadRank()
    }

    private fun modeLabel(mode: String): String =
        refreshModes.firstOrNull { it.first == mode }?.second ?: "每次进入"

    /** 顶部档位胶囊显示当前档位 */
    private fun updateRefreshModeLabel() {
        val b = binding ?: return
        b.rankRefreshMode.text = modeLabel(Prefs.rankRefreshMode(requireContext()))
    }

    /** 点按档位胶囊：弹单选对话框，选中即写入持久化配置 */
    private fun showRefreshModeDialog() {
        val ctx = context ?: return
        val current = Prefs.rankRefreshMode(ctx)
        val idx = refreshModes.indexOfFirst { it.first == current }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("排行榜自动刷新")
            .setSingleChoiceItems(refreshModes.map { it.second }.toTypedArray(), idx) { dialog, which ->
                Prefs.saveRankRefreshMode(ctx, refreshModes[which].first)
                updateRefreshModeLabel()
                dialog.dismiss()
                // 新档位若已到期，立即联网刷新；未到期保持当前数据
                if (!loading && shouldAutoRefresh()) loadRank()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 按设置项判断现在是否该联网刷新：never 只允许首次初始化一次 */
    private fun shouldAutoRefresh(): Boolean {
        val ctx = context ?: return true
        val mode = Prefs.rankRefreshMode(ctx)
        val last = Prefs.rankLastRefresh(ctx)
        return when (mode) {
            "never" -> last <= 0L
            "entry" -> true
            else -> {
                val interval = (mode.toLongOrNull() ?: 0L) * 60_000L
                interval <= 0L || last <= 0L || System.currentTimeMillis() - last >= interval
            }
        }
    }

    private fun refreshPills() {
        val primary = ThemeManager.primaryColor(requireContext())
        val text2 = text2Color()
        typeViews.forEach { (t, v) ->
            v.setTextColor(if (t == currentType) primary else text2)
            v.setTypeface(v.typeface, if (t == currentType) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun text2Color(): Int {
        val a = requireContext().theme.obtainStyledAttributes(intArrayOf(R.attr.dsaText2))
        val c = a.getColor(0, 0xFF8A8A8E.toInt())
        a.recycle()
        return c
    }

    private fun loadRank(force: Boolean = false) {
        val b = binding ?: return
        if (loading && !force) return
        loading = true
        b.rankRefreshSpin.visibility = View.VISIBLE
        refreshPills()
        b.rankStatus.text = "正在读取榜单…"
        val type = currentType
        val source = currentSource
        Thread {
            var list: List<RankBoardApi.RankItem> = emptyList()
            var err: String? = null
            try {
                list = RankBoardApi.fetch(type, source)
            } catch (e: Exception) {
                err = e.message ?: "加载失败"
            }
            val ctx = context
            activity?.runOnUiThread {
                val bb = binding ?: return@runOnUiThread
                loading = false
                bb.rankRefreshSpin.visibility = View.GONE
                if (ctx == null) return@runOnUiThread
                if (err == null) Prefs.setRankLastRefresh(ctx, System.currentTimeMillis())
                renderRank(list, err)
            }
        }.start()
    }

    private fun renderRank(list: List<RankBoardApi.RankItem>, err: String? = null) {
        val b = binding ?: return
        val container = rankList ?: return
        container.removeAllViews()
        b.rankStatus.text = when {
            err != null -> "榜单加载失败：$err，点右上角刷新重试"
            list.isEmpty() -> "暂无榜单数据，请稍后重试"
            else -> ""
        }
        val inflater = LayoutInflater.from(requireContext())
        list.forEach { item ->
            val ib = ItemRankBinding.inflate(inflater, container, false)
            bindRankItem(ib, item)
            ib.root.setOnClickListener { openDetail(item) }
            container.addView(ib.root)
        }
    }

    private fun bindRankItem(ib: ItemRankBinding, item: RankBoardApi.RankItem) {
        ib.rankRank.text = item.rank.toString()
        ib.rankRank.setTextColor(
            if (item.rank <= 3) 0xFFE53935.toInt() else text2Color()
        )
        val suffix = if (
            currentType == RankBoardApi.Type.HOT ||
            currentType == RankBoardApi.Type.LONGHU
        ) boardSuffix(item.code) else ""
        ib.rankName.text = item.name + suffix
        ib.rankSub.text = item.sub

        val color = if (item.changePct >= 0) 0xFFE53935.toInt() else 0xFF43A047.toInt()

        // 现价（无现价的板块榜隐藏）
        if (item.price > 0) {
            ib.rankPrice.visibility = View.VISIBLE
            ib.rankPrice.text = String.format("%.2f", item.price)
            ib.rankPrice.setTextColor(color)
        } else {
            ib.rankPrice.visibility = View.GONE
        }
        ib.rankChange.text = String.format("%+.2f%%", item.changePct)
        ib.rankChange.setTextColor(color)

        // 技术面信号（无信号隐藏）
        if (item.signal.isBlank()) {
            ib.rankSignal.visibility = View.GONE
        } else {
            ib.rankSignal.visibility = View.VISIBLE
            ib.rankSignal.text = item.signal
            ib.rankSignal.setTextColor(
                when (item.signal) {
                    "强烈买入" -> 0xFFE53935.toInt()
                    "买入" -> 0xFFFB8C00.toInt()
                    else -> 0xFF9E9E9E.toInt()
                }
            )
        }

        // 综合评分（0 分隐藏）
        if (item.score > 0) {
            ib.rankScore.visibility = View.VISIBLE
            ib.rankScore.text = item.score.toString()
        } else {
            ib.rankScore.visibility = View.GONE
        }

        ThemeManager.applyTagAlpha(ib.root, ib.root.context)
    }

    /** 个股榜（热股/龙虎）与 ETF 可点进详情；板块为 BK 代码，暂无详情页，不跳转 */
    private fun openDetail(item: RankBoardApi.RankItem) {
        val clickable = currentType == RankBoardApi.Type.HOT ||
            currentType == RankBoardApi.Type.LONGHU ||
            currentType == RankBoardApi.Type.ETF
        if (!clickable) return
        if (!Regex("^\\d{6}$").matches(item.code)) return
        startActivity(
            Intent(requireContext(), StockDetailActivity::class.java)
                .putExtra("code", item.code)
        )
    }

    override fun onResume() {
        super.onResume()
        val b = binding ?: return
        ThemeManager.applyTagAlphaTree(b.root, requireContext())
        updateRefreshModeLabel()
        // v2.12.25：是否自动刷新由档位决定；未到时间只保留上次数据，手动刷新随时可用
        if (!loading && shouldAutoRefresh()) loadRank()
    }

    override fun onDestroyView() {
        binding = null
        rankList = null
        super.onDestroyView()
    }
}
