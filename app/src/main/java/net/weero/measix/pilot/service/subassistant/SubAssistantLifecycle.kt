package net.weero.measix.pilot.service.subassistant
import net.weero.measix.pilot.service.turn.TurnFinalizer

import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ReplaceMessageTree
import net.weero.measix.pilot.service.runtime.toSnapshot
import kotlin.uuid.Uuid

/** Owns sub-assistant lineage retention and Child deletion outside process recovery. */
class SubAssistantLifecycle(
    private val conversationRepository: ConversationRepository,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val settingsStore: SettingsStore,
    private val turnFinalizer: TurnFinalizer,
    private val json: Json,
) {
    suspend fun applyRetentionAfterTreeMutation(masterConversationId: Uuid) {
        val master = runtimeRegistry.findRuntime(masterConversationId)?.snapshot?.value?.durable
            ?: conversationRepository.getConversationSnapshotById(masterConversationId)
            ?: return
        val children = conversationRepository.getChildConversationSnapshots(master.conversationId)
            .associateBy { it.conversationId }
        val plan = planSubAssistantRetention(master.conversationId, master.nodes, children, json)

        plan.truncatedChildren.forEach { child ->
            commandCoordinator.executeOrThrow(child.conversationId, ReplaceMessageTree(child.nodes))
        }
        plan.deletedChildIds.forEach { childId ->
            commandCoordinator.deleteOrThrow(childId)
        }
    }

    internal suspend fun finalizeRunsBeforeTreeMutation(master: ConversationAggregateSnapshot): ConversationAggregateSnapshot {
        require(master.header.parentConversationId == null)
        val children = conversationRepository.getChildConversationSnapshots(master.conversationId)
            .associateBy { it.conversationId }
        val result = reconcileMasterSubAssistantCalls(
            masterId = master.conversationId,
            masterAssistantId = master.header.assistantId,
            masterNodes = master.nodes,
            settings = settingsStore.effectiveSettings.value.settings,
            childrenById = children,
            json = json,
        )
        if (result.masterNodes != master.nodes) {
            commandCoordinator.executeOrThrow(master.conversationId, ReplaceMessageTree(result.masterNodes))
        }
        result.childStopReasons.forEach { (childId, reason) ->
            if (children[childId] != null) turnFinalizer.finalizeCurrentChild(childId, reason)
        }
        (children.keys - result.referencedChildIds).forEach { orphanId ->
            commandCoordinator.deleteOrThrow(orphanId)
        }
        return master.copy(nodes = result.masterNodes)
    }
}
