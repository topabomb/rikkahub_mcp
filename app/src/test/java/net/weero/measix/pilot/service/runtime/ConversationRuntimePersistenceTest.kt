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
import net.weero.measix.pilot.data.repository.ConversationRepository
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
            initial = Conversation.ofId(Uuid.random()),
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
}
