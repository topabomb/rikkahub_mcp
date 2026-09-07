package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsMigration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class McpCatalogPersistenceTest {
    @Test
    fun restoredAndDiscoveredCatalogsRemainIdenticalToDiskAcrossOwnersReopening() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "mcp-catalog-test-${Uuid.random()}").apply { check(mkdirs()) }
        val old = McpServerConfig.StreamableHTTPServer(url = "https://old.example/mcp")
        val restored = McpServerConfig.StreamableHTTPServer(url = "https://restored.example/mcp")
        val replacement = candidate(restored, "restored").initialSnapshot()
        var final: McpCatalogSnapshot? = null
        try {
            withStore(context, root) { store ->
                store.commitCandidate(candidate(old, "old"))
                store.restoreCatalogs(listOf(replacement), listOf(restored))
                assertEquals(mapOf(restored.id to replacement), store.catalogs.value)
            }
            withStore(context, root) { store ->
                assertEquals(listOf(replacement), store.snapshotForBackup(listOf(restored)))
                assertEquals(mapOf(restored.id to replacement), store.catalogs.value)
                final = (store.commitCandidate(candidate(restored, "discovered")) as McpCatalogCommitResult.Committed).snapshot
                assertEquals(2L, final!!.revision)
            }
            withStore(context, root) { store ->
                assertEquals(listOf(final!!), store.snapshotForBackup(listOf(restored)))
                assertEquals(mapOf(restored.id to final), store.catalogs.value)
                store.remove(restored.id)
            }
            withStore(context, root) { store ->
                assertTrue(store.snapshotForBackup(listOf(restored)).isEmpty())
                assertTrue(store.catalogs.value.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    private suspend fun withStore(app: Context, root: File, operation: suspend (McpCatalogStore) -> Unit) {
        val scope = AppScope(Dispatchers.Default)
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        }
        val preferences = PreferenceDataStoreFactory.create(
            migrations = listOf(UserSettingsMigration()), scope = scope,
            produceFile = { File(root, "settings.preferences_pb") },
        )
        val settings = SettingsStore(context, scope, dataStore = preferences)
        val catalog = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "catalog.preferences_pb") })
        try {
            settings.effectiveSettings.first { !it.settings.init }
            operation(McpCatalogStore(catalog, scope, settings))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    private fun candidate(server: McpServerConfig, tool: String) = McpCatalogCandidate(
        server.id, server.mcpDefinitionDigest(),
        listOf(McpCatalogTool(tool, inputSchema = buildJsonObject { put("type", "object") })),
    )
}
