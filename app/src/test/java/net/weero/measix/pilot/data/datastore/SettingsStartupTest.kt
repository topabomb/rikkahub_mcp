package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.utils.JsonInstant
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

    private fun isolatedContext(): Context {
        val root = temporary.newFolder()
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
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
