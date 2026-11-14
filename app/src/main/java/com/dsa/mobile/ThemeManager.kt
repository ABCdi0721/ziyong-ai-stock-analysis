package com.dsa.mobile

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import java.io.File

/** v2.8.0：主题管理器——按 Prefs 选择主题 / 深色模式 / 背景图片
 *  v2.12.4：新增「玫瑰」预设为默认；背景模糊改为真实高斯 + 开关 + 缓存（滚动零开销） */
object ThemeManager {

    private val PRESETS = listOf("rose", "blue", "red", "green", "purple", "orange", "teal")

    /** 背景图模糊结果缓存（同一张图 + 同一参数只算一次） */
    private var cacheKey: String? = null
    private var cacheBmp: Bitmap? = null

    /** 当前是否深色模式（follow = 跟随系统） */
    fun isDark(ctx: Context): Boolean {
        return when (Prefs.themeDarkMode(ctx)) {
            "dark" -> true
            "light" -> false
            else -> {
                val mode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                mode == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    /** 主题样式资源：预设色 × 浅/深 */
    fun themeRes(ctx: Context): Int {
        val dark = isDark(ctx)
        return when (Prefs.themePreset(ctx)) {
            "blue" -> if (dark) R.style.Theme_Dsa_Blue_Dark else R.style.Theme_Dsa_Blue
            "red" -> if (dark) R.style.Theme_Dsa_Red_Dark else R.style.Theme_Dsa_Red
            "green" -> if (dark) R.style.Theme_Dsa_Green_Dark else R.style.Theme_Dsa_Green
            "purple" -> if (dark) R.style.Theme_Dsa_Purple_Dark else R.style.Theme_Dsa_Purple
            "orange" -> if (dark) R.style.Theme_Dsa_Orange_Dark else R.style.Theme_Dsa_Orange
            "teal" -> if (dark) R.style.Theme_Dsa_Teal_Dark else R.style.Theme_Dsa_Teal
            else -> if (dark) R.style.Theme_Dsa_Rose_Dark else R.style.Theme_Dsa_Rose
        }
    }

    /** 必须在 super.onCreate 之前调用 */
    fun apply(activity: Activity) {
        migrateIfNeeded(activity)
        activity.setTheme(themeRes(activity))
    }

    /** v2.12.4 一次性迁移：仍停留在默认蓝白（且未用自定义色）的用户切到玫瑰预设 */
    private fun migrateIfNeeded(ctx: Context) {
        if (Prefs.themeMigrated24(ctx)) return
        if (Prefs.themePreset(ctx) == "blue") {
            Prefs.saveTheme(
                ctx, "rose", -1,
                Prefs.themeBgImage(ctx), Prefs.themeBgAlpha(ctx),
                Prefs.themeBgBlur(ctx), Prefs.themeDarkMode(ctx)
            )
        }
        Prefs.markThemeMigrated24(ctx)
    }

    /** 从当前主题解析 dsaPrimary 色值 */
    fun primaryColor(ctx: Context): Int {
        val a = ctx.theme.obtainStyledAttributes(intArrayOf(R.attr.dsaPrimary))
        val c = a.getColor(0, 0xFFFF7FA8.toInt())
        a.recycle()
        return c
    }

    fun presetName(p: String): String = when (p) {
        "rose" -> "玫瑰"
        "blue" -> "蓝白"
        "red" -> "红白"
        "green" -> "绿白"
        "purple" -> "紫白"
        "orange" -> "橙白"
        "teal" -> "青白"
        else -> "玫瑰"
    }

    fun presetNames(): List<Pair<String, String>> =
        PRESETS.map { it to presetName(it) }

    /** 主题变更指纹：任一主题相关设置变化都会导致 key 变化（供页面刷新判断） */
    fun themeKey(ctx: Context): String {
        return "${Prefs.themePreset(ctx)}|${Prefs.themeDarkMode(ctx)}|" +
                "${Prefs.themeBgImage(ctx)}|${Prefs.themeBgAlpha(ctx)}|${Prefs.themeBgBlur(ctx)}|" +
                "${Prefs.themeBgBlurOn(ctx)}|${Prefs.tagAlpha(ctx)}|${Prefs.mascotGlobal(ctx)}"
    }

    /** 布局中标签 View 的 tag 标记（与 XML android:tag="dsa_tag" 对应） */
    const val TAG_MARK = "dsa_tag"

    /** 标签背景透明度（0-255） */
    fun tagAlpha255(ctx: Context): Int =
        (Prefs.tagAlpha(ctx) * 2.55f).toInt().coerceIn(0, 255)

    /** 缓存原始背景色的 View tag key，避免列表复用时透明度被二次衰减 */
    private const val TAG_ORIG_COLOR = 0x51A0001

    /** 给单个标签 View 的背景应用透明度 */
    fun applyTagAlpha(view: View, ctx: Context) {
        val bg = view.background ?: return
        val a = tagAlpha255(ctx)
        bg.mutate()
        bg.alpha = a
        when (bg) {
            is GradientDrawable -> {
                val cached = view.getTag(TAG_ORIG_COLOR) as? Int
                val base = cached ?: run {
                    val c = bg.color?.defaultColor ?: return
                    view.setTag(TAG_ORIG_COLOR, c)
                    c
                }
                val baseA = (base ushr 24) and 0xFF
                val newA = (a * baseA / 255).coerceIn(0, 255)
                bg.setColor((newA shl 24) or (base and 0xFFFFFF))
            }
            is LayerDrawable -> bg.alpha = a
            else -> bg.alpha = a
        }
    }

    /** 递归遍历根视图，给所有打标标签应用背景透明度 */
    fun applyTagAlphaTree(root: View, ctx: Context) {
        if (root.tag == TAG_MARK) applyTagAlpha(root, ctx)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyTagAlphaTree(root.getChildAt(i), ctx)
        }
    }

    /** 生成背景图片 Drawable（透明度 + 模糊）；未设置图片返回 null
     *  v2.12.4：模糊改为真实高斯，由「毛玻璃模糊」开关控制；
     *  按图片/参数变化只计算一次并缓存，滚动时不重算，保证流畅 */
    fun bgDrawable(ctx: Context): Drawable? {
        if (Prefs.themeBgImage(ctx) == "none") return null
        val f = File(ctx.filesDir, "theme_bg.png")
        if (!f.exists()) return null

        val blurOn = Prefs.themeBgBlurOn(ctx)
        val radius = Prefs.themeBgBlur(ctx).let { if (it <= 0) 12 else it }
        val key = "${f.lastModified()}|${f.length()}|$blurOn|$radius"

        var bmp: Bitmap? = null
        synchronized(this) {
            if (cacheKey == key && cacheBmp != null && !cacheBmp!!.isRecycled) {
                bmp = cacheBmp
            } else {
                val src = BitmapFactory.decodeFile(f.absolutePath) ?: return null
                bmp = if (blurOn) gaussBlur(src, radius) else src
                cacheKey = key
                cacheBmp = bmp
            }
        }
        val b = bmp ?: return null
        val d = BitmapDrawable(ctx.resources, b)
        d.alpha = (Prefs.themeBgAlpha(ctx) * 2.55f).toInt().coerceIn(0, 255)
        return d
    }

    /** 给主界面根布局应用背景图片（仅 MainActivity 主界面，K线/详情页保持纯色保证可读）
     *  v2.12.4：缓存命中时立即上屏；未命中（首次/换图/改参数）时在后台线程解码+模糊，
     *  算完再 post 回主线程设置，避免解码与高斯卷积阻塞主线程造成掉帧 */
    fun applyBgToRoot(root: View, ctx: Context) {
        if (Prefs.themeBgImage(ctx) == "none") {
            root.background = null
            return
        }
        val f = File(ctx.filesDir, "theme_bg.png")
        if (!f.exists()) {
            root.background = null
            return
        }

        val blurOn = Prefs.themeBgBlurOn(ctx)
        val radius = Prefs.themeBgBlur(ctx).let { if (it <= 0) 12 else it }
        val key = "${f.lastModified()}|${f.length()}|$blurOn|$radius"

        synchronized(this) {
            if (cacheKey == key && cacheBmp != null && !cacheBmp!!.isRecycled) {
                // 命中缓存：直接上屏，零额外计算
                val d = BitmapDrawable(ctx.resources, cacheBmp)
                d.alpha = (Prefs.themeBgAlpha(ctx) * 2.55f).toInt().coerceIn(0, 255)
                root.background = d
                return
            }
        }

        // 未命中：后台算，主线程只负责贴图
        Thread {
            val d = try { bgDrawable(ctx) } catch (e: Throwable) { null }
            if (d != null) root.post { root.background = d }
        }.apply { isDaemon = true; name = "dsa-bg-blur"; priority = Thread.MIN_PRIORITY }.start()
    }

    /** 清除模糊缓存（切换背景图或参数后可调用） */
    fun clearBgCache() {
        cacheKey = null
        cacheBmp = null
    }

    /** 真实高斯模糊：先降采样再两趟一维卷积（可分离高斯），只算一次且不占主线程
     *  v2.12.4：定点整数权重卷积（不再用浮点内层），480px 上限，模拟器上耗时压到数十毫秒 */
    private fun gaussBlur(src: Bitmap, radius: Int): Bitmap {
        val maxSide = 480
        val longSide = maxOf(src.width, src.height)
        val scaled = if (longSide > maxSide) {
            val s = maxSide.toFloat() / longSide
            Bitmap.createScaledBitmap(
                src,
                (src.width * s).toInt().coerceAtLeast(1),
                (src.height * s).toInt().coerceAtLeast(1),
                true
            )
        } else src

        val w = scaled.width
        val h = scaled.height
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)

        val sigma = radius.toFloat().coerceIn(1f, 28f)
        val kRadius = (sigma * 2.2f).toInt().coerceIn(1, 40)
        val size = kRadius * 2 + 1
        val kernel = IntArray(size)
        val s2 = 2f * sigma * sigma
        var fsum = 0f
        val raw = FloatArray(size)
        for (i in 0 until size) {
            val d = i - kRadius
            raw[i] = Math.exp(-(d * d).toDouble() / s2.toDouble()).toFloat()
            fsum += raw[i]
        }
        // 定点权重：总和 65536，卷积后右移 16 位
        var fixedSum = 0
        for (i in 0 until size) {
            kernel[i] = ((raw[i] / fsum) * 65536f).toInt().coerceAtLeast(1)
            fixedSum += kernel[i]
        }
        kernel[kRadius] += 65536 - fixedSum   // 误差补偿，避免整体亮度漂移

        // 水平方向
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (k in 0 until size) {
                    var sx = x + k - kRadius
                    if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
                    val c = px[row + sx]
                    val kv = kernel[k]
                    r += ((c shr 16) and 0xFF) * kv
                    g += ((c shr 8) and 0xFF) * kv
                    b += (c and 0xFF) * kv
                }
                tmp[row + x] = (0xFF shl 24) or
                        ((r shr 16).coerceIn(0, 255) shl 16) or
                        ((g shr 16).coerceIn(0, 255) shl 8) or
                        (b shr 16).coerceIn(0, 255)
            }
        }

        // 垂直方向
        val px2 = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var r = 0
                var g = 0
                var b = 0
                for (k in 0 until size) {
                    var sy = y + k - kRadius
                    if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
                    val c = tmp[sy * w + x]
                    val kv = kernel[k]
                    r += ((c shr 16) and 0xFF) * kv
                    g += ((c shr 8) and 0xFF) * kv
                    b += (c and 0xFF) * kv
                }
                px2[y * w + x] = (0xFF shl 24) or
                        ((r shr 16).coerceIn(0, 255) shl 16) or
                        ((g shr 16).coerceIn(0, 255) shl 8) or
                        (b shr 16).coerceIn(0, 255)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px2, 0, w, 0, 0, w, h)
        return out
    }
}
