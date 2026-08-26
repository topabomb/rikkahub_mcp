package net.weero.measix.pilot.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.dao.WorkspaceDAO
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.data.model.Assistant
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class WorkspaceRepositoryDeleteTest {
    @Test
    fun `staging failure keeps a fail-closed durable record`() = runTest {
        val fixture = fixture(staged = false, filesDeleted = true)

        assertFalse(fixture.repository.delete("id"))

        coVerify(exactly = 1) {
            fixture.dao.updateShellStatus("id", WorkspaceShellStatus.BROKEN.name, any())
        }
        coVerify(exactly = 0) { fixture.dao.deleteById(any()) }
        coVerify(exactly = 0) { fixture.settingsStore.updateLocal(any()) }
    }

    @Test
    fun `durable identity is removed only after filesystem deletion succeeds`() = runTest {
        val fixture = fixture(staged = true, filesDeleted = true)
        coEvery { fixture.dao.deleteById("id") } returns 1
        coEvery { fixture.settingsStore.updateLocal(any()) } returns net.weero.measix.pilot.data.datastore.Settings()

        assertTrue(fixture.repository.delete("id"))

        coVerify(exactly = 1) { fixture.manager.stageWorkspaceDeletion("root") }
        coVerify(exactly = 1) { fixture.manager.deleteStagedWorkspace("root") }
        coVerify(exactly = 1) { fixture.dao.deleteById("id") }
        coVerify(exactly = 1) { fixture.settingsStore.updateLocal(any()) }
        coVerifyOrder {
            fixture.manager.stageWorkspaceDeletion("root")
            fixture.settingsStore.updateLocal(any())
            fixture.manager.deleteStagedWorkspace("root")
            fixture.dao.deleteById("id")
        }
    }

    @Test
    fun `database deletion failure retains the deletion journal after filesystem removal`() = runTest {
        val fixture = fixture(staged = true, filesDeleted = true)
        coEvery { fixture.dao.deleteById("id") } returns 0
        coEvery { fixture.settingsStore.updateLocal(any()) } returns net.weero.measix.pilot.data.datastore.Settings()

        assertFalse(fixture.repository.delete("id"))

        coVerify(exactly = 1) { fixture.manager.deleteStagedWorkspace("root") }
        coVerify(exactly = 1) { fixture.dao.deleteById("id") }
        coVerify(exactly = 0) { fixture.manager.clearWorkspaceDeletionJournal("root") }
    }

    @Test
    fun `assistant cleanup failure keeps the durable workspace identity retryable`() = runTest {
        val fixture = fixture(staged = true, filesDeleted = true)
        coEvery { fixture.settingsStore.updateLocal(any()) } throws IllegalStateException("settings unavailable")

        val failure = runCatching { fixture.repository.delete("id") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 1) { fixture.manager.stageWorkspaceDeletion("root") }
        coVerify(exactly = 1) { fixture.manager.restoreStagedWorkspaceDeletion("root") }
        coVerify(exactly = 0) { fixture.dao.deleteById(any()) }
    }

    @Test
    fun `filesystem deletion failure stays broken because recursive deletion may be partial`() = runTest {
        val workspaceId = Uuid.random()
        val fixture = fixture(
            staged = true,
            filesDeleted = false,
            id = workspaceId.toString(),
            settings = net.weero.measix.pilot.data.datastore.Settings(
                assistants = listOf(Assistant(workspaceId = workspaceId)),
            ),
        )

        assertFalse(fixture.repository.delete(workspaceId.toString()))

        coVerify(exactly = 1) { fixture.manager.stageWorkspaceDeletion("root") }
        coVerify(exactly = 1) { fixture.manager.deleteStagedWorkspace("root") }
        coVerify(exactly = 0) { fixture.manager.restoreStagedWorkspaceDeletion("root") }
        coVerify(exactly = 0) { fixture.manager.clearWorkspaceDeletionJournal("root") }
        coVerify(exactly = 0) { fixture.dao.updateShellStatus(workspaceId.toString(), WorkspaceShellStatus.READY.name, any()) }
        coVerify(exactly = 0) { fixture.dao.deleteById(any()) }
        assertEquals(null, fixture.settings().assistants.single().workspaceId)
    }

    @Test
    fun `retry continues a deletion already marked as physically started`() = runTest {
        val workspaceId = Uuid.random()
        val fixture = fixture(staged = true, filesDeleted = true, id = workspaceId.toString())
        every { fixture.manager.readWorkspaceDeletionJournal("root") } returns (
            "{\"shellStatus\":\"READY\",\"phase\":\"DELETE_STARTED\",\"assistantWorkspaces\":{}}"
        ).encodeToByteArray()
        every { fixture.manager.hasStagedWorkspaceDeletion("root") } returns true
        coEvery { fixture.dao.deleteById(workspaceId.toString()) } returns 1

        assertTrue(fixture.repository.delete(workspaceId.toString()))

        coVerify(exactly = 0) { fixture.manager.stageWorkspaceDeletion("root") }
        coVerify(exactly = 1) { fixture.manager.deleteStagedWorkspace("root") }
        coVerify(exactly = 1) { fixture.dao.deleteById(workspaceId.toString()) }
        coVerify(exactly = 1) { fixture.manager.clearWorkspaceDeletionJournal("root") }
        coVerify(exactly = 0) { fixture.settingsStore.updateLocal(any()) }
    }

    @Test
    fun `retry retains a started deletion journal when database deletion fails`() = runTest {
        val workspaceId = Uuid.random()
        val fixture = fixture(staged = true, filesDeleted = true, id = workspaceId.toString())
        every { fixture.manager.readWorkspaceDeletionJournal("root") } returns (
            "{\"shellStatus\":\"READY\",\"phase\":\"DELETE_STARTED\",\"assistantWorkspaces\":{}}"
        ).encodeToByteArray()
        every { fixture.manager.hasStagedWorkspaceDeletion("root") } returns true
        coEvery { fixture.dao.deleteById(workspaceId.toString()) } returns 0

        assertFalse(fixture.repository.delete(workspaceId.toString()))

        coVerify(exactly = 1) { fixture.manager.deleteStagedWorkspace("root") }
        coVerify(exactly = 0) { fixture.manager.clearWorkspaceDeletionJournal("root") }
    }

    @Test
    fun `startup keeps a partially deleted workspace broken instead of restoring it`() = runTest {
        val workspaceId = Uuid.random()
        val rootDir = Files.createTempDirectory("workspace-partial-deletion").toFile()
        try {
            val dao = mockk<WorkspaceDAO>()
            val manager = mockk<WorkspaceManager>()
            val settingsStore = mockk<SettingsStore>()
            val workspace = WorkspaceEntity(
                id = workspaceId.toString(),
                name = "Workspace",
                root = "root",
                shellStatus = WorkspaceShellStatus.READY.name,
                createdAt = 1,
                updatedAt = 1,
            )
            coEvery { dao.getAll() } returns listOf(workspace)
            coEvery { dao.updateShellStatus(workspace.id, WorkspaceShellStatus.BROKEN.name, any()) } returns 1
            every { manager.readWorkspaceDeletionJournal("root") } returns (
                "{\"shellStatus\":\"READY\",\"phase\":\"DELETE_STARTED\",\"assistantWorkspaces\":{}}"
            ).encodeToByteArray()
            every { manager.hasStagedWorkspaceDeletion("root") } returns true
            every { manager.workspaceDir("root") } returns rootDir
            val repository = WorkspaceRepository(dao, manager, mockk<RootfsInstaller>(), settingsStore)

            repository.checkIntegrity()

            coVerify(exactly = 1) {
                dao.updateShellStatus(workspace.id, WorkspaceShellStatus.BROKEN.name, any())
            }
            coVerify(exactly = 0) { manager.recoverStagedWorkspaceDeletion("root") }
            coVerify(exactly = 0) { manager.clearWorkspaceDeletionJournal("root") }
            coVerify(exactly = 0) { settingsStore.updateLocal(any()) }
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `startup retains a terminal deletion journal when database deletion fails`() = runTest {
        val workspaceId = Uuid.random()
        val missingRoot = Files.createTempDirectory("workspace-missing-deletion").toFile().also { it.deleteRecursively() }
        val dao = mockk<WorkspaceDAO>()
        val manager = mockk<WorkspaceManager>()
        val settingsStore = mockk<SettingsStore>()
        val workspace = WorkspaceEntity(
            id = workspaceId.toString(),
            name = "Workspace",
            root = "root",
            shellStatus = WorkspaceShellStatus.BROKEN.name,
            createdAt = 1,
            updatedAt = 1,
        )
        coEvery { dao.getAll() } returns listOf(workspace)
        coEvery { dao.deleteById(workspace.id) } returns 0
        coEvery { dao.updateShellStatus(workspace.id, WorkspaceShellStatus.BROKEN.name, any()) } returns 1
        every { manager.readWorkspaceDeletionJournal("root") } returns (
            "{\"shellStatus\":\"READY\",\"phase\":\"DELETE_STARTED\",\"assistantWorkspaces\":{}}"
        ).encodeToByteArray()
        every { manager.hasStagedWorkspaceDeletion("root") } returns false
        every { manager.workspaceDir("root") } returns missingRoot
        val repository = WorkspaceRepository(dao, manager, mockk<RootfsInstaller>(), settingsStore)

        repository.checkIntegrity()

        coVerify(exactly = 1) { dao.deleteById(workspace.id) }
        coVerify(exactly = 1) {
            dao.updateShellStatus(workspace.id, WorkspaceShellStatus.BROKEN.name, any())
        }
        coVerify(exactly = 0) { manager.clearWorkspaceDeletionJournal("root") }
    }

    @Test
    fun `startup restores a journaled workspace binding after interrupted compensation`() = runTest {
        val workspaceId = Uuid.random()
        val assistantId = Uuid.random()
        val rootDir = Files.createTempDirectory("workspace-recovery").toFile()
        try {
            val dao = mockk<WorkspaceDAO>()
            val manager = mockk<WorkspaceManager>()
            val settingsStore = mockk<SettingsStore>()
            var localSettings = net.weero.measix.pilot.data.datastore.Settings(
                assistants = listOf(Assistant(id = assistantId)),
            )
            val workspace = WorkspaceEntity(
                id = workspaceId.toString(),
                name = "Workspace",
                root = "root",
                shellStatus = WorkspaceShellStatus.BROKEN.name,
                createdAt = 1,
                updatedAt = 1,
            )
            coEvery { dao.getAll() } returns listOf(workspace)
            coEvery { dao.updateShellStatus(workspace.id, WorkspaceShellStatus.READY.name, any()) } returns 1
            every { manager.readWorkspaceDeletionJournal("root") } returns (
                "{\"shellStatus\":\"READY\",\"phase\":\"PREPARED\",\"assistantWorkspaces\":{\"$assistantId\":\"$workspaceId\"}}"
            ).encodeToByteArray()
            every { manager.hasStagedWorkspaceDeletion("root") } returns false
            every { manager.workspaceDir("root") } returns rootDir
            every { manager.recoverStagedWorkspaceDeletion("root") } returns true
            every { manager.clearWorkspaceDeletionJournal("root") } returns true
            every { manager.hasRootfs("root") } returns true
            coEvery { settingsStore.updateLocal(any()) } coAnswers {
                firstArg<(net.weero.measix.pilot.data.datastore.Settings) -> net.weero.measix.pilot.data.datastore.Settings>()
                    .invoke(localSettings)
                    .also { localSettings = it }
            }
            val repository = WorkspaceRepository(dao, manager, mockk<RootfsInstaller>(), settingsStore)

            repository.checkIntegrity()

            assertEquals(workspaceId, localSettings.assistants.single().workspaceId)
            coVerify(exactly = 1) { manager.clearWorkspaceDeletionJournal("root") }
            coVerify(exactly = 1) {
                dao.updateShellStatus(workspace.id, WorkspaceShellStatus.READY.name, any())
            }
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `failed prepared rollback retains the journal for startup recovery`() = runTest {
        val workspaceId = Uuid.random()
        var localSettings = net.weero.measix.pilot.data.datastore.Settings(
            assistants = listOf(Assistant(workspaceId = workspaceId)),
        )
        val fixture = fixture(
            staged = true,
            filesDeleted = false,
            id = workspaceId.toString(),
            settings = localSettings,
        )
        every { fixture.manager.writeWorkspaceDeletionJournal(any(), any()) } returnsMany listOf(true, false)
        var writes = 0
        coEvery { fixture.settingsStore.updateLocal(any()) } coAnswers {
            if (writes++ == 1) throw IllegalStateException("DataStore unavailable")
            firstArg<(net.weero.measix.pilot.data.datastore.Settings) -> net.weero.measix.pilot.data.datastore.Settings>()
                .invoke(localSettings)
                .also { localSettings = it }
        }

        val failure = runCatching { fixture.repository.delete(workspaceId.toString()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 1) { fixture.manager.restoreStagedWorkspaceDeletion("root") }
        coVerify(exactly = 0) { fixture.manager.clearWorkspaceDeletionJournal("root") }
        coVerify(exactly = 0) {
            fixture.dao.updateShellStatus(workspaceId.toString(), WorkspaceShellStatus.READY.name, any())
        }
    }

    private fun fixture(
        staged: Boolean = true,
        filesDeleted: Boolean,
        id: String = "id",
        settings: net.weero.measix.pilot.data.datastore.Settings = net.weero.measix.pilot.data.datastore.Settings(),
    ): Fixture {
        val dao = mockk<WorkspaceDAO>()
        val manager = mockk<WorkspaceManager>()
        val settingsStore = mockk<SettingsStore>()
        coEvery { dao.getById(id) } returns WorkspaceEntity(
            id = id,
            name = "Workspace",
            root = "root",
            shellStatus = WorkspaceShellStatus.READY.name,
            createdAt = 1,
            updatedAt = 1,
        )
        coEvery { dao.updateShellStatus(id, WorkspaceShellStatus.BROKEN.name, any()) } returns 1
        coEvery { dao.updateShellStatus(id, WorkspaceShellStatus.READY.name, any()) } returns 1
        every { manager.stageWorkspaceDeletion("root") } returns staged
        every { manager.deleteStagedWorkspace("root") } returns filesDeleted
        every { manager.restoreStagedWorkspaceDeletion("root") } returns true
        every { manager.readWorkspaceDeletionJournal("root") } returns null
        every { manager.writeWorkspaceDeletionJournal(any(), any()) } returns true
        every { manager.clearWorkspaceDeletionJournal(any()) } returns true
        var localSettings = settings
        coEvery { settingsStore.snapshotLocal() } answers { localSettings }
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            firstArg<(net.weero.measix.pilot.data.datastore.Settings) -> net.weero.measix.pilot.data.datastore.Settings>()
                .invoke(localSettings)
                .also { localSettings = it }
        }
        return Fixture(
            repository = WorkspaceRepository(dao, manager, mockk<RootfsInstaller>(), settingsStore),
            dao = dao,
            manager = manager,
            settingsStore = settingsStore,
            settings = { localSettings },
        )
    }

    private data class Fixture(
        val repository: WorkspaceRepository,
        val dao: WorkspaceDAO,
        val manager: WorkspaceManager,
        val settingsStore: SettingsStore,
        val settings: () -> net.weero.measix.pilot.data.datastore.Settings,
    )
}
