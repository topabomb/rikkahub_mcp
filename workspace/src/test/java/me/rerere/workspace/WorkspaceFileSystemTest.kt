package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WorkspaceFileSystemTest {
    @Test
    fun fileOperationsWorkInsideWorkspaceRoot() {
        val root = Files.createTempDirectory("workspace-test").toFile()
        val fileSystem = WorkspaceFileSystem()

        fileSystem.writeText(root, "src/main.txt", "hello\nworkspace")

        assertEquals("hello\nworkspace", fileSystem.readText(root, "src/main.txt"))
        assertEquals(listOf("src"), fileSystem.list(root).map { it.path })
        assertEquals(listOf("src/main.txt"), fileSystem.glob(root, "**/*.txt").map { it.path })
        assertEquals(
            listOf(WorkspaceSearchMatch(path = "src/main.txt", line = 2, text = "workspace")),
            fileSystem.grep(root, "workspace"),
        )
    }

    @Test
    fun pathEscapeIsRejected() {
        val root = Files.createTempDirectory("workspace-test").toFile()
        val fileSystem = WorkspaceFileSystem()

        var rejected = false
        try {
            fileSystem.writeText(root, "../escape.txt", "nope")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
