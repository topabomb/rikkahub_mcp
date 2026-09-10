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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.utils.JsonInstant
import me.rerere.common.configuration.ConfigurationReference

private val Context.mcpCatalogDataStore by preferencesDataStore(name = "mcp_catalog")

/** The complete remote Tool object is the sole catalog fact; consumers read projections. */
@Serializable(with = McpCatalogToolSerializer::class)
data class McpCatalogTool(val definition: JsonObject) {
    constructor(name: String, description: String? = null, inputSchema: JsonObject) : this(
        buildJsonObject {
            put("name", name)
            put("description", description?.let(::JsonPrimitive) ?: JsonNull)
            put("inputSchema", inputSchema)
        }
    )

    init {
        require((definition["name"] as? JsonPrimitive)?.isString == true) { "MCP tool name must be a string" }
        require(definition["inputSchema"] is JsonObject) { "MCP tool inputSchema must be an object" }
        val description = definition["description"]
        require(description == null || description == JsonNull ||
            (description is JsonPrimitive && description.isString)) { "MCP tool description must be a string" }
    }

    val name: String get() = (definition.getValue("name") as JsonPrimitive).content
    val description: String? get() = (definition["description"] as? JsonPrimitive)
        ?.takeUnless { it == JsonNull }?.content
    val inputSchema: JsonObject get() = definition.getValue("inputSchema") as JsonObject
}

/** Keeps the released personal catalog representation and digest unchanged. */
object McpCatalogToolSerializer : KSerializer<McpCatalogTool> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: McpCatalogTool) =
        encoder.encodeSerializableValue(JsonObject.serializer(), value.definition)
    override fun deserialize(decoder: Decoder): McpCatalogTool =
        McpCatalogTool(decoder.decodeSerializableValue(JsonObject.serializer()))
}

/** Stable owner of a remote catalog. Session and connection epochs belong to the runtime. */
@Serializable
data class McpCatalogKey(val scope: ConfigurationScope, val serverId: ConfigurationReference) {
    init {
        when (serverId) {
            is ConfigurationReference.User -> require(scope == ConfigurationScope.Personal) { "Personal MCP catalog requires its personal owner" }
            is ConfigurationReference.Enterprise -> require(scope is ConfigurationScope.Enterprise && scope.authority == serverId.authority) {
                "Enterprise MCP catalog requires its full matching principal"
            }
        }
    }
}

@Serializable
data class McpCatalogSnapshot(
    val scope: ConfigurationScope,
    val serverId: ConfigurationReference,
    val revision: Long,
    val definitionDigest: String,
    val catalogDigest: String,
    val tools: List<McpCatalogTool>,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val managed: McpManagedCatalog? = null,
) {
    val key: McpCatalogKey get() = McpCatalogKey(scope, serverId)
    init {
        key.validateManaged(managed)
    }
}

data class McpCatalogCandidate(
    val scope: ConfigurationScope,
    val serverId: ConfigurationReference,
    val definitionDigest: String,
    val tools: List<McpCatalogTool>,
    val managed: McpManagedCatalog? = null,
) {
    val key: McpCatalogKey get() = McpCatalogKey(scope, serverId)
    init {
        key.validateManaged(managed)
    }
}

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
    val namespace: String = serverName,
    val interactionId: String? = null,
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
    data class RejectedGeneration(val currentGeneration: Long) : McpCatalogCommitResult
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
    private val headTokens = mutableMapOf<McpCatalogKey, Long>()
    private var readFailure: Exception? = null

    private val _catalogs = MutableStateFlow<Map<McpCatalogKey, McpCatalogSnapshot>>(emptyMap())
    val catalogs: StateFlow<Map<McpCatalogKey, McpCatalogSnapshot>> = _catalogs.asStateFlow()

    private val initialization = scope.async {
        migrateLegacySettingsCatalogs()
        commitMutex.withLock {
            try {
                migrateStoredPersonalCatalogs()
                _catalogs.value = readCurrentCatalogs()
            }
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
            migrateStoredPersonalCatalogs()
            val current = readCurrentCatalogs()
            val updated = pending.payload.candidates.fold(current) { catalogs, legacy ->
                val candidate = legacy.toCatalogCandidate()
                if (candidate.key in catalogs) catalogs
                else catalogs + (candidate.key to candidate.initialSnapshot())
            }
            if (updated != current) writeCatalogs(updated)
            _catalogs.value = updated
        }
        settingsStore.completeMcpCatalogMigration(pending.encoded)
    }

    suspend fun commitCandidate(candidate: McpCatalogCandidate): McpCatalogCommitResult =
        commit {
            candidate.managed?.gatewaySurface?.validate(candidate.tools)
            val normalizedTools = candidate.tools.sortedBy { it.name }
            val current = readCurrentCatalogs()
            _catalogs.value = current
            val previous = current[candidate.key]
            val previousGeneration = previous?.managed?.generation
            if (previousGeneration != null && requireNotNull(candidate.managed).generation < previousGeneration) {
                return@commit McpCatalogCommitResult.RejectedGeneration(previousGeneration)
            }
            if (previousGeneration != null && candidate.managed?.generation == previousGeneration) {
                require(previous.managed == candidate.managed) {
                    "Managed MCP surface changed within the same generation"
                }
            }
            val headToken = (headTokens[candidate.key] ?: 0L) + 1L
            if (normalizedTools.isEmpty()) {
                headTokens[candidate.key] = headToken
                return@commit McpCatalogCommitResult.RejectedEmpty(
                    current[candidate.key]?.takeIf { it.definitionDigest == candidate.definitionDigest }
                )
            }
            require(normalizedTools.none { it.name.isBlank() }) { "MCP catalog contains a blank tool name" }
            require(normalizedTools.map { it.name }.toSet().size == normalizedTools.size) {
                "MCP catalog contains duplicate tool names"
            }

            val catalogDigest = mcpCatalogDigest(normalizedTools, candidate.managed)
            if (
                previous != null &&
                previous.definitionDigest == candidate.definitionDigest &&
                previous.catalogDigest == catalogDigest &&
                previous.managed == candidate.managed
            ) {
                headTokens[candidate.key] = headToken
                return@commit McpCatalogCommitResult.Unchanged(previous)
            }

            val next = McpCatalogSnapshot(
                scope = candidate.scope,
                serverId = candidate.serverId,
                revision = (previous?.revision ?: 0L) + 1L,
                definitionDigest = candidate.definitionDigest,
                catalogDigest = catalogDigest,
                tools = normalizedTools,
                managed = candidate.managed,
            )
            val updated = current + (candidate.key to next)
            writeCatalogs(updated)
            _catalogs.value = updated
            headTokens[candidate.key] = headToken
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
            current[committed.key] != committed ||
            headTokens[committed.key] != expectedHeadToken
        ) {
            return@commit
        }
        val updated = if (previous == null) {
            current - committed.key
        } else {
            current + (committed.key to previous)
        }
        writeCatalogs(updated)
        _catalogs.value = updated
        headTokens[committed.key] = expectedHeadToken + 1L
    }

    /** Removes the catalog only when the server definition has been explicitly removed. */
    suspend fun remove(key: McpCatalogKey) = commit {
        val current = readCurrentCatalogs()
        if (key !in current) return@commit
        val updated = current - key
        writeCatalogs(updated)
        _catalogs.value = updated
        headTokens[key] = (headTokens[key] ?: 0L) + 1L
    }

    /** Domain removal reads the durable catalog under the existing writer, including hidden entries. */
    internal suspend fun clearEnterpriseScope(scope: ConfigurationScope.Enterprise) = commit {
        val current = readCurrentCatalogs()
        val removed = current.keys.filter { it.scope == scope }
        if (removed.isEmpty()) return@commit
        val updated = current - removed.toSet()
        writeCatalogs(updated)
        _catalogs.value = updated
        removed.forEach { key -> headTokens[key] = (headTokens[key] ?: 0L) + 1L }
    }

    suspend fun snapshotForBackup(definitions: List<McpServerConfig>): List<McpCatalogSnapshot> {
        // A v4 backup cannot race the one-shot extraction and permanently export an empty catalog.
        initialization.await()
        return commitMutex.withLock {
            readFailure?.let { throw it }
            val expected = definitions.associate { it.id to it.mcpDefinitionDigest() }
            readCurrentCatalogs().values
                .filter { snapshot -> snapshot.scope == ConfigurationScope.Personal && expected[snapshot.serverId] == snapshot.definitionDigest }
                .sortedBy { it.serverId.toString() }
        }
    }

    suspend fun restorePersonalCatalogs(
        snapshots: List<McpCatalogSnapshot>,
        definitions: List<McpServerConfig>,
    ) {
        // A backup replacement must be ordered after any already-leased one-shot Settings
        // migration; otherwise that older payload could append an orphan after restore.
        commit(replaceUnreadable = true) {
            val expected = definitions.associate { it.id to it.mcpDefinitionDigest() }
            val restored = snapshots.map { snapshot ->
                require(snapshot.scope == ConfigurationScope.Personal) { "Personal backup contains an enterprise MCP catalog" }
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
            val preferences = dataStore.data.first()
            // Once the scoped document exists, its enterprise facts must be read successfully.
            // Only the released personal-only key may be replaced without decoding its damaged data.
            val retained = if (preferences[CATALOG_DOCUMENT] == null) emptyMap() else decodeCatalogs(preferences)
                .filterKeys { it.scope != ConfigurationScope.Personal }
            val updated = retained + restored.associateBy { it.key }
            writeCatalogs(updated)
            _catalogs.value = updated
            (headTokens.keys + updated.keys).filter { it.scope == ConfigurationScope.Personal }.forEach { key ->
                headTokens[key] = (headTokens[key] ?: 0L) + 1L
            }
        }
    }

    private companion object {
        val PERSONAL_CATALOGS = stringPreferencesKey("catalogs")
        val CATALOG_DOCUMENT = stringPreferencesKey("catalog_document")
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

    private suspend fun readCurrentCatalogs(): Map<McpCatalogKey, McpCatalogSnapshot> = dataStore.data
        .first()
        .let(::decodeCatalogs)

    private suspend fun writeCatalogs(catalogs: Map<McpCatalogKey, McpCatalogSnapshot>) {
        dataStore.edit { preferences ->
            preferences[CATALOG_DOCUMENT] = encodeMcpCatalogDocument(catalogs.values.toList())
            preferences.remove(PERSONAL_CATALOGS)
        }
    }

    /** One transaction upgrades the released personal-only key and removes its old representation. */
    private suspend fun migrateStoredPersonalCatalogs() {
        val before = dataStore.data.first()
        if (before[CATALOG_DOCUMENT] != null || before[PERSONAL_CATALOGS] == null) return
        dataStore.edit { preferences ->
            if (preferences[CATALOG_DOCUMENT] == null) {
                val encoded = preferences[PERSONAL_CATALOGS] ?: return@edit
                preferences[CATALOG_DOCUMENT] = encodeMcpCatalogDocument(decodePersonalMcpCatalogImport(encoded))
                preferences.remove(PERSONAL_CATALOGS)
            }
        }
    }

    private fun decodeCatalogs(preferences: Preferences): Map<McpCatalogKey, McpCatalogSnapshot> {
        val encoded = preferences[CATALOG_DOCUMENT] ?: return emptyMap()
        return decodeMcpCatalogDocument(encoded).associateBy { it.key }
    }

}

internal fun McpCatalogCandidate.initialSnapshot(): McpCatalogSnapshot {
    managed?.gatewaySurface?.validate(tools)
    val normalizedTools = tools.sortedBy { it.name }
    require(normalizedTools.isNotEmpty()) { "Legacy MCP catalog is empty" }
    require(normalizedTools.none { it.name.isBlank() }) { "Legacy MCP catalog contains a blank tool name" }
    require(normalizedTools.map { it.name }.toSet().size == normalizedTools.size) {
        "Legacy MCP catalog contains duplicate tool names"
    }
    return McpCatalogSnapshot(
        scope = scope,
        serverId = serverId,
        revision = 1L,
        definitionDigest = definitionDigest,
        catalogDigest = mcpCatalogDigest(normalizedTools, managed),
        tools = normalizedTools,
        managed = managed,
    )
}

internal fun McpCatalogSnapshot.validated(): McpCatalogSnapshot? {
    try { managed?.gatewaySurface?.validate(tools) }
    catch (_: IllegalArgumentException) { return null }
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
    val expectedDigest = mcpCatalogDigest(normalizedTools, managed)
    return takeIf { catalogDigest == expectedDigest }?.copy(tools = normalizedTools)
}

/** Callers validate the managed surface before deriving its catalog identity. */
private fun mcpCatalogDigest(tools: List<McpCatalogTool>, managed: McpManagedCatalog?): String =
    managed?.gatewaySurface?.hash?.removePrefix("sha256:") ?: sha256(JsonInstant.encodeToString(tools))

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }


@Serializable
private data class McpCatalogDocument(val formatVersion: Int, val catalogs: List<McpCatalogSnapshot>)

internal fun encodeMcpCatalogDocument(catalogs: List<McpCatalogSnapshot>): String =
    JsonInstant.encodeToString(McpCatalogDocument(1, catalogs.sortedWith(
        compareBy<McpCatalogSnapshot> { JsonInstant.encodeToString(it.scope) }.thenBy { it.serverId.toString() }
    )))

private fun decodeMcpCatalogDocument(encoded: String): List<McpCatalogSnapshot> {
    val document = JsonInstant.decodeFromString<McpCatalogDocument>(encoded)
    require(document.formatVersion == 1) { "Unsupported MCP catalog document version" }
    return validateMcpCatalogSnapshots(document.catalogs)
}

private fun validateMcpCatalogSnapshots(snapshots: List<McpCatalogSnapshot>): List<McpCatalogSnapshot> {
    val validated = snapshots.map { requireNotNull(it.validated()) { "Stored MCP catalog is invalid" } }
    require(validated.map { it.key }.distinct().size == validated.size) { "Stored MCP catalogs contain duplicate owners" }
    return validated
}

/** Released personal arrays are import input only; new documents never fall back to them. */
internal fun decodePersonalMcpCatalogImport(encoded: String): List<McpCatalogSnapshot> {
    val root = JsonInstant.parseToJsonElement(encoded)
    val snapshots = if (root is JsonArray) {
        validateMcpCatalogSnapshots(root.map { element ->
            val record = element as? JsonObject ?: error("Invalid personal MCP catalog")
            require("scope" !in record) { "Legacy MCP catalog cannot declare a principal" }
            val scoped = JsonObject(record + (
                "scope" to JsonInstant.encodeToJsonElement<ConfigurationScope>(ConfigurationScope.Personal)
            ))
            JsonInstant.decodeFromJsonElement<McpCatalogSnapshot>(scoped)
        })
    } else decodeMcpCatalogDocument(encoded)
    require(snapshots.all { it.scope == ConfigurationScope.Personal }) { "Personal backup contains enterprise MCP catalogs" }
    return snapshots
}
