package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.dao.ScopedTurnExecution
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ReconcileOrphanedTurnExecution
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Test
import kotlin.uuid.Uuid

class TurnRecoveryLegacyExecutionTest {
    @Test
    fun `master execution with deleted owner is terminalized without touching messages`() = runTest {
        val conversationId = Uuid.random()
        val missingAssistantId = Uuid.random()
        val execution = execution(conversationId, missingAssistantId.toString())
        val conversation = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(MessageNode.of(UIMessage.user("preserve me"))),
        )
        val scope = CoroutineScope(Job())
        val runtime = ConversationRuntime(conversationId, conversation.toSnapshot(), scope, {})
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>(relaxed = true)
        coEvery { repository.getRecoverableTurnExecutionsByConversation() } returns
            mapOf(conversationId to listOf(execution))
        coEvery { repository.getConversationById(conversationId) } returns conversation
        coEvery { coordinator.load(conversationId) } returns runtime

        recovery(repository, coordinator).recoverInterruptedTurns()

        coVerify(exactly = 1) {
            coordinator.executeRecovery(
                conversationId,
                match<ReconcileOrphanedTurnExecution> {
                    it.turnId.toString() == execution.turnId &&
                        it.assistantMessageId == missingAssistantId &&
                        it.terminalReason == TurnTerminalReasons.OWNER_MESSAGE_MISSING
                },
            )
        }
        coVerify(exactly = 0) { repository.getToolExecutions(execution.turnId) }
        scope.cancel()
    }

    @Test
    fun `child execution without recorded owner is terminalized through the same command`() = runTest {
        val masterId = Uuid.random()
        val childId = Uuid.random()
        val execution = execution(childId, assistantMessageId = null)
        val master = Conversation.ofId(masterId)
        val child = Conversation.ofId(childId).copy(
            parentConversationId = masterId,
            messageNodes = listOf(MessageNode.of(UIMessage.user("preserve child"))),
        )
        val repository = mockk<ConversationRepository>(relaxed = true)
        val coordinator = mockk<ConversationCommandCoordinator>(relaxed = true)
        coEvery { repository.getNonTerminalTurnExecutionsWithScope() } returns listOf(
            ScopedTurnExecution(
                execution = execution,
                isChild = true,
                parentConversationId = masterId.toString(),
            ),
        )
        coEvery { repository.getConversationById(masterId) } returns master
        coEvery { repository.getConversationById(childId) } returns child
        coEvery { repository.getTurnExecutions(childId) } returns listOf(execution)

        recovery(repository, coordinator).recoverInterruptedRuns()

        coVerify(exactly = 1) {
            coordinator.executeRecovery(
                childId,
                match<ReconcileOrphanedTurnExecution> {
                    it.turnId.toString() == execution.turnId &&
                        it.assistantMessageId == null &&
                        it.terminalReason == TurnTerminalReasons.OWNER_MESSAGE_MISSING
                },
            )
        }
        coVerify(exactly = 0) { repository.getToolExecutions(execution.turnId) }
    }

    private fun recovery(
        repository: ConversationRepository,
        coordinator: ConversationCommandCoordinator,
    ): TurnRecovery {
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns MutableStateFlow(Settings())
        return TurnRecovery(
            conversationRepo = repository,
            commandCoordinator = coordinator,
            settingsStore = settingsStore,
            json = Json,
            runGate = mockk(relaxed = true),
        )
    }

    private fun execution(
        conversationId: Uuid,
        assistantMessageId: String?,
    ) = TurnExecutionEntity(
        turnId = Uuid.random().toString(),
        conversationId = conversationId.toString(),
        assistantMessageId = assistantMessageId,
        status = TurnExecutionStatus.RUNNING,
        reason = null,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
