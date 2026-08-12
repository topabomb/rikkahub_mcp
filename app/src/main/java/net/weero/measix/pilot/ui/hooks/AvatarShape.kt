package net.weero.measix.pilot.ui.hooks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 主助手生成中：六边 Cookie，约 3 秒一圈。 */
@Composable
fun rememberAvatarShape(loading: Boolean): Shape {
    val infiniteTransition = rememberInfiniteTransition(label = "assistant-avatar-morph")
    val rotateAngle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
        ),
        label = "assistant-avatar-angle",
    )
    return if (loading) {
        MaterialShapes.Cookie6Sided.toShape(rotateAngle.value.roundToInt())
    } else {
        CircleShape
    }
}

/**
 * 子助手进行中：贴圆形内沿走一圈柔光。
 * 像边框上掠过的高光：两端淡入淡出，主题色为主，白只给中间一点染色。
 * 不画光点、不画外轨。结束后不加装饰。不改头像形状和占位。
 *
 * 调用方只在进行中套用此 Modifier，避免对每个头像常驻无限动画，
 * 也避免在同一 Composable 里按 active 跳过 remember*。
 */
@Composable
fun Modifier.subAssistantActivityRing(color: Color): Modifier {
    val transition = rememberInfiniteTransition(label = "sub-assistant-arc")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4800, easing = LinearEasing),
        ),
        label = "sub-assistant-arc-progress",
    )
    return drawWithContent {
        drawContent()
        val stroke = 1.4.dp.toPx()
        val radius = size.minDimension / 2f - stroke / 2f
        val pivot = center
        val sheen = lerp(color, Color.White, 0.22f)
        drawCircle(
            color = color.copy(alpha = 0.08f),
            radius = radius,
            style = Stroke(width = 1.dp.toPx()),
        )
        rotate(degrees = progress * 360f, pivot = pivot) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Transparent,
                        0.10f to color.copy(alpha = 0.05f),
                        0.20f to color.copy(alpha = 0.12f),
                        0.28f to Color.Transparent,
                        1.00f to Color.Transparent,
                    ),
                    center = pivot,
                ),
                radius = radius,
                style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
            )
            drawCircle(
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Transparent,
                        0.06f to color.copy(alpha = 0.06f),
                        0.12f to color.copy(alpha = 0.18f),
                        0.18f to color.copy(alpha = 0.34f),
                        0.23f to sheen.copy(alpha = 0.46f),
                        0.28f to color.copy(alpha = 0.20f),
                        0.36f to Color.Transparent,
                        1.00f to Color.Transparent,
                    ),
                    center = pivot,
                ),
                radius = radius,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
