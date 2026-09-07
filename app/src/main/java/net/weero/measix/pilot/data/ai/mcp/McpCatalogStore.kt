package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.utils.JsonInstant
import me.rerere.common.configuration.ConfigurationReference

private val Context.mcpCatalogDataStore by preferencesDataStore(name = "mcp_catalog")

@Serializable
data class McpCatalogTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
)

@Serializable
data class McpCatalogSnapshot(
    val serverId: ConfigurationReference,
    val revision: Long,
    val definitionDigest: String,
    val catalogDigest: String,
    val tools: List<McpCatalogTool>,
)

@Serializable
data class McpCatalogCandidate(
    val serverId: ConfigurationReference,
    val definitionDigest: String,
    val tools: List<McpCatalogTool>,
)

data class McpAvailableTool(
    val serverId: ConfigurationReference,
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
    val serverId: ConfigurationReference,
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
class McpCatalogStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
    private val settingsStore: SettingsStore,
) {
    constructor(context: Context, scope: AppScope, settingsStore: SettingsStore) :
        this(context.mcpCatalogDataStore, scope, settingsStore)

    private val commitMutex = Mutex()
    private val headTokens = mutableMapOf<ConfigurationReference, Long>()
    private var readFailure: Exception? = null

    private val _catalogs = MutableStateFlow<Map<ConfigurationReference, McpCatalogSnapshot>>(emptyMap())
    val catalogs: StateFlow<Map<ConfigurationReference, McpCatalogSnapshot>> = _catalogs.asStateFlow()

    private val initialization = scope.async {
        migrateLegacySettingsCatalogs()
        commitMutex.withLock {
            try { _catalogs.value = readCurrentCatalogs() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { readFailure = error }
        }
    }

    internal suspend fun awaitReady() {
        initialization.await()
        commitMutex.withLock { readFailure?.let { throw it } }
    }

    private suspend fun migrateLegacySettingsCatalogs() {
        val pending = settingsStore.pendingMcpCatalogMigration() ?: return
        commitLocked {
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
        commit {
            val normalizedTools = candidate.tools.sortedBy { it.name }
            val current = readCurrentCatalogs()
            _catalogs.value = current
            val headToken = (headTokens[candidate.serverId] ?: 0L) + 1L
            if (normalizedTools.isEmpty()) {
                headTokens[candidate.serverId] = headToken
                return@commit McpCatalogCommitResult.RejectedEmpty(
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
                headTokens[candidate.serverId] = headToken
                return@commit McpCatalogCommitResult.Unchanged(previous)
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
            headTokens[candidate.serverId] = headToken
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
    ) = commit {
        val current = readCurrentCatalogs()
        if (
            current[committed.serverId] != committed ||
            headTokens[committed.serverId] != expectedHeadToken
        ) {
            return@commit
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
    suspend fun remove(serverId: ConfigurationReference) = commit {
        val current = readCurrentCatalogs()
        if (serverId !in current) return@commit
        val updated = current - serverId
        writeCatalogs(updated)
        _catalogs.value = updated
        headTokens[serverId] = (headTokens[serverId] ?: 0L) + 1L
    }

    suspend fun snapshotForBackup(definitions: List<McpServerConfig>): List<McpCatalogSnapshot> {
        // A v4 backup cannot race the one-shot extraction and permanently export an empty catalog.
        initialization.await()
        return commitMutex.withLock {
            readFailure?.let { throw it }
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
        commit(replaceUnreadable = true) {
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

    private suspend fun <T> commit(replaceUnreadable: Boolean = false, operation: suspend () -> T): T {
        initialization.await()
        return commitLocked {
            if (!replaceUnreadable) readFailure?.let { throw it }
            operation().also { if (replaceUnreadable) readFailure = null }
        }
    }

    /** The accepted commit owns disk acknowledgement, projection and head-token publication together. */
    private suspend fun <T> commitLocked(operation: suspend () -> T): T = commitMutex.withLock {
        currentCoroutineContext().ensureActive()
        val result = withContext(NonCancellable) { operation() }
        currentCoroutineContext().ensureActive()
        result
    }

    private suspend fun readCurrentCatalogs(): Map<ConfigurationReference, McpCatalogSnapshot> = dataStore.data
        .first()
        .let(::decodeCatalogs)

    private suspend fun writeCatalogs(catalogs: Map<ConfigurationReference, McpCatalogSnapshot>) {
        dataStore.edit { preferences ->
            preferences[CATALOGS] = JsonInstant.encodeToString(
                catalogs.values.sortedBy { it.serverId.toString() }
            )
        }
    }

    private fun decodeCatalogs(preferences: Preferences): Map<ConfigurationReference, McpCatalogSnapshot> {
        val encoded = preferences[CATALOGS] ?: return emptyMap()
        val snapshots = JsonInstant.decodeFromString<List<McpCatalogSnapshot>>(encoded).map {
            requireNotNull(it.validated()) { "Stored MCP catalog is invalid" }
        }
        require(snapshots.map { it.serverId }.distinct().size == snapshots.size) { "Stored MCP catalogs contain duplicate servers" }
        return snapshots.associateBy { it.serverId }
    }
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
