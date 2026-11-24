package com.dsa.mobile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.RadioButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.dsa.mobile.databinding.ActivityThemeBinding

/** v2.8.0：主题外观设置页（预设色 / 自定义颜色 / 深色模式 / 自定义图片背景） */
class ThemeActivity : BaseActivity() {

    private lateinit var binding: ActivityThemeBinding

    // v2.12.64：先系统选图，再进裁切页按屏幕比例预览/裁切，不再默认拉伸铺满
    private val pickImage = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) cropLauncher.launch(CropActivity.buildIntent(this, uri))
    }

    private val cropLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) onCropDone()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v2.12.9：顶部栏统一为内页标准样式（← + 紧贴箭头左对齐标题），与历史报告页一致
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowCustomEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = Prefs.appTitle(this)

        // 预览：当前主色 + 背景图片
        binding.previewSwatch.background = circle(Color.TRANSPARENT)
        binding.previewSwatch.post {
            val d = (binding.previewSwatch.background as? GradientDrawable) ?: return@post
            d.setColor(ThemeManager.primaryColor(this))
            binding.previewLabel.text =
                "${ThemeManager.presetName(Prefs.themePreset(this))} · ${modeName()}"
            binding.themePreview.post { ThemeManager.bgDrawable(this)?.let { binding.themePreview.background = it } }
        }

        buildPresetRow()
        setupDarkMode()
        setupSwitchAnim()
        setupImageControls()
    }

    private fun modeName(): String = when (Prefs.themeDarkMode(this)) {
        "dark" -> "深色"
        "light" -> "浅色"
        else -> "跟随手机"
    }

    private fun circle(c: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(3, 0xFFB0BEC5.toInt()) }

    private fun swatch(color: Int, selected: Boolean, onClick: () -> Unit): android.view.View {
        val size = dp(46)
        val v = android.view.View(this)
        v.layoutParams = android.view.ViewGroup.LayoutParams(size, size)
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(if (selected) dp(4) else dp(1), if (selected) 0xFF202124.toInt() else 0xFFCFD8DC.toInt())
        }
        v.background = d
        v.setOnClickListener { onClick() }
        return v
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildPresetRow() {
        val cur = Prefs.themePreset(this)
        val map = linkedMapOf(
            "rose" to R.color.theme_rose,
            "blue" to R.color.theme_blue,
            "red" to R.color.theme_red,
            "green" to R.color.theme_green,
            "purple" to R.color.theme_purple,
            "orange" to R.color.theme_orange,
            "teal" to R.color.theme_teal
        )
        map.forEach { (key, res) ->
            val color = ContextCompat.getColor(this, res)
            val selected = cur == key
            val v = swatch(color, selected) {
                Prefs.saveTheme(this, key, -1,
                    Prefs.themeBgImage(this), Prefs.themeBgAlpha(this), Prefs.themeBgBlur(this),
                    Prefs.themeDarkMode(this))
                recreate()
            }
            binding.presetRow.addView(v)
            if (key != map.keys.last()) {
                val sp = android.view.View(this)
                sp.layoutParams = android.view.ViewGroup.LayoutParams(dp(12), dp(1))
                binding.presetRow.addView(sp)
            }
        }
    }

    private fun setupDarkMode() {
        val cur = Prefs.themeDarkMode(this)
        binding.rdFollow.isChecked = cur == "follow"
        binding.rdLight.isChecked = cur == "light"
        binding.rdDark.isChecked = cur == "dark"
        val onChange = {
            val mode = when {
                binding.rdDark.isChecked -> "dark"
                binding.rdLight.isChecked -> "light"
                else -> "follow"
            }
            Prefs.saveTheme(this, Prefs.themePreset(this), -1,
                Prefs.themeBgImage(this), Prefs.themeBgAlpha(this), Prefs.themeBgBlur(this), mode)
            recreate()
        }
        binding.rdFollow.setOnClickListener { onChange() }
        binding.rdLight.setOnClickListener { onChange() }
        binding.rdDark.setOnClickListener { onChange() }
    }

    private fun setupSwitchAnim() {
        val cur = Prefs.switchAnim(this)
        binding.rdAnimSlide.isChecked = cur == "slide"
        binding.rdAnimFade.isChecked = cur == "slide_fade"
        binding.rdAnimNone.isChecked = cur == "none"
        val onChange = {
            val mode = when {
                binding.rdAnimNone.isChecked -> "none"
                binding.rdAnimFade.isChecked -> "slide_fade"
                else -> "slide"
            }
            Prefs.saveSwitchAnim(this, mode)
            Toast.makeText(this, "切换动画已保存", Toast.LENGTH_SHORT).show()
        }
        binding.rdAnimSlide.setOnClickListener { onChange() }
        binding.rdAnimFade.setOnClickListener { onChange() }
        binding.rdAnimNone.setOnClickListener { onChange() }
    }

    private fun setupImageControls() {
        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        binding.btnRemoveImage.setOnClickListener {
            Prefs.saveTheme(this, Prefs.themePreset(this), -1,
                "none", Prefs.themeBgAlpha(this), Prefs.themeBgBlur(this), Prefs.themeDarkMode(this))
            recreate()
        }
        binding.sbAlpha.progress = Prefs.themeBgAlpha(this)
        binding.sbBlur.progress = Prefs.themeBgBlur(this)
        binding.tvAlpha.text = "${binding.sbAlpha.progress}%"
        binding.tvBlur.text = "${binding.sbBlur.progress}"

        // v2.12.4：毛玻璃模糊开关（真实高斯，只在换图/改参数时算一次）
        val hasImage = Prefs.themeBgImage(this) != "none"
        binding.swBlur.isChecked = Prefs.themeBgBlurOn(this)
        binding.swBlur.isEnabled = hasImage
        binding.swBlur.setOnCheckedChangeListener { _, checked ->
            Prefs.saveThemeBgBlurOn(this, checked)
            if (checked && binding.sbBlur.progress <= 0) {
                binding.sbBlur.progress = 12
                Prefs.saveTheme(this, Prefs.themePreset(this), -1,
                    Prefs.themeBgImage(this), Prefs.themeBgAlpha(this), 12, Prefs.themeDarkMode(this))
                binding.tvBlur.text = "12"
            }
            ThemeManager.clearBgCache()
            binding.themePreview.post { ThemeManager.bgDrawable(this)?.let { binding.themePreview.background = it } }
        }

        binding.sbAlpha.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                binding.tvAlpha.text = "$v%"
                if (fromUser) saveBgParams()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        binding.sbBlur.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                binding.tvBlur.text = "$v"
                if (fromUser) saveBgParams()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        // v2.12.11：胶囊/徽章类标签背景透明度（0 全透 ~ 100 不透明）
        binding.sbTagAlpha.progress = Prefs.tagAlpha(this)
        binding.tvTagAlpha.text = "${binding.sbTagAlpha.progress}%"
        binding.sbTagAlpha.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                binding.tvTagAlpha.text = "$v%"
                if (fromUser) Prefs.saveTagAlpha(this@ThemeActivity, v)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        // v2.12.11：宠物全局显示开关（关 → 仅多智能体分析页；开 → 主界面各页签悬浮）
        binding.swMascotGlobal.isChecked = Prefs.mascotGlobal(this)
        binding.swMascotGlobal.setOnCheckedChangeListener { _, checked ->
            Prefs.saveMascotGlobal(this, checked)
        }
    }

    private fun saveBgParams() {
        Prefs.saveTheme(this, Prefs.themePreset(this), -1,
            Prefs.themeBgImage(this), binding.sbAlpha.progress, binding.sbBlur.progress,
            Prefs.themeDarkMode(this))
        ThemeManager.clearBgCache()
        binding.themePreview.post { ThemeManager.bgDrawable(this)?.let { binding.themePreview.background = it } }
    }

    /** v2.12.64：裁切页已把图片写入 theme_bg.png；这里登记文件名、清缓存并刷新预览 */
    private fun onCropDone() {
        ThemeManager.clearBgCache()
        Prefs.saveTheme(this, Prefs.themePreset(this), -1,
            "theme_bg.png", Prefs.themeBgAlpha(this), Prefs.themeBgBlur(this),
            Prefs.themeDarkMode(this))
        binding.swBlur.isEnabled = true
        binding.themePreview.post { ThemeManager.bgDrawable(this)?.let { binding.themePreview.background = it } }
        Toast.makeText(this, "背景图片已保存", Toast.LENGTH_SHORT).show()
    }

    /** v2.12.8：点击顶部栏返回箭头关闭本页 */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
