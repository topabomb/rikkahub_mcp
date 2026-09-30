package net.weero.measix.pilot.ui.components.richtext

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import net.weero.measix.pilot.service.ImageSource

internal val LocalMarkdownHtmlAllowed = staticCompositionLocalOf { true }

/** File readers provide their own authorized image and explicit link actions. No network fallback. */
@Composable
internal fun RestrictedMarkdown(
    content: String, modifier: Modifier = Modifier,
    images: suspend (String) -> ImageSource?, openLink: (String) -> Unit,
) {
    val actions = remember(openLink) {
        RichTextActions(object : UriHandler {
            override fun openUri(uri: String) {
                if (android.net.Uri.parse(uri).scheme?.lowercase() in setOf("https", "http", "mailto")) openLink(uri)
            }
        }, exportText = null, preview = {})
    }
    CompositionLocalProvider(LocalRichTextActions provides actions, LocalImageSourceResolver provides images) {
        MarkdownBlock(content, modifier, allowHtml = false)
    }
}
