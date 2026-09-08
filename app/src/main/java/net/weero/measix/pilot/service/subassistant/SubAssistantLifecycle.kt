package net.weero.measix.pilot.service.subassistant

import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ReplaceMessageTree
import kotlin.uuid.Uuid

/** Owns sub-assistant lineage retention and Child deletion outside process recovery. */
class SubAssistantLifecycle(
    private val conversationRepository: ConversationRepository,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val json: Json,
) {
    internal suspend fun commitSummary(master: ConversationAggregateSnapshot, nodes: List<net.weero.measix.pilot.data.model.MessageNode>) {
        commandCoordinator.withRootTree(master.header.scope, master.conversationId) {
            requireClosedRunsBeforeTreeMutation(master)
            val children = conversationRepository.getChildConversationSnapshots(master.conversationId)
                .associateBy { it.conversationId }
            val retention = planSubAssistantRetention(master.conversationId, nodes, children, json)
            commandCoordinator.commitTreeMutation(
                master.header.scope,
                master.conversationId,
                buildMap {
                    put(master.conversationId, ReplaceMessageTree(nodes, clearSuggestions = true))
                    retention.truncatedChildren.forEach { put(it.conversationId, ReplaceMessageTree(it.nodes)) }
                },
                retention.deletedChildIds.toSet(),
            )
        }
    }

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

    /** The turn owners have already stopped and joined; a tree command must not repair execution history. */
    internal suspend fun requireClosedRunsBeforeTreeMutation(master: ConversationAggregateSnapshot): ConversationAggregateSnapshot {
        require(master.header.parentConversationId == null)
        val children = conversationRepository.getChildConversationSnapshots(master.conversationId)
        children.forEach { child ->
            check(conversationRepository.getTurnExecutions(child.conversationId).none {
                it.status == net.weero.measix.pilot.data.db.entity.TurnExecutionStatus.RUNNING ||
                    it.status == net.weero.measix.pilot.data.db.entity.TurnExecutionStatus.AWAITING_USER
            }) { "Child turn must be finalized by its owner before a parent tree mutation" }
        }
        return master
    }
}
