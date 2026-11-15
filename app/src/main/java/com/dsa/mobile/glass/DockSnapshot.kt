package com.dsa.mobile.glass

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dsa.mobile.R

/**
 * 底部 dock 的后方内容快照捕获器。
 *
 * 背景：backdrop 库的模糊/折射只作用于「同一 Compose 树内录制到的背景层」，而主界面是 View/XML
 * 体系，dock 的 ComposeView 录不到下方列表卡片。为在不重构业务的前提下让 dock 呈现真液态玻璃
 * （透出后方内容并被模糊/折射），这里把 dock 覆盖区域的当前画面抓成一张位图，交给 Compose 的
 * CanvasBackdrop 作为背景层。
 *
 * 两条路径：
 *  - Android 11 (R)+：[captureWindow] 用 PixelCopy 直接复制窗口合成帧，像素位置与真实屏幕完全一致；
 *  - 低版本：[capture] 退化为 root 自绘整树位图裁剪（硬件加速内容可能不全，仅作兜底）。
 *
 * 快照是某一帧的位图（非连续实时模糊），在页面切换 / 滚动停止 / 数据刷新后按需重抓。
 */
object DockSnapshot {

    /** 快照降采样比例（仅自绘兜底路径使用），兼顾清晰度与整树绘制开销 */
    private const val SCALE = 0.5f

    /** 捕获进行中标志：避免滚动/布局高频触发导致多次隐藏-恢复竞态 */
    private var capturing = false

    /** 是否有一次窗口帧捕获正在进行（供调用方排队补抓，避免切页时快照被旧帧占用） */
    fun isCapturing(): Boolean = capturing

    /**
     * Android 11+：复制窗口合成帧中 [dock] 覆盖区域的位图。
     *
     * 捕获前仅临时隐藏 [extraHidden]（如全局宠物层），dock 自身保持可见以避免捕获瞬间闪动；两帧 post 后
     * 再发起 PixelCopy，确保去 dock 的画面已经上屏。回调在主线程返回。
     */
    fun captureWindow(
        activity: Activity,
        dock: View,
        extraHidden: List<View> = emptyList(),
        onResult: (ImageBitmap?) -> Unit
    ) {
        if (capturing) { onResult(null); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || dock.width <= 0 || dock.height <= 0) {
            onResult(capture(activity.window.decorView as? View ?: dock, dock, extraHidden))
            return
        }
        capturing = true

        val w = dock.width
        val h = dock.height
        val loc = IntArray(2)
        dock.getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // 仅隐藏宠物等悬浮层；dock 自身不隐藏，避免捕获瞬间 dock 消失造成闪动。
        // 快照里带入的 dock 图标/底色会被 backdrop 的模糊层柔化，前景清晰图标再叠在其上。
        val hidden = extraHidden
        hidden.forEach { it.alpha = 0f }
        var restored = false
        fun restore() {
            if (restored) return
            restored = true
            capturing = false
            // 正常态 dock 与宠物层 alpha 恒为 1；直接复位以避免多次捕获竞态把 alpha 记成 0
            hidden.forEach { it.alpha = 1f }
        }

        val handler = Handler(Looper.getMainLooper())
        // 两帧 post：等待当前布局稳定后再复制已合成帧
        dock.post {
            dock.post {
                try {
                    PixelCopy.request(activity.window, rect, bmp, { result ->
                        restore()
                        if (result == PixelCopy.SUCCESS) onResult(bmp.asImageBitmap()) else onResult(null)
                    }, handler)
                    // 兜底：回调未触发时也必须恢复悬浮层可见性
                    handler.postDelayed({ restore() }, 150)
                } catch (t: Throwable) {
                    restore()
                    onResult(null)
                }
            }
        }
    }

    /**
     * 低版本兜底：裁剪 [root] 树在 [dock] 区域内的自绘快照位图。
     *
     * @param root 宿主根 View（背景 + 各页内容）
     * @param dock 底部 dock 容器
     * @param extraHidden 捕获时需要临时隐藏、避免在快照里留下自身重影的额外 View
     */
    fun capture(root: View, dock: View, extraHidden: List<View> = emptyList()): ImageBitmap? {
        val rootW = root.width
        val rootH = root.height
        if (rootW <= 0 || rootH <= 0 || dock.width <= 0 || dock.height <= 0) return null

        val bw = (rootW * SCALE).toInt().coerceAtLeast(1)
        val bh = (rootH * SCALE).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(SCALE, SCALE)

        // 纯色模式下根背景被置空、底色由 Window 层提供，自绘位图缺这一层；先按主题底色铺满，
        // 背景图模式下 root.background 位图会在其上叠加。
        val ta = root.context.theme.obtainStyledAttributes(intArrayOf(R.attr.dsaBg))
        val bgColor = ta.getColor(0, 0xFF101014.toInt())
        ta.recycle()
        canvas.drawColor(bgColor)

        val rootLoc = IntArray(2)
        val dockLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        dock.getLocationInWindow(dockLoc)
        val left = dockLoc[0] - rootLoc[0]
        val top = dockLoc[1] - rootLoc[1]

        extraHidden.forEach { it.alpha = 0f }
        try {
            root.draw(canvas)
        } finally {
            extraHidden.forEach { it.alpha = 1f }
        }

        val sx = (left * SCALE).toInt().coerceIn(0, bw - 1)
        val sy = (top * SCALE).toInt().coerceIn(0, bh - 1)
        val sw = (dock.width * SCALE).toInt().coerceAtLeast(1).coerceAtMost(bw - sx)
        val sh = (dock.height * SCALE).toInt().coerceAtLeast(1).coerceAtMost(bh - sy)

        val cropped = Bitmap.createBitmap(bitmap, sx, sy, sw, sh)
        return cropped.asImageBitmap()
    }
}
