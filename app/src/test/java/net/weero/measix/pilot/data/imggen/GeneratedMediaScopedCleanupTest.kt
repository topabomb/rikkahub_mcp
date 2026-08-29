package net.weero.measix.pilot.data.imggen

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.repository.GenMediaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * 生成媒体范围清理：按 create_at cutoff 只删除候选，`All` 与 `deleteCreatedBefore` 同一协议，
 * 全部走 row→payload 的既有删除路径，不做目录扫描。
 */
class GeneratedMediaScopedCleanupTest {

    private fun tempDir(prefix: String): File = createTempDirectory(prefix).toFile()

    private fun entity(id: Int, fileName: String, createdAt: Long) = GenMediaEntity(
        id = id,
        path = "images/$fileName",
        modelId = "model",
        prompt = "prompt-$id",
        createAt = createdAt,
    )

    private fun store(
        filesDir: File,
        repository: GenMediaRepository,
        deletePayload: (File) -> Boolean = File::delete,
    ) = GeneratedMediaStore(
        filesDir = filesDir,
        genMediaRepository = repository,
        artifactStore = mockk<ArtifactStore>(relaxed = true),
        deleteCommittedPayload = deletePayload,
    )

    @Test
    fun `deleteCreatedBefore only removes candidates at or before cutoff`() = runTest {
        val filesDir = tempDir("media-scope")
        val old = entity(1, "old.png", createdAt = 1_000L)
        val fresh = entity(2, "fresh.png", createdAt = 5_000L)
        File(filesDir, "images").mkdirs()
        File(filesDir, "images/old.png").writeText("old")
        File(filesDir, "images/fresh.png").writeText("fresh")

        val repository = mockk<GenMediaRepository>()
        coEvery { repository.listCreatedBefore(2_000L) } returns listOf(old)
        coEvery { repository.getMediaById(1) } returns old
        coEvery { repository.deleteMedia(1) } returns Unit

        val result = store(filesDir, repository).deleteCreatedBefore(2_000L)

        assertEquals(1, result.deleted)
        assertEquals(0, result.failed)
        assertEquals(0, result.cleanupPending)
        assertTrue("old payload must be removed", !File(filesDir, "images/old.png").exists())
        assertTrue("fresh payload must survive", File(filesDir, "images/fresh.png").exists())
        filesDir.deleteRecursively()
    }

    @Test
    fun `deferred payload cleanup is reported and reconciliation finishes it`() = runTest {
        val filesDir = tempDir("media-deferred")
        val images = File(filesDir, "images").apply { mkdirs() }
        val entity = entity(9, "deferred.png", createdAt = 100L)
        File(images, "deferred.png").writeText("x")
        val repository = mockk<GenMediaRepository>()
        coEvery { repository.listCreatedBefore(Long.MAX_VALUE) } returns listOf(entity)
        every { repository.deleteMedia(9) } returns Unit
        coEvery { repository.getAllMediaList() } returns emptyList()
        val store = store(filesDir, repository, deletePayload = { false })

        val result = store.deleteCreatedBefore(Long.MAX_VALUE)

        assertEquals(0, result.deleted)
        assertEquals(1, result.cleanupPending)
        assertEquals(0, result.failed)
        val tombstone = File(images, "deferred.png${GeneratedMediaStore.DELETING_SUFFIX}")
        assertTrue(tombstone.isFile)

        // 下一次 owner reconcile 续跑 row 已删后的 payload 收口。
        GeneratedMediaStore(filesDir, repository, mockk(relaxed = true)).reconcile()
        assertTrue(!tombstone.exists())
        filesDir.deleteRecursively()
    }

    @Test
    fun `row deletion failure restores payload and is reported`() = runTest {
        val filesDir = tempDir("media-row-failure")
        val images = File(filesDir, "images").apply { mkdirs() }
        val entity = entity(10, "restore.png", createdAt = 100L)
        val original = File(images, "restore.png").apply { writeText("x") }
        val repository = mockk<GenMediaRepository>()
        coEvery { repository.listCreatedBefore(Long.MAX_VALUE) } returns listOf(entity)
        every { repository.deleteMedia(10) } throws IllegalStateException("db unavailable")

        val result = store(filesDir, repository).deleteCreatedBefore(Long.MAX_VALUE)

        assertEquals(0, result.deleted)
        assertEquals(0, result.cleanupPending)
        assertEquals(1, result.failed)
        assertTrue(original.isFile)
        assertTrue(!File(images, "restore.png${GeneratedMediaStore.DELETING_SUFFIX}").exists())
        filesDir.deleteRecursively()
    }

    @Test
    fun `deleteAll routes through the same scoped protocol`() = runTest {
        val filesDir = tempDir("media-all")
        File(filesDir, "images").mkdirs()
        val entity = entity(7, "a.png", createdAt = 100L)
        File(filesDir, "images/a.png").writeText("a")

        val repository = mockk<GenMediaRepository>()
        coEvery { repository.listCreatedBefore(Long.MAX_VALUE) } returns listOf(entity)
        coEvery { repository.getMediaById(7) } returns entity
        coEvery { repository.deleteMedia(7) } returns Unit

        val ok = store(filesDir, repository).deleteAll()

        assertTrue(ok)
        assertTrue(!File(filesDir, "images/a.png").exists())
        filesDir.deleteRecursively()
    }

    @Test
    fun `candidateCount reflects the query boundary`() = runTest {
        val filesDir = tempDir("media-count")
        val repository = mockk<GenMediaRepository>()
        coEvery { repository.listCreatedBefore(2_000L) } returns listOf(entity(1, "old.png", 1_000L))
        coEvery { repository.listCreatedBefore(1L) } returns emptyList()

        val store = store(filesDir, repository)

        assertEquals(1, store.candidateCount(2_000L))
        assertEquals(0, store.candidateCount(1L))
        filesDir.deleteRecursively()
    }
}
