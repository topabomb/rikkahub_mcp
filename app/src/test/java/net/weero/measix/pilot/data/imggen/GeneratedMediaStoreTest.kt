package net.weero.measix.pilot.data.imggen

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.ImageGenerationItem
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.repository.GenMediaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedMediaStoreTest {
    private fun tempDir(prefix: String): File = createTempDirectory(prefix).toFile()

    @Test
    fun `generated media selects the first free candidate in all four tiers`() = runTest {
        val filesDir = tempDir("media-candidate-priority")
        try {
            val candidates = listOf("aaaaaa", "bbbbbbb", "cccccccc", "Dddddddd")
            for (freeIndex in candidates.indices) {
                val directory = File(filesDir, freeIndex.toString()).apply { mkdirs() }
                val repository = mockk<GenMediaRepository>()
                coEvery { repository.existsByPath(any()) } answers {
                    firstArg<String>() in candidates.take(freeIndex).map { "images/$it.png" }
                }
                every { repository.insertMedia(any()) } returns 1L
                val store = GeneratedMediaStore(directory, repository, mockk(relaxed = true), fileNameCandidates = { candidates })
                val committed = store.commit(item(TINY_PNG, "image/png"), "cat", "model")
                assertEquals("images/${candidates[freeIndex]}.png", committed.canonicalRelativePath)
                candidates.drop(freeIndex + 1).forEach { stem ->
                    coVerify(exactly = 0) { repository.existsByPath("images/$stem.png") }
                }
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `generated all-candidate collisions and duplicate text use first stem suffixes`() = runTest {
        val filesDir = tempDir("media-candidate-suffix")
        try {
            val candidates = listOf("aaaaaa", "bbbbbbb", "cccccccc", "cccccccc")
            val repository = mockk<GenMediaRepository>()
            coEvery { repository.existsByPath(any()) } answers { firstArg<String>() != "images/aaaaaa-3.png" }
            every { repository.insertMedia(any()) } returns 1L
            val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true), fileNameCandidates = { candidates })
            val committed = store.commit(item(TINY_PNG, "image/png"), "cat", "model")
            assertEquals("images/aaaaaa-3.png", committed.canonicalRelativePath)
            coVerify(exactly = 1) { repository.existsByPath("images/cccccccc.png") }
            coVerify(exactly = 1) { repository.existsByPath("images/aaaaaa-2.png") }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `short generated names skip final pending deleting and recorded paths`() = runTest {
        val filesDir = tempDir("media-name-collision")
        try {
            val images = File(filesDir, "images").apply { mkdirs() }
            val existingNames = listOf("000000.png", "000000-2.png.pending", "000000-3.png.deleting")
            existingNames.forEach { File(images, it).writeText(it) }
            val repository = mockk<GenMediaRepository>()
            coEvery { repository.existsByPath(any()) } answers { firstArg<String>() == "images/000000-4.png" }
            every { repository.insertMedia(any()) } returns 1L
            val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true), fileNameCandidates = { List(4) { "000000" } })

            val committed = store.commit(item(TINY_PNG, "image/png"), "cat", "model")

            assertEquals("images/000000-5.png", committed.canonicalRelativePath)
            existingNames.forEach { assertEquals(it, File(images, it).readText()) }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `concurrent generated images with repeated candidates use distinct suffixes`() = runTest {
        val filesDir = tempDir("media-name-concurrent")
        try {
            val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
            every { repository.insertMedia(any()) } returns 1L
            val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true), fileNameCandidates = { List(4) { "000000" } })
            val committed = (0 until 10).map {
                async(Dispatchers.Default) { store.commit(item(TINY_PNG, "image/png"), "cat", "model") }
            }.awaitAll()
            assertEquals(10, committed.map { it.canonicalRelativePath }.toSet().size)
            committed.forEach { assertTrue(it.canonicalFile.readBytes().contentEquals(TINY_PNG)) }
            assertEquals(10, File(filesDir, "images").listFiles().orEmpty().size)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `failed generated publication preserves all preexisting collision payloads`() = runTest {
        val filesDir = tempDir("media-name-rollback")
        try {
            val images = File(filesDir, "images").apply { mkdirs() }
            val existingNames = listOf("000000.png", "000000-2.png.pending")
            existingNames.forEach { File(images, it).writeText(it) }
            val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
            every { repository.insertMedia(any()) } throws CancellationException("cancel insert")
            val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true), fileNameCandidates = { List(4) { "000000" } })
            val failure = runCatching { store.commit(item(TINY_PNG, "image/png"), "cat", "model") }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(existingNames.sorted(), images.listFiles().orEmpty().map { it.name }.sorted())
            existingNames.forEach { assertEquals(it, File(images, it).readText()) }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `mime type selects extension`() {
        assertEquals("jpg", GeneratedMediaStore.extensionForMime("image/jpeg"))
        assertEquals("webp", GeneratedMediaStore.extensionForMime("image/webp"))
        assertEquals("png", GeneratedMediaStore.extensionForMime("image/png"))
        assertEquals("gif", GeneratedMediaStore.extensionForMime("image/gif"))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun item(bytes: ByteArray, mimeType: String) = ImageGenerationItem(
        data = Base64.encode(bytes),
        mimeType = mimeType,
    )

    @Test
    fun `commit writes gallery file and returns media id`() = runTest {
        val filesDir = tempDir("media-store")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returns 42L
        val artifactStore = mockk<ArtifactStore>()
        val owned = mockk<OwnedArtifact>()
        io.mockk.every { owned.localRef } returns LocalArtifactRef(
            relativePath = "upload/chat.png",
            mimeType = "image/png",
        )
        coEvery {
            artifactStore.copyFile(any(), any(), any(), any(), any())
        } returns owned
        val store = GeneratedMediaStore(filesDir, repository, artifactStore)
        val committed = store.commit(
            item = item(TINY_PNG, "image/png"),
            prompt = "cat",
            modelLabel = "GPT Image",
            consumerPlan = GeneratedMediaConsumerPlan.CHAT_TOOL_RESULT,
        )
        assertEquals(42L, committed.mediaId)
        assertTrue(committed.canonicalFile.exists())
        assertTrue(committed.canonicalFile.name.endsWith(".png"))
        assertFalse(committed.canonicalFile.name.contains("GPT"))
        assertEquals("image/png", committed.mimeType)
        assertEquals("upload/chat.png", committed.chatArtifact?.localRef?.relativePath)
        filesDir.deleteRecursively()
    }

    @Test
    fun `room insert failure deletes canonical file`() = runTest {
        val filesDir = tempDir("media-fail")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } throws IllegalStateException("db")
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))
        runCatching {
            store.commit(
                item = item(TINY_PNG, "image/png"),
                prompt = "cat",
                modelLabel = "model",
            )
        }
        val leftovers = File(filesDir, "images").listFiles().orEmpty().filter { it.isFile }
        assertTrue(leftovers.none { !it.name.endsWith(".pending") })
        filesDir.deleteRecursively()
    }

    @Test
    fun `cancellation before insert deletes the canonical file`() = runTest {
        val filesDir = tempDir("media-cancel")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } throws CancellationException("persist cancelled")
        val artifactStore = mockk<ArtifactStore>()
        val owned = mockk<OwnedArtifact>()
        coEvery { artifactStore.copyFile(any(), any(), any(), any(), any()) } returns owned
        coEvery { artifactStore.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(7L)
        val store = GeneratedMediaStore(filesDir, repository, artifactStore)
        val result = async {
            runCatching {
                store.commit(
                    item = item(TINY_PNG, "image/png"),
                    prompt = "cat",
                    modelLabel = "model",
                    consumerPlan = GeneratedMediaConsumerPlan.CHAT_TOOL_RESULT,
                )
            }
        }.await()
        assertTrue(result.exceptionOrNull() is CancellationException)
        val leftovers = File(filesDir, "images").listFiles().orEmpty().filter { it.isFile }
        assertTrue(leftovers.none { !it.name.endsWith(".pending") })
        coVerify(exactly = 1) { artifactStore.discardUnpublished(owned) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `cancellation after insert keeps durable media and discards unpublished consumer artifact`() = runTest {
        val filesDir = tempDir("media-cancel-after-commit")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        val cancellationJob = Job()
        every { repository.insertMedia(any()) } answers {
            cancellationJob.cancel(CancellationException("cancel after commit"))
            43L
        }
        val artifactStore = mockk<ArtifactStore>()
        val owned = mockk<OwnedArtifact>()
        io.mockk.every { owned.localRef } returns LocalArtifactRef(
            relativePath = "upload/chat.png",
            mimeType = "image/png",
        )
        coEvery { artifactStore.copyFile(any(), any(), any(), any(), any()) } returns owned
        coEvery { artifactStore.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(7L)
        val store = GeneratedMediaStore(filesDir, repository, artifactStore)
        val result = runCatching {
            CoroutineScope(coroutineContext + cancellationJob).async {
                store.commit(
                    item = item(TINY_PNG, "image/png"),
                    prompt = "cat",
                    modelLabel = "model",
                    consumerPlan = GeneratedMediaConsumerPlan.CHAT_TOOL_RESULT,
                )
            }.await()
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, File(filesDir, "images").listFiles().orEmpty().count { it.isFile })
        verify(exactly = 1) { repository.insertMedia(any()) }
        coVerify(exactly = 1) { artifactStore.discardUnpublished(owned) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `reconcile deletes orphan files and broken rows`() = runTest {
        val filesDir = tempDir("media-fix")
        val images = File(filesDir, "images").apply { mkdirs() }
        val orphan = File(images, "orphan.png").apply {
            writeText("x")
            setLastModified(1L)
        }
        val pending = File(images, "live.png.pending").apply { writeText("p") }
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        coEvery { repository.getAllMediaList() } returns listOf(
            GenMediaEntity(id = 9, path = "images/missing.png", modelId = "m", prompt = "p", createAt = 1L),
        )
        every { repository.deleteMedia(9) } returns Unit
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))
        store.reconcile(nowMs = GeneratedMediaStore.PROTECTION_MS + 10)
        assertFalse(orphan.exists())
        assertTrue(pending.exists())
        verify { repository.deleteMedia(9) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `delete removes committed file and room row`() = runTest {
        val filesDir = tempDir("media-delete")
        val images = File(filesDir, "images").apply { mkdirs() }
        val file = File(images, "keep.png").apply { writeText("x") }
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        coEvery { repository.getMediaById(3) } returns GenMediaEntity(
            id = 3,
            path = "images/keep.png",
            modelId = "m",
            prompt = "p",
            createAt = 1L,
        )
        every { repository.deleteMedia(3) } returns Unit
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))
        assertTrue(store.delete(3))
        assertFalse(file.exists())
        coVerify { repository.getMediaById(3) }
        verify { repository.deleteMedia(3) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `delete keeps the room row when the file cannot be removed`() = runTest {
        val filesDir = tempDir("media-delete-fail")
        val images = File(filesDir, "images").apply { mkdirs() }
        val locked = File(images, "locked.png").apply { mkdirs() }
        File(locked, "child").writeText("x")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        coEvery { repository.getMediaById(4) } returns GenMediaEntity(
            id = 4,
            path = "images/locked.png",
            modelId = "m",
            prompt = "p",
            createAt = 1L,
        )
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))
        assertFalse(store.delete(4))
        verify(exactly = 0) { repository.deleteMedia(any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `deleteAll skips pending files`() = runTest {
        val filesDir = tempDir("media-clean")
        val images = File(filesDir, "images").apply { mkdirs() }
        val committed = File(images, "done.png").apply { writeText("x") }
        val pending = File(images, "live.png.pending").apply { writeText("p") }
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        coEvery { repository.listCreatedBefore(Long.MAX_VALUE) } returns listOf(
            GenMediaEntity(id = 1, path = "images/done.png", modelId = "m", prompt = "p", createAt = 1L),
        )
        every { repository.deleteMedia(1) } returns Unit
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))
        assertTrue(store.deleteAll())
        assertFalse(committed.exists())
        assertTrue(pending.exists())
        verify { repository.deleteMedia(1) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `countCommitted ignores pending files`() = runTest {
        val filesDir = tempDir("media-count")
        val images = File(filesDir, "images").apply { mkdirs() }
        File(images, "a.png").writeBytes(ByteArray(10))
        File(images, "b.png.pending").writeBytes(ByteArray(20))
        val store = GeneratedMediaStore(filesDir, mockk(relaxed = true), mockk(relaxed = true))
        val stats = store.countCommitted()
        assertEquals(1, stats.count)
        assertEquals(10L, stats.sizeBytes)
        filesDir.deleteRecursively()
    }

    @Test
    fun `declared jpeg png payload is saved as png`() = runTest {
        val filesDir = tempDir("media-mime-mismatch")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returns 8L
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))
        val committed = store.commit(
            item = item(TINY_PNG, "image/jpeg"),
            prompt = "cat",
            modelLabel = "model",
        )
        assertEquals("image/png", committed.mimeType)
        assertTrue(committed.canonicalFile.name.endsWith(".png"))
        filesDir.deleteRecursively()
    }

    @Test
    fun `unknown mime uses detected signature`() = runTest {
        val filesDir = tempDir("media-unknown-mime")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        every { repository.insertMedia(any()) } returns 9L
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))
        val committed = store.commit(
            item = item(TINY_PNG, "application/octet-stream"),
            prompt = "cat",
            modelLabel = "model",
        )
        assertEquals("image/png", committed.mimeType)
        assertTrue(committed.canonicalFile.name.endsWith(".png"))
        filesDir.deleteRecursively()
    }

    @Test
    fun `non image payload is rejected`() = runTest {
        val filesDir = tempDir("media-not-image")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))
        val result = runCatching {
            store.commit(
                item = item("hello".toByteArray(), "image/png"),
                prompt = "cat",
                modelLabel = "model",
            )
        }
        assertTrue(result.isFailure)
        verify(exactly = 0) { repository.insertMedia(any()) }
        val leftovers = File(filesDir, "images").listFiles().orEmpty().filter { it.isFile }
        assertTrue(leftovers.none { !it.name.endsWith(".pending") })
        filesDir.deleteRecursively()
    }

    @Test
    fun `oversized payload is rejected`() {
        val oversized = ByteArray(GeneratedMediaStore.MAX_IMAGE_BYTES + 1) { TINY_PNG[it % TINY_PNG.size] }
        val result = runCatching { GeneratedMediaStore.inspectImagePayload(oversized, "image/png") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `jpeg gif and webp signatures are detected`() {
        assertEquals("image/png", GeneratedMediaStore.detectImageMimeBySignature(TINY_PNG))
        assertEquals("image/jpeg", GeneratedMediaStore.detectImageMimeBySignature(TINY_JPEG))
        assertEquals("image/gif", GeneratedMediaStore.detectImageMimeBySignature(TINY_GIF))
        assertEquals("image/webp", GeneratedMediaStore.detectImageMimeBySignature(TINY_WEBP))
        assertEquals(null, GeneratedMediaStore.detectImageMimeBySignature("<html>".toByteArray()))
    }

    @Test
    fun `extended webp with a following image chunk is accepted`() {
        val inspected = GeneratedMediaStore.inspectImagePayload(extendedWebp(), "image/webp")

        assertEquals("image/webp", inspected.mimeType)
        assertEquals("webp", inspected.extension)
    }

    @Test
    fun `extended webp without image payload is rejected`() {
        val headerOnly = extendedWebp().copyOf(30).also { bytes ->
            val riffSize = bytes.size - 8
            bytes[4] = (riffSize and 0xFF).toByte()
            bytes[5] = ((riffSize shr 8) and 0xFF).toByte()
            bytes[6] = ((riffSize shr 16) and 0xFF).toByte()
            bytes[7] = ((riffSize shr 24) and 0xFF).toByte()
        }

        assertTrue(runCatching { GeneratedMediaStore.inspectImagePayload(headerOnly, "image/webp") }.isFailure)
    }

    @Test
    fun `delete reports failure when dao delete throws`() = runTest {
        val filesDir = tempDir("media-delete-dao")
        val images = File(filesDir, "images").apply { mkdirs() }
        val file = File(images, "gone.png").apply { writeText("x") }
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        coEvery { repository.getMediaById(5) } returns GenMediaEntity(
            id = 5,
            path = "images/gone.png",
            modelId = "m",
            prompt = "p",
            createAt = 1L,
        )
        every { repository.deleteMedia(5) } throws IllegalStateException("dao")
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))
        assertFalse(store.delete(5))
        assertTrue(file.exists())
        filesDir.deleteRecursively()
    }

    @Test
    fun `image signature without a decodable image is rejected`() {
        val signatureOnly = TINY_PNG.copyOf(8)

        val result = runCatching {
            GeneratedMediaStore.inspectImagePayload(signatureOnly, "image/png")
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `generated media path cannot escape the images domain`() {
        val filesDir = tempDir("media-path")
        val store = GeneratedMediaStore(filesDir, mockk(relaxed = true), mockk(relaxed = true))
        val entity = GenMediaEntity(
            id = 1,
            path = "images/../../outside.png",
            modelId = "m",
            prompt = "p",
            createAt = 1L,
        )

        val result = runCatching { store.resolveCanonicalFile(entity) }

        assertTrue(result.isFailure)
        filesDir.deleteRecursively()
    }

    @Test
    fun `managed file predicate accepts only descendants of the generated media root`() {
        val filesDir = tempDir("media-managed-path")
        val images = File(filesDir, GeneratedMediaStore.IMAGES_DIR).apply { mkdirs() }
        val inside = File(images, "inside.png")
        val sibling = File(filesDir, "images2/outside.png")
        val store = GeneratedMediaStore(filesDir, mockk(relaxed = true), mockk(relaxed = true))

        assertTrue(store.isManagedFile(inside))
        assertFalse(store.isManagedFile(sibling))
        assertFalse(store.isManagedFile(File(images, "../outside.png")))
        filesDir.deleteRecursively()
    }

    @Test
    fun `delete succeeds after row commit when tombstone cleanup is deferred`() = runTest {
        val filesDir = tempDir("media-delete-deferred")
        val images = File(filesDir, "images").apply { mkdirs() }
        File(images, "deferred.png").writeText("x")
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        coEvery { repository.getMediaById(7) } returns GenMediaEntity(
            id = 7,
            path = "images/deferred.png",
            modelId = "m",
            prompt = "p",
            createAt = 1L,
        )
        every { repository.deleteMedia(7) } returns Unit
        val store = GeneratedMediaStore(
            filesDir = filesDir,
            genMediaRepository = repository,
            artifactStore = mockk(relaxed = true),
            deleteCommittedPayload = { false },
        )

        assertTrue(store.delete(7))
        assertTrue(File(images, "deferred.png${GeneratedMediaStore.DELETING_SUFFIX}").exists())
        verify(exactly = 1) { repository.deleteMedia(7) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `reconcile restores deletion tombstone when row still exists`() = runTest {
        val filesDir = tempDir("media-delete-recovery")
        val images = File(filesDir, "images").apply { mkdirs() }
        val deleting = File(images, "keep.png${GeneratedMediaStore.DELETING_SUFFIX}").apply { writeText("x") }
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        coEvery { repository.getAllMediaList() } returns listOf(
            GenMediaEntity(id = 6, path = "images/keep.png", modelId = "m", prompt = "p", createAt = 1L),
        )
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))

        store.reconcile()

        assertFalse(deleting.exists())
        assertTrue(File(images, "keep.png").exists())
        verify(exactly = 0) { repository.deleteMedia(any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `deleteAll reports failure when a row delete fails`() = runTest {
        val filesDir = tempDir("media-delete-all-dao")
        val images = File(filesDir, "images").apply { mkdirs() }
        val deleted = File(images, "a.png").apply { writeText("a") }
        val restored = File(images, "b.png").apply { writeText("b") }
        val repository = mockk<GenMediaRepository> { coEvery { existsByPath(any()) } returns false }
        coEvery { repository.listCreatedBefore(Long.MAX_VALUE) } returns listOf(
            GenMediaEntity(id = 1, path = "images/a.png", modelId = "m", prompt = "p", createAt = 1L),
            GenMediaEntity(id = 2, path = "images/b.png", modelId = "m", prompt = "p", createAt = 1L),
        )
        every { repository.deleteMedia(1) } returns Unit
        every { repository.deleteMedia(2) } throws IllegalStateException("dao")
        val store = GeneratedMediaStore(filesDir, repository, mockk(relaxed = true))
        assertFalse(store.deleteAll())
        assertFalse(deleted.exists())
        assertTrue(restored.exists())
        filesDir.deleteRecursively()
    }
}

private fun extendedWebp(): ByteArray {
    val vp8x = byteArrayOf(
        0x56, 0x50, 0x38, 0x58,
        0x0A, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00,
        0x00, 0x00, 0x00,
    )
    val imageChunk = TINY_WEBP.copyOfRange(12, TINY_WEBP.size)
    return byteArrayOf(
        0x52, 0x49, 0x46, 0x46,
        0x00, 0x00, 0x00, 0x00,
        0x57, 0x45, 0x42, 0x50,
    ).plus(vp8x).plus(imageChunk).also { bytes ->
        val riffSize = bytes.size - 8
        bytes[4] = (riffSize and 0xFF).toByte()
        bytes[5] = ((riffSize shr 8) and 0xFF).toByte()
        bytes[6] = ((riffSize shr 16) and 0xFF).toByte()
        bytes[7] = ((riffSize shr 24) and 0xFF).toByte()
    }
}
