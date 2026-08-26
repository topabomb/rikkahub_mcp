package net.weero.measix.pilot.service.workspace

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.workspace.WorkspaceShellStatus
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceApplicationServiceTest {
    @Test
    fun `delete waits for an in-flight rename of the same workspace`() = runTest {
        val repository = mockk<WorkspaceRepository>()
        val terminals = mockk<WorkspaceTerminalRuntime>()
        val renameStarted = CompletableDeferred<Unit>()
        val finishRename = CompletableDeferred<Unit>()
        val workspace = workspace()
        coEvery { repository.rename("id", "Renamed") } coAnswers {
            renameStarted.complete(Unit)
            finishRename.await()
            true
        }
        coEvery { repository.getById("id") } returns workspace
        coEvery { terminals.closeWorkspace("root") } returns Unit
        coEvery { repository.delete("id") } returns true
        val service = WorkspaceApplicationService(repository, terminals)

        val rename = async { service.renameWorkspace("id", "Renamed") }
        renameStarted.await()
        val delete = async { service.deleteWorkspace("id") }
        runCurrent()

        coVerify(exactly = 0) { repository.delete(any()) }
        finishRename.complete(Unit)
        assertTrue(rename.await())
        assertTrue(delete.await())
        coVerify(exactly = 1) { repository.delete("id") }
    }

    @Test
    fun `delete waits for an in-flight file write of the same workspace`() = runTest {
        val repository = mockk<WorkspaceRepository>()
        val terminals = mockk<WorkspaceTerminalRuntime>()
        val writeStarted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val workspace = workspace()
        coEvery { repository.writeText("id", "notes.md", "body", overwrite = true) } coAnswers {
            writeStarted.complete(Unit)
            finishWrite.await()
            mockk()
        }
        coEvery { repository.getById("id") } returns workspace
        coEvery { terminals.closeWorkspace("root") } returns Unit
        coEvery { repository.delete("id") } returns true
        val service = WorkspaceApplicationService(repository, terminals)

        val write = async { service.writeText("id", "notes.md", "body") }
        writeStarted.await()
        val delete = async { service.deleteWorkspace("id") }
        runCurrent()

        coVerify(exactly = 0) { repository.delete(any()) }
        finishWrite.complete(Unit)
        write.await()
        assertTrue(delete.await())
        coVerify(exactly = 1) { repository.delete("id") }
    }

    @Test
    fun `delete waits for a compound model tool operation on the same workspace`() = runTest {
        val repository = mockk<WorkspaceRepository>()
        val terminals = mockk<WorkspaceTerminalRuntime>()
        val toolStarted = CompletableDeferred<Unit>()
        val finishTool = CompletableDeferred<Unit>()
        coEvery { repository.getById("id") } returns workspace()
        coEvery { terminals.closeWorkspace("root") } returns Unit
        coEvery { repository.delete("id") } returns true
        val service = WorkspaceApplicationService(repository, terminals)

        val tool = async {
            service.executeTool("id") {
                toolStarted.complete(Unit)
                finishTool.await()
            }
        }
        toolStarted.await()
        val delete = async { service.deleteWorkspace("id") }
        runCurrent()

        coVerify(exactly = 0) { repository.delete(any()) }
        finishTool.complete(Unit)
        tool.await()
        assertTrue(delete.await())
        coVerify(exactly = 1) { repository.delete("id") }
    }

    @Test
    fun `unknown persisted shell status cannot create a terminal`() = runTest {
        val repository = mockk<WorkspaceRepository>()
        val terminals = mockk<WorkspaceTerminalRuntime>()
        coEvery { repository.getById("id") } returns WorkspaceEntity(
            id = "id",
            name = "Workspace",
            root = "root",
            shellStatus = "FUTURE_OR_CORRUPT_VALUE",
            createdAt = 1,
            updatedAt = 1,
        )

        val result = WorkspaceApplicationService(repository, terminals).createTerminal("id")

        assertEquals(WorkspaceTerminalCreateResult.NotReady, result)
        coVerify(exactly = 0) { terminals.create(any(), any()) }
    }

    @Test
    fun `rootfs install closes every terminal before repository mutation`() = runTest {
        val calls = mutableListOf<String>()
        val repository = mockk<WorkspaceRepository>()
        val terminals = mockk<WorkspaceTerminalRuntime>()
        val workspace = WorkspaceEntity(
            id = "id",
            name = "Workspace",
            root = "root",
            shellStatus = WorkspaceShellStatus.READY.name,
            createdAt = 1,
            updatedAt = 1,
        )
        coEvery { repository.getById("id") } returns workspace
        coEvery { terminals.closeWorkspace("root") } coAnswers { calls += "close" }
        coEvery { repository.installRootfs("id", "url", any()) } coAnswers {
            calls += "install"
            true
        }

        WorkspaceApplicationService(repository, terminals).installRootfs("id", "url") {}

        assertEquals(listOf("close", "install"), calls)
    }

    @Test
    fun `terminal creation waits for rootfs mutation on the same workspace`() = runTest {
        val repository = mockk<WorkspaceRepository>()
        val terminals = mockk<WorkspaceTerminalRuntime>()
        val mutationStarted = CompletableDeferred<Unit>()
        val finishMutation = CompletableDeferred<Unit>()
        val workspace = WorkspaceEntity(
            id = "id",
            name = "Workspace",
            root = "root",
            shellStatus = WorkspaceShellStatus.READY.name,
            createdAt = 1,
            updatedAt = 1,
        )
        coEvery { repository.getById("id") } returns workspace
        coEvery { terminals.closeWorkspace("root") } returns Unit
        coEvery { repository.installRootfs("id", "url", any()) } coAnswers {
            mutationStarted.complete(Unit)
            finishMutation.await()
            true
        }
        coEvery { terminals.create("root", any()) } returns WorkspaceTerminalCreateResult.Created("tab")
        val service = WorkspaceApplicationService(repository, terminals)

        val install = async { service.installRootfs("id", "url") {} }
        mutationStarted.await()
        val create = async { service.createTerminal("id") }
        runCurrent()

        coVerify(exactly = 0) { terminals.create(any(), any()) }
        finishMutation.complete(Unit)
        assertTrue(install.await())
        assertEquals(WorkspaceTerminalCreateResult.Created("tab"), create.await())
        coVerify(exactly = 1) { terminals.create("root", any()) }
    }

    @Test
    fun `asynchronous terminal preparation reenters the shared workspace command gate`() = runTest {
        val repository = mockk<WorkspaceRepository>()
        val terminals = mockk<WorkspaceTerminalRuntime>()
        val toolStarted = CompletableDeferred<Unit>()
        val finishTool = CompletableDeferred<Unit>()
        val prepared = CompletableDeferred<Unit>()
        lateinit var prepareUnderGate: suspend (suspend () -> Boolean) -> Boolean
        coEvery { repository.getById("id") } returns workspace()
        coEvery { terminals.create("root", any()) } coAnswers {
            prepareUnderGate = secondArg()
            WorkspaceTerminalCreateResult.Created("tab")
        }
        val service = WorkspaceApplicationService(repository, terminals)
        assertEquals(WorkspaceTerminalCreateResult.Created("tab"), service.createTerminal("id"))

        val tool = async {
            service.executeTool("id") {
                toolStarted.complete(Unit)
                finishTool.await()
            }
        }
        toolStarted.await()
        val preparation = async {
            prepareUnderGate {
                prepared.complete(Unit)
                true
            }
        }
        runCurrent()

        assertFalse(prepared.isCompleted)
        finishTool.complete(Unit)
        tool.await()
        assertTrue(preparation.await())
        assertTrue(prepared.isCompleted)
    }

    private fun workspace() = WorkspaceEntity(
        id = "id",
        name = "Workspace",
        root = "root",
        shellStatus = WorkspaceShellStatus.READY.name,
        createdAt = 1,
        updatedAt = 1,
    )
}
