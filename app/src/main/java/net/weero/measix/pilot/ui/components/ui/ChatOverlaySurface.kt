package net.weero.measix.pilot.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import net.weero.measix.pilot.ui.theme.ChatSurfacePolicy
import net.weero.measix.pilot.ui.theme.withOverlayAlpha

/** 输入卡片和悬浮播放工具条共用背景材质，不降低内容的不透明度。 */
@Composable
fun ChatOverlaySurface(
    shape: Shape,
    enableBlurEffect: Boolean,
    hasVisibleBackground: Boolean,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.surfaceContainerLow
    val blurStyle = HazeBlurStyle.Material3(containerColor = tint) {
        blurRadius(12.dp)
    }
    val blurEnabled = enableBlurEffect && hazeState != null
    Surface(
        modifier = modifier.clip(shape).then(
            if (blurEnabled) Modifier.hazeBlur(
                input = HazeInput.Sources(hazeState),
                style = blurStyle,
            ) else Modifier
        ),
        shape = shape,
        color = if (blurEnabled) Color.Transparent else tint.withOverlayAlpha(
            ChatSurfacePolicy.pageChromeAlpha(hasVisibleBackground)
        ),
        tonalElevation = 0.dp,
        border = border,
        content = content,
    )
}
