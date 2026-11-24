package com.dsa.mobile.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主操作按钮：主色渐变实底 + 白字 + 大圆角，按压 Q 弹缩放。
 * 业务点击逻辑由 onClick 透传，组件只负责视觉与反馈。
 */
@Composable
fun GlassPrimaryButton(
    colors: GlassColors,
    text: String,
    modifier: Modifier = Modifier,
    cornerDp: Dp = 20.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "primaryButtonScale"
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(cornerDp))
            .background(Brush.linearGradient(listOf(colors.primary, colors.primaryDark)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = colors.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        )
    }
}

/**
 * 玻璃次按钮：胶囊玻璃壳 + 主色文字，按压 Q 弹。
 */
@Composable
fun GlassGlassButton(
    colors: GlassColors,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassPill(modifier = modifier, onClick = onClick) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = text,
                style = TextStyle(
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            )
        }
    }
}
