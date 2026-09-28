package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.asr.ASRProviderSetting
import me.rerere.common.configuration.EnterpriseAuthority
import me.rerere.tts.provider.TTSProviderSetting
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.service.ApplicationRecoveryCoordinator
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ApplicationRecoveryState
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.turn.TurnRecovery
import net.weero.measix.pilot.service.AssistantManagementService
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Force a caller to reach the writer before the asynchronously scheduled startup work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStartupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `first read failures reach recovery and the same store retries without writing`() = runTest {
        for (failure in listOf(java.io.IOException("settings volume unavailable"), CorruptionException("bad preferences"))) {
            val scope = AppScope(StandardTestDispatcher(testScheduler))
            val preferences = ReadablePreferences(failure)
            val settings = SettingsStore(isolatedContext(), scope, preferences)
            val gate = ApplicationRecoveryGate()
            val artifacts = mockk<ArtifactStore>()
            val media = mockk<GeneratedMediaStore>()
            val conversations = mockk<ConversationRepository>()
            val turns = mockk<TurnRecovery>()
            val assistants = mockk<AssistantManagementService>()
            coEvery { artifacts.reconcileStartup() } returns Unit
            coEvery { artifacts.ensureReferenceProjection() } returns Unit
            coEvery { media.reconcile(any()) } returns Unit
            coEvery { conversations.ensureSearchProjection() } returns Unit
            coEvery { turns.recoverInterruptedRuns() } returns Unit
            coEvery { turns.recoverInterruptedTurns() } returns Unit
            coEvery { assistants.performPendingDeletionCleanupDuringRecovery() } returns Unit
            var maintenance = 0
            val recovery = ApplicationRecoveryCoordinator(
                appScope = scope,
                settingsStore = settings,
                artifactStore = artifacts,
                generatedMediaStore = media,
                conversationRepository = conversations,
                turnRecovery = turns,
                assistantManagementService = lazy { assistants },
                gate = gate,
                recoverEnterpriseConfiguration = {},
                postRecoveryMaintenance = { maintenance++ },
                recoveryDispatcher = StandardTestDispatcher(testScheduler),
                startImmediately = false,
            )
            try {
                recovery.recoverNow()
                assertEquals(failure, (gate.state.value as ApplicationRecoveryState.Failed).error)
                assertTrue(settings.userSettings.value.init)
                assertEquals(0, maintenance)
                coVerify(exactly = 0) { artifacts.reconcileStartup() }
                preferences.failure = null
                recovery.retry()
                runCurrent()
                assertEquals(ApplicationRecoveryState.Ready, gate.state.value)
                assertFalse(settings.userSettings.value.init)
                assertEquals(1, maintenance)
                assertEquals(0, preferences.writes)
                assertEquals(1, preferences.activeReads)
            } finally { scope.cancel() }
        }
    }

    @Test fun `decode failure preserves published settings and concurrent retry owns one observer`() = runTest {
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        val preferences = ReadablePreferences()
        val settings = SettingsStore(isolatedContext(), scope, preferences)
        try {
            settings.initializeForRecovery()
            runCurrent()
            val before = settings.userSettings.value
            val saved = preferences.values.value
            assertEquals(1, preferences.activeReads)
            preferences.values.value = mutablePreferencesOf(SettingsStore.USER_SETTINGS to "{invalid-json")
            // Let the existing observer consume the bad document and fail before recovery can cancel it.
            runCurrent()
            assertEquals(0, preferences.activeReads)
            assertTrue(scope.isActive)
            assertEquals(before, settings.userSettings.value)
            val error = runCatching { settings.initializeForRecovery() }.exceptionOrNull()
            assertTrue(error is kotlinx.serialization.SerializationException)
            assertEquals(0, preferences.activeReads)
            assertEquals(before, settings.userSettings.value)
            preferences.values.value = saved
            coroutineScope {
                repeat(4) { launch { settings.initializeForRecovery() } }
            }
            runCurrent()
            assertFalse(settings.userSettings.value.init)
            assertEquals(before.themeId, settings.userSettings.value.themeId)
            assertEquals(saved, preferences.values.value)
            assertEquals(1, preferences.activeReads)
            assertEquals(0, preferences.writes)
        } finally { scope.cancel() }
    }

    @Test fun `corrupt preferences bytes remain untouched by recovery reads`() = runTest {
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        val file = temporary.newFile("broken.preferences_pb")
        val bytes = byteArrayOf(-1, -1, -1, -1)
        file.writeBytes(bytes)
        val preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val settings = SettingsStore(isolatedContext(), scope, preferences)
        try {
            repeat(2) {
                assertTrue(runCatching { settings.initializeForRecovery() }.exceptionOrNull() is CorruptionException)
                org.junit.Assert.assertArrayEquals(bytes, file.readBytes())
                assertTrue(settings.userSettings.value.init)
            }
        } finally { scope.cancel() }
    }

    @Test fun `failed migration is propagated and a valid migration can retry on the same store`() = runTest {
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        val file = java.io.File(temporary.newFolder(), "migration.preferences_pb")
        val failure = java.io.IOException("migration input unavailable")
        var reject = true
        var cleanup = 0
        val migration = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences) = !currentData.contains(SettingsStore.USER_SETTINGS)
            override suspend fun migrate(currentData: Preferences): Preferences {
                if (reject) throw failure
                return mutablePreferencesOf(SettingsStore.USER_SETTINGS to JsonInstant.encodeToString(UserSettingsDocument.empty()))
            }
            override suspend fun cleanUp() { cleanup++ }
        }
        val preferences = PreferenceDataStoreFactory.create(migrations = listOf(migration), scope = scope, produceFile = { file })
        val settings = SettingsStore(isolatedContext(), scope, preferences)
        try {
            assertEquals(failure, runCatching { settings.initializeForRecovery() }.exceptionOrNull())
            assertFalse(file.exists())
            assertEquals(0, cleanup)
            reject = false
            settings.initializeForRecovery()
            assertFalse(settings.userSettings.value.init)
            assertTrue(file.length() > 0)
            assertEquals(1, cleanup)
        } finally { scope.cancel() }
    }

    @Test fun `first local mutation and restore do not hold the writer while awaiting startup`() = runTest {
        for (restore in listOf(false, true)) {
            val root = temporary.newFolder()
            val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = root
            }
            val appScope = AppScope(StandardTestDispatcher(testScheduler))
            val preferences = object : DataStore<Preferences> {
                override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(
                    SettingsStore.USER_SETTINGS to JsonInstant.encodeToString(UserSettingsDocument.empty())))
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    transform(data.value).also { data.value = it }
            }
            val settings = SettingsStore(context, appScope, dataStore = preferences)
            val write = async(start = CoroutineStart.UNDISPATCHED) {
                if (restore) settings.restoreLocal(Settings(themeId = "early-restore")) { value, commit -> commit(value) }
                else settings.updateLocal { it.copy(themeId = "early-write") }
            }
            try {
                runCurrent()
                val result = withContext(Dispatchers.Default) { withTimeout(5_000) { write.await() } }
                assertEquals(if (restore) "early-restore" else "early-write", result.themeId)
                assertEquals(result.themeId, settings.snapshotLocal().themeId)
                assertEquals(result.themeId, settings.userSettings.value.themeId)
            } finally {
                write.cancel()
                appScope.cancel()
            }
        }
    }
    @Test fun `rejected datastore write cannot publish the proposed configuration`() = runTest {
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        val preferences = AcknowledgedPreferences(fail = true)
        val settings = SettingsStore(isolatedContext(), scope, preferences)
        try {
            runCurrent()
            val before = settings.userSettings.value
            val result = runCatching { settings.updateLocal { it.copy(themeId = "rejected") } }
            assertTrue(result.exceptionOrNull() is java.io.IOException)
            runCurrent()
            assertEquals(before, settings.userSettings.value)
            assertEquals(before.themeId, settings.snapshotLocal().themeId)
        } finally { scope.cancel() }
    }

    @Test fun `clearing a recent conversation preserves other scopes and saved selections`() = runTest {
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        val settings = SettingsStore(isolatedContext(), scope, AcknowledgedPreferences())
        val personal = net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal
        val enterprise = net.weero.measix.pilot.data.configuration.ConfigurationScope.Enterprise(
            me.rerere.common.configuration.EnterpriseAuthority("dep_recent"), "usr_recent")
        val personalId = kotlin.uuid.Uuid.random()
        val enterpriseId = kotlin.uuid.Uuid.random()
        try {
            runCurrent()
            val selected = settings.snapshotUserDocument().preferences.forScope(personal)
            settings.rememberConversation(personal, personalId)
            settings.rememberConversation(enterprise, enterpriseId)
            assertEquals(personalId, settings.lastConversation(personal))
            settings.rememberConversation(personal, null)
            assertEquals(null, settings.lastConversation(personal))
            assertEquals(enterpriseId, settings.lastConversation(enterprise))
            assertEquals(selected, settings.snapshotUserDocument().preferences.forScope(personal))
        } finally { scope.cancel() }
    }

    @Test fun `accepted write waits for acknowledgement and publishes before propagating cancellation`() = runTest {
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        val acknowledgement = CompletableDeferred<Unit>()
        val preferences = AcknowledgedPreferences(acknowledgement = acknowledgement)
        val settings = SettingsStore(isolatedContext(), scope, preferences)
        try {
            runCurrent()
            val before = settings.userSettings.value
            val write = launch { settings.updateLocal { it.copy(themeId = "accepted") } }
            runCurrent()
            assertTrue(preferences.accepted.isCompleted)
            write.cancel()
            runCurrent()
            assertFalse(write.isCompleted)
            assertEquals(before, settings.userSettings.value)
            acknowledgement.complete(Unit)
            runCurrent()
            assertTrue(write.isCancelled)
            assertTrue(write.isCompleted)
            assertEquals("accepted", settings.userSettings.value.themeId)
            assertEquals("accepted", settings.snapshotLocal().themeId)
        } finally {
            acknowledgement.complete(Unit)
            scope.cancel()
        }
    }

    @Test fun `speech reorders are observed and persisted without changing selections or other scopes`() = runTest {
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        val file = java.io.File(temporary.newFolder(), "speech-order.preferences_pb")
        val preferences = PreferenceDataStoreFactory.create(
            migrations = listOf(UserSettingsMigration()),
            scope = scope,
            produceFile = { file },
        )
        val settings = SettingsStore(isolatedContext(), scope, preferences)
        val enterprise = ConfigurationScope.Enterprise(EnterpriseAuthority("dep_order"), "usr_order")
        val enterpriseConversation = kotlin.uuid.Uuid.random()
        val ttsA = TTSProviderSetting.SystemTTS(name = "TTS A")
        val ttsB = TTSProviderSetting.SystemTTS(name = "TTS B")
        val asrA = ASRProviderSetting.OpenAIRealtime(name = "ASR A")
        val asrB = ASRProviderSetting.OpenAIRealtime(name = "ASR B")
        val state = MutableStateFlow<EnterpriseState>(EnterpriseState.Loading)
        val observed = mutableListOf<ResolvedConfiguration>()
        var observer: Job? = null
        fun ResolvedConfiguration.order(category: ConfigurationCategory) =
            catalog.keys.filter { it.category == category }.map { it.reference }
        try {
            settings.initializeForRecovery()
            settings.updateLocal { it.copy(
                ttsProviders = it.ttsProviders + listOf(ttsA, ttsB),
                asrProviders = listOf(asrA, asrB),
                selectedTTSProviderId = ttsA.id,
                selectedASRProviderId = asrA.id,
            ) }
            settings.rememberConversation(enterprise, enterpriseConversation)
            val before = settings.snapshotUserDocument()
            fun assertScopesUnchanged(actual: UserPreferences) {
                assertEquals(before.preferences.scopes.size, actual.scopes.size)
                assertEquals(actual.scopes.size, actual.scopes.map { it.scope }.toSet().size)
                assertEquals(before.preferences.scopes.associateBy { it.scope }, actual.scopes.associateBy { it.scope })
            }
            observer = backgroundScope.launch {
                settings.observeConfiguration(state, ConfigurationScope.Personal).collect { observed += it }
            }
            runCurrent()
            assertEquals(1, observed.size)
            val initial = observed.single()
            assertEquals(ttsA.id, initial.selections.selectedTTSProviderId)
            assertEquals(asrA.id, initial.selections.selectedASRProviderId)

            settings.updateLocal { it.copy(ttsProviders = it.ttsProviders.dropLast(2) + listOf(ttsB, ttsA)) }
            runCurrent()
            assertEquals(2, observed.size)
            val ttsReordered = observed.last()
            assertEquals(initial, ttsReordered)
            assertEquals(initial.order(ConfigurationCategory.TTS).dropLast(2) + listOf(ttsB.id, ttsA.id),
                ttsReordered.order(ConfigurationCategory.TTS))
            assertEquals(initial.order(ConfigurationCategory.ASR), ttsReordered.order(ConfigurationCategory.ASR))

            settings.updateLocal { it.copy(asrProviders = it.asrProviders.reversed()) }
            runCurrent()
            assertEquals(3, observed.size)
            val reordered = observed.last()
            assertEquals(initial, reordered)
            assertEquals(ttsReordered.order(ConfigurationCategory.TTS), reordered.order(ConfigurationCategory.TTS))
            assertEquals(listOf(asrB.id, asrA.id), reordered.order(ConfigurationCategory.ASR))
            observed.forEach {
                assertEquals(initial.selections, it.selections)
                assertEquals(initial.storedSelections, it.storedSelections)
            }
            val persisted = JsonInstant.decodeFromString<UserSettingsDocument>(
                requireNotNull(preferences.data.first()[SettingsStore.USER_SETTINGS]),
            )
            val expectedConfiguration = before.configuration.copy(
                ttsProviders = before.configuration.ttsProviders.dropLast(2) + listOf(ttsB, ttsA),
                asrProviders = listOf(asrB, asrA),
            )
            assertEquals(JsonInstant.encodeToJsonElement(expectedConfiguration),
                JsonInstant.encodeToJsonElement(persisted.configuration))
            assertEquals(before.preferences.common, persisted.preferences.common)
            assertScopesUnchanged(persisted.preferences)
            assertEquals(before.internalState, persisted.internalState)
            assertEquals(enterpriseConversation, settings.lastConversation(enterprise))
            assertTrue(file.length() > 0)

            val resubscribed = settings.observeConfiguration(state, ConfigurationScope.Personal).first()
            assertEquals(reordered.catalog.keys.toList(), resubscribed.catalog.keys.toList())
            assertEquals(reordered, resubscribed)
            settings.updateLocal { it.copy(ttsProviders = it.ttsProviders.toList(), asrProviders = it.asrProviders.toList()) }
            runCurrent()
            assertEquals(3, observed.size)
            settings.updateLocal { it.copy(themeId = "unrelated-speech-order-theme") }
            runCurrent()
            assertEquals(3, observed.size)
            assertEquals("unrelated-speech-order-theme", settings.snapshotLocal().themeId)
            val afterTheme = settings.snapshotUserDocument()
            assertEquals(before.preferences.common.copy(themeId = "unrelated-speech-order-theme"), afterTheme.preferences.common)
            assertScopesUnchanged(afterTheme.preferences)
            assertEquals(before.internalState, afterTheme.internalState)
        } finally {
            observer?.cancel()
            scope.cancel()
        }
    }

    private fun isolatedContext(): Context {
        val root = temporary.newFolder()
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
        }
    }

    private class ReadablePreferences(var failure: Exception? = null) : DataStore<Preferences> {
        val values = MutableStateFlow<Preferences>(mutablePreferencesOf(
            SettingsStore.USER_SETTINGS to JsonInstant.encodeToString(UserSettingsDocument.empty())))
        var writes = 0
        var activeReads = 0
        override val data = flow {
            activeReads++
            try {
                failure?.let { throw it }
                emitAll(values)
            } finally { activeReads-- }
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writes++
            return transform(values.value).also { values.value = it }
        }
    }

    /** Controls the independent DataStore writer's acceptance and durable acknowledgement. */
    private class AcknowledgedPreferences(
        private val fail: Boolean = false,
        private val acknowledgement: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) : DataStore<Preferences> {
        val accepted = CompletableDeferred<Unit>()
        override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(
            SettingsStore.USER_SETTINGS to JsonInstant.encodeToString(UserSettingsDocument.empty()),
        ))
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val proposed = transform(data.value)
            accepted.complete(Unit)
            acknowledgement.await()
            if (fail) throw java.io.IOException("injected write failure")
            data.value = proposed
            return proposed
        }
    }

}
