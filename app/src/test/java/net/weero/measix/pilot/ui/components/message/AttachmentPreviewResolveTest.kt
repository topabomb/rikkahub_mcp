package net.weero.measix.pilot.ui.components.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class AttachmentPreviewResolveTest {
    private val ref = AttachmentRefs.format(Uuid.random())

    private fun message(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.USER, parts = parts.toList())

    @Test
    fun `local file ref resolves to its url`() {
        val url = "file:///data/user/0/files/upload/a.png"
        val messages = listOf(
            message(
                UIMessagePart.Image(
                    url = url,
                    metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                ),
            ),
        )
        assertEquals(url, resolveAttachmentPreviewUrl(messages, ref))
    }

    @Test
    fun `remote url ref resolves to null for ui boundary`() {
        // UI 不为渲染发起远程下载
        val messages = listOf(
            message(
                UIMessagePart.Image(
                    url = "https://cdn.example/a.png",
                    metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                ),
            ),
        )
        assertNull(resolveAttachmentPreviewUrl(messages, ref))
    }

    @Test
    fun `unknown or malformed ref resolves to null`() {
        val messages = listOf(
            message(
                UIMessagePart.Image(
                    url = "file:///data/user/0/files/upload/a.png",
                    metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                ),
            ),
        )
        assertNull(resolveAttachmentPreviewUrl(messages, AttachmentRefs.format(Uuid.random())))
        assertNull(resolveAttachmentPreviewUrl(messages, "https://cdn.example/a.png"))
        assertNull(resolveAttachmentPreviewUrl(emptyList(), ref))
    }

    @Test
    fun `ref inside tool output is found`() {
        val url = "file:///data/user/0/files/upload/b.png"
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "g1",
                        toolName = "generate_image",
                        input = "{}",
                        output = listOf(
                            UIMessagePart.Image(
                                url = url,
                                metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(url, resolveAttachmentPreviewUrl(messages, ref))
    }
}
