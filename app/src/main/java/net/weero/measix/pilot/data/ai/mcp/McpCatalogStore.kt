package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

private val Context.mcpCatalogDataStore by preferencesDataStore(name = "mcp_catalog")
private const val TAG = "McpCatalogStore"

@Serializable
data class McpCatalogTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
)

@Serializable
data class McpCatalogSnapshot(
    val serverId: Uuid,
    val revision: Long,
    val definitionDigest: String,
    val catalogDigest: String,
    val tools: List<McpCatalogTool>,
)

@Serializable
data class McpCatalogCandidate(
    val serverId: Uuid,
    val definitionDigest: String,
    val tools: List<McpCatalogTool>,
)

data class McpAvailableTool(
    val serverId: Uuid,
    val serverName: String,
    val catalogRevision: Long,
    val definitionDigest: String,
    val catalogDigest: String,
    val name: String,
    val description: String?,
    val inputSchema: JsonObject,
    val needsApproval: Boolean,
)

data class TurnMcpCapabilitySnapshot(
    val tools: List<McpAvailableTool>,
    val serverOutcomes: List<McpServerCapabilityOutcome> = emptyList(),
) {
    companion object {
        val EMPTY = TurnMcpCapabilitySnapshot(emptyList())
    }
}

enum class McpServerCapabilityState {
    READY,
    TIMEOUT,
    UNAVAILABLE,
    AUTHORIZATION_REQUIRED,
    EMPTY_CATALOG,
}

data class McpServerCapabilityOutcome(
    val serverId: Uuid,
    val serverName: String,
    val state: McpServerCapabilityState,
    val toolCount: Int,
)

sealed interface McpCatalogCommitResult {
    data class Committed(
        val snapshot: McpCatalogSnapshot,
        val previous: McpCatalogSnapshot?,
        val headToken: Long,
    ) : McpCatalogCommitResult
    data class Unchanged(val snapshot: McpCatalogSnapshot) : McpCatalogCommitResult
    data class RejectedEmpty(val lastKnownGood: McpCatalogSnapshot?) : McpCatalogCommitResult
}

/**
 * Durable owner of validated remote MCP tool catalogs.
 *
 * Settings owns server definitions and user policy. This store owns only a complete non-empty
 * last-known-good remote catalog; a failed, partial or empty discovery never replaces it.
 */
class McpCatalogStore(
    context: Context,
    scope: AppScope,
    private val settingsStore: SettingsStore,
) {
    private val dataStore = context.mcpCatalogDataStore
    private val commitMutex = Mutex()
    private val headTokens = mutableMapOf<Uuid, Long>()
    private val legacyMigrationComplete = CompletableDeferred<Unit>()

    private val _catalogs = MutableStateFlow<Map<Uuid, McpCatalogSnapshot>>(emptyMap())
    val catalogs: StateFlow<Map<Uuid, McpCatalogSnapshot>> = _catalogs.asStateFlow()

    init {
        scope.launch {
            try {
                migrateLegacySettingsCatalogs()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to migrate legacy MCP catalogs", error)
            } finally {
                legacyMigrationComplete.complete(Unit)
            }
            dataStore.data
                .catch { error ->
                    if (error is IOException) emit(emptyPreferences()) else throw error
                }
                .map(::decodeCatalogs)
                .collect { _catalogs.value = it }
        }
    }

    private suspend fun migrateLegacySettingsCatalogs() {
        val pending = settingsStore.pendingMcpCatalogMigration() ?: return
        commitMutex.withLock {
            val current = readCurrentCatalogs()
            val updated = pending.payload.candidates.fold(current) { catalogs, candidate ->
                if (candidate.serverId in catalogs) catalogs
                else catalogs + (candidate.serverId to candidate.initialSnapshot())
            }
            if (updated != current) writeCatalogs(updated)
            _catalogs.value = updated
        }
        settingsStore.completeMcpCatalogMigration(pending.encoded)
    }

    suspend fun commitCandidate(candidate: McpCatalogCandidate): McpCatalogCommitResult =
        commitMutex.withLock {
            val normalizedTools = candidate.tools.sortedBy { it.name }
            val current = readCurrentCatalogs()
            _catalogs.value = current
            val headToken = (headTokens[candidate.serverId] ?: 0L) + 1L
            headTokens[candidate.serverId] = headToken
            if (normalizedTools.isEmpty()) {
                return@withLock McpCatalogCommitResult.RejectedEmpty(
                    current[candidate.serverId]?.takeIf { it.definitionDigest == candidate.definitionDigest }
                )
            }
            require(normalizedTools.none { it.name.isBlank() }) { "MCP catalog contains a blank tool name" }
            require(normalizedTools.map { it.name }.toSet().size == normalizedTools.size) {
                "MCP catalog contains duplicate tool names"
            }

            val catalogDigest = sha256(JsonInstant.encodeToString(normalizedTools))
            val previous = current[candidate.serverId]
            if (
                previous != null &&
                previous.definitionDigest == candidate.definitionDigest &&
                previous.catalogDigest == catalogDigest
            ) {
                return@withLock McpCatalogCommitResult.Unchanged(previous)
            }

            val next = McpCatalogSnapshot(
                serverId = candidate.serverId,
                revision = (previous?.revision ?: 0L) + 1L,
                definitionDigest = candidate.definitionDigest,
                catalogDigest = catalogDigest,
                tools = normalizedTools,
            )
            val updated = current + (candidate.serverId to next)
            writeCatalogs(updated)
            _catalogs.value = updated
            McpCatalogCommitResult.Committed(next, previous, headToken)
        }

    /**
     * 仅撤销仍是当前 durable head 的候选；若已有更新 revision，绝不覆盖新事实。
     * 用于 ServerRuntime 在 commit 后发现 connection lease 已失效时精确补偿。
     */
    suspend fun rollbackCommitted(
        committed: McpCatalogSnapshot,
        previous: McpCatalogSnapshot?,
        expectedHeadToken: Long,
    ) = commitMutex.withLock {
        val current = readCurrentCatalogs()
        if (
            current[committed.serverId] != committed ||
            headTokens[committed.serverId] != expectedHeadToken
        ) {
            return@withLock
        }
        val updated = if (previous == null) {
            current - committed.serverId
        } else {
            current + (committed.serverId to previous)
        }
        writeCatalogs(updated)
        _catalogs.value = updated
        headTokens[committed.serverId] = expectedHeadToken + 1L
    }

    /** Removes the catalog only when the server definition has been explicitly removed. */
    suspend fun remove(serverId: Uuid) = commitMutex.withLock {
        val current = readCurrentCatalogs()
        if (serverId !in current) return@withLock
        val updated = current - serverId
        writeCatalogs(updated)
        _catalogs.value = updated
        headTokens[serverId] = (headTokens[serverId] ?: 0L) + 1L
    }

    suspend fun snapshotForBackup(definitions: List<McpServerConfig>): List<McpCatalogSnapshot> {
        // A v4 backup cannot race the one-shot extraction and permanently export an empty catalog.
        legacyMigrationComplete.await()
        return commitMutex.withLock {
            val expected = definitions.associate { it.id to it.mcpDefinitionDigest() }
            readCurrentCatalogs().values
                .filter { snapshot -> expected[snapshot.serverId] == snapshot.definitionDigest }
                .sortedBy { it.serverId.toString() }
        }
    }

    suspend fun restoreCatalogs(
        snapshots: List<McpCatalogSnapshot>,
        definitions: List<McpServerConfig>,
    ) {
        // A backup replacement must be ordered after any already-leased one-shot Settings
        // migration; otherwise that older payload could append an orphan after restore.
        legacyMigrationComplete.await()
        commitMutex.withLock {
            val expected = definitions.associate { it.id to it.mcpDefinitionDigest() }
            val restored = snapshots.map { snapshot ->
                requireNotNull(snapshot.validated()) { "Backup contains an invalid MCP catalog" }
                    .also { valid ->
                        require(expected[valid.serverId] == valid.definitionDigest) {
                            "Backup MCP catalog does not match its server definition"
                        }
                    }
            }
            require(restored.map { it.serverId }.toSet().size == restored.size) {
                "Backup contains duplicate MCP catalogs"
            }
            val updated = restored.associateBy { it.serverId }
            writeCatalogs(updated)
            _catalogs.value = updated
            (headTokens.keys + updated.keys).forEach { serverId ->
                headTokens[serverId] = (headTokens[serverId] ?: 0L) + 1L
            }
        }
    }

    private companion object {
        val CATALOGS = stringPreferencesKey("catalogs")
    }

    private suspend fun readCurrentCatalogs(): Map<Uuid, McpCatalogSnapshot> = dataStore.data
        .first()
        .let(::decodeCatalogs)

    private suspend fun writeCatalogs(catalogs: Map<Uuid, McpCatalogSnapshot>) {
        dataStore.edit { preferences ->
            preferences[CATALOGS] = JsonInstant.encodeToString(
                catalogs.values.sortedBy { it.serverId.toString() }
            )
        }
    }

    private fun decodeCatalogs(
        preferences: androidx.datastore.preferences.core.Preferences,
    ): Map<Uuid, McpCatalogSnapshot> = preferences[CATALOGS]
        ?.let { encoded ->
            runCatching { JsonInstant.decodeFromString<List<McpCatalogSnapshot>>(encoded) }
                .getOrElse { emptyList() }
        }
        .orEmpty()
        .mapNotNull(McpCatalogSnapshot::validated)
        .groupBy { it.serverId }
        .filterValues { snapshots -> snapshots.size == 1 }
        .mapValues { (_, snapshots) -> snapshots.single() }
}

internal fun McpCatalogCandidate.initialSnapshot(): McpCatalogSnapshot {
    val normalizedTools = tools.sortedBy { it.name }
    require(normalizedTools.isNotEmpty()) { "Legacy MCP catalog is empty" }
    require(normalizedTools.none { it.name.isBlank() }) { "Legacy MCP catalog contains a blank tool name" }
    require(normalizedTools.map { it.name }.toSet().size == normalizedTools.size) {
        "Legacy MCP catalog contains duplicate tool names"
    }
    return McpCatalogSnapshot(
        serverId = serverId,
        revision = 1L,
        definitionDigest = definitionDigest,
        catalogDigest = sha256(JsonInstant.encodeToString(normalizedTools)),
        tools = normalizedTools,
    )
}

internal fun McpCatalogSnapshot.validated(): McpCatalogSnapshot? {
    val normalizedTools = tools.sortedBy { it.name }
    if (
        revision <= 0L ||
        definitionDigest.isBlank() ||
        normalizedTools.isEmpty() ||
        normalizedTools.any { it.name.isBlank() } ||
        normalizedTools.map { it.name }.toSet().size != normalizedTools.size
    ) {
        return null
    }
    val expectedDigest = sha256(JsonInstant.encodeToString(normalizedTools))
    return takeIf { catalogDigest == expectedDigest }?.copy(tools = normalizedTools)
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
