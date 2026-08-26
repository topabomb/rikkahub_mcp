package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreManagedStateTest {
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = createTempDirectory("settings-store-managed").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `newly applied lock rejects the immediately following local write`() = runTest {
        val assistantId = Uuid.random()
        val environment = store { _, _, _, _, _ ->
            ManagedConfigurationSnapshot(
                state = ManagedConfigurationState.ACTIVE,
                generation = 1,
                overlay = ManagedSettingsOverlay(
                    records = Settings(),
                    access = SettingsAccessIndex(
                        lockReasons = mapOf("records/assistants/$assistantId" to "Managed assistant is read-only"),
                    ),
                ),
            )
        }

        try {
            advanceUntilIdle()
            val revisionBeforeApply = environment.store.effectiveSettings.value.revision
            assertTrue(environment.store.applyManagedSnapshot(managedEnvelope(1)) is ManagedApplyResult.Applied)
            assertEquals(ManagedConfigurationState.ACTIVE, environment.store.effectiveSettings.value.managedState)
            assertTrue(environment.store.effectiveSettings.value.revision > revisionBeforeApply)

            val error = runCatching {
                environment.store.updateLocal { settings ->
                    settings.copy(assistants = listOf(Assistant(id = assistantId, name = "local")))
                }
            }.exceptionOrNull() as? SettingsLockedException
            assertTrue(error != null)
            assertEquals("records/assistants/$assistantId", error?.path)
        } finally {
            environment.scope.cancel()
        }
    }

    @Test
    fun `expiry degrades the active generation without discarding its overlay or lock`() = runTest {
        val assistantId = Uuid.random()
        val environment = store { _, _, _, _, _ ->
            ManagedConfigurationSnapshot(
                state = ManagedConfigurationState.ACTIVE,
                generation = 2,
                expiresAtEpochMillis = 1_000,
                overlay = ManagedSettingsOverlay(
                    records = Settings(assistants = listOf(Assistant(id = assistantId, name = "managed"))),
                    access = SettingsAccessIndex(
                        lockReasons = mapOf("records/assistants/$assistantId" to "Managed assistant is read-only"),
                    ),
                ),
            )
        }

        try {
            advanceUntilIdle()
            assertTrue(environment.store.applyManagedSnapshot(managedEnvelope(2)) is ManagedApplyResult.Applied)
            advanceTimeBy(1_000)
            advanceUntilIdle()

            val effective = environment.store.effectiveSettings.value
            assertEquals(ManagedConfigurationState.DEGRADED, effective.managedState)
            assertEquals("managed", effective.settings.assistants.single { it.id == assistantId }.name)
            assertEquals("Managed assistant is read-only", effective.access.reasonFor("records/assistants/$assistantId"))
        } finally {
            environment.scope.cancel()
        }
    }

    private fun TestScope.store(verifier: ManagedSnapshotVerifier): Environment {
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        return Environment(
            store = SettingsStore.forManagedStateTest(
                appContext = TestContext(ApplicationProvider.getApplicationContext(), filesDir),
                scope = scope,
                runtime = ManagedConfigurationRuntime(
                    nowMillis = { testScheduler.currentTime },
                    verifier = verifier,
                ),
            ),
            scope = scope,
        )
    }

    private data class Environment(
        val store: SettingsStore,
        val scope: AppScope,
    )

    private fun managedEnvelope(generation: Long): ByteArray = JsonInstant.encodeToString(
        ManagedConfigurationEnvelope(
            schemaVersion = 1,
            keyId = "test",
            tenantId = "tenant",
            generation = generation,
            payload = ManagedConfigurationPayload(),
            signature = "",
        ),
    ).encodeToByteArray()

    private class TestContext(base: Context, private val testFilesDir: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = testFilesDir
    }
}
