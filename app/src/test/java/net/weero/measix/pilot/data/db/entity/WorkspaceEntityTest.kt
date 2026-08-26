package net.weero.measix.pilot.data.db.entity

import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceEntityTest {
    @Test
    fun `unknown persisted shell status fails closed as broken`() {
        val entity = WorkspaceEntity(
            id = "id",
            name = "Workspace",
            root = "root",
            shellStatus = "FUTURE_OR_CORRUPT_VALUE",
            createdAt = 1,
            updatedAt = 1,
        )

        assertEquals(WorkspaceShellStatus.BROKEN, entity.resolvedShellStatus())
        assertEquals(WorkspaceShellStatus.BROKEN, entity.toWorkspace().shellStatus)
    }
}
