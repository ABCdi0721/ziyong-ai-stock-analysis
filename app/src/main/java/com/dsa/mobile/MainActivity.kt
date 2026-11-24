package com.dsa.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.dsa.mobile.databinding.ActivityMainBinding
import com.dsa.mobile.glass.DockGlassBar
import com.dsa.mobile.glass.GlassAmbientBackground
import com.dsa.mobile.glass.setGlassContent
import com.google.android.material.color.MaterialColors
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * 主界面（v2.12.7）
 *
 * 页面容器改为 ViewPager2：左右拖动时当前页与相邻页同屏联动平移（能看到下一页画面），
 * 松手按位移/速度吸附或回弹，全部由 ViewPager2 原生手势处理（1:1 跟手）。
 * 底部 dock 为自绘指示胶囊，跟随 onPageScrolled 的浮点进度实时平移，与页面完全同步。
 *
 * v2.12.21：dock 背景改为透明叠层，不再抓取后方内容快照位图；下方 pager 真实页面由视图层级
 * 直接透出，拖动 / 切页实时跟随，无快照帧滞后与旧帧残留。
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastThemeKey: String? = null

    private lateinit var icons: List<ImageView>
    private lateinit var labels: List<TextView>
    private var dockItems: List<android.view.View> = emptyList()
    private var pillFraction = 0f

    /** 着色缓存：拖动过程中只在选中格变化时刷新图标/文字，颜色按主题取一次 */
    private var dockSelected = -1
    private var dockOnColor = 0
    private var dockOffColor = 0

    /** 胶囊宽度：布局完成后按格宽算一次冻结，滚动帧内不再重设 layoutParams，避免反复 requestLayout */
    private var pillWidth = 0f

    /** 底部四个页签的顺序（左→右），与 ViewPager2 的 position 一一对应 */
    private val pageCount = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v2.12.4：主界面隐藏 ActionBar，各 tab 使用页内大标题（避免标题重复）
        supportActionBar?.hide()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // v2.12.22：edge-to-edge，壁纸铺满状态栏区域；根布局顶部按状态栏高度避让，避免页内标题被状态栏图标压住
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val sbRes = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sbH = if (sbRes > 0) resources.getDimensionPixelSize(sbRes) else 0
        binding.root.setPadding(
            binding.root.paddingLeft, sbH,
            binding.root.paddingRight, binding.root.paddingBottom
        )

        // v2.8.0：主界面应用自定义背景图片（透明度/模糊按设置）
        binding.root.post { ThemeManager.applyBgToRoot(binding.root, this) }

        // v2.6.0：Android 13+ 运行时申请通知权限（AI 分析后台通知 / 完成通知）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        setupAmbient()
        setupDock()
        setupPager()
        setupGlobalMascot()
    }

    /** v2.12.63：全局液态玻璃氛围光晕层（主色径向光斑，深浅/预设主色自适应） */
    private fun setupAmbient() {
        binding.ambientLayer.setGlassContent(this) { colors ->
            GlassAmbientBackground(colors = colors, modifier = Modifier.fillMaxSize())
        }
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
        // 底层冗余通道：直接监听 pager 内部 RecyclerView 滚动，按像素偏移独立算进度驱动胶囊，
        // 不依赖 OnPageChangeCallback 的回调时机，保证拖动过程逐帧跟手。
        attachPagerScrollFallback()
    }

    /** 取 pager 内部 RecyclerView，挂底层滚动监听；onScrolled 每像素触发，独立计算浮点页索引 */
    private fun attachPagerScrollFallback() {
        binding.pager.post {
            val rv = binding.pager.getChildAt(0) as? RecyclerView
            rv?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val first = lm.findFirstVisibleItemPosition()
                    if (first < 0) return
                    val firstView = lm.findViewByPosition(first) ?: return
                    val pageW = recyclerView.width.toFloat()
                    if (pageW <= 0f) return
                    val offsetPx = -firstView.left
                    val frac = first + offsetPx / pageW
                    updateDock(frac)
                }
            })
        }
    }

    // ------------------------- 底部 dock -------------------------

    private fun setupDock() {
        // 透明玻璃栏：下方 pager 真实页面直接透出，胶囊平移与图标着色不变。
        binding.dockGlass.setGlassContent(this) { colors ->
            DockGlassBar(
                colors = colors,
                blurEnabled = Prefs.themeBgBlurOn(this@MainActivity),
                modifier = androidx.compose.ui.Modifier.fillMaxSize()
            ) {}
        }
        icons = listOf(
            binding.dockIcon0, binding.dockIcon1, binding.dockIcon2, binding.dockIcon3
        )
        labels = listOf(
            binding.dockLabel0, binding.dockLabel1, binding.dockLabel2, binding.dockLabel3
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
        binding.dockItems.doOnLayout {
            layoutDockPill()
            updateDock(pillFraction)
        }
        binding.dockItems.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            layoutDockPill()
            updateDock(pillFraction)
        }
        // 首帧按 pager 当前页定位胶囊，避免恢复 / 重建后胶囊停在默认第 1 格
        binding.root.post {
            pillFraction = binding.pager.currentItem.toFloat()
            updateDock(pillFraction)
        }
    }

    /** 布局完成后按格宽算一次胶囊宽度并冻结；滚动帧内不再改 layoutParams */
    private fun layoutDockPill() {
        val itemsWidth = binding.dockItems.width
        if (itemsWidth <= 0) return
        val itemWidth = itemsWidth.toFloat() / pageCount
        val pw = (itemWidth - dp(6) * 2f).coerceAtLeast(dp(48).toFloat())
        if (binding.dockPill.layoutParams.width != pw.toInt()) {
            val lp = binding.dockPill.layoutParams
            lp.width = pw.toInt()
            binding.dockPill.layoutParams = lp
        }
        pillWidth = pw
    }

    /** fraction 为浮点页索引（如 1.42 表示停在第 2 页往第 3 页拖了 42%） */
    private fun updateDock(fraction: Float) {
        // 同步回写字段：布局回调 / 尺寸变化重绘时沿用最新位置，避免 onPageSelected 定位后被旧 fraction 覆盖
        pillFraction = fraction
        val itemsWidth = binding.dockItems.width
        if (itemsWidth <= 0) return
        val itemWidth = itemsWidth.toFloat() / pageCount
        if (pillWidth <= 0f) layoutDockPill()
        // 每帧按同一浮点进度平移胶囊，与页面 1:1 跟手；滚动帧内不重设 layoutParams，避免反复 requestLayout
        binding.dockPill.translationX = itemWidth * fraction + (itemWidth - pillWidth) / 2f

        // 高亮只在选中格变化时刷新；拖动过半才切到下一项，选中主色 / 未选主题次要文字色
        val selected = fraction.roundToIntSafe()
        if (selected == dockSelected) return
        dockSelected = selected
        if (dockOnColor == 0) {
            dockOnColor = MaterialColors.getColor(binding.root, R.attr.dsaPrimary, 0xFF1A73E8.toInt())
            dockOffColor = MaterialColors.getColor(binding.root, R.attr.dsaText2, 0xFF8A8A8E.toInt())
        }
        icons.forEachIndexed { index, icon ->
            icon.setColorFilter(if (index == selected) dockOnColor else dockOffColor)
        }
        labels.forEachIndexed { index, label ->
            label.setTextColor(if (index == selected) dockOnColor else dockOffColor)
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
        // v2.12.64：替换背景图时文件名不变、主题 key 不变，需主动重绘根背景，
        // 否则返回主界面仍是旧图，要杀进程重开才生效。
        binding.root.post { ThemeManager.applyBgToRoot(binding.root, this) }
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
