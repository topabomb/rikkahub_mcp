package net.weero.measix.pilot.data.files

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Android filesystem publication and real Room uniqueness, not a mocked DAO contract. */
@RunWith(AndroidJUnit4::class)
class ManagedFileCreationIntegrationTest {
    private lateinit var root: File
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var store: ArtifactStore
    private lateinit var payloadContext: Context
    private lateinit var settingsCoordinator: ArtifactSettingsCoordinator

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(application.cacheDir.toPath(), "managed-file-test-").toFile()
        payloadContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = root
        }
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        appScope = AppScope()
        settingsCoordinator = ArtifactSettingsCoordinator(SettingsStore(application, appScope))
        store = newStore()
    }

    private fun newStore(): ArtifactStore = ArtifactStore(
        payloadStore = ArtifactPayloadStore(payloadContext),
        artifactDAO = database.artifactDao(),
        artifactReferenceDAO = database.artifactReferenceDao(),
        systemMetaDAO = database.systemMetaDao(),
        conversationDAO = database.conversationDao(),
        messageNodeDAO = database.messageNodeDao(),
        settingsCoordinator = settingsCoordinator,
        transactionRunner = RoomDatabaseTransactionRunner(database),
        fileNameCandidates = { listOf("aaaaaa", "bbbbbbb", "cccccccc", "Dddddddd") },
    )

    @After
    fun tearDown() {
        appScope.cancel()
        database.close()
        check(root.deleteRecursively())
    }

    @Test
    fun concurrentCreatesKeepEveryPayloadAndHistoricalFile() = runBlocking {
        val oldName = "809278de-6677-4bc1-9249-d94c85b0930c.txt"
        val historical = File(root, "upload/$oldName").apply {
            parentFile!!.mkdirs()
            writeText("historical")
        }
        val results = (0 until 12).map { index ->
            async(Dispatchers.IO) {
                index to store.createFromBytes(
                    "payload-$index".toByteArray(), "original.txt", "text/plain", origin = ArtifactOrigin.USER,
                )
            }
        }.awaitAll()

        assertEquals(12, results.map { it.second.localRef.relativePath }.toSet().size)
        results.forEach { (index, owned) ->
            assertEquals(ArtifactState.ACTIVE.name, database.artifactDao().getById(owned.entity.id)!!.state)
            assertArrayEquals("payload-$index".toByteArray(), store.file(owned.localRef).readBytes())
            assertEquals(store.file(owned.localRef), store.resolveToolPath(owned.localRef.toolPath()!!))
        }
        assertEquals(
            (listOf("aaaaaa", "bbbbbbb", "cccccccc", "Dddddddd").map { "upload/$it.txt" } +
                (2..9).map { "upload/aaaaaa-$it.txt" }).toSet(),
            results.map { it.second.localRef.relativePath }.toSet(),
        )
        assertEquals("historical", historical.readText())
        assertEquals(oldName, historical.name)
        assertTrue(File(root, ArtifactPayloadStore.STAGING_FOLDER).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun metadataWithoutPayloadStillReservesItsName() = runBlocking {
        val first = store.createFromBytes(byteArrayOf(1), "a.txt", "text/plain", origin = ArtifactOrigin.USER)
        assertTrue(store.file(first.localRef).delete())
        assertTrue(database.artifactDao().existsByPath(first.localRef.relativePath))

        val second = store.createFromBytes(byteArrayOf(2), "b.txt", "text/plain", origin = ArtifactOrigin.USER)

        assertEquals("upload/bbbbbbb.txt", second.localRef.relativePath)
        assertFalse(store.file(first.localRef).exists())
        assertArrayEquals(byteArrayOf(2), store.file(second.localRef).readBytes())
        assertEquals(first.localRef.relativePath, database.artifactDao().getById(first.entity.id)!!.relativePath)
    }

}
