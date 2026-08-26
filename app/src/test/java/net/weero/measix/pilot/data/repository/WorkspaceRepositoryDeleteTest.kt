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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRepositoryDeleteTest {
    @Test
    fun `filesystem failure keeps a fail-closed durable record`() = runTest {
        val fixture = fixture(filesDeleted = false)

        assertFalse(fixture.repository.delete("id"))

        coVerify(exactly = 1) {
            fixture.dao.updateShellStatus("id", WorkspaceShellStatus.BROKEN.name, any())
        }
        coVerify(exactly = 0) { fixture.dao.deleteById(any()) }
        coVerify(exactly = 0) { fixture.settingsStore.update(any()) }
    }

    @Test
    fun `durable identity is removed only after filesystem deletion succeeds`() = runTest {
        val fixture = fixture(filesDeleted = true)
        coEvery { fixture.dao.deleteById("id") } returns 1
        coEvery { fixture.settingsStore.update(any()) } returns Unit

        assertTrue(fixture.repository.delete("id"))

        coVerify(exactly = 1) { fixture.manager.deleteWorkspace("root") }
        coVerify(exactly = 1) { fixture.dao.deleteById("id") }
        coVerify(exactly = 1) { fixture.settingsStore.update(any()) }
        coVerifyOrder {
            fixture.settingsStore.update(any())
            fixture.dao.deleteById("id")
        }
    }

    @Test
    fun `assistant cleanup failure keeps the durable workspace identity retryable`() = runTest {
        val fixture = fixture(filesDeleted = true)
        coEvery { fixture.settingsStore.update(any()) } throws IllegalStateException("settings unavailable")

        val failure = runCatching { fixture.repository.delete("id") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 1) { fixture.manager.deleteWorkspace("root") }
        coVerify(exactly = 0) { fixture.dao.deleteById(any()) }
    }

    private fun fixture(filesDeleted: Boolean): Fixture {
        val dao = mockk<WorkspaceDAO>()
        val manager = mockk<WorkspaceManager>()
        val settingsStore = mockk<SettingsStore>()
        coEvery { dao.getById("id") } returns WorkspaceEntity(
            id = "id",
            name = "Workspace",
            root = "root",
            shellStatus = WorkspaceShellStatus.READY.name,
            createdAt = 1,
            updatedAt = 1,
        )
        coEvery { dao.updateShellStatus("id", WorkspaceShellStatus.BROKEN.name, any()) } returns 1
        every { manager.deleteWorkspace("root") } returns filesDeleted
        return Fixture(
            repository = WorkspaceRepository(dao, manager, mockk<RootfsInstaller>(), settingsStore),
            dao = dao,
            manager = manager,
            settingsStore = settingsStore,
        )
    }

    private data class Fixture(
        val repository: WorkspaceRepository,
        val dao: WorkspaceDAO,
        val manager: WorkspaceManager,
        val settingsStore: SettingsStore,
    )
}
