package net.weero.measix.pilot.data.files

import net.weero.measix.pilot.data.configuration.ConfigurationScope

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import net.weero.measix.pilot.data.datastore.UserSettingsMigration
import net.weero.measix.pilot.data.datastore.ScopedUserPreferences
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.UsageValue
import net.weero.measix.pilot.data.model.Assistant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.utils.JsonInstant
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.repository.GenMediaRepository
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.service.*
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
    private lateinit var settings: SettingsStore
    private lateinit var preferences: DataStore<Preferences>
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
        preferences = PreferenceDataStoreFactory.create(scope = appScope, migrations = listOf(UserSettingsMigration()),
            produceFile = { File(root, "settings.preferences_pb") })
        settings = SettingsStore(payloadContext, appScope, dataStore = preferences)
        settingsCoordinator = ArtifactSettingsCoordinator(settings)
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
        runBlocking { appScope.coroutineContext[Job]!!.cancelAndJoin() }
        database.close()
        check(root.deleteRecursively())
    }

    @Test
    fun fileDirectoriesStatisticsAndDeletionUseTheSelectedPrincipal() = runBlocking {
        store.ensureReferenceProjection()
        val packet = payloadContext.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "enterprise")))
        sessions.recover()
        val originalPersonal = requireNotNull(sessions.observeSelectedRealmSelection().first())
        sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
        val enterpriseSelection = requireNotNull(sessions.observeSelectedRealmSelection().first())
        val enterprise = enterpriseSelection.access.scope
        val other = scopeFor(3)
        val scopes = listOf(ConfigurationScope.Personal, enterprise, other)
        val uploads = scopes.mapIndexed { index, scope ->
            store.createFromBytes(scope, ByteArray(index + 1), "file-$index.txt", "text/plain", origin = ArtifactOrigin.USER)
                .also(store::abandonUnpublished)
        }
        val generated = GeneratedMediaStore(root, GenMediaRepository(database.genMediaDao()), store)
        val mediaIds = scopes.mapIndexed { index, scope ->
            val file = File(root, "images/$index.png").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(index + 4)) }
            withContext(Dispatchers.IO) {
                database.genMediaDao().insert(GenMediaEntity(path = "images/${file.name}", modelId = "model", prompt = "scope-$index", createAt = 1L, scope = scope)).toInt()
            }
        }
        File(root, "images/unregistered.png").writeBytes(ByteArray(90))
        val gate = ApplicationRecoveryGate().also { it.ready() }
        val query = FileManagementQueryService(store, generated, gate, sessions)
        val commands = FileManagementApplicationService(store, generated, gate, sessions)
        val directory = query.observeDirectory().first { it.selection != null }
        assertEquals(listOf(uploads[1].entity.id), directory.uploads.map { (it.key as ManagedFileKey.Upload).artifactId })
        assertEquals(listOf(mediaIds[1]), directory.generated.map { (it.key as ManagedFileKey.Generated).mediaId })
        assertEquals(ManagedStorageUiModel(2, 7), query.observeStorageStats().first { it != null })
        val page = generated.pagingSource(enterprise).load(androidx.paging.PagingSource.LoadParams.Refresh(null, 20, false))
        assertEquals(listOf(mediaIds[1]), (page as androidx.paging.PagingSource.LoadResult.Page).data.map { it.id })
        assertEquals(null, query.inspectUpload(ManagedFileKey.Upload(uploads[0].entity.id, enterpriseSelection)))
        assertEquals(ArtifactDeleteOutcome.AlreadyDeleted, commands.deleteUpload(ManagedFileKey.Upload(uploads[0].entity.id, enterpriseSelection)))
        assertFalse(commands.deleteGenerated(ManagedFileKey.Generated(mediaIds[0], enterpriseSelection)))
        assertEquals(1, query.candidateCount(enterpriseSelection, FileCleanupCategory.UPLOAD, FileCleanupRange.All))
        assertEquals(1, commands.cleanup(enterpriseSelection, FileCleanupCategory.UPLOAD, FileCleanupRange.All).deleted)
        assertEquals(1, commands.cleanup(enterpriseSelection, FileCleanupCategory.GENERATED_IMAGES, FileCleanupRange.All).deleted)
        assertEquals(emptyList<Any>(), store.list(enterprise))
        assertTrue(store.file(uploads[0].entity).isFile)
        assertTrue(store.file(uploads[2].entity).isFile)
        assertTrue(File(root, "images/0.png").isFile)
        assertTrue(File(root, "images/2.png").isFile)
        assertTrue(File(root, "images/unregistered.png").isFile)
        sessions.switchRealm(RealmSwitchRequest(enterpriseSelection, RealmAccess.Personal)) {}
        assertTrue(runCatching { commands.deleteUpload(ManagedFileKey.Upload(uploads[0].entity.id, originalPersonal)) }.exceptionOrNull() is EnterpriseConfigurationException)
        assertEquals(ManagedStorageUiModel(2, 5), query.observeStorageStats().first { it != null })
    }

    @Test
    fun configurationRootsSurviveOwnerRecreationAndDetachAcrossDomains() = runBlocking {
        store.ensureReferenceProjection()
        val owned = store.createText(ConfigurationScope.Personal, "preset payload")
        val uri = owned.uri.toString()
        store.updateSettingsReferences { it.copy(assistants = listOf(Assistant(background = uri))) }
        val before = settings.snapshotUserDocument()
        val enterprise = scopeFor(1)
        val usage = AssistantUsagePreferences(ConfigurationReference.random(), background = UsageValue(uri),
            presetMessages = UsageValue(listOf(UIMessage(role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("keep"), UIMessagePart.Document(uri, "preset.txt", "text/plain"))))))
        preferences.edit { it[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(before.copy(
            preferences = before.preferences.copy(scopes = before.preferences.scopes +
                ScopedUserPreferences(enterprise, assistantUsage = listOf(usage))))) }
        val reopened = newStore()
        reopened.reconcileStartup()
        assertTrue(reopened.collectGarbage(0).isEmpty())
        assertEquals("preset payload", reopened.file(owned.entity).readText())
        assertTrue(reopened.deleteUserRequested(ConfigurationScope.Personal, owned.entity.id) is ArtifactDeleteResult.Completed)
        val after = settings.snapshotUserDocument()
        assertTrue(ArtifactReferencePolicy.roots(after).isEmpty())
        val retained = after.preferences.scopes.single { it.scope == enterprise }.assistantUsage.single()
        assertEquals(UsageValue<String?>(null), retained.background)
        assertEquals(listOf(UIMessagePart.Text("keep")), retained.presetMessages!!.value.single().parts)
        assertFalse(reopened.file(owned.entity).exists())
        assertEquals(null, database.artifactDao().getById(owned.entity.id))
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
                index to store.createFromBytes(scopeFor(index),
                    "payload-$index".toByteArray(), "original.txt", "text/plain", origin = ArtifactOrigin.USER,
                )
            }
        }.awaitAll()

        assertEquals(12, results.map { it.second.localRef.relativePath }.toSet().size)
        results.forEach { (index, owned) ->
            assertEquals(ArtifactState.ACTIVE.name, database.artifactDao().getById(owned.entity.id)!!.state)
            assertEquals(scopeFor(index), newStore().get(owned.entity.id)?.scope)
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

    private fun scopeFor(index: Int): ConfigurationScope = if (index % 2 == 0) ConfigurationScope.Personal else
        ConfigurationScope.Enterprise(me.rerere.common.configuration.EnterpriseAuthority("local:example", "deployment"), "user-$index")

    @Test
    fun metadataWithoutPayloadStillReservesItsName() = runBlocking {
        val first = store.createFromBytes(ConfigurationScope.Personal, byteArrayOf(1), "a.txt", "text/plain", origin = ArtifactOrigin.USER)
        assertTrue(store.file(first.localRef).delete())
        assertTrue(database.artifactDao().existsByPath(first.localRef.relativePath))

        val second = store.createFromBytes(ConfigurationScope.Personal, byteArrayOf(2), "b.txt", "text/plain", origin = ArtifactOrigin.USER)

        assertEquals("upload/bbbbbbb.txt", second.localRef.relativePath)
        assertFalse(store.file(first.localRef).exists())
        assertArrayEquals(byteArrayOf(2), store.file(second.localRef).readBytes())
        assertEquals(first.localRef.relativePath, database.artifactDao().getById(first.entity.id)!!.relativePath)
    }

}
