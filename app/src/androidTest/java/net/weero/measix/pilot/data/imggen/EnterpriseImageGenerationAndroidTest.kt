package net.weero.measix.pilot.data.imggen

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderManager
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsMigration
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.ArtifactPayloadStore
import net.weero.measix.pilot.data.files.ArtifactSettingsCoordinator
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.repository.GenMediaRepository
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ModelExecutionService
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Native PNG, actual DataStore/Room and the production local image queue; no live Provider. */
@RunWith(AndroidJUnit4::class)
class EnterpriseImageGenerationAndroidTest {
    @Test fun localGenerationAndEditPreserveOriginalScopeAndRejectOldPageAfterSwitchAndExit() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.noBackupFilesDir, "enterprise-image-${Uuid.random()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
        val scope = AppScope(Dispatchers.Default)
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        var networkCalls = 0
        val client = OkHttpClient.Builder().addInterceptor { networkCalls++; error("local image attempted network") }.build()
        try {
            val preferences = PreferenceDataStoreFactory.create(migrations = listOf(UserSettingsMigration()), scope = scope,
                produceFile = { File(root, "settings.preferences_pb") })
            val settings = SettingsStore(context, scope, dataStore = preferences)
            settings.effectiveSettings.first { !it.settings.init }
            val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "enterprise")))
            sessions.recover()
            val packet = app.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
            sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
            val original = requireNotNull(sessions.observeSelectedRealmSelection().first())
            val gate = ApplicationRecoveryGate().apply { ready() }
            val artifacts = ArtifactStore(
                payloadStore = ArtifactPayloadStore(context), artifactDAO = db.artifactDao(),
                artifactReferenceDAO = db.artifactReferenceDao(), systemMetaDAO = db.systemMetaDao(),
                conversationDAO = db.conversationDao(), messageNodeDAO = db.messageNodeDao(),
                settingsCoordinator = ArtifactSettingsCoordinator(settings), transactionRunner = RoomDatabaseTransactionRunner(db),
            )
            val repository = GenMediaRepository(db.genMediaDao())
            val providers = ProviderManager(client, context)
            val models = ModelExecutionService(settings, sessions, gate, providers)
            val coordinator = ImageGenerationCoordinator(scope, GeneratedMediaStore(context.filesDir, repository, artifacts), models, providers, sessions)
            val source = ImageGenerationSource.Page(original, packet.identity.reference("mdl_image"))
            val generated = coordinator.enqueue(ImageGenerationRequest(source = source, prompt = "local generation", size = "256x256", numOfImages = 2)) as ImageGenerationOutcome.Success
            assertFalse(generated.cleanupPending)
            assertEquals(2, generated.media.size)
            generated.media.forEach {
                val bitmap = requireNotNull(BitmapFactory.decodeFile(it.canonicalFile.path))
                try { assertEquals(256, bitmap.width); assertEquals(256, bitmap.height) } finally { bitmap.recycle() }
            }
            val edited = coordinator.enqueue(ImageGenerationRequest(source = source, prompt = "local edit", size = "512x512",
                mediaKind = GeneratedMediaKind.EDIT, editImages = listOf(generated.media.first().canonicalFile.path))) as ImageGenerationOutcome.Success
            val bitmap = requireNotNull(BitmapFactory.decodeFile(edited.media.single().canonicalFile.path))
            try { assertEquals(512, bitmap.width) } finally { bitmap.recycle() }
            assertEquals(3, repository.observeAllMedia(packet.identity.scope).first().size)
            assertTrue(repository.observeAllMedia(ConfigurationScope.Personal).first().isEmpty())
            sessions.selectPersonalFixture()
            sessions.selectEnterpriseFixture()
            try {
                coordinator.enqueue(ImageGenerationRequest(source = source, prompt = "stale page", size = "256x256"))
                fail("old page was accepted after realm roundtrip")
            } catch (_: EnterpriseConfigurationException) { }
            val exit = sessions.beginExit(requireNotNull(sessions.captureExitRequest()))
            coordinator.cancelAndAwait(original.access as RealmAccess.Enterprise)
            sessions.finishExit(exit)
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (sessions.state.value as EnterpriseState.Available).manifest.phase)
            assertEquals(3, repository.observeAllMedia(packet.identity.scope).first().size)
            assertEquals(0, networkCalls)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            check(root.deleteRecursively())
        }
    }
}
