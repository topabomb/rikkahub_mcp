package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class WorkspaceFileSystemTest {
    @Test fun textReadsPreserveLiteralBytesAndRejectBinaryOrInvalidUtf8() {
        val root = Files.createTempDirectory("workspace-text").toFile()
        try {
            val files = WorkspaceFileSystem()
            val original = "\uFEFF<html>中文 {{literal}}</html>\r\n\r\n"
            root.resolve("page.html").writeBytes(original.toByteArray())
            assertEquals(original, files.readText(root, "page.html"))
            root.resolve("binary").writeBytes(byteArrayOf(65, 0, 66))
            root.resolve("invalid.json").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertThrows(IllegalArgumentException::class.java) { files.readText(root, "binary") }
            assertThrows(java.nio.charset.CharacterCodingException::class.java) { files.readText(root, "invalid.json") }
            assertEquals(original, root.resolve("page.html").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun statDoesNotDependOnDirectoryPageAndRejectsEscapes() {
        val root = Files.createTempDirectory("workspace-stat").toFile()
        try {
            val files = WorkspaceFileSystem(WorkspaceConfig(maxListEntries = 1))
            val expected = files.writeText(root, "z.png", "image")
            files.writeText(root, "a.txt", "first")
            assertEquals(listOf("a.txt"), files.list(root).map { it.path })
            assertEquals(expected, files.stat(root, "z.png"))
            assertTrue(runCatching { files.stat(root, "../outside") }.isFailure)
        } finally { root.deleteRecursively() }
    }

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
