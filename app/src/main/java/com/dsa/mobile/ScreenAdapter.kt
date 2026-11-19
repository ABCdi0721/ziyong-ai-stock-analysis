package com.dsa.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dsa.mobile.databinding.ItemScreenBinding

/**
 * 选股结果适配器（支持每只股票显示信号标签、相关新闻标题与可展开的因子分项明细）。
 * v2.6.3：信号与详情页"操作信号"同源（强烈买入/买入/观望/卖出/强烈卖出），所有策略生效。
 * v2.12.35：点击"分项"展开六因子 + 风险/成本惩罚明细；缺失因子标中性，不编造数值。
 */
class ScreenAdapter(
    private val items: List<Triple<ScreenStock, Double, Map<String, Double>>>,
    private val news: Map<String, String> = emptyMap(),
    private val signals: Map<String, String> = emptyMap(),
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<ScreenAdapter.Holder>() {

    /** 已展开明细的股票代码集合 */
    private val expanded = HashSet<String>()

    /** 六因子中文名（展示顺序固定） */
    private val factorZh = linkedMapOf(
        "value" to "估值", "liquidity" to "流动", "momentum" to "动量",
        "activity" to "活跃", "stability" to "稳定", "reversal" to "反转"
    )

    /** 风险 / 成本惩罚分项 */
    private val penaltyZh = linkedMapOf(
        "risk" to "风险惩罚", "cost" to "成本惩罚"
    )

    class Holder(val binding: ItemScreenBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemScreenBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (st, score, parts) = items[position]
        val b = holder.binding
        b.sName.text = st.name + boardSuffix(st.code)
        b.sCode.text = st.code
        b.sPrice.text = String.format("%.2f", st.price)
        val color = if (st.changePct >= 0) 0xFFE53935.toInt() else 0xFF43A047.toInt()
        b.sPrice.setTextColor(color)
        b.sChange.text = String.format("%+.2f%%", st.changePct)
        b.sChange.setTextColor(color)
        b.sScore.text = score.toInt().toString()

        // v2.6.3：信号标签与详情页"操作信号"同源（强烈买入红/买入橙/观望灰/卖出等其他灰），无信号隐藏
        val sig = signals[st.code]
        if (sig.isNullOrBlank()) {
            b.sSignal.visibility = View.GONE
        } else {
            b.sSignal.visibility = View.VISIBLE
            b.sSignal.text = sig
            b.sSignal.setTextColor(
                when (sig) {
                    "强烈买入" -> 0xFFE53935.toInt()
                    "买入" -> 0xFFFB8C00.toInt()
                    else -> 0xFF9E9E9E.toInt()
                }
            )
        }

        val title = news[st.code]
        if (title.isNullOrBlank()) {
            b.sNews.visibility = View.GONE
        } else {
            b.sNews.visibility = View.VISIBLE
            b.sNews.text = title
        }

        // v2.12.35：分项明细展开 / 收起
        val isOpen = st.code in expanded
        b.sDetail.visibility = if (isOpen) View.VISIBLE else View.GONE
        if (isOpen) b.sDetail.text = detailText(st, parts)
        b.sToggle.setOnClickListener {
            if (st.code in expanded) expanded.remove(st.code) else expanded.add(st.code)
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
        }
        b.root.setOnClickListener { onClick(st.code) }
        ThemeManager.applyTagAlpha(b.root, b.root.context)
    }

    /** 组装展开明细：六因子一行、风险/成本一行、可成交性标注一行；缺失项不显示 */
    private fun detailText(st: ScreenStock, parts: Map<String, Double>): String {
        if (parts.isEmpty()) return "分项数据不足（该结果由快照信号生成，无截面因子分项）"
        val factorLine = factorZh.mapNotNull { (k, zh) ->
            parts[k]?.let { "$zh ${it.toInt()}" }
        }.joinToString(" · ")
        val penaltyLine = penaltyZh.mapNotNull { (k, zh) ->
            parts[k]?.let { "$zh ${it.toInt()}" }
        }.joinToString(" · ")
        val guard = MarketGuard.label(st)
        return buildList {
            if (factorLine.isNotBlank()) add(factorLine)
            if (penaltyLine.isNotBlank()) add(penaltyLine)
            if (guard.isNotBlank()) add(guard)
        }.joinToString("\n").ifBlank { "分项数据不足" }
    }
}
