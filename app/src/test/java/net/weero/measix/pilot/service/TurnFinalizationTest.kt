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
import me.rerere.ai.ui.MessageMediaFailureReason
import me.rerere.ai.ui.mediaFailureMetadataOrNull
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ActiveTurnState
import net.weero.measix.pilot.service.runtime.ConversationChange
import net.weero.measix.pilot.service.runtime.ConversationCommand
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.ConversationTurnPhase
import net.weero.measix.pilot.service.runtime.currentTurnPresentation
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnFinalizationTest {
    @Test
    fun `failure preparation marks only unpersisted base64 and preserves published file parts`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val localImage = UIMessagePart.Image("file:///upload/published.png")
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            localImage,
            UIMessagePart.Image("data:image/png;base64,unfinished"),
            UIMessagePart.Tool("call", "image_tool", "{}", output = listOf(
                localImage,
                UIMessagePart.Image("data:image/png;base64,unfinished-tool-image"),
            )),
        ))
        val base = Conversation.ofId(conversationId).copy(messageNodes = listOf(MessageNode.of(assistant))).toSnapshot()
        val handle = TurnHandle(conversationId, 1, turnId, assistant.id)
        val snapshot = base.copy(activeTurn = ActiveTurnState(1, turnId, assistant.id, listOf(assistant)))
        val finalization = TurnFinalization(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), Json)

        val prepared = finalization.prepareOwnedTurnMessagesForFailure(
            snapshot, handle, listOf(assistant), "user_stop", true,
        ).last()

        assertEquals(localImage, prepared.parts[0])
        assertEquals(MessageMediaFailureReason.PERSISTENCE_FAILED, prepared.parts[1].mediaFailureMetadataOrNull()?.reason)
        val toolOutput = prepared.getTools().single().output
        assertEquals(localImage, toolOutput[0])
        assertEquals(MessageMediaFailureReason.PERSISTENCE_FAILED, toolOutput[1].mediaFailureMetadataOrNull()?.reason)
        assertTrue(!prepared.hasBase64Part())
    }

    @Test
    fun `failure preparation retains messages emitted after the durable checkpoint`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val durableAssistant = UIMessage.assistant("checkpoint").copy(id = Uuid.random())
        val streamedAssistant = durableAssistant.copy(
            parts = listOf(
                UIMessagePart.Text("checkpoint + after checkpoint"),
                UIMessagePart.Tool(
                    toolCallId = "streamed-tool",
                    toolName = "streamed_tool",
                    input = "{}",
                ),
            ),
        )
        val base = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(
                MessageNode.of(UIMessage.user("question")),
                MessageNode.of(durableAssistant),
            ),
        ).toSnapshot()
        val handle = TurnHandle(conversationId, 3, turnId, durableAssistant.id)
        val snapshot = base.copy(
            activeTurn = ActiveTurnState(
                epoch = handle.epoch,
                turnId = turnId,
                assistantMessageId = durableAssistant.id,
                messages = base.currentMessages().dropLast(1) + streamedAssistant,
            ),
        )
        val finalization = TurnFinalization(
            conversationRepository = mockk(relaxed = true),
            runtimeRegistry = mockk(relaxed = true),
            commandCoordinator = mockk(relaxed = true),
            json = Json,
        )

        val prepared = finalization.prepareOwnedTurnMessagesForFailure(
            snapshot = snapshot,
            handle = handle,
            latestMessages = base.currentMessages().dropLast(1) + streamedAssistant,
            reason = "provider_error",
            cancelledByUser = false,
        )

        val preparedAssistant = prepared.last()
        assertEquals(
            "checkpoint + after checkpoint",
            (preparedAssistant.parts.first() as UIMessagePart.Text).text,
        )
        assertTrue(preparedAssistant.getTools().single().hasReplayResult)

        val staleFailure = runCatching {
            finalization.prepareOwnedTurnMessagesForFailure(
                snapshot = snapshot,
                handle = handle.copy(epoch = handle.epoch + 1),
                latestMessages = base.currentMessages().dropLast(1) + streamedAssistant,
                reason = "provider_error",
                cancelledByUser = false,
            )
        }.exceptionOrNull()
        assertTrue(staleFailure is IllegalArgumentException)
    }

    @Test
    fun `stale Child cleanup never finalizes a newer active turn`() = runTest {
        val conversationId = Uuid.random()
        val staleTurnId = Uuid.random()
        val newerTurnId = Uuid.random()
        val newerAssistant = UIMessage.assistant("newer output")
        val base = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(MessageNode.of(UIMessage.user("task")), MessageNode.of(newerAssistant)),
        ).toSnapshot()
        val runtime = ConversationRuntime(
            id = conversationId,
            initial = base.copy(
                activeTurn = ActiveTurnState(
                    epoch = 9,
                    turnId = newerTurnId,
                    assistantMessageId = newerAssistant.id,
                    messages = base.currentMessages(),
                ),
            ),
            scope = this,
            onIdle = {},
        )
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        coEvery { repository.getConversationHeader(conversationId) } returns mockk(relaxed = true)
        coEvery { repository.getTurnExecution(staleTurnId.toString()) } returns TurnExecutionEntity(
            turnId = staleTurnId.toString(),
            conversationId = conversationId.toString(),
            assistantMessageId = Uuid.random().toString(),
            status = TurnExecutionStatus.CANCELLED,
            reason = "superseded",
            createdAt = 1,
            updatedAt = 2,
        )
        coEvery { coordinator.load(conversationId) } returns runtime

        TurnFinalization(
            conversationRepository = repository,
            runtimeRegistry = mockk(relaxed = true),
            commandCoordinator = coordinator,
            json = Json,
        ).finalizeChild(conversationId, staleTurnId, "late_cleanup")

        coVerify(exactly = 0) { coordinator.executeOrThrow(any(), any()) }
        assertEquals(newerTurnId, runtime.snapshot.value.activeTurn?.turnId)
    }

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
        coEvery { coordinator.executeOrThrow(conversationId, capture(command)) } coAnswers {
            val captured = command.captured
            val old = runtime.snapshot.value
            val change = ConversationTransition.plan(old, captured, old.header.updateAt)
            runtime.publishCommitted(old, captured, (change as ConversationChange.Durable).snapshot)
        }

        runtime.installActiveRequest(turnId, kotlinx.coroutines.Job())
        runtime.retainAwaitingApproval(TurnHandle(conversationId, 7, turnId, assistant.id))
        assertEquals(ConversationTurnPhase.AWAITING_USER, runtime.currentTurnPresentation().phase)

        val registry = mockk<ConversationRuntimeRegistry>()
        io.mockk.every { registry.findRuntime(conversationId) } returns runtime
        TurnFinalization(
            conversationRepository = repository,
            runtimeRegistry = registry,
            commandCoordinator = coordinator,
            json = Json,
        ).stopTurn(conversationId)

        coVerify(exactly = 1) { coordinator.executeOrThrow(conversationId, any()) }
        val finalize = command.captured as FinalizeTurn
        assertEquals(TurnExecutionStatus.CANCELLED, finalize.terminalStatus)
        assertEquals(turnId, finalize.handle.turnId)
        assertEquals(7, finalize.handle.epoch)
        assertEquals(ConversationTurnPhase.IDLE, runtime.currentTurnPresentation().phase)
        assertNull(runtime.currentGenerationTurnId())
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
