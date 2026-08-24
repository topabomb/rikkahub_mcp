package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * ConversationRuntime 权威测试。
 *  - 100 协程并发交错 submit 无丢失更新（commandMutex 单写互斥）
 *  - applyStreamingDelta：无 DB 调用（内存态更新）且唯一快照投影含最新流式内容
 *  - TTS 队列复用、
 *    替换 job 不能清除当前 job、cancel reason 绑定 turn。
 */
class ConversationRuntimeTest {

    private suspend fun persist(
        old: ConversationSnapshot,
        new: ConversationSnapshot,
        command: ConversationCommand,
    ): ConversationSnapshot = new

    private fun user(text: String): UIMessage =
        UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun runtime(scope: CoroutineScope, onIdle: () -> Unit = {}): ConversationRuntime =
        ConversationRuntime(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID).toSnapshot(),
            scope = scope,
            onIdle = { onIdle() },
        )

    @Test
    fun `concurrent submits have no lost updates`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default)
        val rt = runtime(scope)
        val n = 100
        val jobs = (0 until n).map { i ->
            async {
                rt.submit(AppendUserMessage(user("msg-$i")), ::persist)
            }
        }
        jobs.awaitAll()
        assertEquals(n, rt.snapshot.value.nodes.size)
        // 每条用户消息都保留
        val texts = rt.snapshot.value.nodes.map { it.messages.single().toText() }
        (0 until n).forEach { i ->
            assertTrue("msg-$i present", texts.contains("msg-$i"))
        }
        scope.cancel()
    }

    @Test
    fun `streaming delta updates snapshot without persistence`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        // 先建一个 assistant 槽
        val handle = rt.startTurn(turnId, assistantId, resume = false, ::persist)
        val streamingText = UIMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("streamed")),
        )
        // applyStreamingDelta 直接更新 activeTurn（无 DB 调用，纯内存）
        rt.applyStreamingDelta(handle, listOf(streamingText))
        // currentMessages() 读取入口能看到流式内容（activeTurn 覆盖末节点）
        val last = rt.snapshot.value.currentMessages().lastOrNull()
        assertNotNull(last)
        assertEquals(assistantId, last?.id)
        assertEquals("streamed", last?.toText())
        scope.cancel()
    }

    @Test
    fun `streaming after user task overlays the BeginTurn assistant slot not the user node`() = runTest {
        val scope = CoroutineScope(Job())
        val userNode = MessageNode.of(user("child task"))
        val rt = ConversationRuntime(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID)
                .copy(messageNodes = listOf(userNode)).toSnapshot(),
            scope = scope,
            onIdle = {},
        )
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        val handle = rt.startTurn(turnId, assistantId, resume = false, ::persist)
        assertEquals(2, rt.snapshot.value.nodes.size)
        assertSame(userNode, rt.snapshot.value.nodes[0])
        assertEquals(MessageRole.ASSISTANT, rt.snapshot.value.nodes[1].messages.single().role)

        rt.applyStreamingDelta(
            handle,
            listOf(UIMessage(id = assistantId, role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("delta")))),
        )
        // currentMessages 读取入口：activeTurn 覆盖末节点，未污染用户节点
        val current = rt.snapshot.value.currentMessages()
        assertEquals(MessageRole.USER, current[0].role)
        assertEquals("child task", current[0].toText())
        assertEquals(MessageRole.ASSISTANT, current.last().role)
        assertEquals("delta", current.last().toText())
        assertSame(userNode, rt.snapshot.value.nodes[0])
        scope.cancel()
    }

    @Test
    fun `header commit racing a stream preserves both projections`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random(), false, ::persist)
        val persistStarted = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val update = async {
            rt.submit(UpdateHeader(title = "renamed")) { _, new, _ ->
                persistStarted.complete(Unit)
                allowCommit.await()
                new
            }
        }
        persistStarted.await()
        val delta = UIMessage.assistant("latest").copy(id = handle.assistantMessageId)
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, listOf(delta)))
        allowCommit.complete(Unit)
        update.await()

        assertEquals("renamed", rt.snapshot.value.header.title)
        assertEquals("latest", rt.snapshot.value.currentMessages().last().toText())
        scope.cancel()
    }

    @Test
    fun `tree mutation is rejected while a turn owns the tree`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        rt.startTurn(Uuid.random(), Uuid.random(), false, ::persist)
        val before = rt.snapshot.value

        val failure = runCatching {
            rt.submit(AppendUserMessage(user("must not append")), ::persist)
        }.exceptionOrNull()

        assertTrue(failure is ConversationCommandConflictException)
        assertEquals(before, rt.snapshot.value)
        scope.cancel()
    }

    @Test
    fun `old turn delta is rejected after a replacement turn starts`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val first = rt.startTurn(Uuid.random(), Uuid.random(), false, ::persist)
        rt.submit(
            FinalizeTurn(first, null, TurnExecutionStatus.COMPLETED, null, false),
            ::persist,
        )
        val second = rt.startTurn(Uuid.random(), Uuid.random(), false, ::persist)

        assertEquals(
            StreamingDeltaResult.STALE_TURN,
            rt.applyStreamingDelta(first, listOf(UIMessage.assistant("stale"))),
        )
        val current = UIMessage.assistant("current").copy(id = second.assistantMessageId)
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(second, listOf(current)))
        assertEquals("current", rt.snapshot.value.currentMessages().last().toText())
        scope.cancel()
    }

    @Test
    fun `pin command preserves the active turn owner`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random(), false, ::persist)

        rt.submit(TogglePinned, ::persist)

        assertTrue(rt.snapshot.value.header.isPinned)
        assertEquals(handle.turnId, rt.snapshot.value.activeTurn?.turnId)
        scope.cancel()
    }

    @Test
    fun `tool approval updates the durable tree without replacing the active turn owner`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random(), false, ::persist)
        val waiting = UIMessage(
            id = handle.assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "approval",
                    toolName = "approval_tool",
                    input = "{}",
                    approvalState = ToolApprovalState.Pending,
                )
            ),
        )
        rt.submit(
            CommitCheckpoint(
                handle = handle,
                messages = listOf(waiting),
                turnStatus = TurnExecutionStatus.AWAITING_APPROVAL,
                turnReason = null,
                toolExecution = null,
            ),
            ::persist,
        )

        rt.submit(
            UpdateToolApproval(
                messageId = waiting.id,
                toolOrdinal = 0,
                approvalState = ToolApprovalState.Approved,
            ),
            ::persist,
        )

        assertEquals(handle.turnId, rt.snapshot.value.activeTurn?.turnId)
        assertEquals(
            ToolApprovalState.Approved,
            rt.snapshot.value.currentMessages().last().getTools().single().approvalState,
        )
        scope.cancel()
    }

    @Test
    fun `submit returns new conversation and updates state`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val result = rt.submit(AppendUserMessage(user("hello")), ::persist)
        assertEquals(1, result.nodes.size)
        assertEquals(1, rt.snapshot.value.nodes.size)
        assertEquals(rt.snapshot.value, result)
        scope.cancel()
    }

    // ---- Runtime lifecycle state ----

    @Test
    fun `tts queue id is reused only when resuming the same master turn`() {
        val scope = CoroutineScope(Job())
        val rt = ConversationRuntime(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID).toSnapshot(),
            scope = scope,
            onIdle = {},
        )

        val firstTurn = rt.getTtsQueueSessionId(resumeExistingTurn = false)
        val resumedTurn = rt.getTtsQueueSessionId(resumeExistingTurn = true)
        val nextTurn = rt.getTtsQueueSessionId(resumeExistingTurn = false)

        assertEquals(firstTurn, resumedTurn)
        assertNotEquals(firstTurn, nextTurn)
        scope.cancel()
    }

    @Test
    fun `completion of replaced job cannot clear current job`() = runTest {
        val cleanupGate = CompletableDeferred<Unit>()
        val firstJob = Job()
        val firstChild = CoroutineScope(firstJob).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanupGate.await() }
            }
        }
        val rt = ConversationRuntime(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID).toSnapshot(),
            scope = this,
            onIdle = {},
        )
        val replacementJob = Job()

        runCurrent()
        rt.setJob(firstJob)
        rt.setJob(replacementJob)
        assertSame(replacementJob, rt.getJob())

        cleanupGate.complete(Unit)
        firstChild.join()

        assertSame(replacementJob, rt.getJob())
        replacementJob.cancel()
    }

    @Test
    fun `installed unreferenced runtime becomes idle without an explicit release`() = runTest {
        var idleCount = 0
        val rt = ConversationRuntime(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID).toSnapshot(),
            scope = this,
            onIdle = { idleCount++ },
            idleTimeoutMs = 100L,
        )

        rt.armIdleEviction()
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(1, idleCount)
    }

    @Test
    fun `cancel reasons remain bound to their turn when a replacement job starts`() = runTest {
        val cleanupGate = CompletableDeferred<Unit>()
        val firstJob = Job()
        val firstChild = CoroutineScope(firstJob).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanupGate.await() }
            }
        }
        val rt = ConversationRuntime(
            id = Uuid.random(),
            initial = Conversation.ofId(Uuid.random(), assistantId = DEFAULT_ASSISTANT_ID).toSnapshot(),
            scope = this,
            onIdle = {},
        )
        val firstTurn = Uuid.random()
        val secondTurn = Uuid.random()
        val replacementJob = Job()

        runCurrent()
        rt.trackGenerationTurn(firstTurn)
        rt.setJob(firstJob, firstTurn)
        rt.requestCancel(firstTurn, "superseded_by_new_turn")
        rt.setJob(replacementJob, secondTurn)
        rt.requestCancel(secondTurn, "user_stop")

        assertEquals(secondTurn, rt.currentGenerationTurnId())
        assertEquals("superseded_by_new_turn", rt.consumeCancelReason(firstTurn))
        assertEquals("user_stop", rt.peekCancelReason(secondTurn))

        cleanupGate.complete(Unit)
        firstChild.join()

        assertSame(replacementJob, rt.getJob())
        assertEquals(secondTurn, rt.currentGenerationTurnId())
        assertEquals("user_stop", rt.consumeCancelReason(secondTurn))
        replacementJob.cancel()
    }

}
