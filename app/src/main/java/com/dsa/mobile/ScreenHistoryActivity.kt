package com.dsa.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dsa.mobile.databinding.ActivityScreenHistoryBinding
import com.dsa.mobile.databinding.ItemScreenHistoryBinding

/**
 * 历史选股（v2.5.0）：
 * 普通模式：单击展开股票列表，长按进入多选
 * 多选模式：单击切换勾选，底部操作栏单选/多选删除（替代原"清除 7 天前"按钮）
 */
class ScreenHistoryActivity : BaseActivity() {

    private lateinit var binding: ActivityScreenHistoryBinding
    private var adapter: ScreenHistoryAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "历史选股"

        binding.historyRecycler.layoutManager = LinearLayoutManager(this)
        binding.shBtnDelete.setOnClickListener { confirmDeleteSelected() }
        binding.shBtnCancel.setOnClickListener { exitSelection() }
        binding.shSelectAll.setOnClickListener {
            val a = adapter ?: return@setOnClickListener
            if (binding.shSelectAll.isChecked) a.selectAll() else a.clearSelection()
        }
        render()
    }

    private fun render() {
        val list = Prefs.screenHistory(this)
        binding.historyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        adapter = ScreenHistoryAdapter(
            list,
            onClick = { h -> showStocks(h) },
            onLongClick = { enterSelection() }
        ).apply {
            onSelectionChanged = { count, all ->
                binding.shSelectedCount.text = "已选 $count 条"
                binding.shSelectAll.isChecked = all
                binding.shSelectedCount.visibility = if (count > 0) View.VISIBLE else View.INVISIBLE
            }
        }
        binding.historyRecycler.adapter = adapter
    }

    private fun enterSelection() {
        adapter?.enterSelectionMode()
        binding.historyBar.visibility = View.VISIBLE
        binding.shSelectedCount.text = "已选 0 条"
        binding.shSelectedCount.visibility = View.INVISIBLE
    }

    private fun exitSelection() {
        adapter?.exitSelectionMode()
        binding.historyBar.visibility = View.GONE
        binding.shSelectAll.isChecked = false
    }

    private fun confirmDeleteSelected() {
        val a = adapter ?: return
        val sel = a.selectedItems()
        if (sel.isEmpty()) {
            android.widget.Toast.makeText(this, "请先选择要删除的记录", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除历史选股")
            .setMessage("确定删除选中的 ${sel.size} 条记录？删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                Prefs.deleteScreenHistory(this, sel)
                exitSelection()
                render()
                android.widget.Toast.makeText(this, "已删除 ${sel.size} 条", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showStocks(h: ScreenHistory) {
        if (h.stocks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("${h.strategy} · ${h.time}")
                .setMessage("无符合条件的股票")
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        val labels = h.stocks.mapIndexed { i, s ->
            "${i + 1}. ${s.name + boardSuffix(s.code)}（${s.code}）  评分 ${s.score.toInt()}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${h.strategy} · ${h.time}（${h.stocks.size} 只）")
            .setItems(labels) { _, which ->
                val s = h.stocks[which]
                startActivity(
                    Intent(this, StockDetailActivity::class.java).putExtra("code", s.code)
                )
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** 历史选股适配器（长按进入多选，底部栏删除） */
    private class ScreenHistoryAdapter(
        private val items: List<ScreenHistory>,
        private val onClick: (ScreenHistory) -> Unit,
        private val onLongClick: (ScreenHistory) -> Unit
    ) : RecyclerView.Adapter<ScreenHistoryAdapter.Holder>() {

        private val selected = HashSet<Int>()
        private var selectionMode = false

        class Holder(val binding: ItemScreenHistoryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemScreenHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val h = items[position]
            val b = holder.binding
            b.shStrategy.text = h.strategy
            b.shCount.text = "${h.matched} 只符合"
            b.shMeta.text = "时间：${h.time}    数据源：${h.source}    全市场 ${h.total} 只"

            val isSel = selected.contains(position)
            if (selectionMode) {
                b.shCheck.visibility = View.VISIBLE
                b.shCheck.isChecked = isSel
                b.root.alpha = if (isSel) 1f else 0.55f
            } else {
                b.shCheck.visibility = View.GONE
                b.root.alpha = 1f
            }

            b.root.setOnClickListener {
                if (selectionMode) toggle(position, h) else onClick(h)
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

        fun enterSelectionMode() {
            if (selectionMode) return
            selectionMode = true
            selected.clear()
            notifyDataSetChanged()
        }

        fun toggle(position: Int, h: ScreenHistory) {
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

        fun exitSelectionMode() {
            selectionMode = false
            selected.clear()
            notifyDataSetChanged()
            onSelectionChanged(0, false)
        }

        fun selectedItems(): List<ScreenHistory> = selected.sorted().map { items[it] }

        var onSelectionChanged: (Int, Boolean) -> Unit = { _, _ -> }
    }
}
