package net.weero.measix.pilot.service

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import net.weero.measix.pilot.data.files.AttachmentCloner
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class ConversationAttachmentPreviewProjectorTest {
    @Test
    fun `known tool input previews a managed image without any conversation attachment`() = runTest {
        val store = mockk<ArtifactStore>()
        val file = File("D:/managed/abc123.png")
        coEvery { store.resolveToolPath("/upload/abc123.png") } returns file
        coEvery { store.resolveImagePreviewForFile(file) } returns "file:///D:/managed/abc123.png"
        for (toolName in listOf("inspect_attachments", "assistant_call")) {
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                toolCallId = "call", toolName = toolName,
                input = """{"attachments":["/upload/abc123.png"],"request":"read this"}""",
            )))
            val snapshot = snapshotOf(listOf(message))
            val before = snapshot.nodes
            val result = ConversationAttachmentPreviewProjector(store).project(snapshot)
            assertEquals(mapOf("/upload/abc123.png" to "file:///D:/managed/abc123.png"), result)
            assertEquals(before, snapshot.nodes)
        }
        coEvery { store.resolveToolPath("/upload/abc123.png") } returns null
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "call", toolName = "inspect_attachments",
            input = """{"attachments":["/upload/abc123.png"]}""",
        )))
        assertTrue(ConversationAttachmentPreviewProjector(store).project(snapshotOf(listOf(message))).isEmpty())
    }

    @Test
    fun `preview does not interpret unrelated tool inputs or unsafe paths`() = runTest {
        val store = mockk<ArtifactStore>()
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                toolCallId = "unknown", toolName = "other", input = """{"attachments":["/upload/abc123.png"]}""",
            ))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                toolCallId = "bad", toolName = "inspect_attachments",
                input = """{"attachments":["/upload/../private.png","attachment:123","https://example.com/a.png"]}""",
            ))),
        )
        assertTrue(ConversationAttachmentPreviewProjector(store).project(snapshotOf(messages)).isEmpty())
        coVerify(exactly = 0) { store.resolveToolPath(any()) }
    }
    @Test
    fun `forked inspection input resolves its copied preview without retaining a source path alias`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("forked-inspection-preview").toFile()
        try {
            val sourceRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
            val copiedRef = LocalArtifactRef(relativePath = "upload/copied.png", mimeType = "image/png")
            val sourceFile = sourceRef.file(filesDir).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val copiedFile = copiedRef.file(filesDir)
            val stableRef = AttachmentRefs.format(Uuid.random())
            val sourceImage = stampedImage(sourceRef.fileUri(filesDir), stableRef)
            val sourcePath = sourceRef.toolPath()!!
            val inspection = UIMessagePart.Tool(
                toolCallId = "inspection", toolName = "inspect_attachments",
                input = """{"attachments":["$sourcePath"],"request":"Read the image"}""",
                output = listOf(UIMessagePart.Text("Image inspected")),
            )
            val store = mockk<ArtifactStore>()
            val owned = mockk<OwnedArtifact>()
            val copiedUri = mockk<Uri>()
            every { owned.localRef } returns copiedRef
            every { owned.uri } returns copiedUri
            every { copiedUri.toString() } returns copiedRef.fileUri(filesDir)
            every { store.file(sourceRef) } returns sourceFile
            coEvery { store.resolveManagedReference(sourceFile) } returns sourceRef
            coEvery { store.resolveManagedReference(copiedFile) } returns copiedRef
            coEvery { store.resolveToolPath(sourcePath) } returns sourceFile
            coEvery { store.copyFilePreservingOrigin(sourceFile, "image/png", sourceFile.name, any()) } answers {
                sourceFile.copyTo(copiedFile)
                owned
            }
            coEvery { store.resolveImagePreviewForFile(copiedFile) } answers {
                copiedRef.fileUri(filesDir).takeIf { copiedFile.isFile }
            }
            val created = mutableListOf<OwnedArtifact>()
            val copiedArtifacts = linkedMapOf<String, OwnedArtifact>()
            val rewriter = ToolArtifactRewriter(filesDir, store)
            val forkMessages = listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(sourceImage)),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(inspection)),
            ).map { message ->
                message.copy(parts = AttachmentCloner.cloneParts(
                    message.parts, store, created, rewriter, copiedArtifacts,
                ))
            }
            assertTrue(sourceFile.delete())

            val previews = ConversationAttachmentPreviewProjector(store).project(snapshotOf(forkMessages))
            val clonedInspection = forkMessages.last().parts.single() as UIMessagePart.Tool
            val inputRef = JsonInstant.parseToJsonElement(clonedInspection.input).jsonObject["attachments"]!!
                .jsonArray.single().jsonPrimitive.content

            assertEquals(copiedRef.toolPath(), inputRef)
            assertEquals(copiedRef.fileUri(filesDir), previews[inputRef])
            assertEquals(copiedRef.fileUri(filesDir), previews[stableRef])
            assertNull(previews[sourcePath])
            assertEquals(sourcePath, JsonInstant.parseToJsonElement(inspection.input).jsonObject["attachments"]!!
                .jsonArray.single().jsonPrimitive.content)
            assertEquals(listOf(owned), created)
            coVerify(exactly = 1) { store.copyFilePreservingOrigin(any(), any(), any(), any()) }
        } finally {
            filesDir.deleteRecursively()
        }
    }

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
        coEvery { store.resolveManagedReference(file) } returns managed
        val previews = ConversationAttachmentPreviewProjector(store).project(snapshotOf(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))),
        ))

        assertEquals(AttachmentRefs.fileToFileUrl(file), previews[ref])
        assertEquals(AttachmentRefs.fileToFileUrl(file), previews["/upload/shared.png"])
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
        coEvery { store.resolveManagedReference(AttachmentRefs.parseFileUrl(directUrl)!!) } returns
            LocalArtifactRef(relativePath = "upload/direct.png", mimeType = "image/png")
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
        assertEquals(directUrl, previews["/upload/direct.png"])
        assertEquals(AttachmentRefs.fileToFileUrl(artifactFile), previews["/upload/generated.png"])
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
        coEvery { store.resolveManagedReference(AttachmentRefs.parseFileUrl(localUrl)!!) } returns
            LocalArtifactRef(relativePath = "upload/generated.png", mimeType = "image/png")
        val projector = ConversationAttachmentPreviewProjector(store)

        val previews = projector.project(snapshotOf(messages))

        assertEquals(localUrl, previews[localRef])
        assertEquals(localUrl, previews["/upload/generated.png"])
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
        coEvery { store.resolveManagedReference(file) } returns
            LocalArtifactRef(relativePath = "upload/u7km2n4p.png", mimeType = "image/png")
        val projector = ConversationAttachmentPreviewProjector(store)
        val snapshot = snapshotOf(listOf(UIMessage(role = MessageRole.USER, parts = listOf(image))))

        val first = projector.project(snapshot)
        assertEquals(AttachmentRefs.fileToFileUrl(file), first[ref])
        assertEquals(AttachmentRefs.fileToFileUrl(file), first["/upload/u7km2n4p.png"])
        assertEquals(emptyMap<String, String>(), projector.project(snapshot))
        coVerify(exactly = 1) { store.resolveManagedReference(file) }
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

    @Test
    fun `path resolution cancellation propagates without publishing a partial preview`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val url = "file:///managed/u7km2n4p.png"
        val image = stampedImage(url, ref)
        val store = mockk<ArtifactStore>()
        val cancelled = CancellationException("conversation switched")
        coEvery { store.resolveImagePreviewForFile(any()) } returns url
        coEvery { store.resolveManagedReference(AttachmentRefs.parseFileUrl(url)!!) } throws cancelled

        try {
            ConversationAttachmentPreviewProjector(store).project(snapshotOf(
                listOf(UIMessage(role = MessageRole.USER, parts = listOf(image))),
            ))
            org.junit.Assert.fail("cancellation must propagate")
        } catch (actual: CancellationException) {
            assertEquals(cancelled, actual)
        }
    }

    @Test
    fun `path resolution failure never advertises a tool path or stops other previews`() = runTest {
        val brokenRef = AttachmentRefs.format(Uuid.random())
        val goodRef = AttachmentRefs.format(Uuid.random())
        val brokenUrl = "file:///managed/broken.png"
        val goodUrl = "file:///managed/u7km2n4p.png"
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveImagePreviewForFile(AttachmentRefs.parseFileUrl(brokenUrl)!!) } returns brokenUrl
        coEvery { store.resolveImagePreviewForFile(AttachmentRefs.parseFileUrl(goodUrl)!!) } returns goodUrl
        coEvery { store.resolveManagedReference(AttachmentRefs.parseFileUrl(brokenUrl)!!) } throws
            IllegalStateException("artifact lookup unavailable")
        coEvery { store.resolveManagedReference(AttachmentRefs.parseFileUrl(goodUrl)!!) } returns
            LocalArtifactRef(relativePath = "upload/u7km2n4p.png", mimeType = "image/png")

        val result = ConversationAttachmentPreviewProjector(store).project(snapshotOf(listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(stampedImage(brokenUrl, brokenRef), stampedImage(goodUrl, goodRef))),
        )))

        assertNull(result["/upload/broken.png"])
        assertEquals(goodUrl, result[goodRef])
        assertEquals(goodUrl, result["/upload/u7km2n4p.png"])
    }

    @Test
    fun `managed nonupload preview retains UUID without advertising an upload alias`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val url = "file:///managed/generated.png"
        val store = mockk<ArtifactStore>()
        coEvery { store.resolveImagePreviewForFile(any()) } returns url
        coEvery { store.resolveManagedReference(AttachmentRefs.parseFileUrl(url)!!) } returns
            LocalArtifactRef(relativePath = "images/generated.png", mimeType = "image/png")

        assertEquals(mapOf(ref to url), ConversationAttachmentPreviewProjector(store).project(snapshotOf(
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(stampedImage(url, ref)))),
        )))
    }

    private fun stampedImage(url: String, ref: String) = AttachmentRefs.withMetadata(
        UIMessagePart.Image(url),
        AttachmentRefs.mergeMetadata(null, mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(ref))),
    )

    private fun snapshotOf(messages: List<UIMessage>) = Conversation.ofId(
        id = Uuid.random(),
        assistantId = DEFAULT_ASSISTANT_ID,
        messages = messages.map(UIMessage::toMessageNode),
    ).toSnapshot().toPresentationSnapshot()
}
