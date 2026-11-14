package com.dsa.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsa.mobile.databinding.FragmentHistoryBinding

/**
 * v2.12.1 我的收藏：
 * 只列收藏过的 AI 分析报告（复用历史报告的数据与卡片样式），
 * 点开可看全文，长按进入多选可批量取消收藏（报告本身仍保留在历史里）。
 */
class FavoriteFragment : Fragment() {

    private var binding: FragmentHistoryBinding? = null
    private var adapter: HistoryAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val b = FragmentHistoryBinding.inflate(inflater, container, false)
        binding = b
        // 收藏页不需要搜索/筛选/排序，隐藏整条工具条
        b.hToolbar.visibility = View.GONE
        b.hBtnDelete.text = "取消收藏"
        b.hBtnCancel.setOnClickListener { exitSelection() }
        b.hBtnDelete.setOnClickListener { confirmUnfavorite() }
        b.hSelectAll.setOnClickListener {
            val a = adapter ?: return@setOnClickListener
            if (b.hSelectAll.isChecked) a.selectAll() else a.clearSelection()
        }
        return b.root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val b = binding ?: return
        val list = Prefs.favorites(requireContext())
        b.hResultCount.text = if (list.isEmpty()) "" else "共 ${list.size} 份收藏"
        b.historyEmpty.text = if (list.isEmpty()) {
            "还没有收藏的报告\n在报告页点「☆ 收藏」即可加入"
        } else ""
        b.historyEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val a = ensureAdapter()
        a.submit(list)
        if (b.historyRecycler.adapter !== a) {
            b.historyRecycler.layoutManager = LinearLayoutManager(requireContext())
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
                b.hSelectedCount.text = "已选 $count 份"
                b.hSelectAll.isChecked = allSelected
                b.hSelectedCount.visibility =
                    if (count > 0) View.VISIBLE else View.INVISIBLE
            }
        }
        adapter = a
        return a
    }

    private fun enterSelection() {
        val b = binding ?: return
        adapter?.enterSelectionMode()
        b.historyBar.visibility = View.VISIBLE
        b.hSelectedCount.text = "已选 0 份"
        b.hSelectedCount.visibility = View.INVISIBLE
    }

    private fun exitSelection() {
        val b = binding ?: return
        adapter?.exitSelectionMode()
        b.historyBar.visibility = View.GONE
        b.hSelectAll.isChecked = false
    }

    private fun confirmUnfavorite() {
        val sel = adapter?.selectedItems().orEmpty()
        if (sel.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择要取消收藏的报告", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("取消收藏")
            .setMessage("确定取消收藏选中的 ${sel.size} 份报告？报告仍会保留在历史记录中。")
            .setPositiveButton("取消收藏") { _, _ -> unfavorite(sel) }
            .setNegativeButton("再想想", null)
            .show()
    }

    private fun unfavorite(sel: List<HistoryReport>) {
        val ctx = requireContext()
        sel.forEach { Prefs.setFavorite(ctx, it.id, false) }
        val n = sel.size
        exitSelection()
        load()
        Toast.makeText(ctx, "已取消收藏 $n 份", Toast.LENGTH_SHORT).show()
    }

    private fun openReport(h: HistoryReport) {
        startActivity(
            Intent(requireContext(), ReportActivity::class.java)
                .putExtra("code", h.code)
                .putExtra("name", h.name)
                .putExtra("score", h.score)
                .putExtra("signal", h.signal)
                .putExtra("date", h.date)
                .putExtra("time", h.time)
                .putExtra("report", h.report)
                .putExtra("readonly", true)
                .putExtra("history_id", h.id)
        )
    }

    override fun onDestroyView() {
        binding = null
        adapter = null
        super.onDestroyView()
    }
}
