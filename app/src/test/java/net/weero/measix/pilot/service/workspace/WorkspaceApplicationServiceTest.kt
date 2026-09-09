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
    private val selection = net.weero.measix.pilot.data.enterprise.RealmSelection(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0)
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()
    private fun workspaceService(repository: WorkspaceRepository, terminals: WorkspaceTerminalRuntime) = WorkspaceApplicationService(
        repository, terminals, mockk(),
        net.weero.measix.pilot.data.enterprise.EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore(temporary.newFolder())),
        temporary.newFolder(), net.weero.measix.pilot.service.ApplicationRecoveryGate().apply { ready() },
    )

    @Test fun `document mutation rechecks registration after waiting for the existing workspace gate`() = runTest {
        val repository = mockk<WorkspaceRepository>()
        var registered: WorkspaceEntity? = workspace()
        coEvery { repository.getByRoot("root") } coAnswers { registered }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { repository.deleteFile("id", any(), "old", false) } coAnswers {
            entered.complete(Unit)
            release.await()
            true
        }
        val service = workspaceService(repository, mockk())
        val deletion = async { service.deleteFile("id", me.rerere.workspace.WorkspaceStorageArea.FILES, "old", false) }
        entered.await()
        val creation = async { runCatching { service.createDocument("root", "", "new", false) } }
        runCurrent()
        registered = null
        release.complete(Unit)
        deletion.await()
        assertTrue(creation.await().isFailure)
        coVerify(exactly = 0) { repository.createDocument(any(), any(), any(), any()) }
    }

    @Test fun `cancelled input preparation cleans its partial directory and expired capabilities cannot escape`() = runTest {
        val repository = mockk<WorkspaceRepository>()
        coEvery { repository.getById("id") } returns workspace()
        val artifacts = mockk<net.weero.measix.pilot.data.files.ArtifactStore>()
        val started = CompletableDeferred<java.io.File>()
        coEvery { artifacts.copyUploads(any(), any(), any(), any()) } coAnswers {
            val dir = thirdArg<java.io.File>()
            java.io.File(dir, "partial.txt").writeText("partial")
            started.complete(dir)
            kotlinx.coroutines.awaitCancellation()
        }
        val service = WorkspaceApplicationService(repository, mockk(), artifacts,
            net.weero.measix.pilot.data.enterprise.EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore(temporary.newFolder())),
            temporary.newFolder(), net.weero.measix.pilot.service.ApplicationRecoveryGate().apply { ready() })
        val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal
        val request = async { service.executeTool("id", access) { executeCommand("cat", uploads = listOf("/upload/a")) } }
        val dir = started.await()
        assertTrue(dir.isDirectory)
        request.cancel()
        request.join()
        assertFalse(dir.exists())
        coVerify(exactly = 0) { repository.executeCommand(any(), any(), any(), any(), any(), any()) }
        val expired = service.executeTool("id", access) { this }
        assertTrue(runCatching { expired.readRootfsBytes("/workspace/a", 10) }.isFailure)
        coVerify(exactly = 0) { repository.readRootfsBytes(any(), any(), any()) }
    }

    @Test fun `image reads the captured area and rejects changed files before and after IO`() = runTest {
        val repository = mockk<WorkspaceRepository>()
        coEvery { repository.getById("id") } returns workspace()
        val area = me.rerere.workspace.WorkspaceStorageArea.FILES
        val bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        val original = me.rerere.workspace.WorkspaceFileEntry("photo.png", "photo.png", false, bytes.size.toLong(), 10L)
        var current = original
        var changeDuringRead = false
        coEvery { repository.statFile("id", area, "photo.png") } coAnswers { current }
        coEvery { repository.exportFile("id", area, "photo.png", any()) } coAnswers {
            arg<java.io.OutputStream>(3).write(bytes)
            if (changeDuringRead) current = original.copy(updatedAt = 11L)
        }
        val source = workspaceService(repository, mockk()).imageSource("id", area, original)
        org.junit.Assert.assertArrayEquals(bytes, source.readBytes())
        current = original.copy(updatedAt = 11L)
        assertTrue(runCatching { source.requireAccess() }.isFailure)
        assertTrue(runCatching { source.readBytes() }.isFailure)
        current = original
        changeDuringRead = true
        assertTrue(runCatching { source.readBytes() }.isFailure)
        coVerify(exactly = 0) { repository.listFiles(any(), any(), any()) }
    }

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
        val service = workspaceService(repository, terminals)

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
        val service = workspaceService(repository, terminals)

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
        val service = workspaceService(repository, terminals)

        val tool = async {
            service.executeTool("id", net.weero.measix.pilot.data.enterprise.RealmAccess.Personal) {
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

        val result = workspaceService(repository, terminals).createTerminal("id", selection)

        assertEquals(WorkspaceTerminalCreateResult.NotReady, result)
        coVerify(exactly = 0) { terminals.create(any(), any(), any()) }
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

        workspaceService(repository, terminals).installRootfs("id", "url") {}

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
        coEvery { terminals.create("root", selection, any()) } returns WorkspaceTerminalCreateResult.Created("tab")
        val service = workspaceService(repository, terminals)

        val install = async { service.installRootfs("id", "url") {} }
        mutationStarted.await()
        val create = async { service.createTerminal("id", selection) }
        runCurrent()

        coVerify(exactly = 0) { terminals.create(any(), any(), any()) }
        finishMutation.complete(Unit)
        assertTrue(install.await())
        assertEquals(WorkspaceTerminalCreateResult.Created("tab"), create.await())
        coVerify(exactly = 1) { terminals.create("root", selection, any()) }
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
        coEvery { terminals.create("root", selection, any()) } coAnswers {
            prepareUnderGate = thirdArg()
            WorkspaceTerminalCreateResult.Created("tab")
        }
        val service = workspaceService(repository, terminals)
        assertEquals(WorkspaceTerminalCreateResult.Created("tab"), service.createTerminal("id", selection))

        val tool = async {
            service.executeTool("id", net.weero.measix.pilot.data.enterprise.RealmAccess.Personal) {
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
