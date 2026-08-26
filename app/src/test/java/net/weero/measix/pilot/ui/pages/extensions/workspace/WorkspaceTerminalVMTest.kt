package net.weero.measix.pilot.ui.pages.extensions.workspace

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalFailure
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalFailureReason
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalScreenUiModel
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalWorkspaceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceTerminalVMTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `asynchronous runtime failure is projected to the terminal command error`() = runTest(dispatcher) {
        val source = MutableStateFlow(WorkspaceTerminalScreenUiModel(workspaceId = "workspace"))
        val query = mockk<WorkspaceTerminalQueryService>()
        every { query.observe("workspace") } returns source
        val vm = WorkspaceTerminalVM(
            workspaceId = "workspace",
            applicationService = mockk<WorkspaceApplicationService>(),
            queryService = query,
        )
        runCurrent()

        source.value = WorkspaceTerminalScreenUiModel(
            workspaceId = "workspace",
            terminal = WorkspaceTerminalWorkspaceState(
                lastFailure = WorkspaceTerminalFailure(
                    id = "failure",
                    reason = WorkspaceTerminalFailureReason.Unexpected,
                ),
            ),
        )
        runCurrent()

        assertEquals(WorkspaceTerminalCommandError.Unexpected, vm.commandError.value)
    }
}
