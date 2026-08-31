package me.rerere.workspace

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

@RunWith(AndroidJUnit4::class)
class RootfsFileAccessTest {
    private lateinit var base: File
    private lateinit var manager: WorkspaceManager
    private lateinit var outside: File
    private lateinit var upload: File
    private val root = "test"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        base = Files.createTempDirectory(context.cacheDir.toPath(), "rootfs-io-").toFile().canonicalFile
        outside = File(base, "outside").apply { mkdirs() }
        upload = File(base, "upload").apply { mkdirs() }
        manager = WorkspaceManager(
            File(base, "workspaces"),
            bindMounts = listOf(WorkspaceBindMount(upload, "/upload")),
        )
        manager.ensureWorkspace(root)
        File(manager.linuxDir(root), "tmp").mkdirs()
        File(manager.linuxDir(root), "etc").mkdirs()
    }

    @After
    fun tearDown() {
        Thread.interrupted()
        // walkFileTree does not follow symlinks; tests never remove a link's target recursively.
        Files.walkFileTree(base.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    @Test
    fun safeWriteReadEditAndExistingModesArePreserved() {
        val path = "/workspace/nested/notes.txt"
        manager.writeRootfsText(root, path, "hello 世界", false, false)
        assertEquals("hello 世界", read(path))
        val target = File(manager.filesDir(root), "nested/notes.txt")
        Os.chmod(target.path, 0x1ED)
        manager.updateRootfsText(root, path, 1024, false) { it.replace("hello", "hi") }
        assertEquals("hi 世界", read(path))
        assertEquals(0x1ED, Os.stat(target.path).st_mode and 0x1FF)
        assertThrows(IOException::class.java) { manager.writeRootfsText(root, path, "oops", false, false) }
        assertEquals("hi 世界", target.readText())
        manager.writeRootfsText(root, "/tmp/a", "temp", true, false)
        assertEquals("temp", read("/tmp/a"))
    }

    @Test
    fun existingReadOnlyTargetIsNotMadeWritableByAtomicReplacement() {
        val target = File(manager.filesDir(root), "readonly").apply { writeText("original") }
        Os.chmod(target.path, 0x124)
        assertThrows(IOException::class.java) {
            manager.writeRootfsText(root, "/workspace/readonly", "new", true, false)
        }
        assertThrows(IOException::class.java) {
            manager.updateRootfsText(root, "/workspace/readonly", 1024, false) { "new" }
        }
        assertEquals("original", target.readText())
        assertEquals(0x124, Os.stat(target.path).st_mode and 0x1FF)
    }

    @Test
    fun outsideAndNormalizedTraversalRequireActualApproval() {
        listOf("/etc/x", "/tmp/../etc/x", "/workspace/../etc/x", "/upload/x").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                manager.writeRootfsText(root, path, "denied", true, false)
            }
        }
        assertFalse(File(manager.linuxDir(root), "etc/x").exists())
        assertFalse(File(upload, "x").exists())
        manager.writeRootfsText(root, "/tmp/../etc/x", "approved", true, true)
        assertEquals("approved", read("/etc/x"))
        manager.updateRootfsText(root, "/etc/x", 1024, true) { "$it edit" }
        assertEquals("approved edit", read("/etc/x"))
        manager.writeRootfsText(root, "/upload/x", "mount", true, true)
        assertEquals("mount", File(upload, "x").readText())
        assertEquals("mount", read("/upload/x"))
    }

    @Test
    fun symbolicLinksAreRejectedForLeafParentsAndTmpAnchor() {
        val protected = File(outside, "target").apply { writeText("protected") }
        val files = manager.filesDir(root)
        Os.symlink(protected.path, File(files, "leaf").path)
        Os.symlink(outside.path, File(files, "linked").path)
        listOf("/workspace/leaf", "/workspace/linked/target").forEach { path ->
            assertThrows(IOException::class.java) { manager.writeRootfsText(root, path, "changed", true, false) }
            assertThrows(IOException::class.java) { read(path) }
        }
        val tmp = File(manager.linuxDir(root), "tmp")
        assertTrue(tmp.delete())
        Os.symlink(outside.path, tmp.path)
        assertThrows(IOException::class.java) { manager.writeRootfsText(root, "/tmp/target", "changed", true, false) }
        assertEquals("protected", protected.readText())
    }

    @Test
    fun replacedWorkspaceAndBindAnchorsAreNotFollowed() {
        val protected = File(outside, "target").apply { writeText("protected") }
        val files = manager.filesDir(root)
        assertTrue(files.renameTo(File(files.parentFile, "saved-files")))
        Os.symlink(outside.path, files.path)
        assertThrows(IOException::class.java) { manager.writeRootfsText(root, "/workspace/target", "bad", true, false) }
        assertTrue(upload.renameTo(File(base, "saved-upload")))
        Os.symlink(outside.path, upload.path)
        assertThrows(IOException::class.java) { manager.writeRootfsText(root, "/upload/target", "bad", true, true) }
        assertEquals("protected", protected.readText())
    }

    @Test
    fun hardLinkedWriteTargetCannotModifyAnotherPath() {
        val protected = File(outside, "target").apply { writeText("protected") }
        try {
            Os.link(protected.path, File(manager.filesDir(root), "linked").path)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EACCES && error.errno != OsConstants.EPERM) throw error
            // SELinux can prohibit fixture creation before the application guard is exercised.
            assumeNoException("Device policy prevents creating a hard-link fixture", error)
        }
        assertThrows(IOException::class.java) { manager.writeRootfsText(root, "/workspace/linked", "bad", true, false) }
        assertThrows(IOException::class.java) { manager.updateRootfsText(root, "/workspace/linked", 1024, false) { "bad" } }
        assertEquals("protected", protected.readText())
    }

    @Test
    fun parentReplacementDuringEditKeepsOriginalDirectoryDescriptor() {
        val parent = File(manager.filesDir(root), "parent").apply { mkdirs() }
        val saved = File(manager.filesDir(root), "saved")
        File(parent, "target").writeText("original")
        val protected = File(outside, "target").apply { writeText("protected") }
        manager.updateRootfsText(root, "/workspace/parent/target", 1024, false) {
            assertEquals("original", it)
            assertTrue(parent.renameTo(saved))
            Os.symlink(outside.path, parent.path)
            "updated"
        }
        assertEquals("updated", File(saved, "target").readText())
        assertEquals("protected", protected.readText())
    }

    @Test
    fun replacedLeafDuringEditIsRejectedAndTemporaryIsRemoved() {
        val target = File(manager.filesDir(root), "target").apply { writeText("original") }
        assertThrows(IOException::class.java) {
            manager.updateRootfsText(root, "/workspace/target", 1024, false) {
                val replacement = File(target.parentFile, "replacement").apply { writeText("concurrent") }
                assertTrue(replacement.renameTo(target))
                "new"
            }
        }
        assertEquals("concurrent", target.readText())
        assertFalse(target.parentFile!!.listFiles()!!.any { it.name.startsWith(".workspace-write-") })
    }

    @Test
    fun cancellationBeforeCommitDoesNotTruncateExistingFile() {
        val target = File(manager.filesDir(root), "target").apply { writeText("original") }
        try {
            assertThrows(InterruptedException::class.java) {
                manager.updateRootfsText(root, "/workspace/target", 1024, false) {
                    Thread.currentThread().interrupt()
                    "replacement"
                }
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals("original", target.readText())
        assertFalse(target.parentFile!!.listFiles()!!.any { it.name.startsWith(".workspace-write-") })
    }

    @Test
    fun failedTransformMissingDirectoryAndReadLimitHaveNoWriteEffects() {
        val target = File(manager.filesDir(root), "target").apply { writeText("original") }
        assertThrows(IllegalArgumentException::class.java) {
            manager.updateRootfsText(root, "/workspace/target", 1024, false) { throw IllegalArgumentException("no match") }
        }
        assertThrows(IOException::class.java) { manager.readRootfsBytes(root, "/workspace/target", 3) }
        assertThrows(IOException::class.java) { read("/workspace/missing") }
        assertThrows(IOException::class.java) { read("/workspace") }
        Os.mkfifo(File(manager.filesDir(root), "fifo").path, 0x180)
        assertThrows(IOException::class.java) { read("/workspace/fifo") }
        assertEquals("original", target.readText())
    }

    private fun read(path: String): String = manager.readRootfsBytes(root, path, 8L * 1024 * 1024).toString(Charsets.UTF_8)
}
