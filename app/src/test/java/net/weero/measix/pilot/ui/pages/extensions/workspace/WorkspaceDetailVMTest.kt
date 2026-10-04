package net.weero.measix.pilot.ui.pages.extensions.workspace

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import me.rerere.workspace.*
import net.weero.measix.pilot.service.workspace.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceDetailVMTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun `refresh coalesces and ignores a cancelled old directory result`() = runTest(dispatcher) {
        val query = mockk<WorkspaceQueryService>()
        coEvery { query.getWorkspace("id") } returns WorkspaceUiModel("id", "Test", WorkspaceShellStatus.DISABLED)
        val release = CompletableDeferred<Unit>()
        val old = WorkspaceFileEntry("old.txt", "old.txt", false, 1, 0)
        coEvery { query.listFiles("id", WorkspaceStorageArea.FILES, "") } coAnswers {
            withContext(NonCancellable) { release.await() }
            listOf(old)
        }
        coEvery { query.listFiles("id", WorkspaceStorageArea.FILES, "folder") } returns emptyList()
        val vm = WorkspaceDetailVM("id", mockk(), query)
        runCurrent()
        vm.refresh(); vm.refresh(); runCurrent()
        coVerify(exactly = 1) { query.listFiles("id", WorkspaceStorageArea.FILES, "") }
        vm.open(WorkspaceFileEntry("folder", "folder", true, 0, 0))
        runCurrent()
        assertFalse(vm.state.value.loading)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("folder", vm.state.value.path)
        assertTrue(vm.state.value.entries.isEmpty())
    }

    @Test fun `refresh retains entries until a new result arrives`() = runTest(dispatcher) {
        val query = mockk<WorkspaceQueryService>()
        coEvery { query.getWorkspace("id") } returns WorkspaceUiModel("id", "Test", WorkspaceShellStatus.DISABLED)
        val entry = WorkspaceFileEntry("file.txt", "file.txt", false, 1, 0)
        coEvery { query.listFiles("id", WorkspaceStorageArea.FILES, "") } returns listOf(entry)
        val vm = WorkspaceDetailVM("id", mockk(), query)
        advanceUntilIdle()
        val release = CompletableDeferred<Unit>()
        coEvery { query.listFiles("id", WorkspaceStorageArea.FILES, "") } coAnswers { release.await(); emptyList() }
        vm.refresh(); runCurrent()
        assertTrue(vm.state.value.loading)
        assertEquals(listOf(entry), vm.state.value.entries)
        release.complete(Unit); advanceUntilIdle()
        assertTrue(vm.state.value.entries.isEmpty())
        assertFalse(vm.state.value.loading)
    }
}
