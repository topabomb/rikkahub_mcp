package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallArtifact
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
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
    fun `same ref direct output image takes precedence over equivalent manifest`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val file = kotlin.io.path.createTempFile(suffix = ".png").toFile().apply { deleteOnExit() }
        val managed = LocalArtifactRef(relativePath = "upload/shared.png", mimeType = "image/png")
        val outputImage = AttachmentRefs.withMetadata(
            UIMessagePart.Image(AttachmentRefs.fileToFileUrl(file)),
            AttachmentRefs.mergeMetadata(
                null,
                mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(ref)),
            ),
        ) as UIMessagePart.Image
        val tool = UIMessagePart.Tool(
            toolCallId = "child",
            toolName = "assistant_call",
            input = "{}",
            output = listOf(outputImage),
        ).mergeSubAssistantCallMetadata(
            JsonInstant,
            SubAssistantCallMetadata(
                runId = Uuid.random().toString(),
                targetAssistantId = Uuid.random().toString(),
                targetNameSnapshot = "Image assistant",
                state = SubAssistantCallState.COMPLETED,
                artifacts = listOf(SubAssistantCallArtifact(ref, "image", "image/png", managed)),
            ),
        )
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveImagePreviewForFile(any()) } returns AttachmentRefs.fileToFileUrl(file)
        val previews = ConversationAttachmentPreviewProjector(store).project(snapshotOf(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))),
        ))

        assertEquals(AttachmentRefs.fileToFileUrl(file), previews[ref])
    }

    @Test
    fun `direct image and sub-assistant artifact use the same stable ref projection`() = runTest {
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
        coEvery { store.resolveImagePreviewForFile(any()) } returns directUrl
        coEvery { store.resolveImagePreviewForArtifact(managed) } returns AttachmentRefs.fileToFileUrl(artifactFile)
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
    fun `message-part projection accepts nested local images but never remote urls`() = runTest {
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
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveImagePreviewForFile(any()) } returns localUrl
        val projector = ConversationAttachmentPreviewProjector(store)

        val previews = projector.project(snapshotOf(messages))

        assertEquals(localUrl, previews[localRef])
        assertNull(previews[remoteRef])
        assertNull(previews["https://cdn.example/remote.png"])
        assertNull(previews[AttachmentRefs.format(Uuid.random())])
    }

    @Test
    fun `artifact lifecycle changes invalidate preview on the next query`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val file = kotlin.io.path.createTempFile(suffix = ".png").toFile().apply { deleteOnExit() }
        val image = AttachmentRefs.withMetadata(
            UIMessagePart.Image(AttachmentRefs.fileToFileUrl(file)),
            AttachmentRefs.mergeMetadata(
                null,
                mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(ref)),
            ),
        )
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveImagePreviewForFile(any()) } returnsMany listOf(
            AttachmentRefs.fileToFileUrl(file),
            null,
        )
        val projector = ConversationAttachmentPreviewProjector(store)
        val snapshot = snapshotOf(listOf(UIMessage(role = MessageRole.USER, parts = listOf(image))))

        assertEquals(AttachmentRefs.fileToFileUrl(file), projector.project(snapshot)[ref])
        assertNull(projector.project(snapshot)[ref])
    }

    @Test
    fun `unmanaged local file URL is never exposed as a preview`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val image = AttachmentRefs.withMetadata(
            UIMessagePart.Image("file:///data/data/hidden/private.png"),
            AttachmentRefs.mergeMetadata(
                null,
                mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(ref)),
            ),
        )
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveImagePreviewForFile(any()) } returns null

        assertNull(
            ConversationAttachmentPreviewProjector(store)
                .project(snapshotOf(listOf(UIMessage(role = MessageRole.USER, parts = listOf(image)))))[ref],
        )
    }

    @Test
    fun `direct preview propagates cancellation`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val image = AttachmentRefs.withMetadata(
            UIMessagePart.Image("file:///managed/cancel.png"),
            AttachmentRefs.mergeMetadata(
                null,
                mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(ref)),
            ),
        )
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveImagePreviewForFile(any()) } throws CancellationException("switch conversation")

        try {
            ConversationAttachmentPreviewProjector(store).project(
                snapshotOf(listOf(UIMessage(role = MessageRole.USER, parts = listOf(image)))),
            )
            org.junit.Assert.fail("cancellation must propagate")
        } catch (_: CancellationException) {
            // expected: mapLatest can stop the query projection promptly
        }
    }

    @Test
    fun `managed preview exception is fail closed without stopping the query`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val managed = LocalArtifactRef(relativePath = "upload/managed.png", mimeType = "image/png")
        val tool = UIMessagePart.Tool(
            toolCallId = "assistant-call",
            toolName = "assistant_call",
            input = "{}",
        ).mergeSubAssistantCallMetadata(
            JsonInstant,
            SubAssistantCallMetadata(
                runId = Uuid.random().toString(),
                targetAssistantId = Uuid.random().toString(),
                targetNameSnapshot = "Image assistant",
                state = SubAssistantCallState.COMPLETED,
                artifacts = listOf(SubAssistantCallArtifact(ref, "image", "image/png", managed)),
            ),
        )
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveImagePreviewForArtifact(managed) } throws IllegalStateException("database unavailable")

        assertEquals(
            emptyMap<String, String>(),
            ConversationAttachmentPreviewProjector(store).project(
                snapshotOf(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))),
            ),
        )
    }

    @Test
    fun `artifact lifecycle flow emits initial state and later invalidations`() = runTest {
        val artifacts = MutableStateFlow<List<ArtifactEntity>>(emptyList())
        val store = mockk<ArtifactStore>()
        every { store.observe() } returns artifacts
        val projector = ConversationAttachmentPreviewProjector(store)
        val emissions = mutableListOf<Unit>()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            projector.lifecycleChanges().take(2).collect { emissions += it }
        }
        artifacts.value = listOf(mockk())
        job.join()

        assertEquals(2, emissions.size)
    }

    private fun snapshotOf(messages: List<UIMessage>) = Conversation.ofId(
        id = Uuid.random(),
        assistantId = DEFAULT_ASSISTANT_ID,
        messages = messages.map(UIMessage::toMessageNode),
    ).toSnapshot()
}
