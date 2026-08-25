package net.weero.measix.pilot.service

import io.mockk.every
import io.mockk.mockk
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallArtifact
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class ConversationAttachmentPreviewProjectorTest {
    @Test
    fun `direct image and sub-assistant artifact use the same stable ref projection`() {
        val directRef = AttachmentRefs.format(Uuid.random())
        val directUrl = "file:///managed/direct.png"
        val direct = AttachmentRefs.withMetadata(
            UIMessagePart.Image(directUrl),
            AttachmentRefs.mergeMetadata(
                null,
                mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(directRef)),
            ),
        )
        val artifactRef = AttachmentRefs.format(Uuid.random())
        val managed = LocalArtifactRef(relativePath = "upload/generated.png", mimeType = "image/png")
        val artifactFile = kotlin.io.path.createTempFile(suffix = ".png").toFile().apply { deleteOnExit() }
        val store = mockk<ArtifactStore>()
        every { store.file(managed) } returns artifactFile
        val subAssistantTool = UIMessagePart.Tool(
            toolCallId = "child",
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
                        ref = artifactRef,
                        type = "image",
                        mime = "image/png",
                        artifact = managed,
                    ),
                ),
            ),
        )
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(direct)),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(subAssistantTool)),
        )
        val projector = ConversationAttachmentPreviewProjector(store)

        val previews = projector.project(snapshotOf(messages))

        assertEquals(directUrl, previews[directRef])
        assertEquals(AttachmentRefs.fileToFileUrl(artifactFile), previews[artifactRef])
    }

    @Test
    fun `message-part projection accepts nested local images but never remote urls`() {
        val localRef = AttachmentRefs.format(Uuid.random())
        val remoteRef = AttachmentRefs.format(Uuid.random())
        fun image(url: String, ref: String) = AttachmentRefs.withMetadata(
            UIMessagePart.Image(url),
            AttachmentRefs.mergeMetadata(
                null,
                mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(ref)),
            ),
        )
        val localUrl = "file:///managed/generated.png"
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "generate",
                        toolName = "generate_image",
                        input = "{}",
                        output = listOf(
                            image(localUrl, localRef),
                            image("https://cdn.example/remote.png", remoteRef),
                        ),
                    ),
                ),
            ),
        )
        val projector = ConversationAttachmentPreviewProjector(mockk(relaxed = true))

        val previews = projector.project(snapshotOf(messages))

        assertEquals(localUrl, previews[localRef])
        assertNull(previews[remoteRef])
        assertNull(previews["https://cdn.example/remote.png"])
        assertNull(previews[AttachmentRefs.format(Uuid.random())])
    }

    private fun snapshotOf(messages: List<UIMessage>) = Conversation.ofId(
        id = Uuid.random(),
        assistantId = DEFAULT_ASSISTANT_ID,
        messages = messages.map(UIMessage::toMessageNode),
    ).toSnapshot()
}
