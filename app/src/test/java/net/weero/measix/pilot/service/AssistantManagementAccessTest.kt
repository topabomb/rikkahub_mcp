package net.weero.measix.pilot.service

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.ArtifactStore
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
class AssistantManagementAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `original enterprise execution keeps its principal after space switch and cannot reuse a replacement session`() = runTest {
        val f = Fixture(AppScope(StandardTestDispatcher(testScheduler)))
        try {
            val caller = f.enroll()
            runCurrent()
            f.sessions.selectPersonalFixture()
            val created = f.service.createAssistant("Child", "d", "p", caller).getOrThrow()
            val saved = f.settings.snapshotUserDocument()
            assertEquals(setOf(created.id), saved.preferences.assistantUsage(f.packet.identity.scope, caller.assistantId)?.additionalSubAssistantIds)
            assertFalse(saved.configuration.assistants.any { created.id in it.allowedSubAssistantIds })
            val closing = f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest()))
            f.sessions.finishExit(closing)
            f.sessions.enrollFixture(f.packet)
            assertTrue(f.service.createAssistant("Stale", "d", "p", caller).isFailure)
            assertEquals(JsonInstant.encodeToString(saved), JsonInstant.encodeToString(f.settings.snapshotUserDocument()))
        } finally { f.scope.cancel() }
    }

    @Test fun `latest policy and expiry after artifact validation both reject before durable configuration commit`() = runTest {
        val f = Fixture(AppScope(StandardTestDispatcher(testScheduler)))
        try {
            val caller = f.enroll()
            val initial = JsonInstant.encodeToString(f.settings.snapshotUserDocument())
            val access = caller.realmAccess as RealmAccess.Enterprise
            f.sessions.synchronize(access, f.packet.copy(configuration = f.packet.configuration.copy(generation = 2,
                policy = f.packet.configuration.policy.copy(allowLocalAssistants = false))))
            assertTrue(f.service.createAssistant("Denied", "d", "p", caller).isFailure)
            assertEquals(initial, JsonInstant.encodeToString(f.settings.snapshotUserDocument()))
            f.sessions.synchronize(access, f.packet.copy(configuration = f.packet.configuration.copy(generation = 3)))
            f.beforeCommit = { f.now = (f.sessions.state.value as EnterpriseState.Available).manifest.session!!.expiresAtMillis }
            assertTrue(f.service.createAssistant("Expired", "d", "p", caller).isFailure)
            assertEquals(initial, JsonInstant.encodeToString(f.settings.snapshotUserDocument()))
        } finally { f.scope.cancel() }
    }

    private inner class Fixture(val scope: AppScope) {
        val packet = exampleEnterprisePackage()
        var now = 1000L
        var beforeCommit: suspend () -> Unit = {}
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { now }
        private val callerId = packet.identity.reference(packet.configuration.assistants.first().id)
        private val root = temporary.newFolder()
        private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
        }
        private val initial = UserSettingsDocument.empty().let { it.copy(preferences = it.preferences.withAssistantUsage(packet.identity.scope,
            AssistantUsagePreferences(callerId, localTools = UsageValue(listOf(LocalToolOption.AssistantManagement))))) }
        private val data = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(SettingsStore.USER_SETTINGS to JsonInstant.encodeToString(initial)))
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
        }
        val settings = SettingsStore(context, scope, data)
        private val artifacts = mockk<ArtifactStore>()
        val service: AssistantManagementService
        init {
            coEvery { artifacts.manageAssistantReferences(any(), any(), any(), any(), any()) } coAnswers {
                settings.manageAssistant(firstArg(), secondArg(), thirdArg(), arg(3), arg(4)) { _, _, commit ->
                    beforeCommit()
                    withContext(NonCancellable) { commit() }
                }
            }
            service = AssistantManagementService(settings, mockk(), artifacts, mockk(), ApplicationRecoveryGate().apply { ready() }, mockk(), sessions)
        }
        suspend fun enroll(): AssistantManagementCaller {
            sessions.enrollFixture(packet)
            return AssistantManagementCaller(callerId, sessions.captureRealmAccess(packet.identity.scope))
        }
    }
}
