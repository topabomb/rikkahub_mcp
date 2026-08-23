package net.weero.measix.pilot.service.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Runtime→applyMutation 持久化接线测试。
 * 验证 submit(CommitCheckpoint) 确实通过注入的 ConversationRepository.applyMutation 落库。
 */
class ConversationRuntimePersistenceTest {

    @Test
    fun `submit CommitCheckpoint persists via applyMutation`() = runTest {
        val repo = mockk<ConversationRepository>(relaxed = true)
        coEvery { repo.applyMutation(any(), any()) } returns true
        val scope = CoroutineScope(Job())
        val rt = ConversationRuntime(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random()).toSnapshot(),
            scope = scope,
            onIdle = {},
            repository = repo,
        )
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        rt.submit(BeginTurn(turnId, assistantId, null, resume = false, onStart = true))
        val msg = UIMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("checkpoint")),
        )
        rt.submit(
            CommitCheckpoint(
                turnId = turnId,
                assistantMessageId = assistantId,
                messages = listOf(msg),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = null,
            )
        )
        coVerify(atLeast = 1) { repo.applyMutation(any(), any()) }
        scope.cancel()
    }

    @Test
    fun `FinalizeTurn does not upsert historical nodes with finished reasoning`() = runTest {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val mutations = mutableListOf<net.weero.measix.pilot.service.runtime.ConversationMutation>()
        coEvery { repo.applyMutation(any(), any()) } answers {
            mutations.add(invocation.args[0] as net.weero.measix.pilot.service.runtime.ConversationMutation)
            true
        }
        val histUser = MessageNode.of(
            UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text("q"))),
        )
        val histAssistant = MessageNode.of(
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(
                        reasoning = "old",
                        finishedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
                    ),
                    UIMessagePart.Text("old-answer"),
                ),
            ),
        )
        val activeId = Uuid.random()
        val active = MessageNode.of(
            UIMessage(
                id = activeId,
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "live", finishedAt = null),
                    UIMessagePart.Text("new-answer"),
                ),
            ),
        )
        val conversationId = Uuid.random()
        val scope = CoroutineScope(Job())
        val rt = ConversationRuntime(
            id = conversationId,
            initial = Conversation.ofId(conversationId).copy(messageNodes = listOf(histUser, histAssistant, active)).toSnapshot(),
            scope = scope,
            onIdle = {},
            repository = repo,
        )
        rt.submit(
            FinalizeTurn(
                turnId = Uuid.random(),
                assistantMessageId = activeId,
                messages = null,
                terminalStatus = TurnExecutionStatus.COMPLETED,
                terminalReason = null,
                closeInterruptedTools = false,
            )
        )
        val last = mutations.last()
        assertEquals(1, last.upsertedNodes.size)
        assertEquals(active.id, last.upsertedNodes.single().id)
        assertEquals(activeId, last.upsertedNodes.single().messages.single().id)
        assertEquals(listOf(2), last.upsertedNodeIndices)
        scope.cancel()
    }
}
