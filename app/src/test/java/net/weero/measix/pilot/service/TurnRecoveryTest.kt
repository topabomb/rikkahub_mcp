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
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.db.dao.ScopedTurnExecution
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TurnRecoveryTest {
    @Test
    fun `master recovery fails closed when a non-terminal turn has no owning assistant message`() = runTest {
        val conversationId = Uuid.random()
        val execution = execution(conversationId, Uuid.random().toString())
        val conversation = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(MessageNode.of(me.rerere.ai.ui.UIMessage.user("preserve me"))),
        )
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>(relaxed = true)
        coEvery { repository.getRecoverableTurnExecutionsByConversation() } returns
            mapOf(conversationId to listOf(execution))
        coEvery { repository.getConversationById(conversationId) } returns conversation
        val scope = CoroutineScope(Job())
        coEvery { coordinator.load(conversationId) } returns
            ConversationRuntime(conversationId, conversation.toSnapshot(), scope, {})

        try {
            val error = try {
                recovery(repository, coordinator).recoverInterruptedTurns()
                null
            } catch (error: IllegalArgumentException) {
                error
            }

            assertTrue(error?.message.orEmpty().contains("missing owning assistant message"))
            coVerify(exactly = 0) { coordinator.executeRecovery(any(), any()) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `child recovery fails closed when a non-terminal turn has no owning assistant message`() = runTest {
        val masterId = Uuid.random()
        val childId = Uuid.random()
        val execution = execution(childId, assistantMessageId = null)
        val master = Conversation.ofId(masterId)
        val child = Conversation.ofId(childId).copy(
            parentConversationId = masterId,
            messageNodes = listOf(MessageNode.of(me.rerere.ai.ui.UIMessage.user("preserve child"))),
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

        val error = try {
            recovery(repository, coordinator).recoverInterruptedRuns()
            null
        } catch (error: IllegalArgumentException) {
            error
        }

        assertTrue(error?.message.orEmpty().contains("missing owning assistant message"))
        coVerify(exactly = 0) { coordinator.executeRecovery(any(), any()) }
    }

    private fun recovery(
        repository: ConversationRepository,
        coordinator: ConversationCommandCoordinator,
    ): TurnRecovery {
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns MutableStateFlow(Settings().toEffectiveSettingsSnapshot())
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
