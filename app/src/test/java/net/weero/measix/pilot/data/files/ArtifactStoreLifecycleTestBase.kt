package net.weero.measix.pilot.data.files

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsAccessIndex
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import org.junit.After
import org.junit.Before

internal abstract class ArtifactStoreLifecycleTestBase {
    protected lateinit var context: Context
    protected lateinit var database: AppDatabase
    protected lateinit var payloadStore: ArtifactPayloadStore
    protected lateinit var settingsFlow: MutableStateFlow<Settings>
    protected lateinit var effectiveSettings: MutableStateFlow<EffectiveSettingsSnapshot>
    protected lateinit var store: ArtifactStore
    protected val folders = mutableSetOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        payloadStore = ArtifactPayloadStore(context)
        settingsFlow = MutableStateFlow(Settings())
        effectiveSettings = MutableStateFlow(settingsFlow.value.toEffectiveSnapshot())
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns effectiveSettings
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(settingsFlow.value).also { updated ->
                settingsFlow.value = updated
                effectiveSettings.value = updated.toEffectiveSnapshot()
            }
        }
        store = ArtifactStore(
            payloadStore = payloadStore,
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsCoordinator = ArtifactSettingsCoordinator(settingsStore),
            transactionRunner = RoomDatabaseTransactionRunner(database),
            fileNameCandidates = { List(4) { "000000" } },
        )
    }

    @After
    fun tearDown() {
        folders.forEach { File(context.filesDir, it).deleteRecursively() }
        File(context.filesDir, ArtifactPayloadStore.STAGING_FOLDER).deleteRecursively()
        database.close()
    }

    protected suspend fun stageBytes(folder: String, bytes: ByteArray, fileName: String): ArtifactPayloadStore.StagedPayload =
        payloadStore.stageFromBytes(payloadStore.reserve(folder, fileName), bytes)

    protected fun testStore(
        payload: ArtifactPayloadStore,
        candidates: List<String> = List(4) { "000000" },
    ) = ArtifactStore(
        payloadStore = payload,
        artifactDAO = database.artifactDao(),
        artifactReferenceDAO = database.artifactReferenceDao(),
        systemMetaDAO = database.systemMetaDao(),
        conversationDAO = database.conversationDao(),
        messageNodeDAO = database.messageNodeDao(),
        settingsCoordinator = mockk(relaxed = true),
        transactionRunner = RoomDatabaseTransactionRunner(database),
        fileNameCandidates = { candidates },
    )

    protected fun folder(): String = "artifact-test-${Uuid.random()}".also(folders::add)

    protected fun entity(
        relativePath: String,
        folder: String,
        state: ArtifactState,
        token: String?,
        createdAt: Long = 1L,
    ) = ArtifactEntity(
        folder = folder,
        relativePath = relativePath,
        displayName = File(relativePath).name,
        mimeType = "application/octet-stream",
        sizeBytes = 1,
        createdAt = createdAt,
        updatedAt = 1,
        state = state.name,
        payloadToken = token,
        origin = ArtifactOrigin.USER.name,
    )
}

internal fun Settings.toEffectiveSnapshot(): EffectiveSettingsSnapshot = EffectiveSettingsSnapshot(
    settings = this,
    access = SettingsAccessIndex(),
    revision = 0L,
    managedState = ManagedConfigurationState.ABSENT,
)
