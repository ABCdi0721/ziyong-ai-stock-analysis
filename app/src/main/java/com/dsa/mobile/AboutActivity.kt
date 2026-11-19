package com.dsa.mobile

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.dsa.mobile.databinding.ActivityAboutBinding

/** 关于页（v2.12.34）：展示版本号与历史更新简略版日志 */
class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    /** 简略更新日志：版本号 + 一行要点（最新在前） */
    private val changelog = listOf(
        "2.12.33" to "移植 OpenBB 趋势/量价/波动/风险调整指标（ADX、Choppiness、Aroon、OBV、ADOSC、夏普/回撤/Var95 等）",
        "2.12.32" to "取消自选联动取消收藏报告；历史报告报错检测与自动清理",
        "2.12.31" to "新增五个短线策略预设；修复排行榜信号与评分不显示",
        "2.12.30" to "新增「短线·强势突破」策略；补 ATR/唐奇安通道/振幅/NR7 算子",
        "2.12.29" to "截面因子长尾压缩、分层归一、事件因子指数衰减编码",
        "2.12.28" to "移植因子标准化与风险调整打分链路（FactorModel）",
        "2.12.27" to "修复排行榜外层卡片标签透明度跟随设置",
        "2.12.26" to "修复大盘警示卡浅底文字可读性；胶囊标签跟随透明度",
        "2.12.25" to "榜单数据源持久化、自动刷新档位、多智能体新增板块环节、修复北交所 920",
        "2.12.24" to "榜单放开前 100 名、数据源下拉、条目新样式、修复 ETF 详情跳转",
        "更早版本" to "多智能体分析、收藏与通知、全局换肤、排行榜、WebDAV 备份、短线策略等基础功能"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "关于"

        showVersion()
        renderChangelog()
    }

    private fun showVersion() {
        val pm = packageManager
        val info = try { pm.getPackageInfo(packageName, 0) } catch (e: Exception) { null }
        val name = info?.versionName ?: "未知"
        val code = info?.longVersionCode?.toInt() ?: 0
        binding.aboutVersion.text = "版本号 $name（versionCode $code）"
    }

    private fun renderChangelog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val subColor = com.google.android.material.color.MaterialColors.getColor(
            this, R.attr.dsaText2, android.graphics.Color.GRAY
        )
        val primary = com.google.android.material.color.MaterialColors.getColor(
            this, R.attr.dsaPrimary, android.graphics.Color.BLACK
        )

        changelog.forEachIndexed { i, (ver, desc) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.TOP
                setPadding(0, dp(10), 0, dp(10))
            }
            val verView = TextView(this).apply {
                text = ver
                setTextColor(primary)
                textSize = 13f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val descView = TextView(this).apply {
                text = desc
                setTextColor(subColor)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(10), 0, 0, 0)
            }
            row.addView(verView)
            row.addView(descView)
            binding.aboutLogs.addView(row)

            // 条目间分隔线（最后一条不加）
            if (i < changelog.size - 1) {
                val line = android.view.View(this).apply {
                    setBackgroundColor(0x14888888)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    )
                }
                binding.aboutLogs.addView(line)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        supportActionBar?.title = "关于"
    }
}
