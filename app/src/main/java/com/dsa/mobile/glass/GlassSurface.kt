package com.dsa.mobile.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃容器（基于 Kyant backdrop 库的 blur / lens / vibrancy / highlight 原语）。
 *
 * 说明：库的折射/模糊只作用于同一 Compose 树内录制到的背景层；嵌入现有 View 页面时，
 * 跨层级的 View 背景无法被录制，因此默认使用 [emptyBackdrop]，玻璃观感来自表面叠层、
 * 高光描边、边缘折射（lens）与库自带的高光/投影；需要局部底色时可传入自绘 CanvasBackdrop。
 *
 * @param tint 表面半透明叠层色（含 alpha），Unspecified 表示不叠色
 * @param stroke 边缘高光描边色，Unspecified 表示不描边
 * @param blurDp 背景模糊半径（仅 Android 12+ 生效，0 表示不模糊）
 * @param lensHeightDp 折射高度（边缘透镜厚度，0 表示关闭折射）
 * @param lensAmountDp 折射强度
 * @param shadowEnabled 是否启用库默认柔和投影
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    backdrop: Backdrop = emptyBackdrop(),
    tint: Color = Color.Unspecified,
    stroke: Color = Color.Unspecified,
    blurDp: Dp = 0.dp,
    lensHeightDp: Dp = 10.dp,
    lensAmountDp: Dp = 14.dp,
    shadowEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                if (blurDp > 0.dp) blur(blurDp.toPx())
                if (lensHeightDp > 0.dp && lensAmountDp > 0.dp) {
                    lens(lensHeightDp.toPx(), lensAmountDp.toPx())
                }
            },
            shadow = if (shadowEnabled) ({ Shadow.Default }) else null,
            onDrawSurface = {
                if (tint != Color.Unspecified) drawRect(tint)
            },
            onDrawFront = {
                if (stroke != Color.Unspecified) {
                    val r = (shape as? CornerBasedShape)?.let {
                        val px = it.topStart.toPx(size, this)
                        CornerRadius(px, px)
                    } ?: CornerRadius(20.dp.toPx(), 20.dp.toPx())
                    drawRoundRect(
                        color = stroke,
                        cornerRadius = r,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        ),
        content = content
    )
}
