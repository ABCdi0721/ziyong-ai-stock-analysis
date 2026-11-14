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
import com.dsa.mobile.databinding.FragmentWatchlistBinding

class WatchlistFragment : Fragment() {

    private var binding: FragmentWatchlistBinding? = null
    private val items = mutableListOf<Pair<String, Quote?>>()
    private lateinit var adapter: StockAdapter
    private var refreshing = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val b = FragmentWatchlistBinding.inflate(inflater, container, false)
        binding = b
        adapter = StockAdapter(items,
            onClick = { code -> openDetail(code) },
            onLongClick = { code -> confirmRemove(code) }
        )
        b.watchlistRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.watchlistRecycler.adapter = adapter
        // v2.11.0：+ 弹窗从"只输代码"升级为按名称/代码搜索后点选添加
        b.fabAdd.setOnClickListener {
            StockSearchDialog.show(requireContext(), "添加自选股") { code, name ->
                if (Prefs.inWatchlist(requireContext(), code)) {
                    Toast.makeText(requireContext(), "$name 已在自选列表", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.addWatch(requireContext(), code)
                    Toast.makeText(requireContext(), "已添加 $name（$code）", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
        }
        return b.root
    }

    override fun onResume() {
        super.onResume()
        binding?.let { ThemeManager.applyTagAlphaTree(it.root, requireContext()) }
        refresh()
    }

    private fun refresh() {
        if (refreshing) return
        val codes = Prefs.watchlist(requireContext())
        val b = binding ?: return
        b.emptyView.visibility = if (codes.isEmpty()) View.VISIBLE else View.GONE
        items.clear()
        codes.forEach { items.add(Pair(it, null)) }
        adapter.notifyDataSetChanged()
        if (codes.isEmpty()) return

        refreshing = true
        Thread {
            val quotes = try {
                TencentApi.fetchQuotes(codes)
            } catch (e: Exception) {
                emptyMap()
            }
            activity?.runOnUiThread {
                refreshing = false
                if (!isAdded) return@runOnUiThread
                for (i in items.indices) {
                    val q = quotes[items[i].first]
                    if (q != null) items[i] = Pair(items[i].first, q)
                }
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun confirmRemove(code: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除自选股")
            .setMessage("确定将 $code 移出自选股吗？")
            .setPositiveButton("删除") { _, _ ->
                Prefs.removeWatch(requireContext(), code)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openDetail(code: String) {
        startActivity(Intent(requireContext(), StockDetailActivity::class.java).putExtra("code", code))
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
