package com.dsa.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dsa.mobile.databinding.ItemStockBinding

/** 自选股列表适配器 */
class StockAdapter(
    private val items: MutableList<Pair<String, Quote?>>,
    private val onClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit
) : RecyclerView.Adapter<StockAdapter.Holder>() {

    class Holder(val binding: ItemStockBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemStockBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (code, q) = items[position]
        val b = holder.binding
        b.tvCode.text = code
        if (q == null) {
            b.tvName.text = "加载中…"
            b.tvPrice.text = "--"
            b.tvChange.text = "--"
            b.tvExtra.text = ""
            b.tvScore.text = ""
        } else {
            b.tvName.text = q.name + boardSuffix(code)
            b.tvPrice.text = String.format("%.2f", q.price)
            val color = if (q.changePct >= 0) 0xFFE53935.toInt() else 0xFF43A047.toInt()
            b.tvChange.text = String.format("%+.2f%%", q.changePct)
            b.tvChange.setTextColor(color)
            b.tvPrice.setTextColor(color)
            b.tvExtra.text = "量比 ${String.format("%.2f", q.volumeRatio)}  换手 ${String.format("%.2f", q.turnover)}%"
            b.tvScore.text = ""
        }
        b.root.setOnClickListener { onClick(code) }
        b.root.setOnLongClickListener { onLongClick(code); true }
        ThemeManager.applyTagAlpha(b.root, b.root.context)
        ThemeManager.applyTagAlpha(b.tvChange, b.root.context)
    }
}
