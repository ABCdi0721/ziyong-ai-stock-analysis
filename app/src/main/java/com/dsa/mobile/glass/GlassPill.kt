package com.dsa.mobile.glass

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 胶囊形液态玻璃（涨跌标签、小按钮、徽章等） */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    stroke: Color = Color.Unspecified,
    highlight: Color = Color.Unspecified,
    lensHeightDp: Dp = 8.dp,
    lensAmountDp: Dp = 10.dp,
    shadowEnabled: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LocalGlassColors.current
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        tint = tint.ifUnspecified { colors?.pillTint() ?: Color.Unspecified },
        stroke = stroke.ifUnspecified { colors?.glassStroke ?: Color.Unspecified },
        highlightColor = highlight.ifUnspecified { colors?.highlight() ?: Color.Unspecified },
        lensHeightDp = lensHeightDp,
        lensAmountDp = lensAmountDp,
        shadowEnabled = shadowEnabled,
        onClick = onClick,
        content = content
    )
}

/** 圆角矩形液态玻璃卡片（列表卡片、分组面板等） */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerDp: Dp = 20.dp,
    tint: Color = Color.Unspecified,
    stroke: Color = Color.Unspecified,
    highlight: Color = Color.Unspecified,
    blurDp: Dp = 0.dp,
    shadowEnabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LocalGlassColors.current
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerDp),
        tint = tint.ifUnspecified { colors?.cardTint() ?: Color.Unspecified },
        stroke = stroke.ifUnspecified { colors?.glassStroke ?: Color.Unspecified },
        highlightColor = highlight.ifUnspecified { colors?.highlight() ?: Color.Unspecified },
        blurDp = blurDp,
        lensHeightDp = 10.dp,
        lensAmountDp = 14.dp,
        shadowEnabled = shadowEnabled,
        onClick = onClick,
        content = content
    )
}

private fun Color.ifUnspecified(fallback: () -> Color): Color =
    if (this == Color.Unspecified) fallback() else this
