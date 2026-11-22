package com.dsa.mobile

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.dsa.mobile.databinding.ActivityAboutBinding

/** 关于页（v2.12.34）：展示版本号与历史更新简略版日志 */
class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

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

        ReleaseNotes.NOTES.forEachIndexed { i, (ver, desc) ->
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
            if (i < ReleaseNotes.NOTES.size - 1) {
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
