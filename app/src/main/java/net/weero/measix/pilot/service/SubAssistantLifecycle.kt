package net.weero.measix.pilot.service

import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.ReplaceMessageTree
import net.weero.measix.pilot.service.runtime.toSnapshot
import kotlin.uuid.Uuid

/** Owns sub-assistant lineage retention and Child deletion outside process recovery. */
class SubAssistantLifecycle(
    private val conversationRepository: ConversationRepository,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val settingsStore: SettingsStore,
    private val turnFinalization: TurnFinalization,
    private val json: Json,
) {
    suspend fun applyRetentionAfterTreeMutation(masterConversationId: Uuid) {
        val master = runtimeRegistry.findRuntime(masterConversationId)?.snapshot?.value
            ?: conversationRepository.getConversationById(masterConversationId)?.toSnapshot()
            ?: return
        val children = conversationRepository.getChildConversations(master.conversationId).associateBy { it.id }
        val plan = planSubAssistantRetention(master.conversationId, master.nodes, children, json)

        plan.truncatedChildren.forEach { child ->
            commandCoordinator.executeOrThrow(child.id, ReplaceMessageTree(child.messageNodes))
        }
        plan.deletedChildren.forEach { child ->
            commandCoordinator.deleteOrThrow(child.id)
        }
    }

    suspend fun finalizeRunsBeforeTreeMutation(master: ConversationSnapshot): ConversationSnapshot {
        require(master.header.parentConversationId == null)
        val children = conversationRepository.getChildConversations(master.conversationId).associateBy { it.id }
        val result = reconcileMasterSubAssistantCalls(
            masterId = master.conversationId,
            masterAssistantId = master.header.assistantId,
            masterNodes = master.nodes,
            settings = settingsStore.settingsFlow.value,
            childrenById = children,
            json = json,
        )
        if (result.masterNodes != master.nodes) {
            commandCoordinator.executeOrThrow(master.conversationId, ReplaceMessageTree(result.masterNodes))
        }
        result.childStopReasons.forEach { (childId, reason) ->
            if (children[childId] != null) turnFinalization.finalizeChild(childId, reason)
        }
        (children.keys - result.referencedChildIds).forEach { orphanId ->
            commandCoordinator.deleteOrThrow(orphanId)
        }
        return master.copy(nodes = result.masterNodes, activeTurn = null)
    }
}
