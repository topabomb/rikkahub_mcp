package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ActiveTurnState
import net.weero.measix.pilot.service.runtime.ConversationCommand
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnFinalizationTest {
    @Test
    fun `late cancellation never overwrites a completed turn`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val runtime = mockk<ConversationRuntime>(relaxed = true)
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        coEvery { coordinator.load(conversationId) } returns runtime
        coEvery { repository.getTurnExecution(turnId.toString()) } returns TurnExecutionEntity(
            turnId = turnId.toString(),
            conversationId = conversationId.toString(),
            assistantMessageId = Uuid.random().toString(),
            status = TurnExecutionStatus.COMPLETED,
            reason = null,
            createdAt = 1,
            updatedAt = 2,
        )

        TurnFinalization(
            conversationRepository = repository,
            runtimeRegistry = mockk(relaxed = true),
            commandCoordinator = coordinator,
            json = Json,
        ).finalizeSupersededTurn(conversationId, turnId)

        coVerify(exactly = 0) { coordinator.executeOrThrow(any(), any()) }
    }

    @Test
    fun `approval-paused turn is cancelled before a replacement turn starts`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "pending-call",
                    toolName = "pending_tool",
                    input = "{}",
                ),
            ),
        )
        val base = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(
                MessageNode.of(UIMessage.user("question")),
                MessageNode.of(assistant),
            ),
        ).toSnapshot()
        val snapshot = base.copy(
            activeTurn = ActiveTurnState(
                epoch = 7,
                turnId = turnId,
                assistantMessageId = assistant.id,
                messages = base.currentMessages(),
            ),
        )
        val runtime = ConversationRuntime(
            id = conversationId,
            initial = snapshot,
            scope = this,
            onIdle = {},
        )
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        val command = slot<ConversationCommand>()
        coEvery { coordinator.load(conversationId) } returns runtime
        coEvery { repository.getTurnExecution(turnId.toString()) } returns TurnExecutionEntity(
            turnId = turnId.toString(),
            conversationId = conversationId.toString(),
            assistantMessageId = assistant.id.toString(),
            status = TurnExecutionStatus.AWAITING_APPROVAL,
            reason = null,
            createdAt = 1,
            updatedAt = 2,
        )
        coEvery { coordinator.executeOrThrow(conversationId, capture(command)) } returns Unit

        TurnFinalization(
            conversationRepository = repository,
            runtimeRegistry = mockk<ConversationRuntimeRegistry>(relaxed = true),
            commandCoordinator = coordinator,
            json = Json,
        ).finalizeSupersededTurn(conversationId, turnId)

        coVerify(exactly = 1) { coordinator.executeOrThrow(conversationId, any()) }
        val finalize = command.captured as FinalizeTurn
        assertEquals(TurnExecutionStatus.CANCELLED, finalize.terminalStatus)
        assertEquals(turnId, finalize.handle.turnId)
        assertEquals(7, finalize.handle.epoch)
    }

    @Test
    fun `partial text turn without tools is also cancelled before replacement`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val assistant = UIMessage.assistant("partial answer")
        val base = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(
                MessageNode.of(UIMessage.user("question")),
                MessageNode.of(assistant),
            ),
        ).toSnapshot()
        val runtime = ConversationRuntime(
            id = conversationId,
            initial = base.copy(
                activeTurn = ActiveTurnState(
                    epoch = 4,
                    turnId = turnId,
                    assistantMessageId = assistant.id,
                    messages = base.currentMessages(),
                ),
            ),
            scope = this,
            onIdle = {},
        )
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        val command = slot<ConversationCommand>()
        coEvery { coordinator.load(conversationId) } returns runtime
        coEvery { repository.getTurnExecution(turnId.toString()) } returns TurnExecutionEntity(
            turnId = turnId.toString(),
            conversationId = conversationId.toString(),
            assistantMessageId = assistant.id.toString(),
            status = TurnExecutionStatus.RUNNING,
            reason = null,
            createdAt = 1,
            updatedAt = 2,
        )
        coEvery { coordinator.executeOrThrow(conversationId, capture(command)) } returns Unit

        TurnFinalization(
            conversationRepository = repository,
            runtimeRegistry = mockk<ConversationRuntimeRegistry>(relaxed = true),
            commandCoordinator = coordinator,
            json = Json,
        ).finalizeSupersededTurn(conversationId, turnId)

        val finalize = command.captured as FinalizeTurn
        assertEquals(TurnExecutionStatus.CANCELLED, finalize.terminalStatus)
        assertEquals(turnId, finalize.handle.turnId)
        assertEquals(4, finalize.handle.epoch)
    }
}
