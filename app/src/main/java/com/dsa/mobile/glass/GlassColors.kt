package com.dsa.mobile.glass

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.dsa.mobile.Prefs
import com.dsa.mobile.R
import com.dsa.mobile.ThemeManager

/**
 * 液态玻璃组件的配色快照：从当前 View 主题（dsa* attr）与偏好设置读取，
 * 传给 Compose 玻璃组件，保证深浅色 / 预设主色 / 标签透明度与现有页面一致。
 */
data class GlassColors(
    val primary: Color,
    val onPrimary: Color,
    val text: Color,
    val text2: Color,
    val card: Color,
    val surface2: Color,
    val bg: Color,
    val glassTint: Color,
    val glassStroke: Color,
    val navBg: Color,
    val isDark: Boolean,
    /** 标签背景透明度 0f（全透）~ 1f（不透明），对应设置页「标签透明度」 */
    val tagAlpha: Float
) {
    /** 按标签透明度衰减后的玻璃叠层色 */
    fun tintWithAlpha(base: Color): Color =
        if (tagAlpha >= 1f) base else base.copy(alpha = base.alpha * tagAlpha)
}

fun GlassColors(ctx: Context): GlassColors {
    val attrs = intArrayOf(
        R.attr.dsaPrimary,
        R.attr.dsaOnPrimary,
        R.attr.dsaText,
        R.attr.dsaText2,
        R.attr.dsaCard,
        R.attr.dsaSurface2,
        R.attr.dsaBg,
        R.attr.dsaGlassTint,
        R.attr.dsaGlassStroke,
        R.attr.dsaNavBg
    )
    val a = ctx.theme.obtainStyledAttributes(attrs)
    fun col(index: Int, def: Int): Color = Color(a.getColor(index, def))
    val colors = GlassColors(
        primary = col(0, 0xFFFF7FA8.toInt()),
        onPrimary = col(1, 0xFFFFFFFF.toInt()),
        text = col(2, 0xFF17171C.toInt()),
        text2 = col(3, 0xFF6B6B76.toInt()),
        card = col(4, 0xFFFFFFFF.toInt()),
        surface2 = col(5, 0xFFEFEFF3.toInt()),
        bg = col(6, 0xFFF6F6F9.toInt()),
        glassTint = col(7, 0x14FFFFFF.toInt()),
        glassStroke = col(8, 0x14000000.toInt()),
        navBg = col(9, 0xE8FFFFFF.toInt()),
        isDark = ThemeManager.isDark(ctx),
        tagAlpha = Prefs.tagAlpha(ctx) / 100f
    )
    a.recycle()
    return colors
}
