package com.dsa.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.dsa.mobile.databinding.ActivityMainBinding
import com.dsa.mobile.glass.GlassCard
import com.dsa.mobile.glass.setGlassContent
import com.google.android.material.color.MaterialColors
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp

/**
 * 主界面（v2.12.7）
 *
 * 页面容器改为 ViewPager2：左右拖动时当前页与相邻页同屏联动平移（能看到下一页画面），
 * 松手按位移/速度吸附或回弹，全部由 ViewPager2 原生手势处理（1:1 跟手）。
 * 底部 dock 改为自绘指示胶囊，跟随 onPageScrolled 的浮点进度实时平移，与页面完全同步。
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastThemeKey: String? = null

    private lateinit var icons: List<ImageView>
    private var dockItems: List<android.view.View> = emptyList()
    private var pillFraction = 0f

    /** 底部四个页签的顺序（左→右），与 ViewPager2 的 position 一一对应 */
    private val pageCount = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v2.12.4：主界面隐藏 ActionBar，各 tab 使用页内大标题（避免标题重复）
        supportActionBar?.hide()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v2.8.0：主界面应用自定义背景图片（透明度/模糊按设置）
        binding.root.post { ThemeManager.applyBgToRoot(binding.root, this) }

        // v2.6.0：Android 13+ 运行时申请通知权限（AI 分析后台通知 / 完成通知）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        setupDock()
        setupPager()
        setupGlobalMascot()
    }

    /** v2.12.11：外观设置开启「宠物全局显示」后，在主界面各页签右下角悬浮同一宠物 */
    private fun setupGlobalMascot() {
        if (Prefs.mascotGlobal(this)) {
            // 抬高到底栏 + 自选页 FAB 之上，避免遮挡可操作按钮
            MascotHelper.attach(this, binding.mascotLayer, bottomMarginDp = 140)
        }
    }

    // ------------------------- 页面容器 -------------------------

    private fun setupPager() {
        binding.pager.adapter = MainPagerAdapter(this)
        binding.pager.offscreenPageLimit = 1
        // 「无动画」模式下不接管拖动，点击瞬时切换（与既有设置含义一致）
        binding.pager.isUserInputEnabled = Prefs.switchAnim(this) != "none"
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                // 浮点页索引：0 → 1 的整个拖动过程都连续变化，dock 胶囊按同一进度平移
                pillFraction = position + positionOffset
                updateDock(pillFraction)
                if (Prefs.switchAnim(this@MainActivity) == "slide_fade") {
                    applyPageAlpha(position, positionOffset)
                }
            }

            override fun onPageSelected(position: Int) {
                resetPageAlpha()
                updateDock(position.toFloat())
            }
        })
    }

    // ------------------------- 底部 dock -------------------------

    private fun setupDock() {
        // 液态玻璃外壳：仅视觉背景层，胶囊平移与图标着色逻辑保持不变。
        // 半透明底色由静态导航底单层承担，Compose 层只保留描边/高光/折射观感，不叠实底、不开投影。
        binding.dockGlass.setGlassContent(this) { colors ->
            GlassCard(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                cornerDp = 26.dp,
                tint = androidx.compose.ui.graphics.Color.Unspecified,
                stroke = colors.glassStroke,
                shadowEnabled = false
            ) {}
        }
        icons = listOf(
            binding.dockIcon0, binding.dockIcon1, binding.dockIcon2, binding.dockIcon3
        )
        dockItems = listOf(
            binding.dockItem0, binding.dockItem1, binding.dockItem2, binding.dockItem3
        )
        dockItems.forEachIndexed { index, view ->
            view.setOnClickListener {
                val animate = Prefs.switchAnim(this) != "none"
                if (binding.pager.currentItem != index) {
                    binding.pager.setCurrentItem(index, animate)
                }
            }
        }
        // 首次 layout 与尺寸变化（旋转/主题重建）后按当前页重新定位胶囊
        binding.dockItems.doOnLayout { updateDock(pillFraction) }
        binding.dockItems.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDock(pillFraction)
        }
    }

    /** fraction 为浮点页索引（如 1.42 表示停在第 2 页往第 3 页拖了 42%） */
    private fun updateDock(fraction: Float) {
        val itemsWidth = binding.dockItems.width
        if (itemsWidth <= 0) return
        val itemWidth = itemsWidth.toFloat() / pageCount
        val pillWidth = binding.dockPill.width.toFloat().let { if (it > 0f) it else dp(64).toFloat() }
        binding.dockPill.translationX = itemWidth * fraction + (itemWidth - pillWidth) / 2f

        // 图标：就近高亮（拖动过半即切到下一项），选中白 / 未选主题次要文字色
        val selected = fraction.roundToIntSafe()
        val on = Color.WHITE
        val off = MaterialColors.getColor(
            binding.root, R.attr.dsaText2, 0xFF8A8A8E.toInt()
        )
        icons.forEachIndexed { index, icon ->
            icon.setColorFilter(if (index == selected) on else off)
        }
    }

    private fun Float.roundToIntSafe(): Int {
        val v = kotlin.math.round(this)
        return v.toInt().coerceIn(0, pageCount - 1)
    }

    // ------------------------- 「平移+淡入」过渡 -------------------------

    private fun applyPageAlpha(position: Int, offset: Float) {
        val fm = supportFragmentManager
        fm.findFragmentByTag("f$position")?.view?.alpha = 1f - 0.55f * offset
        fm.findFragmentByTag("f${position + 1}")?.view?.alpha = 0.45f + 0.55f * offset
    }

    private fun resetPageAlpha() {
        val fm = supportFragmentManager
        for (i in 0 until pageCount) {
            fm.findFragmentByTag("f$i")?.view?.alpha = 1f
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    // v2.8.0：从主题设置页返回时，若主题/深色/背景发生变化则重建主界面使其生效
    override fun onResume() {
        super.onResume()
        val key = ThemeManager.themeKey(this)
        if (lastThemeKey != null && lastThemeKey != key) {
            lastThemeKey = key
            recreate()
        } else {
            lastThemeKey = key
        }
    }
}

/** 四个页签的 Fragment 适配器（顺序：自选 → 大盘 → 选股 → 我的） */
private class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> WatchlistFragment()
        1 -> MarketFragment()
        2 -> ScreenHolderFragment()
        else -> MineFragment()
    }
}
