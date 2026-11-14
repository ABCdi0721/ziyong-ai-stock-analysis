package com.dsa.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.dsa.mobile.databinding.FragmentScreenBinding

/** 选股页签：搜索股票入口 + 跳转选股 Activity */
class ScreenHolderFragment : Fragment() {

    private var binding: FragmentScreenBinding? = null

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
        return b.root
    }

    override fun onResume() {
        super.onResume()
        val b = binding ?: return
        ThemeManager.applyTagAlphaTree(b.root, requireContext())
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
