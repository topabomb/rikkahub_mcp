package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.files.ArtifactCleanupResult
import net.weero.measix.pilot.data.imggen.GeneratedMediaCleanupResult
import net.weero.measix.pilot.data.imggen.GeneratedMediaStorageStats
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days

class FileManagementServicesTest {
    @Test
    fun `cutoff is calculated once from the supported typed range`() {
        val now = 1_800_000_000_000L

        assertEquals(Long.MAX_VALUE, cutoffFor(FileCleanupRange.All, now))
        assertEquals(
            now - 14.days.inWholeMilliseconds,
            cutoffFor(FileCleanupRange.OlderThanDays(14), now),
        )
        assertTrue(runCatching { FileCleanupRange.OlderThanDays(1) }.isFailure)
    }

    @Test
    fun `application port routes cleanup to one owner and preserves domain result semantics`() = runTest {
        val gate = ApplicationRecoveryGate().also { it.ready() }
        val artifacts = mockk<ArtifactUseCase>()
        val generated = mockk<GeneratedMediaStore>()
        val service = FileManagementApplicationService(artifacts, generated, gate)
        coEvery { artifacts.deleteUploadsCreatedBefore(Long.MAX_VALUE) } returns
            ArtifactCleanupResult(deleted = 2, cleanupPending = 1, skippedInProgress = 3, failed = 4)
        coEvery { generated.deleteCreatedBefore(Long.MAX_VALUE) } returns
            GeneratedMediaCleanupResult(deleted = 5, cleanupPending = 6, failed = 7)

        assertEquals(
            FileCleanupResult(deleted = 2, cleanupPending = 1, skippedInProgress = 3, failed = 4),
            service.cleanup(FileCleanupCategory.UPLOAD, FileCleanupRange.All),
        )
        coVerify(exactly = 0) { generated.deleteCreatedBefore(any()) }

        assertEquals(
            FileCleanupResult(deleted = 5, cleanupPending = 6, skippedInProgress = 0, failed = 7),
            service.cleanup(FileCleanupCategory.GENERATED_IMAGES, FileCleanupRange.All),
        )
        coVerify(exactly = 1) { artifacts.deleteUploadsCreatedBefore(Long.MAX_VALUE) }
    }

    @Test
    fun `query port owns candidate routing and aggregate storage projection`() = runTest {
        val gate = ApplicationRecoveryGate().also { it.ready() }
        val artifacts = mockk<ArtifactUseCase>()
        val generated = mockk<GeneratedMediaStore>()
        val query = FileManagementQueryService(artifacts, generated, gate)
        coEvery { artifacts.uploadCandidateCount(Long.MAX_VALUE) } returns 11
        coEvery { generated.candidateCount(Long.MAX_VALUE) } returns 12
        coEvery { artifacts.uploadStats() } returns ArtifactStorageStats(count = 3, sizeBytes = 400L)
        coEvery { generated.countCommitted() } returns GeneratedMediaStorageStats(count = 5, sizeBytes = 600L)

        assertEquals(11, query.candidateCount(FileCleanupCategory.UPLOAD, FileCleanupRange.All))
        assertEquals(12, query.candidateCount(FileCleanupCategory.GENERATED_IMAGES, FileCleanupRange.All))
        assertEquals(ManagedStorageUiModel(count = 8, sizeBytes = 1_000L), query.storageStats())
    }

    @Test
    fun `file commands remain fail closed until global recovery succeeds`() = runTest {
        val gate = ApplicationRecoveryGate()
        val artifacts = mockk<ArtifactUseCase>()
        val generated = mockk<GeneratedMediaStore>()
        val service = FileManagementApplicationService(artifacts, generated, gate)
        coEvery { artifacts.deleteUploadsCreatedBefore(any()) } returns
            ArtifactCleanupResult(deleted = 0, cleanupPending = 0, skippedInProgress = 0, failed = 0)

        val result = async {
            runCatching { service.cleanup(FileCleanupCategory.UPLOAD, FileCleanupRange.All) }
        }
        runCurrent()
        coVerify(exactly = 0) { artifacts.deleteUploadsCreatedBefore(any()) }

        gate.failed(IllegalStateException("generated media recovery failed"))
        assertTrue(result.await().exceptionOrNull() is ApplicationRecoveryUnavailableException)
        coVerify(exactly = 0) { artifacts.deleteUploadsCreatedBefore(any()) }
    }
}
