package com.dsa.mobile

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dsa.mobile.databinding.DialogSearchStockBinding
import com.dsa.mobile.databinding.ItemSearchHitBinding

/**
 * v2.11.0 通用股票搜索对话框：输入名称/代码/拼音，实时出 A 股候选，点选即回调。
 * 选股页：点选 → 进详情页；自选页：点选 → 加入自选。
 */
object StockSearchDialog {

    fun show(ctx: Context, title: String, onPick: (code: String, name: String) -> Unit) {
        val b = DialogSearchStockBinding.inflate(LayoutInflater.from(ctx))
        val adapter = HitAdapter()
        b.searchResults.layoutManager = LinearLayoutManager(ctx)
        b.searchResults.adapter = adapter

        var worker: Thread? = null
        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                worker?.interrupt()
                if (q.isEmpty()) {
                    adapter.submit(emptyList())
                    b.searchStatus.text = "输入名称或代码，如 五粮液 / 000858"
                    return
                }
                b.searchStatus.text = "搜索中…"
                worker = Thread {
                    val hits = try {
                        StockSearchApi.search(q)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    (ctx as? Activity)?.runOnUiThread {
                        if (q != b.searchInput.text.toString().trim()) return@runOnUiThread // 已被新输入取代
                        adapter.submit(hits)
                        b.searchStatus.text =
                            if (hits.isEmpty()) "未找到匹配的 A 股" else "匹配 ${hits.size} 只 · 点选"
                    }
                }.also { it.start() }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(b.root)
            .setNegativeButton("取消", null)
            .create()
        adapter.onPick = { hit ->
            dialog.dismiss()
            onPick(hit.code, hit.name)
        }
        dialog.show()
    }

    private class HitAdapter : RecyclerView.Adapter<HitAdapter.VH>() {
        var onPick: ((StockSearchApi.Hit) -> Unit)? = null
        private val data = mutableListOf<StockSearchApi.Hit>()

        fun submit(list: List<StockSearchApi.Hit>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(val ib: ItemSearchHitBinding) : RecyclerView.ViewHolder(ib.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemSearchHitBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val hit = data[position]
            h.ib.hitName.text = hit.name + boardSuffix(hit.code)
            h.ib.hitCode.text = hit.code
            h.ib.root.setOnClickListener { onPick?.invoke(hit) }
        }
    }
}
