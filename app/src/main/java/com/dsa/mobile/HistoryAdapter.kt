package com.dsa.mobile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dsa.mobile.databinding.ItemHistoryBinding

/**
 * 历史报告适配器
 * 普通模式：单击打开报告，长按进入多选
 * 多选模式：单击切换勾选，操作栏删除
 */
class HistoryAdapter(
    items: List<HistoryReport>,
    private val onClick: (HistoryReport) -> Unit,
    private val onLongClick: (HistoryReport) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

    private var items: List<HistoryReport> = items
    private val selected = HashSet<Int>()          // 选中的 position
    private var selectionMode = false

    class Holder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    /** v2.9.6 筛选/搜索/排序后刷新列表（保留当前多选模式，清空已选） */
    fun submit(newItems: List<HistoryReport>) {
        items = newItems
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0, false)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val h = items[position]
        val b = holder.binding
        b.hName.text = "${h.name}（${h.code}）"
        // 小字标注：日期 + 具体时间（时分秒）+ 操作信号
        b.hDate.text = buildString {
            append(h.date)
            if (h.time.isNotBlank()) {
                append(" ")
                append(h.time)
            }
            append(" · ")
            append(h.signal)
            // v2.12.1：已收藏的报告在列表里带星标，便于一眼识别
            if (h.favorite) append("  ★")
        }
        b.hScore.text = h.score.toString()

        val isSel = selected.contains(position)
        if (selectionMode) {
            b.hCheck.visibility = android.view.View.VISIBLE
            b.hCheck.isChecked = isSel
            b.root.alpha = if (isSel) 1f else 0.55f
        } else {
            b.hCheck.visibility = android.view.View.GONE
            b.root.alpha = 1f
        }

        b.root.setOnClickListener {
            if (selectionMode) {
                toggle(position, h)
            } else {
                onClick(h)
            }
        }
        ThemeManager.applyTagAlpha(b.root, b.root.context)
        b.root.setOnLongClickListener {
            if (!selectionMode) {
                enterSelectionMode()
                onLongClick(h)
            }
            toggle(position, h)
            true
        }
    }

    /** 进入多选模式（清空选中） */
    fun enterSelectionMode() {
        if (selectionMode) return
        selectionMode = true
        selected.clear()
        notifyDataSetChanged()
    }

    fun isSelectionMode(): Boolean = selectionMode

    /** 切换勾选，回调当前选中数量 */
    fun toggle(position: Int, h: HistoryReport) {
        if (selected.contains(position)) selected.remove(position) else selected.add(position)
        notifyItemChanged(position)
        onSelectionChanged(selected.size, selected.size == items.size)
    }

    fun selectAll() {
        selected.clear()
        for (i in items.indices) selected.add(i)
        notifyDataSetChanged()
        onSelectionChanged(selected.size, true)
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0, false)
    }

    /** 退出多选 */
    fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0, false)
    }

    /** 已选中的记录列表 */
    fun selectedItems(): List<HistoryReport> = selected.sorted().map { items[it] }

    var onSelectionChanged: (Int, Boolean) -> Unit = { _, _ -> }
}
