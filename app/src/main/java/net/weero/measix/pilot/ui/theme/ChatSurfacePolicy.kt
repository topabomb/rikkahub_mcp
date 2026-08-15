package net.weero.measix.pilot.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import net.weero.measix.pilot.data.datastore.DisplaySetting
import net.weero.measix.pilot.data.model.Assistant

private const val UNSPECIFIED_CHROME_ALPHA = -1f

/**
 * 聊天页消息层 chrome 的容器透明度。
 *
 * - 未提供时默认不透明；消息气泡可传入 [DisplaySetting.bubbleOpacity] 作为回退
 * - [ProvideChatSurfacePolicy] 在聊天主界面按助手背景写入最终值
 */
val LocalChatChromeAlpha = compositionLocalOf { UNSPECIFIED_CHROME_ALPHA }

fun Assistant.hasVisibleChatBackground(): Boolean {
    return useGradientBackground || !background.isNullOrBlank()
}

/**
 * 助手背景下各类渲染卡片（思考过程、气泡、子助手卡、空态卡等）的透明度标准。
 *
 * 产物（代码块、表格、公式、媒体、工具输出正文）必须保持不透明，不得走此策略。
 */
object ChatSurfacePolicy {
    const val MIN_CHROME_ALPHA = 0.1f
    const val BACKGROUND_CHROME_MAX_ALPHA = 0.82f

    fun chromeAlpha(hasVisibleBackground: Boolean, bubbleOpacity: Float): Float {
        val user = bubbleOpacity.coerceIn(MIN_CHROME_ALPHA, 1f)
        return if (hasVisibleBackground) {
            minOf(user, BACKGROUND_CHROME_MAX_ALPHA)
        } else {
            user
        }
    }

    fun pageChromeAlpha(hasVisibleBackground: Boolean): Float {
        return if (hasVisibleBackground) BACKGROUND_CHROME_MAX_ALPHA else 1f
    }

    fun artifactAlpha(): Float = 1f
}

fun Color.withOverlayAlpha(alpha: Float): Color {
    return if (alpha >= 0.999f) this else copy(alpha = alpha)
}

@Composable
fun ProvideChatSurfacePolicy(
    assistant: Assistant,
    displaySetting: DisplaySetting,
    content: @Composable () -> Unit,
) {
    val alpha = ChatSurfacePolicy.chromeAlpha(
        hasVisibleBackground = assistant.hasVisibleChatBackground(),
        bubbleOpacity = displaySetting.bubbleOpacity,
    )
    CompositionLocalProvider(LocalChatChromeAlpha provides alpha, content = content)
}

@Composable
fun resolvedChatChromeAlpha(fallbackBubbleOpacity: Float? = null): Float {
    val provided = LocalChatChromeAlpha.current
    if (provided >= 0f) return provided
    val bubbleOpacity = fallbackBubbleOpacity ?: return 1f
    return ChatSurfacePolicy.chromeAlpha(
        hasVisibleBackground = false,
        bubbleOpacity = bubbleOpacity,
    )
}

@Composable
fun Color.asChatChrome(fallbackBubbleOpacity: Float? = null): Color {
    return withOverlayAlpha(resolvedChatChromeAlpha(fallbackBubbleOpacity))
}
