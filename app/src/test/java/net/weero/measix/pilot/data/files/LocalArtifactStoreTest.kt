package net.weero.measix.pilot.data.files

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.ManagedFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArtifactStoreTest {
    @Test
    fun `upload tool path accepts only a single safe name`() {
        assertEquals("9a3f-image.png", LocalToolPath.parseUploadToolPath("/upload/9a3f-image.png"))
        assertNull(LocalToolPath.parseUploadToolPath("/upload/../secret"))
        assertNull(LocalToolPath.parseUploadToolPath("/upload/a/b.png"))
        assertNull(LocalToolPath.parseUploadToolPath("/upload/%2e%2e"))
        assertNull(LocalToolPath.parseUploadToolPath("/tmp/x.png"))
        assertNull(LocalToolPath.parseUploadToolPath("/upload/.."))
    }

    @Test
    fun `resolveToolPath accepts only registered upload files`() = runTest {
        val filesDir = createTempDirectory("artifact-resolve").toFile()
        File(filesDir, "upload").mkdirs()
        val registered = File(filesDir, "upload/ok.png").apply { writeText("ok") }
        File(filesDir, "upload/orphan.png").writeText("no")
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        io.mockk.every { context.filesDir } returns filesDir
        val filesManager = io.mockk.mockk<FilesManager>()
        io.mockk.coEvery { filesManager.getByRelativePath("upload/ok.png") } returns ManagedFileEntity(
            id = 1,
            folder = "upload",
            relativePath = "upload/ok.png",
            displayName = "ok.png",
            mimeType = "image/png",
            sizeBytes = 2,
            createdAt = 1,
            updatedAt = 1,
        )
        io.mockk.coEvery { filesManager.getByRelativePath("upload/orphan.png") } returns null
        io.mockk.every { filesManager.getFile(any()) } returns registered
        val store = ManagedLocalArtifactStore(context, filesManager)
        assertEquals(registered, store.resolveToolPath("/upload/ok.png"))
        assertNull(store.resolveToolPath("/upload/orphan.png"))
        assertNull(store.resolveToolPath("/upload/../secret"))
        filesDir.deleteRecursively()
    }

    @Test
    fun `unknown metadata version is ignored`() {
        val filesDir = createTempDirectory("artifact-meta").toFile()
        val rewriter = ToolArtifactRewriter(
            filesDir = filesDir,
            artifactStore = unusedStore(filesDir),
        )
        val metadata = JsonObject(
            mapOf(
                ToolArtifactRewriter.ARTIFACT_KEY to buildJsonObject {
                    put("version", 99)
                    put("relativePath", "upload/x.png")
                    put("mimeType", "image/png")
                }
            )
        )
        assertNull(rewriter.decodeArtifactRef(metadata))
        filesDir.deleteRecursively()
    }

    @Test
    fun `rewrite copies file path and image url together`() = runTest {
        val filesDir = createTempDirectory("artifact-rewrite").toFile()
        val upload = File(filesDir, "upload").apply { mkdirs() }
        val source = File(upload, "source.png").apply { writeText("src") }
        val copied = File(upload, "copy.png")
        val store = io.mockk.mockk<ManagedLocalArtifactStore>()
        val sourceRef = LocalArtifactRef(relativePath = "upload/source.png", mimeType = "image/png")
        io.mockk.every { store.materialize(sourceRef) } returns sourceRef
        io.mockk.coEvery {
            store.copyFile(any(), any(), any(), any())
        } answers {
            source.copyTo(copied, overwrite = true)
            LocalArtifactRef(relativePath = "upload/copy.png", mimeType = "image/png")
        }
        val rewriter = ToolArtifactRewriter(filesDir, store)
        val metadata = rewriter.encodeArtifactRef(null, sourceRef)
        val output = listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "completed")
                    put("file", buildJsonObject { put("path", "/upload/source.png") })
                }.toString()
            ),
            UIMessagePart.Image(url = sourceRef.fileUri(filesDir)),
        )
        val (rewritten, newMetadata) = rewriter.rewriteToolOutput(output, metadata)
        val text = rewritten.filterIsInstance<UIMessagePart.Text>().single().text
        val image = rewritten.filterIsInstance<UIMessagePart.Image>().single()
        val newRef = rewriter.decodeArtifactRef(newMetadata!!)!!
        assertTrue(text.contains("/upload/copy.png"))
        assertFalse(text.contains("/upload/source.png"))
        assertEquals(newRef.fileUri(filesDir), image.url)
        assertEquals("upload/copy.png", newRef.relativePath)
        filesDir.deleteRecursively()
    }

    @Test
    fun `rewrite of a missing source does not keep a completed path`() = runTest {
        val filesDir = createTempDirectory("artifact-rewrite-missing").toFile()
        val store = io.mockk.mockk<ManagedLocalArtifactStore>()
        io.mockk.every { store.materialize(any()) } returns null
        val rewriter = ToolArtifactRewriter(filesDir, store)
        val ref = LocalArtifactRef(relativePath = "upload/gone.png", mimeType = "image/png")
        val metadata = rewriter.encodeArtifactRef(null, ref)
        val output = listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "completed")
                    put("file", buildJsonObject { put("path", "/upload/gone.png") })
                }.toString()
            ),
            UIMessagePart.Image(url = "file:///tmp/gone.png"),
        )
        val (rewritten, _) = rewriter.rewriteToolOutput(output, metadata)
        val text = rewritten.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(rewritten.none { it is UIMessagePart.Image })
        assertTrue(text.contains("artifact_missing"))
        assertFalse(text.contains("\"path\":\"/upload/gone.png\""))
        io.mockk.coVerify(exactly = 0) { store.copyFile(any(), any(), any(), any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `rewrite rejects source outside upload and images`() = runTest {
        val filesDir = createTempDirectory("artifact-rewrite-escape").toFile()
        File(filesDir, "databases").mkdirs()
        File(filesDir, "databases/secret.db").writeText("secret")
        val rewriter = ToolArtifactRewriter(filesDir, unusedStore(filesDir))
        val ref = LocalArtifactRef(relativePath = "databases/secret.db", mimeType = "application/octet-stream")
        val metadata = rewriter.encodeArtifactRef(null, ref)
        val output = listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "completed")
                    put("file", buildJsonObject { put("path", "/upload/secret.db") })
                }.toString()
            ),
            UIMessagePart.Image(url = "file:///tmp/secret.db"),
        )
        val (rewritten, _) = rewriter.rewriteToolOutput(output, metadata)
        val text = rewritten.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(rewritten.none { it is UIMessagePart.Image })
        assertTrue(text.contains("artifact_missing"))
        assertFalse(text.contains("\"path\":\"/upload/secret.db\""))
        filesDir.deleteRecursively()
    }

    @Test
    fun `replay transformer rewrites stored path when file exists`() {
        val filesDir = createTempDirectory("artifact-replay").toFile()
        File(filesDir, "upload").mkdirs()
        File(filesDir, "upload/live.png").writeText("ok")
        val store = io.mockk.mockk<ManagedLocalArtifactStore>()
        val ref = LocalArtifactRef(relativePath = "upload/live.png", mimeType = "image/png")
        io.mockk.every { store.materialize(ref) } returns ref
        val rewriter = ToolArtifactRewriter(filesDir, store)
        val metadata = rewriter.encodeArtifactRef(null, ref)
        val stale = listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "completed")
                    put("file", buildJsonObject { put("path", "/upload/stale.png") })
                }.toString()
            ),
            UIMessagePart.Image(url = "file:///tmp/stale.png"),
        )
        val materialized = rewriter.materializeToolOutput(stale, metadata)
        val text = materialized.filterIsInstance<UIMessagePart.Text>().single().text
        val image = materialized.filterIsInstance<UIMessagePart.Image>().single()
        assertTrue(text.contains("/upload/live.png"))
        assertEquals(ref.fileUri(filesDir), image.url)
        filesDir.deleteRecursively()
    }

    @Test
    fun `materialize missing file does not invent a readable path`() {
        val filesDir = createTempDirectory("artifact-missing").toFile()
        val rewriter = ToolArtifactRewriter(
            filesDir = filesDir,
            artifactStore = unusedStore(filesDir),
        )
        val ref = LocalArtifactRef(relativePath = "upload/missing.png", mimeType = "image/png")
        val metadata = rewriter.encodeArtifactRef(null, ref)
        val output = listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "completed")
                    put("file", buildJsonObject { put("path", "/upload/missing.png") })
                }.toString()
            ),
            UIMessagePart.Image(url = "file:///tmp/missing.png"),
        )
        val materialized = rewriter.materializeToolOutput(output, metadata)
        val text = materialized.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(materialized.none { it is UIMessagePart.Image })
        assertTrue(text.contains("artifact_missing"))
        assertFalse(text.contains("\"path\":\"/upload/missing.png\""))
        filesDir.deleteRecursively()
    }

    private fun unusedStore(filesDir: File): ManagedLocalArtifactStore {
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        io.mockk.every { context.filesDir } returns filesDir
        return ManagedLocalArtifactStore(context, io.mockk.mockk(relaxed = true))
    }
}
