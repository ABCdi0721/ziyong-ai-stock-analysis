package com.dsa.mobile.glass

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 主界面底部 dock 的透明玻璃栏。
 *
 * 背景不再抓取后方内容快照位图：dock 在视图层级上悬浮在铺满全屏的 pager 之上，
 * 这里只叠一层很淡的主题色半透明叠层 + 高光描边，下方页面内容由真实视图层级直接透出，
 * 拖动 / 切页时随页面实时跟随，不存在快照帧滞后或旧帧残留。
 *
 * 说明：backdrop 库的高斯模糊 / 透镜折射只能作用于同一 Compose 树内录制到的背景层，
 * 跨层级的 View 页面录不进去；因此本栏不做透出模糊，下方内容保持原样透显。
 * 「背景模糊」开关仍作用于根背景图（见 ThemeManager.bgDrawable），与本栏无关。
 *
 * @param colors 玻璃配色
 * @param blurEnabled 外观设置「背景模糊」开关；透明叠层下仅微调表面叠层深浅，不做透出模糊
 */
@Composable
fun DockGlassBar(
    colors: GlassColors,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val baseAlpha = if (colors.isDark) 0.30f else 0.38f
    val tintAlpha = baseAlpha + if (blurEnabled) 0.06f else 0f
    GlassSurface(
        modifier = modifier,
        // 全圆角胶囊底栏（两端半圆）
        shape = RoundedCornerShape(percent = 50),
        // 无快照背景层：下方真实页面直接透出，叠层只压淡底色保证图标 / 文字可读
        tint = colors.navBg.copy(alpha = tintAlpha),
        stroke = colors.glassStroke,
        // 跨层级 View 无法被 backdrop 录制，关闭无效的模糊与折射，只保留表面叠层与描边
        blurDp = 0.dp,
        lensHeightDp = 0.dp,
        lensAmountDp = 0.dp,
        shadowEnabled = false,
        rainbowStroke = true,
        isDark = colors.isDark,
        content = content
    )
}
