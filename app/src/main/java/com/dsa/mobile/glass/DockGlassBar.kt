package com.dsa.mobile.glass

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

/**
 * 主界面底部 dock 的真液态玻璃栏。
 *
 * 背景来自 [DockSnapshot] 捕获的后方内容快照位图（CanvasBackdrop），库的 blur / lens / vibrancy
 * 作用在该快照层上，从而透出并模糊、折射后方卡片与图标；表面只叠一层很淡的主题色与高光描边，
 * 不再垫实底遮挡内容。快照缺失（首帧未布局 / 捕获失败）时回退到半透明主题底色。
 *
 * @param snapshot 后方内容快照；null 表示暂无快照，回退半透明叠层
 * @param blurEnabled 是否开启背景高斯模糊（跟随外观设置「背景模糊」开关）
 */
@Composable
fun DockGlassBar(
    snapshot: ImageBitmap?,
    colors: GlassColors,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = rememberCanvasBackdrop {
        snapshot?.let { bmp ->
            drawImage(
                image = bmp,
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        }
    }

    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        backdrop = backdrop,
        // 表面叠层进一步压淡，让后方卡片 / 文字透过快照层被模糊后更清晰可见
        tint = colors.navBg.copy(alpha = if (colors.isDark) 0.34f else 0.42f),
        stroke = colors.glassStroke,
        blurDp = if (blurEnabled) 12.dp else 0.dp,
        lensHeightDp = 16.dp,
        lensAmountDp = 22.dp,
        shadowEnabled = false,
        rainbowStroke = true,
        isDark = colors.isDark,
        content = content
    )
}
