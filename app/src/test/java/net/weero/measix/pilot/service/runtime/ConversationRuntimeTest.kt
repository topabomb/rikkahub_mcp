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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.test.testTurnRequestContext
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.ToolResultEvent
import net.weero.measix.pilot.data.ai.TurnModelContextProjection
import net.weero.measix.pilot.data.ai.ToolResultEventStatus
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.service.applyToolUserDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * ConversationRuntime 权威测试。
 *  - 100 协程并发交错 command 无丢失更新（operation lock 外的 projection apply 仍按 identity 发布）
 *  - applyStreamingDelta：无 DB 调用（内存态更新）且唯一快照投影含最新流式内容
 *  - TTS 队列复用、
 *    替换 job 不能清除当前 job、cancel reason 绑定 turn。
 */
class ConversationRuntimeTest {

    private fun user(text: String): UIMessage =
        UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private val commandLock = Mutex()

    private suspend fun ConversationRuntime.applyCommand(command: ConversationCommand): ConversationAggregateSnapshot =
        commandLock.withLock {
            val old = snapshot.value
            if (command is StartTurn) {
                val started = command.copy(epoch = nextTurnEpoch())
                validateConversationCommandOwner(id, old, started, currentGenerationTurnId())
                val change = ConversationTransition.plan(old, started, old.header.updateAt)
                val snapshot = (change as ConversationChange.Durable).snapshot
                publishCommitted(old, started, snapshot)
                return@withLock snapshot
            }
            validateConversationCommandOwner(id, old, command, currentGenerationTurnId())
            val change = ConversationTransition.plan(old, command, old.header.updateAt)
            val snapshot = (change as ConversationChange.Durable).snapshot
            publishCommitted(old, command, snapshot)
            snapshot
        }

    private suspend fun ConversationRuntime.startTurn(
        turnId: Uuid,
        assistantMessageId: Uuid,
    ): TurnHandle {
        if (currentGenerationTurnId() != turnId) {
            installActiveRequest(turnId, Job())
        }
        // START 必须锚定真实 USER：空树先落一条用户消息，模拟首个 durable 边界。
        if (snapshot.value.nodes.isEmpty()) {
            applyCommand(AppendUserMessage(user("anchor")))
        }
        applyCommand(
            ConversationTransition.buildStartTurnCommand(
                current = snapshot.value,
                turnId = turnId,
                modelContextCandidate = disclosureCandidate(),
                assistantMessageId = assistantMessageId,
            ),
        )
        val active = requireNotNull(snapshot.value.activeTurn)
        val handle = TurnHandle(id, active.epoch, active.turnId, active.assistantMessageId)
        markRunning(handle)
        return handle
    }

    private fun runtime(scope: CoroutineScope, onIdle: () -> Unit = {}): ConversationRuntime {
        val conversationId = Uuid.random()
        return ConversationRuntime(
            id = conversationId,
            initial = Conversation.ofId(conversationId, assistantId = DEFAULT_ASSISTANT_ID).toSnapshot(),
            scope = scope,
            onIdle = { onIdle() },
        )
    }

    @Test
    fun `concurrent submits have no lost updates`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default)
        val rt = runtime(scope)
        val n = 100
        val jobs = (0 until n).map { i ->
            async {
                rt.applyCommand(AppendUserMessage(user("msg-$i")))
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
        val handle = rt.startTurn(turnId, assistantId)
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
        val handle = rt.startTurn(turnId, assistantId)
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
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val old = rt.snapshot.value
        val command = UpdateHeader(title = "renamed")
        val committed = (ConversationTransition.plan(old, command, old.header.updateAt) as ConversationChange.Durable).snapshot
        val delta = UIMessage.assistant("latest").copy(id = handle.assistantMessageId)
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, listOf(delta)))
        rt.publishCommitted(old, command, committed)

        assertEquals("renamed", rt.snapshot.value.header.title)
        assertEquals("latest", rt.snapshot.value.currentMessages().last().toText())
        scope.cancel()
    }

    @Test
    fun `tree mutation is rejected while a turn owns the tree`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        rt.startTurn(Uuid.random(), Uuid.random())
        val before = rt.snapshot.value

        val failure = runCatching {
            rt.applyCommand(AppendUserMessage(user("must not append")))
        }.exceptionOrNull()

        assertTrue(failure is ConversationCommandConflictException)
        assertEquals(before, rt.snapshot.value)
        scope.cancel()
    }

    @Test
    fun `old turn delta is rejected after a replacement turn starts`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val first = rt.startTurn(Uuid.random(), Uuid.random())
        rt.applyCommand(FinalizeTurn(first, null, TurnExecutionStatus.COMPLETED, null, false))
        val second = rt.startTurn(Uuid.random(), Uuid.random())

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
        val handle = rt.startTurn(Uuid.random(), Uuid.random())

        rt.applyCommand(TogglePinned)

        assertTrue(rt.snapshot.value.header.isPinned)
        assertEquals(handle.turnId, rt.snapshot.value.activeTurn?.turnId)
        scope.cancel()
    }

    @Test
    fun `request context binds once and approval continuation preserves the same reference`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val turnId = Uuid.random()
        val handle = rt.startTurn(turnId, Uuid.random())
        val initialWorker = requireNotNull(rt.currentWorker())
        val model = Model(modelId = "model", displayName = "Model")
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        val assistant = Assistant(enableMemory = false)
        val context = testTurnRequestContext(
            settings = Settings(providers = listOf(provider), assistants = listOf(assistant)),
            model = model,
            assistant = assistant,
        )

        rt.bindTurnRequestContext(turnId, initialWorker, context)
        val projection = TurnModelContextProjection(entries = emptyList(), locators = emptyMap())
        rt.bindModelContextProjection(turnId, initialWorker, projection)
        assertSame(context, rt.requireTurnRequestContext(turnId, initialWorker))
        assertSame(projection, rt.requireTurnModelContextProjection(turnId, initialWorker))
        assertThrows(IllegalStateException::class.java) {
            rt.bindTurnRequestContext(turnId, initialWorker, context)
        }
        assertThrows(IllegalStateException::class.java) {
            rt.bindModelContextProjection(turnId, initialWorker, projection)
        }
        assertThrows(IllegalStateException::class.java) {
            rt.requireTurnRequestContext(Uuid.random(), initialWorker)
        }
        assertThrows(IllegalStateException::class.java) {
            rt.requireTurnRequestContext(turnId, Job())
        }

        rt.retainAwaitingApproval(handle)
        val continuationWorker = Job()
        rt.continueAwaitingApproval(handle, continuationWorker)
        assertSame(context, rt.requireTurnRequestContext(turnId, continuationWorker))
        assertSame(projection, rt.requireTurnModelContextProjection(turnId, continuationWorker))
        scope.cancel()
    }

    @Test
    fun `cancelled Child owner releases only by exact turn and worker identity`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val turnId = Uuid.random()
        val worker = Job()
        rt.installActiveRequest(turnId, worker)
        val model = Model(modelId = "model", displayName = "Model")
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        val assistant = Assistant(enableMemory = false)
        val context = testTurnRequestContext(
            settings = Settings(providers = listOf(provider), assistants = listOf(assistant)),
            model = model,
            assistant = assistant,
        )
        rt.bindTurnRequestContext(turnId, worker, context)
        rt.requestCancel(turnId, "target_access_revoked")
        assertEquals(ConversationTurnPhase.STOPPING, rt.currentTurnPresentation().phase)

        rt.releaseActiveRequest(turnId, Job(), retainAwaitingOwner = false)
        assertSame(context, rt.requireTurnRequestContext(turnId, worker))
        rt.releaseActiveRequest(turnId, worker, retainAwaitingOwner = false)
        assertEquals(null, rt.currentWorker())
        scope.cancel()
    }

    @Test
    fun `approval continuation fails closed when request context is missing`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        rt.retainAwaitingApproval(handle)

        assertThrows(IllegalArgumentException::class.java) {
            rt.continueAwaitingApproval(handle, Job())
        }
        scope.cancel()
    }

    @Test
    fun `tool approval updates the durable tree without replacing the active turn owner`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
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
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, listOf(waiting)))
        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = net.weero.measix.pilot.data.ai.CheckpointKind.AWAITING_APPROVAL,
                messages = listOf(waiting),
                turnStatus = TurnExecutionStatus.AWAITING_APPROVAL,
                turnReason = null,
                toolExecution = null,
            )
        )
        rt.retainAwaitingApproval(handle)
        assertEquals(ConversationTurnPhase.AWAITING_USER, rt.currentTurnPresentation().phase)

        rt.applyCommand(ResolveToolInteraction(
                messageId = waiting.id,
                toolOrdinal = 0,
                decision = ToolUserDecision.Approve,
                handle = handle,
            )
        )

        assertEquals(handle.turnId, rt.snapshot.value.activeTurn?.turnId)
        assertEquals(
            ToolApprovalState.Approved,
            rt.snapshot.value.currentMessages().last().getTools().single().approvalState,
        )
        assertEquals(
            ToolApprovalState.Approved,
            rt.snapshot.value.nodes.last().currentMessage.getTools().single().approvalState,
        )
        assertEquals(
            ToolCallPhase.READY,
            rt.snapshot.value.activeTurn?.toolCallPhases?.get(ToolCallLocator(waiting.id, 0)),
        )
        assertEquals(ConversationTurnPhase.AWAITING_USER, rt.currentTurnPresentation().phase)
        rt.markRunning(handle)
        assertEquals(ConversationTurnPhase.GENERATING, rt.currentTurnPresentation().phase)
        scope.cancel()
    }

    /**
     * 回归（2026-9-2 15:01）：生产里 HITL 的真实顺序是"流式先投递 Auto 版本，AWAITING_APPROVAL
     * checkpoint 才带来 Pending 版本"。afterCheckpoint 此前只同步 toolCallPhases，activeTurn
     * 的 messages 停留在 Auto 版本，而 currentMessages() 末条取自 turn.messages.last()，
     * applyToolUserDecision 因此读到非 Pending 并抛 "tool interaction is no longer pending"。
     * 勿删除本用例（相邻用例流式与 checkpoint 同为 Pending 版本，覆盖不到这条路径）。
     */
    @Test
    fun `awaiting approval checkpoint replaces the streamed Auto tool with its Pending version`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val streamed = UIMessage(
            id = handle.assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "approval",
                    toolName = "approval_tool",
                    input = "{}",
                    // 流式版本：provider 尚未落 HITL 状态，工具仍是 Auto。
                    approvalState = ToolApprovalState.Auto,
                )
            ),
        )
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, listOf(streamed)))

        val pending = streamed.copy(
            parts = listOf(
                (streamed.parts.single() as UIMessagePart.Tool).copy(
                    approvalState = ToolApprovalState.Pending,
                )
            ),
        )
        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.AWAITING_APPROVAL,
                messages = listOf(pending),
                turnStatus = TurnExecutionStatus.AWAITING_APPROVAL,
                turnReason = null,
                toolExecution = null,
            )
        )
        rt.retainAwaitingApproval(handle)

        val locator = ToolCallLocator(handle.assistantMessageId, 0)
        // 决策路径读到的必须是 checkpoint 提交的 Pending 版本，而不是流式的 Auto 版本。
        assertEquals(
            ToolApprovalState.Pending,
            rt.snapshot.value.currentMessages().last().getTools().single().approvalState,
        )
        assertEquals(
            ToolCallPhase.AWAITING_APPROVAL,
            rt.snapshot.value.activeTurn?.toolCallPhases?.get(locator),
        )

        // 走与生产同一条 applyToolUserDecision：非 Pending 会在定位阶段就抛冲突异常，
        // 能提交即证明投影已对齐 checkpoint 的 committed 版本。
        val submitted = mutableListOf<ResolveToolInteraction>()
        var continuedTurnId: Uuid? = null
        applyToolUserDecision(
            locator = locator,
            decision = ToolUserDecision.Approve,
            awaitPreviousGeneration = {},
            currentSnapshot = { rt.snapshot.value },
            submit = { command ->
                submitted += command
                rt.applyCommand(command)
            },
            onMoreApprovalsPending = { error("single approval must continue its turn") },
            continueTurn = { active, _ -> continuedTurnId = active.turnId },
        )

        assertEquals(1, submitted.size)
        assertEquals(
            ToolApprovalState.Approved,
            rt.snapshot.value.currentMessages().last().getTools().single().approvalState,
        )
        assertEquals(
            ToolApprovalState.Approved,
            rt.snapshot.value.nodes.last().currentMessage.getTools().single().approvalState,
        )
        assertEquals(ToolCallPhase.READY, rt.snapshot.value.activeTurn?.toolCallPhases?.get(locator))
        assertEquals(handle.turnId, rt.snapshot.value.activeTurn?.turnId)
        assertEquals(handle.turnId, continuedTurnId)
        scope.cancel()
    }

    @Test
    fun `same-worker approval wait resumes without replacing the active request`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val waiting = UIMessage(
            id = handle.assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "ask",
                    toolName = "ask_user",
                    input = "{}",
                    approvalState = ToolApprovalState.Pending,
                )
            ),
        )
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, listOf(waiting)))
        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.AWAITING_APPROVAL,
                messages = listOf(waiting),
                turnStatus = TurnExecutionStatus.AWAITING_APPROVAL,
                turnReason = null,
                toolExecution = null,
            )
        )
        val worker = requireNotNull(rt.currentWorker())
        rt.retainAwaitingApproval(handle)
        assertEquals(ConversationTurnPhase.AWAITING_USER, rt.currentTurnPresentation().phase)
        assertSame(worker, rt.currentWorker())
        assertEquals(handle.turnId, rt.currentGenerationTurnId())

        rt.applyCommand(ResolveToolInteraction(
                messageId = waiting.id,
                toolOrdinal = 0,
                decision = ToolUserDecision.Answer("keep going"),
                handle = handle,
            )
        )
        rt.markRunning(handle)
        assertEquals(ConversationTurnPhase.GENERATING, rt.currentTurnPresentation().phase)
        assertSame(worker, rt.currentWorker())
        assertEquals(handle.turnId, rt.snapshot.value.activeTurn?.turnId)
        scope.cancel()
    }

    @Test
    fun `attachment ref backfill is rejected while an approval turn is active`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val user = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image("file:///legacy.png")),
        )
        rt.applyCommand(AppendUserMessage(user))
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val waiting = UIMessage(
            id = handle.assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "image",
                    toolName = "generate_image",
                    input = "{}",
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
        )
        val streamed = listOf(user, waiting)
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, streamed))
        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.AWAITING_APPROVAL,
                messages = streamed,
                turnStatus = TurnExecutionStatus.AWAITING_APPROVAL,
                turnReason = null,
                toolExecution = null,
            )
        )

        val backfills = AttachmentRefs.planBackfills(rt.snapshot.value.nodes)
        assertEquals(1, backfills.size)
        val failure = runCatching {
            rt.applyCommand(BackfillAttachmentRefs(backfills))
        }.exceptionOrNull()

        assertTrue(failure is ConversationCommandConflictException)
        assertTrue(failure?.message?.contains("requires the active turn to finish first") == true)
        assertEquals(null, AttachmentRefs.getRef(rt.snapshot.value.nodes.first().currentMessage.parts.single()))
        assertEquals(
            ToolApprovalState.Pending,
            rt.snapshot.value.activeTurn?.messages?.last()?.getTools()?.single()?.approvalState,
        )
        assertEquals(handle.turnId, rt.snapshot.value.activeTurn?.turnId)
        scope.cancel()
    }

    @Test
    fun `attachment ref backfill applies as an exact durable pre-start command`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val user = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image("file:///legacy.png")),
        )
        rt.applyCommand(AppendUserMessage(user))
        val backfills = AttachmentRefs.planBackfills(rt.snapshot.value.nodes)

        rt.applyCommand(BackfillAttachmentRefs(backfills))

        assertNotNull(AttachmentRefs.getRef(rt.snapshot.value.nodes.single().currentMessage.parts.single()))
        assertEquals(null, rt.snapshot.value.activeTurn)
        scope.cancel()
    }

    @Test
    fun `tool call assembly and durable execution checkpoints have distinct phases`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val locator = ToolCallLocator(handle.assistantMessageId, 0)
        val assembling = UIMessage(
            id = handle.assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "generate_image",
                    input = "{\"prompt\":\"sky",
                ),
            ),
        )

        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, listOf(assembling)))
        assertEquals(ToolCallPhase.CALL_STREAMING, rt.snapshot.value.activeTurn?.toolCallPhases?.get(locator))

        val ready = assembling.copy(
            parts = listOf((assembling.parts.single() as UIMessagePart.Tool).copy(input = "{\"prompt\":\"sky\"}")),
        )
        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.STEP_COMPLETED,
                messages = listOf(ready),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = null,
            )
        )
        assertEquals(ToolCallPhase.READY, rt.snapshot.value.activeTurn?.toolCallPhases?.get(locator))

        val startedFact = ToolExecutionEntity(
            executionId = "execution-1",
            turnId = handle.turnId.toString(),
            toolOrdinal = 0,
            status = ToolExecutionStatus.STARTED,
            reason = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.TOOL_EXECUTION_STARTED,
                messages = listOf(ready),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = startedFact,
            )
        )
        assertEquals(ToolCallPhase.EXECUTING, rt.snapshot.value.activeTurn?.toolCallPhases?.get(locator))

        val result = ready.copy(
            parts = listOf(
                (ready.parts.single() as UIMessagePart.Tool).copy(
                    output = listOf(UIMessagePart.Text("{\"status\":\"completed\"}")),
                ),
            ),
        )
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, listOf(result)))
        assertEquals(
            ToolCallPhase.EXECUTING,
            rt.snapshot.value.activeTurn?.toolCallPhases?.get(locator),
        )

        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.TOOL_RESULT_COMPLETED,
                messages = listOf(result),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = startedFact.copy(status = ToolExecutionStatus.COMPLETED),
                toolResults = listOf(ToolResultEvent(result.id, 0, ToolResultEventStatus.COMPLETED)),
            )
        )
        assertEquals(ToolCallPhase.COMPLETED, rt.snapshot.value.activeTurn?.toolCallPhases?.get(locator))
        scope.cancel()
    }

    @Test
    fun `streamed approval and output cannot advance lifecycle before a checkpoint`() {
        val assistantId = Uuid.random()
        val state = ActiveTurnState(
            epoch = 1,
            turnId = Uuid.random(),
            assistantMessageId = assistantId,
            messages = emptyList(),
        )
        val streamed = UIMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call",
                    toolName = "generate_image",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("{\"status\":\"completed\"}")),
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
        )

        val projected = state.withStreamingMessages(listOf(streamed))

        assertEquals(
            ToolCallPhase.CALL_STREAMING,
            projected.toolCallPhases[ToolCallLocator(assistantId, 0)],
        )
    }

    @Test
    fun `result without execution advances only from typed committed result fact`() {
        val assistantId = Uuid.random()
        val handle = TurnHandle(
            conversationId = Uuid.random(),
            epoch = 1L,
            turnId = Uuid.random(),
            assistantMessageId = assistantId,
        )
        val initial = ActiveTurnState(
            epoch = handle.epoch,
            turnId = handle.turnId,
            assistantMessageId = assistantId,
            messages = emptyList(),
        )
        val toolMessage = UIMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("call", "generate_image", "{}")),
        )
        val ready = initial.withStreamingMessages(listOf(toolMessage)).afterCheckpoint(
            CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.STEP_COMPLETED,
                messages = listOf(toolMessage),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = null,
            )
        )
        val failedMessage = toolMessage.copy(
            parts = listOf(
                (toolMessage.parts.single() as UIMessagePart.Tool).copy(
                    output = listOf(UIMessagePart.Text("{\"status\":\"failed\"}")),
                )
            )
        )
        val streamed = ready.withStreamingMessages(listOf(failedMessage))
        val locator = ToolCallLocator(assistantId, 0)
        assertEquals(ToolCallPhase.READY, streamed.toolCallPhases[locator])

        val committed = streamed.afterCheckpoint(
            CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.TOOL_RESULT_COMPLETED,
                messages = listOf(failedMessage),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = null,
                toolResults = listOf(ToolResultEvent(assistantId, 0, ToolResultEventStatus.FAILED)),
            )
        )

        assertEquals(ToolCallPhase.FAILED, committed.toolCallPhases[locator])

        val invalidOrdinal = runCatching {
            streamed.afterCheckpoint(
                CommitCheckpoint(
                    handle = handle,
                    kind = CheckpointKind.TOOL_RESULT_COMPLETED,
                    messages = listOf(failedMessage),
                    turnStatus = TurnExecutionStatus.RUNNING,
                    turnReason = null,
                    toolExecution = null,
                    toolResults = listOf(ToolResultEvent(assistantId, 1, ToolResultEventStatus.FAILED)),
                )
            )
        }.exceptionOrNull()
        assertTrue(invalidOrdinal?.message?.contains("missing tool ordinal") == true)

        val execution = ToolExecutionEntity(
            executionId = "execution",
            turnId = handle.turnId.toString(),
            toolOrdinal = 0,
            status = ToolExecutionStatus.COMPLETED,
            reason = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val conflictingStatus = runCatching {
            streamed.afterCheckpoint(
                CommitCheckpoint(
                    handle = handle,
                    kind = CheckpointKind.TOOL_RESULT_COMPLETED,
                    messages = listOf(failedMessage),
                    turnStatus = TurnExecutionStatus.RUNNING,
                    turnReason = null,
                    toolExecution = execution,
                    toolResults = listOf(ToolResultEvent(assistantId, 0, ToolResultEventStatus.FAILED)),
                )
            )
        }.exceptionOrNull()
        assertTrue(conflictingStatus?.message?.contains("conflicting terminal statuses") == true)
    }

    @Test
    fun `second tool failure checkpoint does not change the neighboring tool phase`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val message = UIMessage(
            id = handle.assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(toolCallId = "call-1", toolName = "first", input = "{}"),
                UIMessagePart.Tool(toolCallId = "call-2", toolName = "second", input = "{}"),
            ),
        )
        val first = ToolCallLocator(message.id, 0)
        val second = ToolCallLocator(message.id, 1)
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, listOf(message)))
        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.STEP_COMPLETED,
                messages = listOf(message),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = null,
            )
        )
        val secondExecution = ToolExecutionEntity(
            executionId = "execution-2",
            turnId = handle.turnId.toString(),
            toolOrdinal = 1,
            status = ToolExecutionStatus.STARTED,
            reason = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.TOOL_EXECUTION_STARTED,
                messages = listOf(message),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = secondExecution,
            )
        )
        assertEquals(ToolCallPhase.READY, rt.snapshot.value.activeTurn?.toolCallPhases?.get(first))
        assertEquals(ToolCallPhase.EXECUTING, rt.snapshot.value.activeTurn?.toolCallPhases?.get(second))

        val failedMessage = message.copy(
            parts = message.parts.mapIndexed { index, part ->
                if (index == 1 && part is UIMessagePart.Tool) {
                    part.copy(output = listOf(UIMessagePart.Text("{\"status\":\"failed\"}")))
                } else {
                    part
                }
            }
        )
        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = CheckpointKind.TOOL_RESULT_COMPLETED,
                messages = listOf(failedMessage),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = secondExecution.copy(
                    status = ToolExecutionStatus.FAILED,
                    reason = "provider_error",
                ),
                toolResults = listOf(ToolResultEvent(message.id, 1, ToolResultEventStatus.FAILED)),
            )
        )
        assertEquals(ToolCallPhase.READY, rt.snapshot.value.activeTurn?.toolCallPhases?.get(first))
        assertEquals(ToolCallPhase.FAILED, rt.snapshot.value.activeTurn?.toolCallPhases?.get(second))
        scope.cancel()
    }

    @Test
    fun `multiple approval decisions remain unique across durable tree and active projection`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val waiting = UIMessage(
            id = handle.assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "question",
                    toolName = "ask_user",
                    input = "{}",
                    approvalState = ToolApprovalState.Pending,
                ),
                UIMessagePart.Tool(
                    toolCallId = "image",
                    toolName = "generate_image",
                    input = "{}",
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
        )
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, listOf(waiting)))
        rt.applyCommand(CommitCheckpoint(
                handle = handle,
                kind = net.weero.measix.pilot.data.ai.CheckpointKind.AWAITING_APPROVAL,
                messages = listOf(waiting),
                turnStatus = TurnExecutionStatus.AWAITING_APPROVAL,
                turnReason = null,
                toolExecution = null,
            )
        )
        rt.retainAwaitingApproval(handle)

        val answered = ToolApprovalState.Answered("keep the current assistant")
        val denied = ToolApprovalState.Denied("background change rejected")
        rt.applyCommand(ResolveToolInteraction(waiting.id, 0, ToolUserDecision.Answer("keep the current assistant"), handle))
        rt.applyCommand(ResolveToolInteraction(waiting.id, 1, ToolUserDecision.Deny("background change rejected"), handle))

        val projected = rt.snapshot.value.currentMessages().last().getTools().map { it.approvalState }
        val durable = rt.snapshot.value.nodes.last().currentMessage.getTools().map { it.approvalState }
        assertEquals(listOf(answered, denied), projected)
        assertEquals(projected, durable)
        assertEquals(
            mapOf(
                ToolCallLocator(waiting.id, 0) to ToolCallPhase.ANSWERED,
                ToolCallLocator(waiting.id, 1) to ToolCallPhase.DENIED,
            ),
            rt.snapshot.value.activeTurn?.toolCallPhases,
        )
        assertEquals(handle.turnId, rt.snapshot.value.activeTurn?.turnId)
        scope.cancel()
    }

    @Test
    fun `submit returns new conversation and updates state`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val result = rt.applyCommand(AppendUserMessage(user("hello")))
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
        rt.installActiveRequest(Uuid.random(), firstJob)
        rt.installActiveRequest(Uuid.random(), replacementJob)
        assertSame(replacementJob, rt.currentWorker())

        cleanupGate.complete(Unit)
        firstChild.join()

        assertSame(replacementJob, rt.currentWorker())
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
        rt.installActiveRequest(firstTurn, firstJob)
        rt.installActiveRequest(secondTurn, replacementJob, supersedeReason = "superseded_by_new_turn")

        assertEquals(secondTurn, rt.currentGenerationTurnId())
        assertEquals("superseded_by_new_turn", rt.peekCancelReason(firstTurn))
        assertEquals(null, rt.peekCancelReason(secondTurn))

        cleanupGate.complete(Unit)
        firstChild.join()

        assertSame(replacementJob, rt.currentWorker())
        assertEquals(secondTurn, rt.currentGenerationTurnId())
        assertEquals(null, rt.peekCancelReason(firstTurn))
        rt.requestCancel(firstTurn, "user_stop")
        assertEquals(null, rt.peekCancelReason(firstTurn))
        rt.requestCancel(secondTurn, "user_stop")
        assertEquals("user_stop", rt.peekCancelReason(secondTurn))
        replacementJob.cancel()
    }

    @Test
    fun `presentation retains the released receipt identity for UI wait termination`() = runTest {
        val rt = runtime(this)
        val turnId = Uuid.random()
        val worker = Job()
        rt.installActiveRequest(turnId, worker)

        rt.releaseActiveRequest(turnId, worker)

        val presentation = rt.currentTurnPresentation()
        assertEquals(ConversationTurnPhase.IDLE, presentation.phase)
        assertEquals(turnId, presentation.lastTerminatedRequestTurnId)
    }

    @Test
    fun `superseding request marks the replaced receipt as terminated`() = runTest {
        val rt = runtime(this)
        val firstTurn = Uuid.random()
        val secondTurn = Uuid.random()
        rt.installActiveRequest(firstTurn, Job())
        rt.installActiveRequest(secondTurn, Job())

        assertEquals(firstTurn, rt.currentTurnPresentation().lastTerminatedRequestTurnId)
        assertEquals(secondTurn, rt.currentTurnPresentation().activeRequestTurnId)
    }

}
