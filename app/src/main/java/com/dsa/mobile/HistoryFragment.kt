package com.dsa.mobile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.dsa.mobile.databinding.FragmentHistoryBinding
import java.time.LocalDate

/**
 * 历史报告页
 * v2.9.6：同一只股票每次分析都独立保留；支持关键词搜索、时间范围筛选、
 * 按时间/评分/信号排序（正序与反序），长按多选删除保持不变。
 */
class HistoryFragment : Fragment() {

    private var binding: FragmentHistoryBinding? = null
    private var adapter: HistoryAdapter? = null

    private var all: List<HistoryReport> = emptyList()
    private var query = ""
    private var days = 0                 // 0=全部时间，1=今天，7=近7天，30=近30天
    private var sortKey = 0              // 0=时间，1=评分，2=信号
    private var desc = true              // true=降序（新→旧 / 高分→低分 / 买入→卖出）

    private val dayLabels = listOf("全部时间", "今天", "近 7 天", "近 30 天")
    private val dayValues = listOf(0, 1, 7, 30)
    private val sortLabels = listOf("按时间", "按评分", "按信号")
    private val orderLabels = listOf("降序", "升序")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val b = FragmentHistoryBinding.inflate(inflater, container, false)
        binding = b
        setupBar(b)
        setupFilters(b)
        return b.root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    // ---------- 顶部工具条 ----------

    private fun setupFilters(b: FragmentHistoryBinding) {
        val ctx = requireContext()
        b.hSpTime.adapter = ArrayAdapter(ctx, R.layout.spinner_item_small, dayLabels)
        b.hSpSort.adapter = ArrayAdapter(ctx, R.layout.spinner_item_small, sortLabels)
        b.hSpOrder.adapter = ArrayAdapter(ctx, R.layout.spinner_item_small, orderLabels)

        b.hSpTime.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                days = dayValues.getOrElse(pos) { 0 }
                apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.hSpSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                sortKey = pos
                apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.hSpOrder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                desc = pos == 0
                apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.hSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                apply()
            }
        })
    }

    private fun setupBar(b: FragmentHistoryBinding) {
        b.hBtnDelete.setOnClickListener { confirmDeleteSelected() }
        b.hBtnCancel.setOnClickListener { exitSelection() }
        b.hSelectAll.setOnClickListener {
            val a = adapter ?: return@setOnClickListener
            if (b.hSelectAll.isChecked) a.selectAll() else a.clearSelection()
        }
    }

    // ---------- 数据 ----------

    private fun load() {
        all = Prefs.history(requireContext())
        apply()
    }

    private fun apply() {
        val b = binding ?: return

        var list = all

        // ① 关键词：股票名称或代码
        if (query.isNotEmpty()) {
            val q = query.lowercase()
            list = list.filter {
                it.name.lowercase().contains(q) || it.code.lowercase().contains(q)
            }
        }

        // ② 时间范围
        if (days > 0) {
            val today = LocalDate.now()
            val from = today.minusDays((days - 1).toLong())
            list = list.filter {
                val d = parseDate(it.date) ?: return@filter false
                !d.isBefore(from) && !d.isAfter(today)
            }
        }

        // ③ 排序：先按时间稳定排序，再按主键排序，保证同值内时间次序不变
        val byTime = list.sortedWith(
            compareBy<HistoryReport> { it.date }
                .thenBy { it.time }
                .thenBy { it.id }
        )
        val sorted = when (sortKey) {
            1 -> byTime.sortedBy { it.score }
            2 -> byTime.sortedBy { signalRank(it.signal) }
            else -> byTime
        }
        val finalList = if (desc) sorted.reversed() else sorted

        // 结果提示
        b.hResultCount.text = when {
            all.isEmpty() -> ""
            finalList.size == all.size -> "共 ${all.size} 条"
            else -> "筛选出 ${finalList.size} / ${all.size} 条"
        }
        if (all.isEmpty()) {
            b.historyEmpty.text = "暂无历史报告，去自选页生成一份吧"
        } else {
            b.historyEmpty.text = "没有匹配的记录，换个关键词或时间范围试试"
        }
        b.historyEmpty.visibility = if (finalList.isEmpty()) View.VISIBLE else View.GONE

        val a = ensureAdapter()
        a.submit(finalList)
        if (b.historyRecycler.adapter !== a) {
            b.historyRecycler.layoutManager =
                androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            b.historyRecycler.adapter = a
        }
    }

    private fun ensureAdapter(): HistoryAdapter {
        adapter?.let { return it }
        val b = binding
        val a = HistoryAdapter(
            items = emptyList(),
            onClick = { h -> openReport(h) },
            onLongClick = { enterSelection() }
        )
        a.onSelectionChanged = { count, allSelected ->
            if (b != null) {
                b.hSelectedCount.text = "已选 $count 条"
                b.hSelectAll.isChecked = allSelected
                b.hSelectedCount.visibility =
                    if (count > 0) View.VISIBLE else View.INVISIBLE
            }
        }
        adapter = a
        return a
    }

    private fun parseDate(s: String): LocalDate? = try {
        LocalDate.parse(s)
    } catch (e: Exception) {
        null
    }

    /** 信号强弱：强烈卖出 1 … 强烈买入 5；观望/未知记 3 */
    private fun signalRank(s: String): Int = when {
        s.contains("强烈卖出") -> 1
        s.contains("强烈买入") -> 5
        s.contains("卖出") || s.contains("看空") || s.contains("悲观") -> 2
        s.contains("买入") || s.contains("看多") || s.contains("乐观") -> 4
        s.contains("观望") || s.contains("中性") -> 3
        else -> 3
    }

    // ---------- 多选删除 ----------

    private fun enterSelection() {
        val b = binding ?: return
        adapter?.enterSelectionMode()
        b.historyBar.visibility = View.VISIBLE
        b.hSelectedCount.text = "已选 0 条"
        b.hSelectedCount.visibility = View.INVISIBLE
    }

    private fun exitSelection() {
        val b = binding ?: return
        adapter?.exitSelectionMode()
        b.historyBar.visibility = View.GONE
        b.hSelectAll.isChecked = false
    }

    private fun confirmDeleteSelected() {
        val a = adapter ?: return
        val sel = a.selectedItems()
        if (sel.isEmpty()) {
            android.widget.Toast.makeText(
                requireContext(), "请先选择要删除的报告", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("删除历史报告")
            .setMessage("确定删除选中的 ${sel.size} 条记录？删除后不可恢复。")
            .setPositiveButton("删除") { _, _ -> deleteItems(sel) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteItems(sel: List<HistoryReport>) {
        val ctx = requireContext()
        val ids = sel.map { it.id }.toSet()
        val remaining = Prefs.history(ctx).filter { it.id !in ids }
        Prefs.replaceHistory(ctx, remaining)
        exitSelection()
        load()
    }

    // ---------- 打开报告 ----------

    private fun openReport(h: HistoryReport) {
        val intent = android.content.Intent(requireContext(), ReportActivity::class.java)
            .putExtra("code", h.code)
            .putExtra("name", h.name)
            .putExtra("score", h.score)
            .putExtra("signal", h.signal)
            .putExtra("date", h.date)
            .putExtra("time", h.time)
            .putExtra("report", h.report)
            .putExtra("readonly", true)
            .putExtra("history_id", h.id)
        startActivity(intent)
    }

    override fun onDestroyView() {
        binding = null
        adapter = null
        super.onDestroyView()
    }
}
