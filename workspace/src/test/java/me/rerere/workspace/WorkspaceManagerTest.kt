package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceManagerTest {
    @Test
    fun rootfsFilesDistinguishMissingAndEmptyDirectoriesFromPresentContent() {
        val baseDir = Files.createTempDirectory("workspace-manager-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        assertFalse(manager.hasRootfsFiles(root))
        manager.ensureWorkspace(root)
        assertFalse(manager.hasRootfsFiles(root))
        File(manager.linuxDir(root), "etc").mkdirs()
        assertTrue(manager.hasRootfsFiles(root))
    }

    @Test
    fun prootRunnerRequiresRootfs() {
        val baseDir = Files.createTempDirectory("workspace-proot-test").toFile()
        val manager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = ProotShellRunner(File(baseDir, "native"))
        )
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(root, "cat /etc/os-release")

        assertEquals(127, result.exitCode)
        assertEquals("Rootfs is not installed", result.stderr)
    }
}
