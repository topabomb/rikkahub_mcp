package net.weero.measix.pilot.data.ai.attachments

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallArtifact
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AttachmentReferenceLookupTest {
    @Test
    fun `non multimedia metadata cannot claim an attachment ref`() {
        val ref = AttachmentRefs.format(Uuid.random())
        val text = UIMessagePart.Text(
            text = "not an attachment",
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
        )
        val image = UIMessagePart.Image(
            url = "file:///tmp/image.png",
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
        )

        val target = AttachmentReferenceLookup.index(
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(text, image))),
        )[ref]

        assertTrue(target is AttachmentReferenceTarget.MessagePart)
        assertEquals(image, (target as AttachmentReferenceTarget.MessagePart).part)
    }

    @Test
    fun `direct part takes precedence over its managed artifact manifest`() {
        val ref = AttachmentRefs.format(Uuid.random())
        val managed = LocalArtifactRef(relativePath = "upload/generated.png", mimeType = "image/png")
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
            toolName = "assistant_call",
            input = "{}",
        ).mergeSubAssistantCallMetadata(
            JsonInstant,
            SubAssistantCallMetadata(
                runId = Uuid.random().toString(),
                targetAssistantId = Uuid.random().toString(),
                targetNameSnapshot = "Image assistant",
                state = SubAssistantCallState.COMPLETED,
                artifacts = listOf(
                    SubAssistantCallArtifact(
                        ref = ref,
                        type = "image",
                        mime = "image/png",
                        artifact = managed,
                    ),
                ),
            ),
        )
        val image = UIMessagePart.Image(
            url = "file:///tmp/direct.png",
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, JsonPrimitive(ref)) },
        )

        val target = AttachmentReferenceLookup.index(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool, image))),
        )[ref]

        assertEquals(image, (target as AttachmentReferenceTarget.MessagePart).part)
    }

    @Test
    fun `different direct resources claiming one ref fail closed`() {
        val ref = AttachmentRefs.format(Uuid.random())
        fun image(url: String) = UIMessagePart.Image(
            url = url,
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
        )

        val target = AttachmentReferenceLookup.index(
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(image("file:///tmp/a.png"), image("file:///tmp/b.png")))),
        )[ref]

        assertEquals(AttachmentReferenceTarget.Conflict, target)
    }
}
