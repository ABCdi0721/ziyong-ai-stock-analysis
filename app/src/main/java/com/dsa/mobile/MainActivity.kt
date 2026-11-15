package com.dsa.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.dsa.mobile.databinding.ActivityMainBinding
import com.dsa.mobile.glass.DockGlassBar
import com.dsa.mobile.glass.DockSnapshot
import com.dsa.mobile.glass.setGlassContent
import com.google.android.material.color.MaterialColors
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

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

    /** dock 后方内容快照（Compose state，写入后触发玻璃层重组）；null 表示首帧尚未捕获 */
    private var dockSnapshot by mutableStateOf<ImageBitmap?>(null)

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
                // 拖动过程不重抓快照（避免反复隐藏 dock 造成闪烁）；停稳后由 onPageSelected 抓一次
                if (Prefs.switchAnim(this@MainActivity) == "slide_fade") {
                    applyPageAlpha(position, positionOffset)
                }
            }

            override fun onPageSelected(position: Int) {
                resetPageAlpha()
                updateDock(position.toFloat())
                binding.pager.post { attachScrollSnapshotListeners(binding.pager) }
                refreshDockSnapshot()
            }
        })
    }

    // ------------------------- 底部 dock -------------------------

    private fun setupDock() {
        // 真液态玻璃：背景层为后方内容快照位图，叠库自带的模糊/折射/高光；胶囊平移与图标着色不变。
        binding.dockGlass.setGlassContent(this) { colors ->
            DockGlassBar(
                snapshot = dockSnapshot,
                colors = colors,
                blurEnabled = Prefs.themeBgBlurOn(this@MainActivity),
                modifier = androidx.compose.ui.Modifier.fillMaxSize()
            ) {}
        }
        // 首帧布局完成后抓一次快照；后续布局变化由切页/滚动空闲回调触发，避免重复隐藏 dock
        var layoutCaptured = false
        binding.root.doOnLayout {
            if (!layoutCaptured) {
                layoutCaptured = true
                refreshDockSnapshot()
            }
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

    /** 重新捕获 dock 后方内容快照，刷新玻璃背景层（异步：PixelCopy 回调内写状态） */
    private var lastCaptureAt = 0L
    private fun refreshDockSnapshot() {
        if (binding.dock.width <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastCaptureAt < 1500) return
        lastCaptureAt = now
        // 捕获时连同选中胶囊、四个图标一起隐藏：快照只保留后方页面内容，
        // 避免旧快照里残留上一页的选中胶囊 / 着色图标，造成未选中格位出现粉底残影
        val hidden = listOf(
            binding.mascotLayer, binding.dockPill,
            binding.dockIcon0, binding.dockIcon1, binding.dockIcon2, binding.dockIcon3
        )
        // 仅当新快照成功返回才替换；捕获进行中 / 失败返回 null 时保留上一张快照，避免背景瞬间置空造成闪烁
        DockSnapshot.captureWindow(this, binding.dock, hidden) { bmp ->
            if (bmp != null) dockSnapshot = bmp
        }
    }

    /** 滚动停止后延迟重抓快照；滚动持续时不断重置，确保只在空闲后抓一次（避免滚动中反复隐藏 dock 闪烁） */
    private var idleSnapshotRunnable: Runnable? = null
    private fun scheduleIdleSnapshot() {
        idleSnapshotRunnable?.let { binding.root.removeCallbacks(it) }
        val r = Runnable { refreshDockSnapshot() }
        idleSnapshotRunnable = r
        binding.root.postDelayed(r, 250)
    }

    /** 递归给当前页内的可滚动容器挂快照刷新监听（大盘 ScrollView / 自选·我的 RecyclerView 等） */
    private fun attachScrollSnapshotListeners(root: View) {
        when (root) {
            is ScrollView -> root.setOnScrollChangeListener { _, _, _, _, _ -> scheduleIdleSnapshot() }
            is NestedScrollView -> root.setOnScrollChangeListener { _, _, _, _, _ -> scheduleIdleSnapshot() }
            is RecyclerView -> {
                // 先清空旧监听，避免反复挂接导致监听器累积、滚动时重复触发快照刷新
                root.clearOnScrollListeners()
                root.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                        if (state == RecyclerView.SCROLL_STATE_IDLE) refreshDockSnapshot()
                    }
                })
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) attachScrollSnapshotListeners(root.getChildAt(i))
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
        // 仅当前页签处于选中态：逐项复位按压/选中状态，避免快速连点或动画过程中多格残留高亮
        dockItems.forEachIndexed { index, view ->
            view.isSelected = index == selected
            view.isPressed = false
        }
        binding.dockPill.invalidate()
        binding.dockItems.invalidate()
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
            binding.root.post {
                attachScrollSnapshotListeners(binding.root)
                refreshDockSnapshot()
            }
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
