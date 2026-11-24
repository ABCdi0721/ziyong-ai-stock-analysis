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
    val primaryDark: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val text: Color,
    val text2: Color,
    val card: Color,
    val surface2: Color,
    val bg: Color,
    val glassTint: Color,
    val glassStroke: Color,
    val glassHi: Color,
    val navBg: Color,
    val divider: Color,
    val isDark: Boolean,
    /** 标签背景透明度 0f（全透）~ 1f（不透明），对应设置页「标签透明度」 */
    val tagAlpha: Float
) {
    /** 按标签透明度衰减后的玻璃叠层色 */
    fun tintWithAlpha(base: Color): Color =
        if (tagAlpha >= 1f) base else base.copy(alpha = base.alpha * tagAlpha)

    /** 卡片玻璃叠层：半透明卡片底 + 玻璃叠层，深色更透、浅色更实 */
    fun cardTint(): Color = card.copy(alpha = if (isDark) 0.50f else 0.78f)

    /** 胶囊 / 小面板玻璃叠层 */
    fun pillTint(): Color = surface2.copy(alpha = if (isDark) 0.42f else 0.66f)

    /** 顶部高光渐变起始色（到透明结束由组件内画） */
    fun highlight(): Color = glassHi

    /** 氛围光晕主色（径向光斑，低透明度） */
    fun ambientPrimary(extra: Float = 0f): Color =
        primary.copy(alpha = if (isDark) 0.22f + extra else 0.16f + extra)

    /** 次级氛围光斑色（主色暗调） */
    fun ambientSecondary(): Color =
        primaryDark.copy(alpha = if (isDark) 0.16f else 0.10f)
}

fun GlassColors(ctx: Context): GlassColors {
    val attrs = intArrayOf(
        R.attr.dsaPrimary,
        R.attr.dsaPrimaryDark,
        R.attr.dsaOnPrimary,
        R.attr.dsaPrimarySoft,
        R.attr.dsaText,
        R.attr.dsaText2,
        R.attr.dsaCard,
        R.attr.dsaSurface2,
        R.attr.dsaBg,
        R.attr.dsaGlassTint,
        R.attr.dsaGlassStroke,
        R.attr.dsaGlassHi,
        R.attr.dsaNavBg,
        R.attr.dsaDivider
    )
    val a = ctx.theme.obtainStyledAttributes(attrs)
    fun col(index: Int, def: Int): Color = Color(a.getColor(index, def))
    val colors = GlassColors(
        primary = col(0, 0xFFFF7FA8.toInt()),
        primaryDark = col(1, 0xFFE0648F.toInt()),
        onPrimary = col(2, 0xFFFFFFFF.toInt()),
        primarySoft = col(3, 0xFFFFE4EE.toInt()),
        text = col(4, 0xFF17171C.toInt()),
        text2 = col(5, 0xFF6B6B76.toInt()),
        card = col(6, 0xFFFFFFFF.toInt()),
        surface2 = col(7, 0xFFEFEFF3.toInt()),
        bg = col(8, 0xFFF6F6F9.toInt()),
        glassTint = col(9, 0x14FFFFFF.toInt()),
        glassStroke = col(10, 0x14000000.toInt()),
        glassHi = col(11, 0x14FFFFFF.toInt()),
        navBg = col(12, 0xE8FFFFFF.toInt()),
        divider = col(13, 0xFFE6E6EC.toInt()),
        isDark = ThemeManager.isDark(ctx),
        tagAlpha = Prefs.tagAlpha(ctx) / 100f
    )
    a.recycle()
    return colors
}
