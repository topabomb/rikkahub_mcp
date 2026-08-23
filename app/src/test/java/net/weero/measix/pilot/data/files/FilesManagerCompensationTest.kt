package net.weero.measix.pilot.data.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import net.weero.measix.pilot.data.db.dao.ArtifactDAO
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Robolectric 提供真实 Uri 实现（JVM stub 的 Uri.parse 返回 null，无法覆盖成功路径）
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FilesManagerCompensationTest {
    private fun tempDir(prefix: String): File = createTempDirectory(prefix).toFile()

    private fun manager(
        filesDir: File,
        artifactDAO: ArtifactDAO,
        resolver: ContentResolver = mockk(relaxed = true),
    ): FilesManager {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.contentResolver } returns resolver
        return FilesManager(context, artifactDAO, mockk<AppScope>(relaxed = true))
    }

    @Test
    fun `insert failure deletes the written file and no untracked file remains`() = runTest {
        val filesDir = tempDir("files-insert-fail")
        val repository = mockk<ArtifactDAO>()
        coEvery { repository.insert(any()) } throws IllegalStateException("db")
        coEvery { repository.getByPath(any()) } returns null
        val listed = MutableStateFlow<List<ArtifactEntity>>(emptyList())
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
        filesDir.deleteRecursively()
    }

    @Test
    fun `registration failure during createChatFilesByContents rolls back the file and returns no uri`() = runTest {
        val filesDir = tempDir("chat-contents-reg-fail")
        val repository = mockk<ArtifactDAO>()
        coEvery { repository.getByPath(any()) } returns null
        coEvery { repository.insert(any()) } throws IllegalStateException("db")
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } answers {
            ByteArrayInputStream("img".toByteArray())
        }
        val manager = manager(filesDir, repository, resolver)

        // 登记失败 → 回滚删文件 + 不返回 URI（"文件+记录"要么都在要么都不在）
        val uris = manager.createChatFilesByContents(listOf(Uri.parse("content://media/pick/1")))

        assertTrue(uris.isEmpty())
        val leftovers = File(filesDir, FileFolders.UPLOAD).listFiles().orEmpty().filter { it.isFile }
        assertTrue("registration failure must roll back the written file", leftovers.isEmpty())
        filesDir.deleteRecursively()
    }

    @Test
    fun `registration failure during createChatTextFile rolls back the file`() = runTest {
        val filesDir = tempDir("chat-text-reg-fail")
        val repository = mockk<ArtifactDAO>()
        coEvery { repository.getByPath(any()) } returns null
        coEvery { repository.insert(any()) } throws IllegalStateException("db")
        val manager = manager(filesDir, repository)

        val result = runCatching { manager.createChatTextFile("long pasted text") }

        assertTrue(result.isFailure)
        val leftovers = File(filesDir, FileFolders.UPLOAD).listFiles().orEmpty().filter { it.isFile }
        assertTrue("registration failure must roll back the written file", leftovers.isEmpty())
        filesDir.deleteRecursively()
    }

    @Test
    fun `copying an existing upload artifact inherits its origin`() = runTest {
        val filesDir = tempDir("chat-origin-inherit")
        val upload = File(filesDir, FileFolders.UPLOAD).apply { mkdirs() }
        val sourcePath = "${FileFolders.UPLOAD}/generated-copy.png"
        val source = File(upload, "generated-copy.png").apply { writeBytes(byteArrayOf(1)) }
        val repository = mockk<ArtifactDAO>()
        // 源实体：生成媒体副本（fork/克隆/子助手入站的结构性复制场景）
        coEvery { repository.getByPath(sourcePath) } returns ArtifactEntity(
            id = 1,
            folder = FileFolders.UPLOAD,
            relativePath = sourcePath,
            displayName = "generated-copy.png",
            mimeType = "image/png",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
            origin = ArtifactOrigin.GENERATED.name,
        )
        val inserted = mutableListOf<ArtifactEntity>()
        coEvery { repository.getByPath(match { it != sourcePath }) } returns null
        coEvery { repository.insert(any()) } coAnswers { inserted.add(firstArg()); 1L }
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream(byteArrayOf(1)) }
        val manager = manager(filesDir, repository, resolver)

        val uris = manager.createChatFilesByContents(listOf(Uri.parse(source.toURI().toString())))

        assertEquals(1, uris.size)
        assertEquals(
            "structural copy must inherit the source origin",
            ArtifactOrigin.GENERATED.name,
            inserted.single().origin,
        )
        filesDir.deleteRecursively()
    }

    @Test
    fun `external content picks are registered as user origin`() = runTest {
        val filesDir = tempDir("chat-origin-user")
        val repository = mockk<ArtifactDAO>()
        coEvery { repository.getByPath(any()) } returns null
        val inserted = mutableListOf<ArtifactEntity>()
        coEvery { repository.insert(any()) } coAnswers { inserted.add(firstArg()); 1L }
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("img".toByteArray()) }
        val manager = manager(filesDir, repository, resolver)

        val uris = manager.createChatFilesByContents(listOf(Uri.parse("content://media/pick/1")))

        assertEquals(1, uris.size)
        assertEquals(
            "external picks default to user origin",
            ArtifactOrigin.USER.name,
            inserted.single().origin,
        )
        filesDir.deleteRecursively()
    }

    @Test
    fun `null input stream does not insert a missing file`() = runTest {
        val filesDir = tempDir("files-null-stream")
        val repository = mockk<ArtifactDAO>()
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
        val repository = mockk<ArtifactDAO>()
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
        val entity = ArtifactEntity(
            id = 7,
            folder = FileFolders.UPLOAD,
            relativePath = "${FileFolders.UPLOAD}/locked.txt",
            displayName = "locked.txt",
            mimeType = "text/plain",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
        )
        val repository = mockk<ArtifactDAO>()
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
        val entity = ArtifactEntity(
            id = 8,
            folder = FileFolders.UPLOAD,
            relativePath = "${FileFolders.UPLOAD}/gone.txt",
            displayName = "gone.txt",
            mimeType = "text/plain",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
        )
        val repository = mockk<ArtifactDAO>()
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
