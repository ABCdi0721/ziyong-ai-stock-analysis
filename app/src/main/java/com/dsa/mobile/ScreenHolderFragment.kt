package com.dsa.mobile

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    private var typeViews: Map<RankBoardApi.Type, TextView> = emptyMap()
    private var sourceViews: Map<RankBoardApi.Source, TextView> = emptyMap()
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
        sourceViews = mapOf(
            RankBoardApi.Source.THS to b.rankSrcThs,
            RankBoardApi.Source.EM to b.rankSrcEm,
            RankBoardApi.Source.TX to b.rankSrcTx
        )

        typeViews.forEach { (type, v) ->
            v.setOnClickListener {
                if (currentType != type) {
                    currentType = type
                    loadRank()
                }
            }
        }
        sourceViews.forEach { (source, v) ->
            v.setOnClickListener {
                if (currentSource != source) {
                    currentSource = source
                    loadRank()
                }
            }
        }

        refreshPills()
        loadRank()
    }

    private fun refreshPills() {
        val primary = ThemeManager.primaryColor(requireContext())
        val text2 = text2Color()
        typeViews.forEach { (t, v) ->
            v.setTextColor(if (t == currentType) primary else text2)
            v.setTypeface(v.typeface, if (t == currentType) Typeface.BOLD else Typeface.NORMAL)
        }
        sourceViews.forEach { (s, v) ->
            v.setTextColor(if (s == currentSource) primary else text2)
            v.setTypeface(v.typeface, if (s == currentSource) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun text2Color(): Int {
        val a = requireContext().theme.obtainStyledAttributes(intArrayOf(R.attr.dsaText2))
        val c = a.getColor(0, 0xFF8A8A8E.toInt())
        a.recycle()
        return c
    }

    private fun loadRank() {
        val b = binding ?: return
        refreshPills()
        b.rankStatus.text = "正在读取榜单…"
        val type = currentType
        val source = currentSource
        Thread {
            val list = try {
                RankBoardApi.fetch(type, source)
            } catch (e: Exception) {
                emptyList()
            }
            val ctx = context
            activity?.runOnUiThread {
                val bb = binding ?: return@runOnUiThread
                if (ctx == null) return@runOnUiThread
                renderRank(list)
            }
        }.start()
    }

    private fun renderRank(list: List<RankBoardApi.RankItem>) {
        val b = binding ?: return
        val container = rankList ?: return
        container.removeAllViews()
        b.rankStatus.text = if (list.isEmpty()) "暂无榜单数据，请稍后重试" else ""
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
        ib.rankChange.text = String.format("%+.2f%%", item.changePct)
        ib.rankChange.setTextColor(color)
        ThemeManager.applyTagAlpha(ib.root, ib.root.context)
    }

    /** 个股榜单（热股/龙虎榜）点进详情；板块、ETF 暂无详情页，不跳转 */
    private fun openDetail(item: RankBoardApi.RankItem) {
        val isStock = currentType == RankBoardApi.Type.HOT ||
            currentType == RankBoardApi.Type.LONGHU
        if (!isStock) return
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
    }

    override fun onDestroyView() {
        binding = null
        rankList = null
        super.onDestroyView()
    }
}
