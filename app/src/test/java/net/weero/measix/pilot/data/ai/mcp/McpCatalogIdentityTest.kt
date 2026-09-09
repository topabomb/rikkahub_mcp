package net.weero.measix.pilot.data.ai.mcp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpCatalogIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val personal = McpServerConfig.StreamableHTTPServer(url = "https://personal.example/mcp")
    private val oldKey = stringPreferencesKey("catalogs")
    private val documentKey = stringPreferencesKey("catalog_document")

    @Test fun `released personal array migrates once and retains its revision and digest`() = runBlocking {
        val original = personalCandidate("old").initialSnapshot().copy(revision = 8)
        withDisk(mutablePreferencesOf(oldKey to releasedArray(original))) { store, disk ->
            store.awaitReady()
            assertEquals(mapOf(original.key to original), store.catalogs.value)
            val persisted = disk.data.first()
            assertNull(persisted[oldKey])
            assertEquals(listOf(original), decodePersonalMcpCatalogImport(persisted[documentKey]!!))
            assertEquals(listOf(original), decodePersonalMcpCatalogImport(releasedArray(original)))
            assertEquals(original.catalogDigest, store.snapshotForBackup(listOf(personal)).single().catalogDigest)
        }
    }

    @Test fun `source user and personal ownership remain isolated across restore and delete`() = runBlocking {
        withDisk { store, disk ->
            val local = EnterpriseAuthority("local:example", "deployment")
            val platform = EnterpriseAuthority("platform:example", "deployment")
            val a = enterpriseCandidate(ConfigurationScope.Enterprise(local, "a")).initialSnapshot()
            val b = enterpriseCandidate(ConfigurationScope.Enterprise(local, "b")).initialSnapshot()
            val remote = enterpriseCandidate(ConfigurationScope.Enterprise(platform, "a")).initialSnapshot()
            listOf(a, b, remote).forEach { snapshot ->
                store.commitCandidate(McpCatalogCandidate(snapshot.scope, snapshot.serverId, snapshot.definitionDigest, snapshot.tools, snapshot.managed))
            }
            store.commitCandidate(personalCandidate("old"))
            assertEquals(4, store.catalogs.value.size)
            assertEquals(1, store.snapshotForBackup(listOf(personal)).size)
            val replacement = personalCandidate("replacement").initialSnapshot()
            store.restorePersonalCatalogs(listOf(replacement), listOf(personal))
            assertEquals(mapOf(a.key to a, b.key to b, remote.key to remote, replacement.key to replacement), store.catalogs.value)
            val before = disk.data.first()[documentKey]
            try { store.restorePersonalCatalogs(listOf(a), listOf(personal)); fail("Enterprise catalog entered personal restore") }
            catch (_: IllegalArgumentException) { }
            assertEquals(before, disk.data.first()[documentKey])
            try { decodePersonalMcpCatalogImport(encodeMcpCatalogDocument(listOf(a))); fail("Enterprise import was accepted") }
            catch (_: IllegalArgumentException) { }
            store.remove(a.key)
            assertEquals(setOf(b.key, remote.key, replacement.key), store.catalogs.value.keys)
            assertEquals(listOf(replacement), store.snapshotForBackup(listOf(personal)))
        }
    }

    @Test fun `personal restore preserves enterprise publication rollback ownership`() = runBlocking {
        withDisk { store, _ ->
            val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "user")
            val candidate = enterpriseCandidate(scope)
            val first = store.commitCandidate(candidate) as McpCatalogCommitResult.Committed
            val next = store.commitCandidate(candidate.copy(tools = listOf(tool("updated")))) as McpCatalogCommitResult.Committed
            val personal = personalCandidate("restored").initialSnapshot()
            store.restorePersonalCatalogs(listOf(personal), listOf(this@McpCatalogIdentityTest.personal))
            // The personal replacement cannot revoke an enterprise connection's compensation receipt.
            store.rollbackCommitted(next.snapshot, next.previous, next.headToken)
            assertEquals(first.snapshot, store.catalogs.value[candidate.key])
            assertEquals(personal, store.catalogs.value[personal.key])
            val latest = store.commitCandidate(candidate.copy(tools = listOf(tool("latest")))) as McpCatalogCommitResult.Committed
            store.restorePersonalCatalogs(emptyList(), emptyList())
            store.rollbackCommitted(first.snapshot, null, first.headToken)
            assertEquals(mapOf(latest.snapshot.key to latest.snapshot), store.catalogs.value)
        }
    }

    @Test fun `damaged scoped document cannot fall back to old personal key or erase enterprise facts`() = runBlocking {
        val original = personalCandidate("old").initialSnapshot()
        val enterprise = enterpriseCandidate(ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "a"))
            .initialSnapshot().copy(catalogDigest = "damaged")
        listOf("{", encodeMcpCatalogDocument(listOf(enterprise))).forEach { corrupt ->
            withDisk(mutablePreferencesOf(documentKey to corrupt, oldKey to releasedArray(original))) { store, disk ->
                try { store.awaitReady(); fail("Damaged current document became ready") }
                catch (_: IllegalArgumentException) { }
                try { store.restorePersonalCatalogs(listOf(original), listOf(personal)); fail("Restore erased unknown enterprise facts") }
                catch (_: IllegalArgumentException) { }
                assertEquals(corrupt, disk.data.first()[documentKey])
                assertEquals(releasedArray(original), disk.data.first()[oldKey])
                assertTrue(store.catalogs.value.isEmpty())
            }
        }
    }

    @Test fun `catalog keys reject inconsistent resource owners`() {
        val authority = EnterpriseAuthority("local:example", "deployment")
        val scope = ConfigurationScope.Enterprise(authority, "user")
        val resource = ConfigurationReference.Enterprise(authority, "mcp_shared")
        listOf(
            { McpCatalogKey(scope, personal.id) },
            { McpCatalogKey(ConfigurationScope.Personal, resource) },
            { McpCatalogKey(scope, ConfigurationReference.Enterprise(EnterpriseAuthority("platform:example", "deployment"), "mcp_shared")) },
        ).forEach { create ->
            try { create(); fail("Catalog accepted inconsistent scope") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `new managed generation advances even for identical tools and stale work cannot replace or protect it`() = runBlocking {
        withDisk { store, disk ->
            val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "user")
            val candidate = enterpriseCandidate(scope)
            val first = store.commitCandidate(candidate) as McpCatalogCommitResult.Committed
            val newer = candidate.copy(managed = McpManagedCatalog(2))
            val next = store.commitCandidate(newer) as McpCatalogCommitResult.Committed
            assertEquals(first.snapshot.catalogDigest, next.snapshot.catalogDigest)
            assertEquals(2L, next.snapshot.revision)
            val bytes = disk.data.first()[documentKey]
            assertEquals(McpCatalogCommitResult.RejectedGeneration(2), store.commitCandidate(candidate.copy(tools = listOf(tool("late")))))
            assertEquals(McpCatalogCommitResult.RejectedGeneration(2), store.commitCandidate(candidate.copy(tools = emptyList())))
            assertEquals(bytes, disk.data.first()[documentKey])
            assertEquals(next.snapshot, store.catalogs.value[candidate.key])
            // Rejected old work cannot take over the current commit's compensation receipt.
            store.rollbackCommitted(next.snapshot, next.previous, next.headToken)
            assertEquals(first.snapshot, store.catalogs.value[candidate.key])
        }
    }

    @Test fun `same managed generation may rotate private binding identity without changing the surface`() = runBlocking {
        withDisk { store, _ ->
            val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "user")
            val first = store.commitCandidate(enterpriseCandidate(scope)) as McpCatalogCommitResult.Committed
            val rotated = store.commitCandidate(enterpriseCandidate(scope).copy(definitionDigest = "rotated-binding")) as McpCatalogCommitResult.Committed
            assertEquals(first.snapshot.managed, rotated.snapshot.managed)
            assertEquals(first.snapshot.catalogDigest, rotated.snapshot.catalogDigest)
            store.rollbackCommitted(first.snapshot, first.previous, first.headToken)
            assertEquals(rotated.snapshot, store.catalogs.value[rotated.snapshot.key])
        }
    }

    @Test fun `managed metadata cannot be omitted or attached to a personal catalog`() {
        val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "user")
        assertThrows(IllegalArgumentException::class.java) { enterpriseCandidate(scope).copy(managed = null) }
        assertThrows(IllegalArgumentException::class.java) { personalCandidate("personal").copy(managed = McpManagedCatalog(1)) }
        val serialized = JsonInstant.encodeToJsonElement(personalCandidate("personal").initialSnapshot()) as JsonObject
        assertFalse("managed" in serialized)
        assertThrows(IllegalArgumentException::class.java) { McpManagedCatalog(0) }
    }

    @Test fun `Gateway metadata cannot be omitted or moved to another resource type on commit or reopen`() = runBlocking {
        val packet = net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage()
        val resource = packet.configuration.gateways.single()
        val scope = packet.identity.scope
        val candidate = McpCatalogCandidate(scope, ConfigurationReference.Enterprise(scope.authority, resource.id),
            "definition", net.weero.measix.pilot.data.enterprise.LocalEnterpriseMcpSurface.gatewayTools,
            McpManagedCatalog(packet.configuration.generation, resource.surface))
        withDisk { store, _ ->
            val saved = store.commitCandidate(candidate) as McpCatalogCommitResult.Committed
            assertEquals(resource.surface, saved.snapshot.managed!!.gatewaySurface)
            assertEquals(resource.surfaceHash.removePrefix("sha256:"), saved.snapshot.catalogDigest)
            val reordered = candidate.copy(tools = candidate.tools.map {
                McpCatalogTool(JsonObject(it.definition.entries.reversed().associate { it.toPair() }))
            })
            assertEquals(McpCatalogCommitResult.Unchanged(saved.snapshot), store.commitCandidate(reordered))
            assertThrows(IllegalArgumentException::class.java) { candidate.copy(managed = McpManagedCatalog(1)) }
            assertThrows(IllegalArgumentException::class.java) {
                candidate.copy(serverId = ConfigurationReference.Enterprise(scope.authority, "mcp_wrong"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                candidate.copy(serverId = ConfigurationReference.Enterprise(scope.authority, "mdl_wrong"))
            }
            try { store.commitCandidate(candidate.copy(tools = candidate.tools.reversed())); fail("Gateway pair order changed") }
            catch (_: IllegalArgumentException) { }
            assertEquals(saved.snapshot, store.catalogs.value[candidate.key])
        }
        val snapshot = candidate.initialSnapshot()
        val row = JsonInstant.encodeToJsonElement(snapshot) as JsonObject
        val metadata = row.getValue("managed") as JsonObject
        val invalidRows = listOf(JsonObject(row + ("managed" to JsonObject(metadata - "gatewaySurface"))),
            JsonObject(row - "managed"))
        for (invalid in invalidRows) {
            val document = JsonInstant.parseToJsonElement(encodeMcpCatalogDocument(listOf(snapshot))) as JsonObject
            val encoded = JsonObject(document + ("catalogs" to JsonArray(listOf(invalid)))).toString()
            withDisk(mutablePreferencesOf(documentKey to encoded)) { store, disk ->
                try { store.awaitReady(); fail("Incomplete Gateway verification became ready") }
                catch (_: IllegalArgumentException) { }
                assertTrue(store.catalogs.value.isEmpty())
                assertEquals(encoded, disk.data.first()[documentKey])
            }
        }
    }

    private fun personalCandidate(name: String) = McpCatalogCandidate(
        ConfigurationScope.Personal, personal.id, personal.mcpDefinitionDigest(), listOf(tool(name)),
    )

    private fun enterpriseCandidate(scope: ConfigurationScope.Enterprise) = McpCatalogCandidate(
        scope, ConfigurationReference.Enterprise(scope.authority, "mcp_shared"), "definition", listOf(tool("search")), McpManagedCatalog(1),
    )

    private fun tool(name: String) = McpCatalogTool(name, inputSchema = buildJsonObject { put("type", "object") })

    private fun releasedArray(snapshot: McpCatalogSnapshot): String = JsonArray(listOf(
        JsonObject((JsonInstant.encodeToJsonElement(snapshot) as JsonObject) - "scope")
    )).toString()

    private suspend fun withDisk(
        initial: Preferences = emptyPreferences(),
        operation: suspend (McpCatalogStore, DataStore<Preferences>) -> Unit,
    ) {
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = AppScope(Dispatchers.Default)
        val disk = PreferenceDataStoreFactory.create(scope = diskScope, produceFile = { File(temporary.newFolder(), "catalog.preferences_pb") })
        disk.updateData { initial }
        val settings = mockk<SettingsStore>()
        coEvery { settings.pendingMcpCatalogMigration() } returns null
        try { operation(McpCatalogStore(disk, owner, settings), disk) }
        finally {
            owner.coroutineContext[Job]!!.cancelAndJoin()
            diskScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
