package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.subassistant.buildToolCreatedAssistant
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.enterprise.*
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
class AssistantManagementPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val packet = exampleEnterprisePackage()
    private val caller = packet.identity.reference(packet.configuration.assistants.first().id)
    private val initial = UserSettingsDocument.empty().let { it.copy(preferences = it.preferences.withAssistantUsage(packet.identity.scope,
        AssistantUsagePreferences(caller, localTools = UsageValue(listOf(LocalToolOption.AssistantManagement)))) ) }

    @Test fun `failed admission artifact validation or datastore acknowledgement cannot leave half a create`() = runTest {
        for (failure in listOf("admission", "artifact", "datastore")) {
            val appScope = AppScope(StandardTestDispatcher(testScheduler))
            val data = ControlledData(initial, fail = failure == "datastore")
            val settings = SettingsStore(context(), appScope, data)
            val child = buildToolCreatedAssistant("Child", "d", "p")
            try {
                runCurrent()
                val state = if (failure == "admission") appliedConfiguration(packet.copy(configuration = packet.configuration.copy(
                    policy = packet.configuration.policy.copy(allowLocalAssistants = false)))) else appliedConfiguration(packet)
                try {
                    settings.manageAssistant(packet.identity.scope, state, caller, AssistantManagementChange.Create(child), {}) { _, _, commit ->
                        if (failure == "artifact") throw java.io.IOException("artifact rejection")
                        commit()
                    }
                    fail("expected rejection")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                assertEquals(JsonInstant.encodeToString(initial), JsonInstant.encodeToString(settings.snapshotUserDocument()))
                assertFalse(settings.userSettings.value.assistants.any { it.id == child.id })
            } finally { appScope.cancel() }
        }
    }

    @Test fun `owned create acknowledges both facts before propagating cancellation`() = runTest {
        val appScope = AppScope(StandardTestDispatcher(testScheduler))
        val ack = CompletableDeferred<Unit>()
        val data = ControlledData(initial, acknowledgement = ack)
        val settings = SettingsStore(context(), appScope, data)
        val child = buildToolCreatedAssistant("Child", "d", "p")
        try {
            val job = launch { settings.manageAssistant(packet.identity.scope, appliedConfiguration(packet), caller,
                AssistantManagementChange.Create(child), {}) { _, _, commit -> withContext(NonCancellable) { commit() } } }
            runCurrent()
            assertTrue(data.accepted.isCompleted)
            job.cancel()
            runCurrent()
            assertFalse(job.isCompleted)
            assertEquals(JsonInstant.encodeToString(initial), JsonInstant.encodeToString(settings.snapshotUserDocument()))
            ack.complete(Unit)
            runCurrent()
            assertTrue(job.isCancelled && job.isCompleted)
            val saved = settings.snapshotUserDocument()
            assertEquals(child, saved.configuration.assistants.single { it.id == child.id })
            assertEquals(setOf(child.id), saved.preferences.assistantUsage(packet.identity.scope, caller)?.additionalSubAssistantIds)
            assertTrue(settings.userSettings.value.assistants.any { it.id == child.id })
        } finally { ack.complete(Unit); appScope.cancel() }
    }

    @Test fun `cancellation while waiting for artifact ownership is not shielded into a new commit`() = runTest {
        val appScope = AppScope(StandardTestDispatcher(testScheduler))
        val data = ControlledData(initial)
        val settings = SettingsStore(context(), appScope, data)
        val readyToCommit = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val job = launch { settings.manageAssistant(packet.identity.scope, appliedConfiguration(packet), caller,
                AssistantManagementChange.Create(buildToolCreatedAssistant("Child", "d", "p")), {}) { _, _, commit ->
                withContext(NonCancellable) { readyToCommit.complete(Unit); release.await(); commit() }
            } }
            runCurrent()
            assertTrue(readyToCommit.isCompleted)
            job.cancel()
            release.complete(Unit)
            runCurrent()
            assertTrue(job.isCancelled && job.isCompleted)
            assertFalse(data.accepted.isCompleted)
            assertEquals(JsonInstant.encodeToString(initial), JsonInstant.encodeToString(settings.snapshotUserDocument()))
        } finally { release.complete(Unit); appScope.cancel() }
    }

    private fun context(): Context {
        val root = temporary.newFolder()
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
        }
    }
    private class ControlledData(initial: UserSettingsDocument, private val fail: Boolean = false,
        private val acknowledgement: CompletableDeferred<Unit> = CompletableDeferred(Unit)) : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(SettingsStore.USER_SETTINGS to JsonInstant.encodeToString(initial)))
        val accepted = CompletableDeferred<Unit>()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(data.value)
            accepted.complete(Unit)
            acknowledgement.await()
            if (fail) throw java.io.IOException("injected commit failure")
            data.value = next
            return next
        }
    }
}
