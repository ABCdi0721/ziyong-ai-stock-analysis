package com.dsa.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dsa.mobile.databinding.ItemScreenBinding

/**
 * 选股结果适配器（支持每只股票显示信号标签与相关新闻标题）。
 * v2.6.3：信号与详情页"操作信号"同源（强烈买入/买入/观望/卖出/强烈卖出），所有策略生效。
 */
class ScreenAdapter(
    private val items: List<Triple<ScreenStock, Double, Map<String, Double>>>,
    private val news: Map<String, String> = emptyMap(),
    private val signals: Map<String, String> = emptyMap(),
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<ScreenAdapter.Holder>() {

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
        b.root.setOnClickListener { onClick(st.code) }
        ThemeManager.applyTagAlpha(b.root, b.root.context)
    }
}
