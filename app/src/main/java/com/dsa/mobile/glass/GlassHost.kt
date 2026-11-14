package com.dsa.mobile.glass

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.ComposeView

/** 当前 Compose 树内可读取的玻璃配色；未注入时为 null，组件应回退到默认色 */
val LocalGlassColors = compositionLocalOf<GlassColors?> { null }

/** 注入当前主题配色，供子级玻璃组件读取 */
@Composable
fun GlassTheme(colors: GlassColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassColors provides colors, content = content)
}

/**
 * 便捷挂载：在现有 View 体系的 ComposeView 上读取当前主题配色并渲染玻璃内容。
 * 业务/数据/导航仍由外层 View 持有，这里只负责视觉层。
 */
fun ComposeView.setGlassContent(ctx: Context, content: @Composable (GlassColors) -> Unit) {
    val colors = GlassColors(ctx)
    setContent {
        GlassTheme(colors) { content(colors) }
    }
}
