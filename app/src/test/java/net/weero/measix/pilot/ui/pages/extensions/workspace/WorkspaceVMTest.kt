package net.weero.measix.pilot.ui.pages.extensions.workspace

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.workspace.WorkspaceShellStatus
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceCreated
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceUiModel
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceVMTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `successful create publishes success only after the command returns`() = runTest(dispatcher) {
        val application = mockk<WorkspaceApplicationService>()
        coEvery { application.createWorkspace("New") } returns WorkspaceCreated("id")
        val vm = WorkspaceVM(application, query())
        var result: WorkspaceMutationResult? = null

        vm.create("New") { result = it }
        assertEquals(null, result)
        advanceUntilIdle()

        assertEquals(WorkspaceMutationResult.Success, result)
    }

    @Test
    fun `false and exception become operation-specific failures`() = runTest(dispatcher) {
        val application = mockk<WorkspaceApplicationService>()
        val workspace = WorkspaceUiModel("id", "Old", WorkspaceShellStatus.READY)
        coEvery { application.renameWorkspace("id", "New") } returns false
        coEvery { application.deleteWorkspace("id") } throws IllegalStateException("raw diagnostic")
        val vm = WorkspaceVM(application, query())
        val results = mutableListOf<WorkspaceMutationResult>()

        vm.rename(workspace, "New", results::add)
        vm.delete(workspace, results::add)
        advanceUntilIdle()

        assertEquals(
            listOf(
                WorkspaceMutationResult.Failure(WorkspaceMutationOperation.RENAME),
                WorkspaceMutationResult.Failure(WorkspaceMutationOperation.DELETE),
            ),
            results,
        )
    }

    @Test
    fun `locked workspace cleanup reports its managed reason`() = runTest(dispatcher) {
        val application = mockk<WorkspaceApplicationService>()
        val workspace = WorkspaceUiModel("id", "Old", WorkspaceShellStatus.READY)
        coEvery { application.deleteWorkspace("id") } throws SettingsLockedException("records/assistants", "Managed")
        val vm = WorkspaceVM(application, query())
        var result: WorkspaceMutationResult? = null

        vm.delete(workspace) { result = it }
        advanceUntilIdle()

        assertEquals(WorkspaceMutationResult.Locked("Managed"), result)
    }

    private fun query(): WorkspaceQueryService = mockk<WorkspaceQueryService>().also {
        every { it.observeWorkspaces() } returns emptyFlow()
    }
}
