package net.weero.measix.pilot.service

import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.subassistant.buildChildUserParts
import net.weero.measix.pilot.data.files.AttachmentCloner
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantChildPartsTest {
    @Test
    fun `child user parts are request text plus images`() {
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
        val parts = buildChildUserParts("do the work", listOf(image))
        assertEquals(2, parts.size)
        assertEquals("do the work", (parts[0] as UIMessagePart.Text).text)
        assertEquals(image, parts[1])
    }

    @Test
    fun `clone copy keeps attachment ref on non-file parts`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val image = UIMessagePart.Image(
            url = "https://example.com/a.png",
            metadata = buildJsonObject {
                put(AttachmentRefs.METADATA_KEY, ref)
                put("thoughtSignature", "sig")
            },
        )
        val artifactStore = mockk<ArtifactStore>(relaxed = true)
        val copied = AttachmentCloner.clonePart(
            image,
            artifactStore,
            mutableListOf(),
            mockk(relaxed = true),
        ) as UIMessagePart.Image
        assertEquals(ref, AttachmentRefs.getRef(copied))
        assertEquals("sig", (copied.metadata!!["thoughtSignature"] as JsonPrimitive).content)
        assertEquals(image.url, copied.url)
    }

    @Test
    fun `clone copy of tool output preserves nested ref`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val tool = UIMessagePart.Tool(
            toolCallId = "t",
            toolName = "generate_image",
            input = "{}",
            output = listOf(
                UIMessagePart.Image(
                    url = "https://example.com/a.png",
                    metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                ),
            ),
        )
        val copied = AttachmentCloner.clonePart(
            tool,
            mockk(relaxed = true),
            mutableListOf<OwnedArtifact>(),
            mockk(relaxed = true),
        ) as UIMessagePart.Tool
        assertEquals(ref, AttachmentRefs.getRef(copied.output.single()))
        assertTrue(copied.output.single() is UIMessagePart.Image)
    }

    @Test
    fun `tool artifact clone returns ownership token to caller`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("tool-artifact-clone").toFile()
        val sourceRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
        val copiedRef = LocalArtifactRef(relativePath = "upload/copy.png", mimeType = "image/png")
        val artifactStore = mockk<ArtifactStore>()
        val owned = mockk<OwnedArtifact>()
        coEvery { artifactStore.materialize(sourceRef) } returns sourceRef
        every { owned.localRef } returns copiedRef
        io.mockk.coEvery {
            artifactStore.copyFilePreservingOrigin(any(), any(), any(), any())
        } returns owned
        val rewriter = ToolArtifactRewriter(filesDir, artifactStore)
        val tool = UIMessagePart.Tool(
            toolCallId = "t",
            toolName = "generate_image",
            input = "{}",
            output = listOf(UIMessagePart.Image(url = sourceRef.fileUri(filesDir))),
            metadata = rewriter.encodeArtifactRef(null, sourceRef),
        )
        val created = mutableListOf<OwnedArtifact>()

        val cloned = AttachmentCloner.clonePart(
            part = tool,
            artifactStore = artifactStore,
            createdArtifacts = created,
            toolArtifactRewriter = rewriter,
        ) as UIMessagePart.Tool

        assertEquals(listOf(owned), created)
        assertEquals(copiedRef.fileUri(filesDir), (cloned.output.single() as UIMessagePart.Image).url)
        assertEquals(copiedRef, rewriter.decodeArtifactRef(cloned.metadata!!))
        filesDir.deleteRecursively()
    }

    @Test
    fun `missing tool artifact clone removes stale durable reference`() = runTest {
        val filesDir = kotlin.io.path.createTempDirectory("missing-tool-artifact-clone").toFile()
        val sourceRef = LocalArtifactRef(relativePath = "upload/missing.png", mimeType = "image/png")
        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.materialize(sourceRef) } returns null
        val rewriter = ToolArtifactRewriter(filesDir, artifactStore)
        val tool = UIMessagePart.Tool(
            toolCallId = "t",
            toolName = "generate_image",
            input = "{}",
            output = listOf(UIMessagePart.Image(url = sourceRef.fileUri(filesDir))),
            metadata = rewriter.encodeArtifactRef(null, sourceRef),
        )

        val cloned = AttachmentCloner.clonePart(
            part = tool,
            artifactStore = artifactStore,
            createdArtifacts = mutableListOf(),
            toolArtifactRewriter = rewriter,
        ) as UIMessagePart.Tool

        assertTrue(cloned.output.isEmpty())
        assertEquals(null, rewriter.decodeArtifactRef(cloned.metadata!!))
        filesDir.deleteRecursively()
    }
}
