package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsPathResolutionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val root = "test-workspace"

    @Test
    fun normalizedGuestPathDeterminesMountAndApproval() {
        val skills = tempFolder.newFolder("skills")
        val manager = WorkspaceManager(
            tempFolder.newFolder("workspaces"),
            bindMounts = listOf(WorkspaceBindMount(skills, "/skills")),
        ).also { it.ensureWorkspace(root) }

        val skill = manager.resolveRootfsPath(root, "/skills/a/../SKILL.md")
        assertEquals(skills.canonicalFile, skill.rootDir)
        assertEquals("SKILL.md", skill.relativePath)
        assertEquals(manager.filesDir(root), manager.resolveRootfsPath(root, "/tmp/../workspace/a").rootDir)
        assertEquals(manager.linuxDir(root), manager.resolveRootfsPath(root, "/skills/../etc/x").rootDir)
        assertEquals("etc/x", manager.resolveRootfsPath(root, "/tmp/../etc/x").relativePath)
        assertEquals("skills-extra/a", manager.resolveRootfsPath(root, "/skills-extra/a").relativePath)
        assertTrue(RootfsPath.parse("/tmp/../etc/x").requiresWriteApproval)
        assertFalse(RootfsPath.parse("/etc/../workspace/x").requiresWriteApproval)
    }

    @Test
    fun longestBindMountWinsOnSegmentBoundary() {
        val skills = tempFolder.newFolder("skills")
        val nested = tempFolder.newFolder("nested")
        val manager = WorkspaceManager(
            tempFolder.newFolder("workspaces"),
            bindMounts = listOf(WorkspaceBindMount(skills, "/skills"), WorkspaceBindMount(nested, "/skills/local")),
        )
        assertEquals(nested.canonicalFile, manager.resolveRootfsPath(root, "/skills/local/a").rootDir)
        assertEquals(skills.canonicalFile, manager.resolveRootfsPath(root, "/skills/local-other/a").rootDir)
    }

    @Test
    fun rootfsInteriorAndTmpKeepLinuxAnchor() {
        val manager = WorkspaceManager(tempFolder.newFolder("workspaces"))
        assertEquals(RootfsLocation(manager.filesDir(root), "a"), manager.resolveRootfsPath(root, "/workspace/a"))
        assertEquals(RootfsLocation(manager.linuxDir(root), "tmp/a"), manager.resolveRootfsPath(root, "/tmp/a"))
        assertEquals(RootfsLocation(manager.linuxDir(root), "etc/a"), manager.resolveRootfsPath(root, "/etc/a"))
    }

    @Test
    fun writeApprovalUsesNormalizedWholeSegments() {
        listOf("/workspace/a", "/tmp/a", "/workspace", "/tmp//./a", "/etc/../tmp/a").forEach {
            assertFalse(it, RootfsPath.parse(it).requiresWriteApproval)
        }
        listOf("/workspace-extra/a", "/tmp-extra/a", "/etc/a", "/", "/workspace/../etc/a").forEach {
            assertTrue(it, RootfsPath.parse(it).requiresWriteApproval)
        }
    }

    @Test
    fun invalidPathsAndRootNamesAreRejected() {
        listOf("", "relative", "/../etc/x", "/tmp/../../x", "/tmp/\u0000x", "/tmp/\\x").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { RootfsPath.parse(it) }
        }
        val manager = WorkspaceManager(tempFolder.newFolder("workspaces"))
        listOf(".", "..", "../outside").forEach {
            assertThrows(IllegalArgumentException::class.java) { manager.workspaceDir(it) }
        }
    }

    @Test
    fun kernelPathsAreRejectedAfterNormalization() {
        val manager = WorkspaceManager(tempFolder.newFolder("workspaces"))
        listOf("/proc/version", "/tmp/../dev/null", "/sys/a").forEach {
            val error = assertThrows(IllegalStateException::class.java) { manager.resolveRootfsPath(root, it) }
            assertTrue(error.message!!.contains("workspace_shell"))
        }
    }
}
