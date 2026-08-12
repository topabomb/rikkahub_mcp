package net.weero.measix.pilot.ui.components.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.weero.measix.pilot.R

/**
 * 子助手头像右下角叠圆。贴头像边缘，不外探。
 *
 * 当作一枚小釉面凸点：tertiary 径向体积、新月高光、发丝边框。
 * 发丝用 `surfaceContainerHighest`，避免贴在照片或渐变上糊成一块。
 */
private val MarkSize = 8.dp

@Composable
fun SubAssistantAvatarMark(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.assistant_page_sub_assistant_tag)
    val fill = MaterialTheme.colorScheme.tertiary
    val ring = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        modifier = modifier
            .size(MarkSize)
            .semantics { contentDescription = description },
    ) {
        val radius = size.minDimension / 2f
        val light = lerp(fill, Color.White, 0.36f)
        val shade = lerp(fill, Color.Black, 0.22f)
        val highlightAt = Offset(center.x - radius * 0.30f, center.y - radius * 0.32f)

        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to light,
                    0.42f to fill,
                    1.00f to shade,
                ),
                center = highlightAt,
                radius = radius * 1.28f,
            ),
            radius = radius,
        )

        val volumeInset = radius * 0.40f
        val volumeSize = Size((radius - volumeInset) * 2f, (radius - volumeInset) * 2f)
        val volumeOrigin = Offset(center.x - radius + volumeInset, center.y - radius + volumeInset)
        drawArc(
            color = Color.Black.copy(alpha = 0.18f),
            startAngle = 24f,
            sweepAngle = 76f,
            useCenter = false,
            topLeft = volumeOrigin,
            size = volumeSize,
            style = Stroke(width = 0.75.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = shade.copy(alpha = 0.40f),
            radius = radius - 1.05.dp.toPx(),
            style = Stroke(width = 0.65.dp.toPx()),
        )

        val crescentInset = radius * 0.44f
        drawArc(
            color = Color.White.copy(alpha = 0.52f),
            startAngle = 200f,
            sweepAngle = 78f,
            useCenter = false,
            topLeft = Offset(center.x - radius + crescentInset, center.y - radius + crescentInset),
            size = Size((radius - crescentInset) * 2f, (radius - crescentInset) * 2f),
            style = Stroke(width = 0.8.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.86f),
            radius = 0.65.dp.toPx(),
            center = Offset(center.x - radius * 0.18f, center.y - radius * 0.22f),
        )

        drawCircle(
            color = ring,
            radius = radius - 0.45.dp.toPx(),
            style = Stroke(width = 0.7.dp.toPx()),
        )
    }
}
