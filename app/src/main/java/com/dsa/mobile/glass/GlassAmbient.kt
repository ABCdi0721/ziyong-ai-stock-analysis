package com.dsa.mobile.glass

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize

/**
 * 全局氛围光晕背景层：在页面最底层叠加主色径向光斑，为液态玻璃提供通透的底色氛围。
 *
 * 纯视觉层，不录制/模糊后方 View；根背景纯色仍由 dsaBg 承担，这里只叠加低透明度光斑。
 * 浅色模式光斑更淡、深色模式略浓，跟随当前深浅主题与预设主色。
 */
@Composable
fun GlassAmbientBackground(
    colors: GlassColors,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 顶部偏左主色光斑
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(colors.ambientPrimary(), Color.Transparent),
                center = Offset(w * 0.12f, h * 0.02f),
                radius = w * 0.9f
            )
        )
        // 底部偏右主色暗调光斑
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(colors.ambientSecondary(), Color.Transparent),
                center = Offset(w * 0.98f, h * 0.88f),
                radius = w * 0.8f
            )
        )
    }
}
