package net.weero.measix.pilot.data.files

import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.UsageValue
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ArtifactSettingsCommitTest {
    @get:Rule val temporary = TemporaryFolder()
    private val enterprise = ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "user")

    @Test
    fun `root commit keeps lifecycle through disk acknowledgement and cancellation`() = runBlocking {
        withStore { e ->
            val owned = e.artifacts.createText(ConfigurationScope.Personal, "asset")
            e.disk.pauseAck = true
            val writer = async { e.artifacts.updateSettingsReferences { it.copy(assistants = listOf(Assistant(background = owned.uri.toString()))) } }
            e.disk.committed.await()
            writer.cancel()
            val gc = async(start = CoroutineStart.UNDISPATCHED) { e.artifacts.collectGarbage(0) }
            assertFalse(gc.isCompleted)
            e.disk.releaseAck.complete(Unit)
            writer.join()
            assertTrue(gc.await().isEmpty())
            assertEquals(owned.uri.toString(), e.settings.snapshotUserDocument().configuration.assistants.single().background)
            // The acknowledged root transferred the creation pin even though its caller was cancelled.
            assertTrue(e.artifacts.deleteUserRequested(ConfigurationScope.Personal, owned.entity.id) is ArtifactDeleteResult.Completed)
        }
    }

    @Test
    fun `configuration reader and deletion cannot reverse the writer and lifecycle locks`() = runBlocking {
        withStore { e ->
            val row = e.seed()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reader = async {
                e.settings.withResolvedConfiguration(ConfigurationScope.Personal, EnterpriseState.Loading) {
                    entered.complete(Unit)
                    release.await()
                    e.artifacts.collectGarbage(0)
                }
            }
            entered.await()
            val deletion = async(start = CoroutineStart.UNDISPATCHED) { e.artifacts.deleteUserRequested(row.scope, row.id) }
            assertFalse(deletion.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { reader.await(); deletion.await() }
        }
    }

    @Test
    fun `gc winner rejects a later root and direct Settings writes cannot bypass validation`() = runBlocking {
        withStore { e ->
            val row = e.seed()
            val uri = e.payload.file(row.relativePath).toUri().toString()
            assertEquals(listOf(row.id), e.artifacts.collectGarbage(0).map { it.id })
            assertTrue(runCatching { e.artifacts.updateSettingsReferences { it.copy(assistants = listOf(Assistant(background = uri))) } }.isFailure)
            assertTrue(runCatching { e.settings.updateLocal { it.copy(assistants = listOf(Assistant(background = uri))) } }.isFailure)
            assertFalse(uri in ArtifactReferencePolicy.roots(e.settings.snapshotUserDocument()))
        }
    }

    @Test
    fun `inactive enterprise preset roots survive gc and all equivalent tokens detach before deletion`() = runBlocking {
        withStore { e ->
            val row = e.seed(enterprise)
            val uri = e.payload.file(row.relativePath).toUri().toString()
            val alias = uri.replace("/upload/", "/upload/./")
            val id = ConfigurationReference.random()
            val usage = AssistantUsagePreferences(id, avatar = UsageValue(Avatar.Image(alias)), background = UsageValue(uri),
                presetMessages = UsageValue(listOf(UIMessage(role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("keep"), UIMessagePart.Image(alias), UIMessagePart.Tool(
                        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "tool",
                        toolName = "tool", input = "{}", output = emptyList(),
                        metadata = buildJsonObject { put("artifact", buildJsonObject { put("relativePath", row.relativePath.replace('/', '\\')) }) },
                    ))))))
            val initial = e.settings.snapshotUserDocument()
            e.disk.delegate.edit { it[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(initial.copy(
                preferences = initial.preferences.copy(scopes = initial.preferences.scopes +
                    ScopedUserPreferences(enterprise, assistantUsage = listOf(usage))))) }
            assertTrue(e.artifacts.collectGarbage(0).isEmpty())
            val impact = e.artifacts.inspect(row)
            assertEquals(1, impact.assistantAvatarCount)
            assertEquals(1, impact.assistantBackgroundCount)
            assertEquals(1, impact.assistantPresetCount)
            // Reusing the same token in a shared definition cannot launder an enterprise asset.
            assertTrue(runCatching { e.artifacts.updateSettingsReferences { it.copy(assistants = listOf(Assistant(background = uri))) } }.isFailure)
            e.disk.rejectWrite = true
            assertTrue(e.artifacts.deleteUserRequested(row.scope, row.id) is ArtifactDeleteResult.CleanupPending)
            assertEquals(ArtifactState.DELETING.name, e.database.artifactDao().getById(row.id)?.state)
            assertTrue(e.payload.file(row.relativePath).isFile)
            assertTrue(e.artifacts.deleteUserRequested(row.scope, row.id) is ArtifactDeleteResult.Completed)
            val updated = e.settings.snapshotUserDocument().preferences.scopes.single { it.scope == enterprise }.assistantUsage.single()
            assertEquals(UsageValue(Avatar.Dummy), updated.avatar)
            assertEquals(UsageValue<String?>(null), updated.background)
            assertEquals(listOf(UIMessagePart.Text("keep")), updated.presetMessages!!.value.single().parts)
            assertTrue(ArtifactReferencePolicy.roots(e.settings.snapshotUserDocument()).isEmpty())
        }
    }

    private suspend fun withStore(block: suspend (Environment) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val files = temporary.newFolder()
        val context = object : ContextWrapper(app) { override fun getFilesDir() = files }
        val scope = AppScope(Dispatchers.Default)
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val delegate = PreferenceDataStoreFactory.create(scope = diskScope, produceFile = { File(files, "settings.preferences_pb") })
        delegate.edit { it[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(UserSettingsDocument.empty()) }
        val disk = PausedDisk(delegate)
        val settings = SettingsStore(context, scope, dataStore = disk)
        val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val payload = ArtifactPayloadStore(context)
        val artifacts = ArtifactStore(payload, database.artifactDao(), database.artifactReferenceDao(), database.systemMetaDao(),
            database.conversationDao(), database.messageNodeDao(), ArtifactSettingsCoordinator(settings), RoomDatabaseTransactionRunner(database))
        try {
            artifacts.ensureReferenceProjection()
            withTimeout(15_000) { block(Environment(settings, disk, database, payload, artifacts)) }
        } finally {
            disk.releaseAck.complete(Unit)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            diskScope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
        }
    }

    private class Environment(val settings: SettingsStore, val disk: PausedDisk, val database: AppDatabase,
        val payload: ArtifactPayloadStore, val artifacts: ArtifactStore) {
        suspend fun seed(scope: ConfigurationScope = ConfigurationScope.Personal): ArtifactEntity {
            val path = "upload/root.txt"
            payload.file(path).apply { parentFile!!.mkdirs(); writeText("asset") }
            val row = ArtifactEntity(scope = scope, folder = "upload", relativePath = path, displayName = "root.txt",
                mimeType = "text/plain", sizeBytes = 5, createdAt = 1, updatedAt = 1,
                state = ArtifactState.ACTIVE.name, origin = ArtifactOrigin.USER.name)
            return row.copy(id = database.artifactDao().insert(row))
        }
    }

    private class PausedDisk(val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        override val data: Flow<Preferences> = delegate.data
        var pauseAck = false
        var rejectWrite = false
        val committed = CompletableDeferred<Unit>()
        val releaseAck = CompletableDeferred<Unit>()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (rejectWrite) { rejectWrite = false; throw IOException("disk write rejected") }
            val result = delegate.updateData(transform)
            if (pauseAck) { pauseAck = false; committed.complete(Unit); releaseAck.await() }
            return result
        }
    }
}
