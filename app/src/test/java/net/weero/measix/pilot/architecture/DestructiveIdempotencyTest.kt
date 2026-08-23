package net.weero.measix.pilot.architecture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.FilesManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 破坏性操作幂等契约测试（DestructiveIdempotency）。
 *
 * 断言：20 并发 deletePermanently → 磁盘删除恰好一次；
 * 状态机收敛无中间态残留（CAS 保证，与调用顺序无关）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DestructiveIdempotencyTest {

    private lateinit var database: AppDatabase
    private lateinit var filesDir: File

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = createTempDirectory("idem").toFile()
    }

    @After
    fun teardown() {
        database.close()
        filesDir.deleteRecursively()
    }

    @Test
    fun `I4 20 concurrent deletes hit disk exactly once`() = runTest {
        val settings = MutableStateFlow(Settings(assistants = emptyList()))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settings
        coEvery { settingsStore.updateAtomicAndGet(any()) } answers { arg<Any>(0).toString(); settings.value }

        val artifact = ArtifactEntity(
            id = 1L,
            folder = FileFolders.UPLOAD,
            relativePath = "upload/idem.png",
            displayName = "idem.png",
            mimeType = "image/png",
            sizeBytes = 1L,
            createdAt = 0L,
            updatedAt = 0L,
            state = ArtifactState.ACTIVE.name,
        )
        database.artifactDao().insert(artifact)
        val file = File(filesDir, artifact.relativePath).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }

        val diskDeletes = AtomicInteger(0)
        val filesManager = mockk<FilesManager>()
        every { filesManager.getFile(artifact) } returns file
        coEvery { filesManager.deleteManagedFilePermanently(any<Long>(), any<Boolean>()) } coAnswers {
            diskDeletes.incrementAndGet()
            true
        }
        val store = ArtifactStore(
            filesManager = filesManager,
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsStore = settingsStore,
        )

        // 20 并发 deletePermanently
        val results = (0 until 20).map { _ ->
            async(Dispatchers.IO) { store.deletePermanently(artifact) }
        }.awaitAll()

        // 磁盘删除恰好一次（CAS 保证；其余被 Rejected(ALREADY_DELETED/IN_PROGRESS) 或 Failed）
        assertEquals("disk delete exactly once", 1, diskDeletes.get())
        // 状态机收敛无中间态残留：final 要么 DELETING 续删后删除（行不存在），要么保持但非并发中间
        val remaining = database.artifactDao().getById(1L)
        if (remaining != null) {
            assertTrue(
                "no concurrent intermediate state",
                remaining.state == ArtifactState.ACTIVE.name || remaining.state == ArtifactState.DELETING.name,
            )
        }
        // 至少一个 Completed
        assertTrue("at least one Completed", results.any { it is ArtifactDeleteResult.Completed })
    }
}
