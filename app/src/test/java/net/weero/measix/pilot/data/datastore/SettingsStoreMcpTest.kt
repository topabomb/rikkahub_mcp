package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpToolPolicy
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
class SettingsStoreMcpTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `definition admission excludes concurrent writes and cancellation releases the existing owner`() = runBlocking {
        withStore { settings, _ -> coroutineScope {
            val original = server("original")
            val changed = original.copy(url = "https://changed.test/mcp")
            settings.updateLocal { it.copy(mcpServers = listOf(original)) }
            val entered = CompletableDeferred<Unit>()
            val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                settings.withUserMcpDefinitions { definitions ->
                    assertEquals(listOf(original), definitions)
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            var transformed = false
            val writer = async(start = CoroutineStart.UNDISPATCHED) {
                settings.updateLocal { transformed = true; it.copy(mcpServers = listOf(changed)) }
            }
            assertFalse(transformed)
            assertFalse(writer.isCompleted)
            reader.cancelAndJoin()
            writer.await()
            // This read uses the durable owner, without waiting for a projection collector.
            assertEquals(listOf(changed), settings.withUserMcpDefinitions { it })
            assertEquals(listOf(changed), settings.userMcpDefinitions.first())
        } }
    }

    @Test fun `MCP reads normalize committed user definitions without persisting or relying on effective overlay`() = runBlocking {
        withStore { settings, preferences ->
            val first = server("same").copy(commonOptions = McpCommonOptions(name = "same", toolPolicies = listOf(
                McpToolPolicy(name = "tool", enable = false), McpToolPolicy(name = "tool", enable = true),
            )))
            val duplicateId = first.copy(url = "https://wrong.test/mcp")
            val duplicateName = server(" SAME ")
            val raw = JsonInstant.encodeToString(UserSettingsDocument.empty().withPersonalSettings(
                Settings(mcpServers = listOf(first, duplicateId, duplicateName)),
            ))
            preferences.edit { it[SettingsStore.USER_SETTINGS] = raw }
            val normalized = first.copy(commonOptions = first.commonOptions.copy(toolPolicies = first.commonOptions.toolPolicies.take(1)))
            assertEquals(listOf(normalized), settings.withUserMcpDefinitions { it })
            assertEquals(listOf(normalized), settings.userMcpDefinitions.first())
            assertEquals(raw, preferences.data.first()[SettingsStore.USER_SETTINGS])
        }
    }

    private fun server(name: String) = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = name), url = "https://original.test/mcp",
    )

    private suspend fun withStore(operation: suspend (SettingsStore, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) {
        val root = temporary.newFolder()
        val scope = AppScope(Dispatchers.Default)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root.resolve("files").apply { mkdirs() }
        }
        val preferences = PreferenceDataStoreFactory.create(scope = scope,
            migrations = listOf(UserSettingsMigration()), produceFile = { root.resolve("settings.preferences_pb") })
        try {
            val settings = SettingsStore(context, scope, dataStore = preferences)
            settings.userSettings.first { !it.init }
            operation(settings, preferences)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
