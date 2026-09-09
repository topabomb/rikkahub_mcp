package net.weero.measix.pilot.data.repository

import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRepositoryDocumentsTest {
    @Test
    fun `cancellation on return from IO closes the descriptor before handoff`() = runTest {
        val owner = Job()
        val descriptor = mockk<ParcelFileDescriptor>()
        every { descriptor.close() } returns Unit
        val manager = mockk<WorkspaceManager>()
        every { manager.openDocument("root", "file", 0) } answers { owner.cancel(); descriptor }
        val repository = WorkspaceRepository(mockk(), manager, mockk(), mockk())
        var cancelled = false
        try { withContext(owner) { repository.openDocument("root", "file", 0) } }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        verify(exactly = 1) { descriptor.close() }
    }
}
