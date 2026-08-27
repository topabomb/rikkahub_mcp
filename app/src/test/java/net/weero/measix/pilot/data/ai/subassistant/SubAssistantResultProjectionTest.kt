package net.weero.measix.pilot.data.ai.subassistant

import java.io.File
import kotlin.io.path.createTempDirectory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.attachments.MAX_ASSISTANT_CALL_ATTACHMENTS
import net.weero.measix.pilot.data.ai.tools.local.GENERATE_IMAGE_TOOL_NAME
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantResultProjectionTest {
    private val taskId = Uuid.random()

    @Test
    fun `last assistant top-level image is a deliverable`() {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val image = env.image(env.png, ref)
        val extracted = extractDeliverableArtifacts(
            messages = listOf(
                task("draw"),
                assistant(UIMessagePart.Text("Here"), image),
            ),
            childTaskNodeId = taskId,
            filesDir = env.filesDir,
        )
        assertEquals(1, extracted.artifacts.size)
        assertEquals(ref, extracted.artifacts.single().ref)
        assertEquals(ARTIFACT_TYPE_IMAGE, extracted.artifacts.single().type)
        assertEquals("upload/${env.png.name}", extracted.artifacts.single().artifact?.relativePath)
        assertTrue(extracted.hasNonTextOutput)
        env.cleanup()
    }

    @Test
    fun `successful generate_image output is a deliverable`() {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val extracted = extractDeliverableArtifacts(
            messages = listOf(
                task("draw"),
                assistant(
                    UIMessagePart.Text("Working"),
                    generateImageTool(env, ref, status = "completed"),
                    UIMessagePart.Text("Done"),
                ),
            ),
            childTaskNodeId = taskId,
            filesDir = env.filesDir,
        )
        assertEquals(1, extracted.artifacts.size)
        assertEquals(ref, extracted.artifacts.single().ref)
        assertEquals("upload/${env.png.name}", extracted.artifacts.single().artifact?.relativePath)
        env.cleanup()
    }

    @Test
    fun `inbound user attachments are not deliverables`() {
        val env = Env()
        val inbound = AttachmentRefs.format(Uuid.random())
        val extracted = extractDeliverableArtifacts(
            messages = listOf(
                UIMessage(
                    id = taskId,
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("use this"), env.image(env.png, inbound)),
                ),
                assistant(UIMessagePart.Text("ok")),
            ),
            childTaskNodeId = taskId,
            filesDir = env.filesDir,
        )
        assertTrue(extracted.artifacts.isEmpty())
        assertFalse(extracted.hasNonTextOutput)
        env.cleanup()
    }

    @Test
    fun `search and mcp tool images are not deliverables`() {
        val env = Env()
        val searchRef = AttachmentRefs.format(Uuid.random())
        val extracted = extractDeliverableArtifacts(
            messages = listOf(
                task("search"),
                assistant(
                    UIMessagePart.Tool(
                        toolCallId = "s1",
                        toolName = "search_web",
                        input = "{}",
                        output = listOf(env.image(env.png, searchRef)),
                    ),
                    UIMessagePart.Text("found it"),
                ),
            ),
            childTaskNodeId = taskId,
            filesDir = env.filesDir,
        )
        assertTrue(extracted.artifacts.isEmpty())
        env.cleanup()
    }

    @Test
    fun `images from a later user task are outside the run range`() {
        val env = Env()
        val later = AttachmentRefs.format(Uuid.random())
        val extracted = extractDeliverableArtifacts(
            messages = listOf(
                task("first"),
                assistant(UIMessagePart.Text("done")),
                UIMessage(
                    id = Uuid.random(),
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("second"), env.image(env.png, later)),
                ),
                assistant(env.image(env.png, later)),
            ),
            childTaskNodeId = taskId,
            filesDir = env.filesDir,
        )
        assertTrue(extracted.artifacts.isEmpty())
        env.cleanup()
    }

    @Test
    fun `failed generate_image is not a deliverable`() {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val extracted = extractDeliverableArtifacts(
            messages = listOf(
                task("draw"),
                assistant(generateImageTool(env, ref, status = "failed")),
            ),
            childTaskNodeId = taskId,
            filesDir = env.filesDir,
        )
        assertTrue(extracted.artifacts.isEmpty())
        env.cleanup()
    }

    @Test
    fun `file url without a managed relative path is visible but not persisted`() {
        val extracted = extractDeliverableArtifacts(
            messages = listOf(
                task("draw"),
                assistant(UIMessagePart.Image(url = "file:///tmp/unmanaged.png")),
            ),
            childTaskNodeId = taskId,
        )
        assertTrue(extracted.hasNonTextOutput)
        assertTrue(extracted.artifacts.isEmpty())
    }

    @Test
    fun `http images are not deliverables`() {
        val extracted = extractDeliverableArtifacts(
            messages = listOf(
                task("draw"),
                assistant(UIMessagePart.Image(url = "https://cdn.example/a.png")),
            ),
            childTaskNodeId = taskId,
        )
        assertTrue(extracted.artifacts.isEmpty())
    }

    @Test
    fun `duplicate attachment refs are collapsed`() {
        val env = Env()
        val ref = AttachmentRefs.format(Uuid.random())
        val extracted = extractDeliverableArtifacts(
            messages = listOf(
                task("draw"),
                assistant(
                    generateImageTool(env, ref, status = "completed"),
                    env.image(env.png, ref),
                ),
            ),
            childTaskNodeId = taskId,
            filesDir = env.filesDir,
        )
        assertEquals(1, extracted.artifacts.size)
        env.cleanup()
    }

    @Test
    fun `more than four deliverables are truncated with omitted count`() {
        val env = Env()
        val extra = File(env.upload, "extra.png").apply { writeBytes(env.png.readBytes()) }
        val images = (1..5).map { index ->
            val file = if (index == 5) extra else File(env.upload, "img$index.png").apply {
                writeBytes(env.png.readBytes())
            }
            env.image(file, AttachmentRefs.format(Uuid.random()))
        }
        val extracted = extractDeliverableArtifacts(
            messages = listOf(task("many"), assistant(*images.toTypedArray())),
            childTaskNodeId = taskId,
            filesDir = env.filesDir,
        )
        assertEquals(MAX_ASSISTANT_CALL_ATTACHMENTS, extracted.artifacts.size)
        assertEquals(1, extracted.omitted)
        assertTrue(extracted.hasNonTextOutput)
        env.cleanup()
    }

    @Test
    fun `last assistant document is listed but generate_image still wins first`() {
        val env = Env()
        val imageRef = AttachmentRefs.format(Uuid.random())
        val doc = File(env.upload, "note.pdf").apply { writeText("pdf") }
        val extracted = extractDeliverableArtifacts(
            messages = listOf(
                task("mix"),
                assistant(
                    generateImageTool(env, imageRef, status = "completed"),
                    UIMessagePart.Document(
                        url = AttachmentRefs.fileToFileUrl(doc),
                        fileName = "note.pdf",
                        mime = "application/pdf",
                        metadata = buildJsonObject {
                            put(AttachmentRefs.METADATA_KEY, AttachmentRefs.format(Uuid.random()))
                        },
                    ),
                ),
            ),
            childTaskNodeId = taskId,
            filesDir = env.filesDir,
        )
        assertEquals(listOf(ARTIFACT_TYPE_IMAGE, ARTIFACT_TYPE_DOCUMENT), extracted.artifacts.map { it.type })
        env.cleanup()
    }

    @Test
    fun `stale extracted artifact is rejected before durable projection`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val local = LocalArtifactRef(relativePath = "upload/image.png", mimeType = "image/png")
        val extracted = SubAssistantExtractedArtifacts(
            artifacts = listOf(
                SubAssistantDeliverableArtifact(
                    ref = ref,
                    type = ARTIFACT_TYPE_IMAGE,
                    mime = "image/png",
                    artifact = local,
                    fileUrl = "file:///managed/image.png",
                ),
            ),
            omitted = 0,
            hasNonTextOutput = true,
        )
        val store = mockk<ArtifactStore>()
        coEvery { store.materialize(local) } returns null

        assertTrue(validateDeliverableArtifacts(extracted, store).artifacts.isEmpty())
    }

    @Test
    fun `output URL that disagrees with managed artifact is rejected`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val local = LocalArtifactRef(relativePath = "upload/image.png", mimeType = "image/png")
        val managedFile = File.createTempFile("managed", ".png").apply { deleteOnExit() }
        val outputFile = File.createTempFile("output", ".png").apply { deleteOnExit() }
        val extracted = SubAssistantExtractedArtifacts(
            artifacts = listOf(
                SubAssistantDeliverableArtifact(
                    ref = ref,
                    type = ARTIFACT_TYPE_IMAGE,
                    mime = "image/png",
                    artifact = local,
                    fileUrl = AttachmentRefs.fileToFileUrl(outputFile),
                ),
            ),
            omitted = 0,
            hasNonTextOutput = true,
        )
        val store = mockk<ArtifactStore>()
        coEvery { store.materialize(local) } returns local
        every { store.file(local) } returns managedFile

        assertTrue(validateDeliverableArtifacts(extracted, store).artifacts.isEmpty())
    }

    @Test
    fun `malformed output URL is rejected even when managed artifact is active`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val local = LocalArtifactRef(relativePath = "upload/image.png", mimeType = "image/png")
        val extracted = SubAssistantExtractedArtifacts(
            artifacts = listOf(
                SubAssistantDeliverableArtifact(
                    ref = ref,
                    type = ARTIFACT_TYPE_IMAGE,
                    mime = "image/png",
                    artifact = local,
                    fileUrl = "not-a-file-url",
                ),
            ),
            omitted = 0,
            hasNonTextOutput = true,
        )
        val store = mockk<ArtifactStore>()
        coEvery { store.materialize(local) } returns local
        every { store.file(local) } returns File.createTempFile("managed", ".png")

        assertTrue(validateDeliverableArtifacts(extracted, store).artifacts.isEmpty())
    }

    @Test
    fun `malformed extracted stable ref is rejected at validation boundary`() = runTest {
        val local = LocalArtifactRef(relativePath = "upload/image.png", mimeType = "image/png")
        val extracted = SubAssistantExtractedArtifacts(
            artifacts = listOf(
                SubAssistantDeliverableArtifact(
                    ref = "not-an-attachment-ref",
                    type = ARTIFACT_TYPE_DOCUMENT,
                    mime = "application/pdf",
                    artifact = local,
                ),
            ),
            omitted = 0,
            hasNonTextOutput = true,
        )
        val store = mockk<ArtifactStore>()
        coEvery { store.materialize(local) } returns local
        every { store.file(local) } returns File.createTempFile("managed", ".pdf")

        assertTrue(validateDeliverableArtifacts(extracted, store).artifacts.isEmpty())
    }

    @Test
    fun `validation preserves omitted count from capped extraction`() = runTest {
        val refs = (1..4).map { AttachmentRefs.format(Uuid.random()) }
        val local = LocalArtifactRef(relativePath = "upload/document.pdf", mimeType = "application/pdf")
        val extracted = SubAssistantExtractedArtifacts(
            artifacts = refs.mapIndexed { index, ref ->
                SubAssistantDeliverableArtifact(
                    ref = ref,
                    type = ARTIFACT_TYPE_DOCUMENT,
                    mime = "application/pdf",
                    artifact = if (index == 0) null else local,
                )
            },
            omitted = 2,
            hasNonTextOutput = true,
        )
        val store = mockk<ArtifactStore>()
        coEvery { store.materialize(local) } returns local
        every { store.file(local) } returns File.createTempFile("managed", ".pdf")

        val validated = validateDeliverableArtifacts(extracted, store)

        assertEquals(3, validated.artifacts.size)
        assertEquals(2, validated.omitted)
    }

    private fun task(text: String) = UIMessage(
        id = taskId,
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistant(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    private fun generateImageTool(env: Env, ref: String, status: String): UIMessagePart.Tool {
        val artifact = LocalArtifactRef(relativePath = "upload/${env.png.name}", mimeType = "image/png")
        val metadata = JsonInstant.encodeToJsonElement(
            LocalArtifactRef.serializer(),
            artifact,
        )
        return UIMessagePart.Tool(
            toolCallId = "g1",
            toolName = GENERATE_IMAGE_TOOL_NAME,
            input = "{}",
            output = listOf(
                UIMessagePart.Text("""{"status":"$status"}"""),
                env.image(env.png, ref),
            ),
            metadata = buildJsonObject {
                put(ToolArtifactRewriter.ARTIFACT_KEY, metadata)
            },
        )
    }

    private class Env {
        val filesDir: File = createTempDirectory("sub-assistant-artifacts").toFile()
        val upload: File = File(filesDir, "upload").apply { mkdirs() }
        val png: File = File(upload, "ok.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        }

        fun image(file: File, ref: String) = UIMessagePart.Image(
            url = AttachmentRefs.fileToFileUrl(file),
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, JsonPrimitive(ref)) },
        )

        fun cleanup() {
            filesDir.deleteRecursively()
        }
    }
}
