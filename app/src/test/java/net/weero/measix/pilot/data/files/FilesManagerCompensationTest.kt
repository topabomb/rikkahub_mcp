package net.weero.measix.pilot.data.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.db.entity.ManagedFileEntity
import net.weero.measix.pilot.data.repository.FilesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FilesManagerCompensationTest {
    private fun tempDir(prefix: String): File = createTempDirectory(prefix).toFile()

    private fun manager(
        filesDir: File,
        repository: FilesRepository,
        resolver: ContentResolver = mockk(relaxed = true),
    ): FilesManager {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.contentResolver } returns resolver
        return FilesManager(context, repository, mockk<AppScope>(relaxed = true))
    }

    @Test
    fun `insert failure deletes the written file and sync does not reinsert it`() = runTest {
        val filesDir = tempDir("files-insert-fail")
        val repository = mockk<FilesRepository>()
        coEvery { repository.insert(any()) } throws IllegalStateException("db")
        coEvery { repository.getByPath(any()) } returns null
        val listed = MutableStateFlow<List<ManagedFileEntity>>(emptyList())
        every { repository.listByFolder(FileFolders.UPLOAD) } returns listed
        val manager = manager(filesDir, repository)
        val result = runCatching {
            manager.saveManagedFromBytes(
                folder = FileFolders.UPLOAD,
                bytes = "payload".toByteArray(),
                displayName = "note.txt",
                mimeType = "text/plain",
            )
        }
        assertTrue(result.isFailure)
        val leftovers = File(filesDir, FileFolders.UPLOAD).listFiles().orEmpty().filter { it.isFile }
        assertTrue(leftovers.isEmpty())
        val sync = manager.syncFolder(FileFolders.UPLOAD)
        assertEquals(0, sync.inserted)
        filesDir.deleteRecursively()
    }

    @Test
    fun `null input stream does not insert a missing file`() = runTest {
        val filesDir = tempDir("files-null-stream")
        val repository = mockk<FilesRepository>()
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } returns null
        val manager = manager(filesDir, repository, resolver)
        val result = runCatching {
            manager.saveManagedFromUri(
                folder = FileFolders.UPLOAD,
                uri = Uri.parse("file:///missing"),
                displayName = "note.txt",
                mimeType = "text/plain",
            )
        }
        assertTrue(result.isFailure)
        io.mockk.coVerify(exactly = 0) { repository.insert(any()) }
        val leftovers = File(filesDir, FileFolders.UPLOAD).listFiles().orEmpty().filter { it.isFile }
        assertTrue(leftovers.isEmpty())
        filesDir.deleteRecursively()
    }

    @Test
    fun `uri copy writes file then rolls back on insert failure`() = runTest {
        val filesDir = tempDir("files-uri-fail")
        val source = File(filesDir, "source.txt").apply { writeText("copied") }
        val repository = mockk<FilesRepository>()
        coEvery { repository.insert(any()) } throws IllegalStateException("db")
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("copied".toByteArray()) }
        val manager = manager(filesDir, repository, resolver)
        val result = runCatching {
            manager.saveManagedFromUri(
                folder = FileFolders.UPLOAD,
                uri = Uri.parse(source.toURI().toString()),
                displayName = "note.txt",
                mimeType = "text/plain",
            )
        }
        assertTrue(result.isFailure)
        val leftovers = File(filesDir, FileFolders.UPLOAD).listFiles().orEmpty().filter { it.isFile }
        assertTrue(leftovers.isEmpty())
        filesDir.deleteRecursively()
    }

    @Test
    fun `delete keeps the row when the file cannot be removed`() = runTest {
        val filesDir = tempDir("files-delete-disk")
        val upload = File(filesDir, FileFolders.UPLOAD).apply { mkdirs() }
        val locked = File(upload, "locked.txt").apply { mkdirs() }
        File(locked, "child").writeText("x")
        val entity = ManagedFileEntity(
            id = 7,
            folder = FileFolders.UPLOAD,
            relativePath = "${FileFolders.UPLOAD}/locked.txt",
            displayName = "locked.txt",
            mimeType = "text/plain",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
        )
        val repository = mockk<FilesRepository>()
        coEvery { repository.getById(7) } returns entity
        val manager = manager(filesDir, repository)
        assertFalse(manager.deleteManagedFilePermanently(7))
        io.mockk.coVerify(exactly = 0) { repository.deleteById(any()) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `delete reports failure when dao delete fails after disk delete`() = runTest {
        val filesDir = tempDir("files-delete-dao")
        val upload = File(filesDir, FileFolders.UPLOAD).apply { mkdirs() }
        val file = File(upload, "gone.txt").apply { writeText("x") }
        val entity = ManagedFileEntity(
            id = 8,
            folder = FileFolders.UPLOAD,
            relativePath = "${FileFolders.UPLOAD}/gone.txt",
            displayName = "gone.txt",
            mimeType = "text/plain",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
        )
        val repository = mockk<FilesRepository>()
        coEvery { repository.getById(8) } returns entity
        coEvery { repository.deleteById(8) } throws IllegalStateException("dao")
        val manager = manager(filesDir, repository)
        val result = runCatching { manager.deleteManagedFilePermanently(8) }
        assertTrue(result.isFailure)
        assertFalse(file.exists())
        filesDir.deleteRecursively()
    }

    @Test
    fun `media failure recovery never swallows cancellation`() = runTest {
        val cancellation = CancellationException("stop")

        try {
            recoverMediaPersistenceFailure(
                onFailure = { "fallback" },
                block = { throw cancellation },
            )
            fail("CancellationException must be rethrown")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun `media failure recovery catches expected exceptions but not fatal errors`() = runTest {
        val fallback = recoverMediaPersistenceFailure(
            onFailure = { error -> error.message.orEmpty() },
            block = { throw IllegalArgumentException("invalid image") },
        )
        assertEquals("invalid image", fallback)

        val fatal = AssertionError("fatal")
        try {
            recoverMediaPersistenceFailure(
                onFailure = { "fallback" },
                block = { throw fatal },
            )
            fail("Fatal errors must be rethrown")
        } catch (actual: AssertionError) {
            assertSame(fatal, actual)
        }
    }
}
