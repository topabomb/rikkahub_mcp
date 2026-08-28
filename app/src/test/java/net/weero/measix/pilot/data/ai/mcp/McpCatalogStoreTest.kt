package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.McpLegacyCatalogSettingsMigration
import net.weero.measix.pilot.data.datastore.PendingMcpCatalogMigration
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class McpCatalogStoreTest {
    @Test
    fun `only complete non-empty catalog advances the durable revision`() = runTest {
        val scope = AppScope(Dispatchers.Default.limitedParallelism(1))
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val legacyServerId = Uuid.random()
            val legacyJson = """
                [
                  {
                    "type": "streamable_http",
                    "id": "$legacyServerId",
                    "commonOptions": {
                      "enable": true,
                      "name": "Legacy tools",
                      "headers": [],
                      "tools": [
                        {
                          "enable": false,
                          "name": "legacy_measure",
                          "description": "Legacy description",
                          "inputSchema": {"type": "object"},
                          "needsApproval": true
                        }
                      ],
                      "oauth": null
                    },
                    "url": "https://legacy.example/mcp"
                  }
                ]
            """.trimIndent()
            val migratedPreferences = McpLegacyCatalogSettingsMigration().migrate(
                mutablePreferencesOf(SettingsStore.MCP_SERVERS to legacyJson)
            )
            val pendingEncoded = requireNotNull(
                migratedPreferences[SettingsStore.PENDING_MCP_CATALOG_MIGRATION]
            )
            val payload = JsonInstant.decodeFromString<McpLegacyCatalogMigrationPayload>(pendingEncoded)
            assertEquals(listOf(legacyServerId), payload.candidates.map { it.serverId })
            val normalizedServers = JsonInstant.decodeFromString<List<McpServerConfig>>(
                requireNotNull(migratedPreferences[SettingsStore.MCP_SERVERS])
            )
            assertEquals(false, normalizedServers.single().commonOptions.toolPolicies.single().enable)
            assertEquals(true, normalizedServers.single().commonOptions.toolPolicies.single().needsApproval)

            val settingsStore = mockk<SettingsStore>()
            coEvery { settingsStore.pendingMcpCatalogMigration() } returns
                PendingMcpCatalogMigration(pendingEncoded, payload)
            val migrationCompletionGate = CompletableDeferred<Unit>()
            coEvery { settingsStore.completeMcpCatalogMigration(pendingEncoded) } coAnswers {
                migrationCompletionGate.await()
            }
            val store = McpCatalogStore(context, scope, settingsStore)

            val migratedCatalog = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000L) {
                    store.catalogs.first { legacyServerId in it }.getValue(legacyServerId)
                }
            }
            assertEquals(listOf("legacy_measure"), migratedCatalog.tools.map { it.name })
            assertEquals("Legacy description", migratedCatalog.tools.single().description)

            val restoredDefinition = McpServerConfig.StreamableHTTPServer(
                commonOptions = McpCommonOptions(name = "restored"),
                url = "https://restored.example/mcp",
            )
            val restoredSnapshot = candidate(
                restoredDefinition.id,
                restoredDefinition.mcpDefinitionDigest(),
                "restored_tool",
            ).initialSnapshot()
            val restore = async {
                store.restoreCatalogs(listOf(restoredSnapshot), listOf(restoredDefinition))
            }
            runCurrent()
            assertFalse(restore.isCompleted)
            restore.cancelAndJoin()

            val backupSnapshot = async { store.snapshotForBackup(normalizedServers) }
            runCurrent()
            assertFalse(backupSnapshot.isCompleted)

            migrationCompletionGate.complete(Unit)
            assertEquals(listOf("legacy_measure"), backupSnapshot.await().single().tools.map { it.name })
            store.restoreCatalogs(listOf(restoredSnapshot), listOf(restoredDefinition))
            coVerify(exactly = 1) { settingsStore.completeMcpCatalogMigration(pendingEncoded) }
            assertEquals(setOf(restoredDefinition.id), store.catalogs.value.keys)
            assertEquals(listOf("restored_tool"), store.catalogs.value.getValue(restoredDefinition.id).tools.map { it.name })

            val serverId = Uuid.random()
            val firstCandidate = candidate(serverId, "definition-a", "search")

            val first = store.commitCandidate(firstCandidate) as McpCatalogCommitResult.Committed
            assertEquals(1L, first.snapshot.revision)
            assertEquals(listOf("search"), first.snapshot.tools.map { it.name })

            val unchanged = store.commitCandidate(firstCandidate) as McpCatalogCommitResult.Unchanged
            assertEquals(first.snapshot, unchanged.snapshot)

            val rejected = store.commitCandidate(firstCandidate.copy(tools = emptyList()))
                as McpCatalogCommitResult.RejectedEmpty
            assertEquals(first.snapshot, rejected.lastKnownGood)

            val wrongDefinition = store.commitCandidate(
                firstCandidate.copy(definitionDigest = "definition-b", tools = emptyList())
            ) as McpCatalogCommitResult.RejectedEmpty
            assertNull(wrongDefinition.lastKnownGood)

            val changed = store.commitCandidate(candidate(serverId, "definition-a", "measure"))
                as McpCatalogCommitResult.Committed
            assertEquals(2L, changed.snapshot.revision)
            assertEquals(listOf("measure"), changed.snapshot.tools.map { it.name })

            assertNull(changed.snapshot.copy(catalogDigest = "corrupt").validated())
            assertEquals(changed.snapshot, changed.snapshot.validated())

            store.rollbackCommitted(changed.snapshot, changed.previous, changed.headToken)
            assertEquals(first.snapshot, store.catalogs.value[serverId])

            val replacement = store.commitCandidate(candidate(serverId, "definition-a", "replace"))
                as McpCatalogCommitResult.Committed
            store.commitCandidate(candidate(serverId, "definition-a", "replace")) as McpCatalogCommitResult.Unchanged
            store.rollbackCommitted(replacement.snapshot, replacement.previous, replacement.headToken)
            assertEquals(
                "an unchanged observation must protect the current head from an older rollback",
                replacement.snapshot,
                store.catalogs.value[serverId],
            )
        } finally {
            scope.cancel()
        }
    }

    private fun candidate(serverId: Uuid, definition: String, toolName: String) = McpCatalogCandidate(
        serverId = serverId,
        definitionDigest = definition,
        tools = listOf(
            McpCatalogTool(
                name = toolName,
                inputSchema = buildJsonObject { put("type", "object") },
            )
        ),
    )
}
