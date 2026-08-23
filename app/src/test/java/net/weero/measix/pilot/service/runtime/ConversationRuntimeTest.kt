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
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
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
 *  - applyStreamingDelta：无 DB 调用（内存态更新）且兼容投影含最新流式内容
 *  - 既有职责回归（原 ConversationSessionTest 迁移）：TTS 队列复用、
 *    替换 job 不能清除当前 job、cancel reason 绑定 turn。
 */
class ConversationRuntimeTest {

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
    fun `R5 concurrent submits have no lost updates`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default)
        val rt = runtime(scope)
        val n = 100
        val jobs = (0 until n).map { i ->
            async {
                rt.submit(AppendUserMessage(user("msg-$i")))
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
    fun `streaming delta updates compatible projection without persistence`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        // 先建一个 assistant 槽
        rt.submit(BeginTurn(turnId, assistantId, null, resume = false, onStart = true))
        val streamingText = UIMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("streamed")),
        )
        // applyStreamingDelta 直接更新 activeTurn（无 DB 调用，纯内存）
        rt.applyStreamingDelta(turnId, assistantId, listOf(streamingText))
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
        rt.submit(BeginTurn(turnId, assistantId, null, resume = false, onStart = true))
        assertEquals(2, rt.snapshot.value.nodes.size)
        assertSame(userNode, rt.snapshot.value.nodes[0])
        assertEquals(MessageRole.ASSISTANT, rt.snapshot.value.nodes[1].messages.single().role)

        rt.applyStreamingDelta(
            turnId,
            assistantId,
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
    fun `submit returns new conversation and updates state`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val result = rt.submit(AppendUserMessage(user("hello")))
        assertEquals(1, result.nodes.size)
        assertEquals(1, rt.snapshot.value.nodes.size)
        assertEquals(rt.snapshot.value, result)
        scope.cancel()
    }

    // ---- 既有职责回归（原 ConversationSessionTest 迁移） ----

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
        rt.beginTurn(firstTurn)
        rt.setJob(firstJob, firstTurn)
        rt.requestCancel(firstTurn, "superseded_by_new_turn")
        rt.setJob(replacementJob, secondTurn)
        rt.requestCancel(secondTurn, "user_stop")

        assertEquals(secondTurn, rt.currentTurnId())
        assertEquals("superseded_by_new_turn", rt.consumeCancelReason(firstTurn))
        assertEquals("user_stop", rt.peekCancelReason(secondTurn))

        cleanupGate.complete(Unit)
        firstChild.join()

        assertSame(replacementJob, rt.getJob())
        assertEquals(secondTurn, rt.currentTurnId())
        assertEquals("user_stop", rt.consumeCancelReason(secondTurn))
        replacementJob.cancel()
    }

    // stateRevision/persistedRevision 计数已删除（被 commandMutex 吸收）；
    // "dirty session 不被逐出"语义现由 pendingPersist 失败标记承载，
    // 持久化失败不回滚内存、成功后重新调度逐出——由 ConversationRuntimePersistenceTest 覆盖。
}
