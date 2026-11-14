package com.dsa.mobile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.dsa.mobile.databinding.FragmentMarketBinding

/** v2.9.0+ 大盘分析 Tab（复刻用户截图：三维一体 / 警示卡 / 三盏灯 / 板块强弱） */
class MarketFragment : Fragment() {

    private var binding: FragmentMarketBinding? = null
    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val b = FragmentMarketBinding.inflate(inflater, container, false)
        binding = b

        b.refreshBtn.setOnClickListener { refresh() }
        b.sectorRefresh.setOnClickListener { refresh() }
        // v2.12.9：顶部刷新档位胶囊（与设置页同一项配置，点按可切换）
        b.tvRefreshMode.setOnClickListener { showRefreshModeDialog() }
        updateRefreshModeLabel()
        applyHeroCard(b)
        ThemeManager.applyTagAlphaTree(b.root, requireContext())

        return b.root
    }

    /**
     * v2.12.6：修复浅色主题下「三维一体」卡内白字看不清的问题。
     * 深色主题保持 v2.12.4 的玻璃渐变卡；浅色主题下浅卡 + 低透明度渐变会让白字几乎不可见，
     * 因此改为深主色实底渐变（白字对比度约 7:1 以上），描边同步加深。
     */
    private fun applyHeroCard(b: FragmentMarketBinding) {
        val cardBg = themeColor(R.attr.dsaCard)
        val isLight = androidx.core.graphics.ColorUtils.calculateLuminance(cardBg) > 0.5
        if (!isLight) {
            b.heroCard.setBackgroundResource(R.drawable.bg_primary_round)
            return
        }
        val primary = themeColor(R.attr.dsaPrimary)
        val top = darken(primary, 0.58f)
        val bottom = darken(primary, 0.40f)
        val d = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), darken(primary, 0.32f))
        }
        b.heroCard.background = d
    }

    private fun darken(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255)
    )

    override fun onResume() {
        super.onResume()
        binding?.let { ThemeManager.applyTagAlphaTree(it.root, requireContext()) }
        // v2.12.9：从设置页返回时同步顶部档位标签（双向同步：设置页改 → 这里跟着变）
        updateRefreshModeLabel()
        // v2.12.8：是否自动刷新由「设置 → 大盘页自动刷新」决定；未到时间只保留上次数据，手动刷新随时可用
        if (!loading && shouldAutoRefresh()) refresh()
    }

    /** v2.12.9：档位取值（与设置页 rg_market_refresh 的六档一一对应） */
    private val refreshModes = listOf(
        "entry" to "每次进入",
        "30" to "30分钟",
        "60" to "1小时",
        "120" to "2小时",
        "180" to "3小时",
        "never" to "从不"
    )

    private fun modeLabel(mode: String): String =
        refreshModes.firstOrNull { it.first == mode }?.second ?: "每次进入"

    /** 顶部胶囊显示当前档位 */
    private fun updateRefreshModeLabel() {
        val b = binding ?: return
        val ctx = context ?: return
        b.tvRefreshMode.text = "${modeLabel(Prefs.marketRefreshMode(ctx))} ▾"
    }

    /** 点击档位胶囊：弹单选对话框，选中即写入同一份配置（设置页同步生效） */
    private fun showRefreshModeDialog() {
        val ctx = context ?: return
        val current = Prefs.marketRefreshMode(ctx)
        val idx = refreshModes.indexOfFirst { it.first == current }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("大盘页自动刷新")
            .setSingleChoiceItems(refreshModes.map { it.second }.toTypedArray(), idx) { dialog, which ->
                Prefs.setMarketRefreshMode(ctx, refreshModes[which].first)
                updateRefreshModeLabel()
                dialog.dismiss()
                // 新档位若已到期，立即联网刷新；未到期保持当前数据（不自动更新以省流量）
                if (!loading && shouldAutoRefresh()) refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 按设置项判断现在是否该联网刷新：never 只允许首次初始化一次 */
    private fun shouldAutoRefresh(): Boolean {
        val ctx = context ?: return true
        val mode = Prefs.marketRefreshMode(ctx)
        val last = Prefs.marketLastRefresh(ctx)
        return when (mode) {
            "never" -> last <= 0L
            "entry" -> true
            else -> {
                val interval = (mode.toLongOrNull() ?: 0L) * 60_000L
                interval <= 0L || last <= 0L || System.currentTimeMillis() - last >= interval
            }
        }
    }

    private fun refresh() {
        val b = binding ?: return
        loading = true
        b.refreshSpin.visibility = View.VISIBLE
        b.loadError.visibility = View.GONE
        // 圆点：加载中灰色
        b.netDot.setBackgroundColor(Color.parseColor("#5F6368"))
        b.netStatus.text = "加载中…"

        Thread {
            var data: MarketApi.MarketData? = null
            var err: String? = null
            try {
                data = MarketApi.load()
            } catch (e: Exception) {
                err = e.message ?: "加载失败"
            }
            activity?.runOnUiThread {
                loading = false
                if (!isAdded) return@runOnUiThread
                val bb = binding ?: return@runOnUiThread
                bb.refreshSpin.visibility = View.GONE
                if (data != null) {
                    Prefs.setMarketLastRefresh(requireContext(), System.currentTimeMillis())
                    bb.netDot.setBackgroundColor(Color.parseColor("#34A853")) // 成功绿
                    render(data)
                } else {
                    bb.netDot.setBackgroundColor(Color.parseColor("#D93025")) // 失败红
                    bb.netStatus.text = "加载失败"
                    bb.loadError.visibility = View.VISIBLE
                    bb.loadError.text = "大盘数据加载失败：$err\n点击右上角刷新重试"
                }
            }
        }.start()
    }

    private fun render(d: MarketApi.MarketData) {
        val b = binding ?: return

        b.netStatus.text = "已联网 ${d.elapsedMs}ms"
        b.dataBadge.text = "联网数据·${d.updateTime}"

        b.strategyText.text = d.strategy
        b.tacticText.text = d.tactic
        b.practiceText.text = d.practice
        b.riskText.text = d.risk

        b.indexName.text = "上证"
        b.indexPrice.text = String.format("%.2f", d.indexPrice)
        b.indexChange.text = String.format("%+.2f%%", d.indexChangePct)
        // v2.9.2：蓝卡底上用亮红/亮绿，避免深绿在蓝底上看不清（图1反馈）
        b.indexChange.setTextColor(if (d.indexChangePct >= 0) CHG_UP_ON_BLUE else CHG_DOWN_ON_BLUE)

        b.warnLine1.text = d.warnLine1
        b.daoLine.text = d.daoLine

        b.lightsBadge.text = "${d.lightsOn}/3"
        setLight(b.light1, d.lightBelowMa20, "跌破MA20")
        setLight(b.light2, d.lightWeak60, "60日转弱")
        setLight(b.light3, d.lightBelowMa60, "跌破MA60")

        b.upTitle.text = "领涨行业（${d.upSectors.size}/${d.totalSectors} 个）"
        b.downTitle.text = "领跌行业（${d.downSectors.size} 个）"
        b.sectorSource.text = "数据源：东方财富行业板块·${d.updateTime} 更新"

        b.upSectors.removeAllViews()
        b.downSectors.removeAllViews()
        val maxAbs = (d.upSectors.map { kotlin.math.abs(it.changePct) } +
            d.downSectors.map { kotlin.math.abs(it.changePct) }).maxOrNull() ?: 1.0
        d.upSectors.forEach { b.upSectors.addView(buildSectorRow(it, true, maxAbs)) }
        d.downSectors.forEach { b.downSectors.addView(buildSectorRow(it, false, maxAbs)) }
    }

    private fun setLight(v: TextView, on: Boolean, name: String) {
        v.text = if (on) "$name\n✓亮" else "$name\n—"
        v.setBackgroundResource(if (on) R.drawable.bg_light_on else R.drawable.bg_light_round)
        v.setTextColor(if (on) DOWN_GREEN else themeColor(ATTR_TEXT2))
    }

    private fun buildSectorRow(s: MarketApi.Sector, isUp: Boolean, maxAbs: Double): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameView = TextView(ctx).apply {
            text = s.name
            textSize = 12f
            setTextColor(themeColor(ATTR_TEXT))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.4f)
        }
        row.addView(nameView)

        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, dp(12), 3.2f).apply { marginStart = dp(6); marginEnd = dp(6) }
        }
        val ratio = (kotlin.math.abs(s.changePct) / maxAbs).coerceIn(0.08, 1.0)
        val bg = View(ctx).apply {
            setBackgroundColor(themeColor(ATTR_SURFACE2))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (1 - ratio).toFloat())
        }
        val fg = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, ratio.toFloat())
            setBackgroundColor(if (isUp) UP_RED else DOWN_GREEN)
            alpha = 0.85f
        }
        track.addView(bg)
        track.addView(fg)
        row.addView(track)

        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.2f)
        }
        right.addView(TextView(ctx).apply {
            text = String.format("%+.2f%%", s.changePct)
            textSize = 12f
            setTextColor(if (isUp) UP_RED else DOWN_GREEN)
            paint.isFakeBoldText = true
        })
        right.addView(TextView(ctx).apply {
            text = "涨${s.upCount}/跌${s.downCount}"
            textSize = 10f
            setTextColor(themeColor(ATTR_TEXT2))
        })
        row.addView(right)

        return row
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ---- 主题属性取值（App 自定义 dsa* 属性，深浅色自适应）----
    private val ATTR_PRIMARY = R.attr.dsaPrimary
    private val ATTR_TEXT2 = R.attr.dsaText2
    private val ATTR_TEXT = R.attr.dsaText
    private val ATTR_SURFACE2 = R.attr.dsaSurface2
    private val ATTR_PRIMARY_SOFT = R.attr.dsaPrimarySoft

    private fun themeColor(attr: Int): Int {
        val a = requireContext().theme.obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, 0xFF888888.toInt())
        a.recycle()
        return c
    }

    private val UP_RED = Color.parseColor("#D93025")
    private val DOWN_GREEN = Color.parseColor("#188038")
    // v2.9.2：蓝卡右上角涨跌幅专用亮色（蓝底对比）
    private val CHG_UP_ON_BLUE = Color.parseColor("#FF8A80")
    private val CHG_DOWN_ON_BLUE = Color.parseColor("#7CF5B0")

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
