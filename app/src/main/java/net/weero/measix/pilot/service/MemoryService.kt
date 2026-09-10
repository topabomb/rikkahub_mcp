package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.data.model.MemoryAddress
import net.weero.measix.pilot.data.model.memoryAddress
import net.weero.measix.pilot.data.repository.MemoryRepository

data class MemoryAccess internal constructor(
    internal val realm: RealmAccess,
    val address: MemoryAddress,
    internal val assistantId: ConfigurationReference,
    internal val requireEnabled: Boolean,
)

/** The row retains its original namespace and session while an editor is open. */
data class MemoryRecord(val access: MemoryAccess, val id: Int, val content: String)

data class MemoryListResult(
    val assistantId: String,
    val assistantName: String,
    val delegatedMemoryScope: String,
    val memories: List<MemoryItem>,
)

data class MemoryItem(val id: Int, val content: String)

class MemoryToolRecord internal constructor(
    internal val realm: RealmAccess,
    internal val conversationId: Uuid,
    internal val locator: ToolCallLocator,
    internal val address: MemoryAddress,
    internal val id: Int,
) {
    fun matches(conversationId: Uuid?, locator: ToolCallLocator?): Boolean =
        this.conversationId == conversationId && this.locator == locator
}

data class MemoryView(val access: MemoryAccess?, val records: List<MemoryRecord>, val unavailableReason: String? = null) {
    companion object { val Loading = MemoryView(null, emptyList()) }
}

/** Application/query port. Session and configuration owners authorize; MemoryRepository alone writes memory. */
class MemoryService internal constructor(
    private val repository: MemoryRepository,
    private val settings: SettingsStore,
    private val sessions: EnterpriseSessionController,
    private val recovery: ApplicationRecoveryGate,
    private val conversations: ConversationQueryService,
) {
    suspend fun inspect(realm: RealmAccess, callerId: ConfigurationReference, assistantId: ConfigurationReference): MemoryListResult {
        recovery.awaitReady()
        return sessions.withRealmAccess(realm) {
            settings.withResolvedConfiguration(realm.scope, sessions.state.value) { configuration ->
                val caller = configuration.assistants[callerId] ?: error("assistant_not_found")
                val target = configuration.assistants[assistantId] ?: error("assistant_not_found")
                check(configuration.access(ConfigurationCategory.ASSISTANT, callerId).canExecute &&
                    configuration.access(ConfigurationCategory.ASSISTANT, assistantId).canExecute &&
                    net.weero.measix.pilot.data.ai.tools.local.LocalToolOption.AssistantManagement in caller.localTools &&
                    callerId != assistantId && net.weero.measix.pilot.data.ai.subassistant.SubAssistantAccessPolicy.canAccess(caller, target)) {
                    "target_not_allowed"
                }
                val mode = when { !target.enableMemory -> "disabled"; target.useGlobalMemory -> "global"; else -> "local" }
                val rows = if (mode == "local") repository.read(target.memoryAddress(realm.scope)) else emptyList()
                MemoryListResult(target.id.toString(), target.name, mode, rows.map { MemoryItem(it.id, it.content) })
            }
        }
    }

    fun observeCurrent(assistantId: ConfigurationReference, enabledOnly: Boolean = false): Flow<MemoryView> = flow {
        recovery.awaitReady()
        val scope = (sessions.state.value as? net.weero.measix.pilot.data.enterprise.EnterpriseState.Available)
            ?.manifest?.selectedScope ?: ConfigurationScope.Personal
        emitAll(observe(scope, assistantId, enabledOnly))
    }

    suspend fun isAllowed(access: MemoryAccess): Boolean = try { authorized(access) { true } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }

    suspend fun captureExecution(realm: RealmAccess, assistant: Assistant): MemoryAccess? {
        if (!assistant.enableMemory) return null
        recovery.awaitReady()
        val access = MemoryAccess(realm, assistant.memoryAddress(realm.scope), assistant.id, requireEnabled = true)
        authorized(access) { }
        return access
    }

    suspend fun captureToolRecord(conversationId: Uuid, locator: ToolCallLocator): MemoryToolRecord? {
        recovery.awaitReady()
        val snapshot = conversations.aggregateSnapshot(conversationId) ?: return null
        val realm = sessions.captureRealmAccess(snapshot.header.scope)
        return sessions.withRealmAccess(realm) {
            val id = toolMemoryId(conversationId, locator, realm.scope) ?: return@withRealmAccess null
            val row = repository.findToolResult(realm.scope, id) ?: return@withRealmAccess null
            MemoryToolRecord(realm, conversationId, locator, row.first, id)
        }
    }

    suspend fun deleteToolRecord(record: MemoryToolRecord) {
        recovery.awaitReady()
        sessions.withRealmAccess(record.realm) {
            check(toolMemoryId(record.conversationId, record.locator, record.realm.scope) == record.id) { "memory_tool_result_changed" }
            repository.delete(record.address, record.id)
        }
    }

    private suspend fun toolMemoryId(conversationId: Uuid, locator: ToolCallLocator, scope: ConfigurationScope): Int? {
        val snapshot = conversations.aggregateSnapshot(conversationId) ?: return null
        if (snapshot.header.scope != scope) return null
        val message = snapshot.nodes.flatMap { it.messages }.singleOrNull { it.id == locator.assistantMessageId } ?: return null
        val tool = message.getTools().singleOrNull { it.stepId == locator.stepId && it.localCallId == locator.localCallId } ?: return null
        if (tool.toolName != "memory_tool" || tool.resultStatus != me.rerere.ai.ui.ToolResultStatus.COMPLETED) return null
        return try {
            val json = net.weero.measix.pilot.utils.JsonInstant
            val action = json.parseToJsonElement(tool.input).jsonObject["action"]?.jsonPrimitive?.contentOrNull
            if (action !in setOf("create", "edit")) null else {
                json.parseToJsonElement(tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                    .jsonObject["id"]?.jsonPrimitive?.intOrNull
            }
        } catch (_: IllegalArgumentException) { null }
    }

    fun observe(scope: ConfigurationScope, assistantId: ConfigurationReference, enabledOnly: Boolean = false): Flow<MemoryView> = flow {
        recovery.awaitReady()
        emitAll(observe(sessions.captureRealmAccess(scope), assistantId, enabledOnly))
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(MemoryView(null, emptyList(), "memory_access_unavailable"))
    }

    fun observe(realm: RealmAccess, assistantId: ConfigurationReference, enabledOnly: Boolean = false): Flow<MemoryView> = flow {
        recovery.awaitReady()
        val scope = realm.scope
        emitAll(sessions.observeRealmAccess(realm).flatMapLatest { allowed ->
            if (!allowed) flowOf(MemoryView(null, emptyList(), "memory_access_unavailable"))
            else settings.observeConfiguration(sessions.state, scope).flatMapLatest { configuration ->
                val assistant = configuration.assistants[assistantId]
                if (assistant == null || !configuration.access(ConfigurationCategory.ASSISTANT, assistantId).canSelect ||
                    (enabledOnly && !assistant.enableMemory)) {
                    flowOf(MemoryView(null, emptyList(), "memory_assistant_unavailable"))
                } else {
                    val access = MemoryAccess(realm, assistant.memoryAddress(scope), assistantId, enabledOnly)
                    repository.observe(access.address).map { rows ->
                        authorized(access) { MemoryView(access, rows.map { MemoryRecord(access, it.id, it.content) }) }
                    }.catch { error ->
                        if (error is CancellationException) throw error
                        emit(MemoryView(null, emptyList(), "memory_access_unavailable"))
                    }
                }
            }
        })
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(MemoryView(null, emptyList(), "memory_access_unavailable"))
    }

    suspend fun read(access: MemoryAccess): List<AssistantMemory> = authorized(access) { repository.read(access.address) }
    suspend fun add(access: MemoryAccess, content: String): AssistantMemory = authorized(access) { repository.add(access.address, content) }
    suspend fun update(record: MemoryRecord): AssistantMemory = update(record.access, record.id, record.content)
    suspend fun update(access: MemoryAccess, id: Int, content: String): AssistantMemory =
        authorized(access) { repository.update(access.address, id, content) }
    suspend fun delete(record: MemoryRecord) = delete(record.access, record.id)
    suspend fun delete(access: MemoryAccess, id: Int) = authorized(access) { repository.delete(access.address, id) }

    private suspend fun <T> authorized(access: MemoryAccess, operation: suspend () -> T): T {
        recovery.awaitReady()
        return sessions.withRealmAccess(access.realm) {
            settings.withResolvedConfiguration(access.address.scope, sessions.state.value) { configuration ->
                validate(configuration, access)
                operation()
            }
        }
    }

    private fun validate(configuration: ResolvedConfiguration, access: MemoryAccess) {
        check(access.realm.scope == access.address.scope) { "memory_scope_mismatch" }
        val assistant = configuration.assistants[access.assistantId] ?: error("memory_assistant_unavailable")
        check(configuration.access(ConfigurationCategory.ASSISTANT, access.assistantId).canSelect &&
            (!access.requireEnabled || assistant.enableMemory) && assistant.memoryAddress(access.address.scope) == access.address) {
            "memory_access_unavailable"
        }
    }
}
