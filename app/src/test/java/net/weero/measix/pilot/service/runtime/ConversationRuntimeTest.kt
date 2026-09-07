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
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.test.testTurnContext
import net.weero.measix.pilot.data.ai.ToolResultFact
import net.weero.measix.pilot.data.ai.request.TurnModelContextProjection
import me.rerere.ai.ui.ToolResultStatus
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.ai.ToolExecutionFact
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.service.applyToolInteractionDecision
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

    // Deterministic call identity so tests can reference a tool by its position within a message.
    private val stepId = Uuid.random()
    private fun stableId(ordinal: Int): Uuid =
        Uuid.parse("00000000-0000-0000-0000-" + ordinal.toLong().toString(16).padStart(12, '0'))
    private fun loc(messageId: Uuid, ordinal: Int) = ToolCallLocator(messageId, stepId, stableId(ordinal))

    private val commandLock = Mutex()

    private suspend fun ConversationRuntime.applyCommand(command: ConversationCommand): ConversationAggregateSnapshot =
        commandLock.withLock {
            val old = snapshot.value.durable
            if (command is StartTurn) {
                val started = command.copy(epoch = nextTurnEpoch())
                validateConversationCommandOwner(id, snapshot.value.stream, started, currentGenerationTurnId())
                val change = ConversationTransition.plan(old, started, old.header.updateAt)
                val snapshot = (change as ConversationChange.Durable).snapshot
                publishCommitted(started, snapshot)
                return@withLock snapshot
            }
            validateConversationCommandOwner(id, snapshot.value.stream, command, currentGenerationTurnId())
            val change = ConversationTransition.plan(old, command, old.header.updateAt)
            val snapshot = (change as ConversationChange.Durable).snapshot
            publishCommitted(command, snapshot)
            snapshot
        }

    private suspend fun ConversationRuntime.startTurn(
        turnId: Uuid,
        assistantMessageId: Uuid,
    ): TurnHandle {
        if (currentGenerationTurnId() != turnId) {
            installTurnWorker(turnId, Job())
        }
        // START 必须锚定真实 USER：空树先落一条用户消息，模拟首个 durable 边界。
        if (snapshot.value.durable.nodes.isEmpty()) {
            applyCommand(AppendUserMessage(user("anchor")))
        }
        applyCommand(
            TurnTransition.buildStartTurnCommand(
                current = snapshot.value.durable,
                turnId = turnId,
                modelContextCandidate = disclosureCandidate(),
                assistantMessageId = assistantMessageId,
            ),
        )
        val active = requireNotNull(snapshot.value.stream)
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
        assertEquals(n, rt.snapshot.value.durable.nodes.size)
        // 每条用户消息都保留
        val texts = rt.snapshot.value.durable.nodes.map { it.messages.single().toText() }
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
        // applyStreamingDelta 直接更新流式投影（无 DB 调用，纯内存）
        rt.applyStreamingDelta(handle, streamingText)
        // currentMessages() 读取入口能看到流式内容（流式投影覆盖末节点）
        val last = rt.snapshot.value.toPresentationSnapshot().currentMessages().lastOrNull()
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
        assertEquals(2, rt.snapshot.value.durable.nodes.size)
        assertSame(userNode, rt.snapshot.value.durable.nodes[0])
        assertEquals(MessageRole.ASSISTANT, rt.snapshot.value.durable.nodes[1].messages.single().role)

        rt.applyStreamingDelta(
            handle,
            UIMessage(id = assistantId, role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("delta"))),
        )
        // currentMessages 读取入口：流式投影覆盖末节点，未污染用户节点
        val current = rt.snapshot.value.toPresentationSnapshot().currentMessages()
        assertEquals(MessageRole.USER, current[0].role)
        assertEquals("child task", current[0].toText())
        assertEquals(MessageRole.ASSISTANT, current.last().role)
        assertEquals("delta", current.last().toText())
        assertSame(userNode, rt.snapshot.value.durable.nodes[0])
        scope.cancel()
    }

    @Test
    fun `header commit racing a stream preserves both projections`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val old = rt.snapshot.value.durable
        val command = UpdateHeader(title = "renamed")
        val committed = (ConversationTransition.plan(old, command, old.header.updateAt) as ConversationChange.Durable).snapshot
        val delta = UIMessage.assistant("latest").copy(id = handle.assistantMessageId)
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, delta))
        rt.publishCommitted(command, committed)

        assertEquals("renamed", rt.snapshot.value.durable.header.title)
        assertEquals("latest", rt.snapshot.value.toPresentationSnapshot().currentMessages().last().toText())
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
        rt.applyCommand(FinalizeTurn(first, null, TurnExecutionStatus.COMPLETED, null))
        val second = rt.startTurn(Uuid.random(), Uuid.random())

        assertEquals(
            StreamingDeltaResult.STALE_TURN,
            rt.applyStreamingDelta(first, UIMessage.assistant("stale")),
        )
        val current = UIMessage.assistant("current").copy(id = second.assistantMessageId)
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(second, current))
        assertEquals("current", rt.snapshot.value.toPresentationSnapshot().currentMessages().last().toText())
        scope.cancel()
    }

    @Test
    fun `pin command preserves the active turn owner`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())

        rt.applyCommand(TogglePinned)

        assertTrue(rt.snapshot.value.durable.header.isPinned)
        assertEquals(handle.turnId, rt.snapshot.value.stream?.turnId)
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
        val context = testTurnContext(
            settings = Settings(providers = listOf(provider), assistants = listOf(assistant)),
            model = model,
            assistant = assistant,
        )

        rt.bindTurnContext(turnId, initialWorker, context)
        val projection = TurnModelContextProjection(entries = emptyList(), locators = emptyMap())
        rt.bindModelContextProjection(turnId, initialWorker, projection)
        assertSame(context, rt.requireTurnContext(turnId, initialWorker))
        assertSame(projection, rt.requireTurnModelContextProjection(turnId, initialWorker))
        assertThrows(IllegalStateException::class.java) {
            rt.bindTurnContext(turnId, initialWorker, context)
        }
        assertThrows(IllegalStateException::class.java) {
            rt.bindModelContextProjection(turnId, initialWorker, projection)
        }
        assertThrows(IllegalStateException::class.java) {
            rt.requireTurnContext(Uuid.random(), initialWorker)
        }
        assertThrows(IllegalStateException::class.java) {
            rt.requireTurnContext(turnId, Job())
        }

        rt.retainAwaitingUser(handle)
        val continuationWorker = Job()
        rt.continueAwaitingUser(handle, continuationWorker)
        assertSame(context, rt.requireTurnContext(turnId, continuationWorker))
        assertSame(projection, rt.requireTurnModelContextProjection(turnId, continuationWorker))
        scope.cancel()
    }

    @Test
    fun `cancelled Child owner releases only by exact turn and worker identity`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val turnId = Uuid.random()
        val worker = Job()
        rt.installTurnWorker(turnId, worker)
        val model = Model(modelId = "model", displayName = "Model")
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        val assistant = Assistant(enableMemory = false)
        val context = testTurnContext(
            settings = Settings(providers = listOf(provider), assistants = listOf(assistant)),
            model = model,
            assistant = assistant,
        )
        rt.bindTurnContext(turnId, worker, context)
        rt.requestCancel(turnId, "target_access_revoked")
        assertEquals(TurnLivePhase.STOPPING, rt.currentTurnPresentation().phase)

        rt.releaseTurnWorker(turnId, Job(), retainAwaitingOwner = false)
        assertSame(context, rt.requireTurnContext(turnId, worker))
        rt.releaseTurnWorker(turnId, worker, retainAwaitingOwner = false)
        assertEquals(null, rt.currentWorker())
        scope.cancel()
    }

    @Test
    fun `approval continuation fails closed when request context is missing`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        rt.retainAwaitingUser(handle)

        assertThrows(IllegalArgumentException::class.java) {
            rt.continueAwaitingUser(handle, Job())
        }
        scope.cancel()
    }

    @Test
    fun `approval continuation fails closed when model context projection is missing`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val turnId = Uuid.random()
        val handle = rt.startTurn(turnId, Uuid.random())
        val worker = requireNotNull(rt.currentWorker())
        val model = Model(modelId = "model", displayName = "Model")
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        val assistant = Assistant(enableMemory = false)
        rt.bindTurnContext(
            turnId,
            worker,
            testTurnContext(
                settings = Settings(providers = listOf(provider), assistants = listOf(assistant)),
                model = model,
                assistant = assistant,
            ),
        )
        rt.retainAwaitingUser(handle)

        assertThrows(IllegalArgumentException::class.java) {
            rt.continueAwaitingUser(handle, Job())
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
                    localCallId = stableId(0), stepId = stepId, providerCallId = "approval",
                    toolName = "approval_tool",
                    input = "{}",
                    interactionState = ToolInteractionState.AwaitingApproval,
                )
            ),
        )
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, waiting))
        rt.applyCommand(ModelResponseCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = waiting,
                turnStatus = TurnExecutionStatus.AWAITING_USER,
            )
        )
        rt.retainAwaitingUser(handle)
        assertEquals(TurnLivePhase.AWAITING_USER, rt.currentTurnPresentation().phase)

        rt.applyCommand(ResolveToolInteraction(
                messageId = waiting.id,
                stepId = stepId, localCallId = stableId(0),
                decision = ToolInteractionDecision.Approve,
                handle = handle,
            )
        )

        assertEquals(handle.turnId, rt.snapshot.value.stream?.turnId)
        assertEquals(
            ToolInteractionState.Approved,
            rt.snapshot.value.toPresentationSnapshot().currentMessages().last().getTools().single().interactionState,
        )
        assertEquals(
            ToolInteractionState.Approved,
            rt.snapshot.value.durable.nodes.last().currentMessage.getTools().single().interactionState,
        )
        assertEquals(
            ToolLivePhase.READY,
            rt.snapshot.value.stream?.toolLivePhases?.get(loc(waiting.id, 0)),
        )
        assertEquals(TurnLivePhase.AWAITING_USER, rt.currentTurnPresentation().phase)
        rt.markRunning(handle)
        assertEquals(TurnLivePhase.PREPARING, rt.currentTurnPresentation().phase)
        scope.cancel()
    }

    /**
     * 回归（2026-9-2 15:01）：生产里 HITL 的真实顺序是"流式先投递 Auto 版本，AWAITING_APPROVAL
     * checkpoint 才带来 Pending 版本"。afterCheckpoint 此前只同步 toolLivePhases，流式投影
     * 的 assistantMessage 停留在 Auto 版本，而 currentMessages() 末条取自 stream.assistantMessage，
     * applyToolInteractionDecision 因此读到非 Pending 并抛 "tool interaction is no longer pending"。
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
                    localCallId = stableId(0), stepId = stepId, providerCallId = "approval",
                    toolName = "approval_tool",
                    input = "{}",
                    // 流式版本：provider 尚未落 HITL 状态，工具仍是 Auto。
                    interactionState = ToolInteractionState.NotRequired,
                )
            ),
        )
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, streamed))

        val pending = streamed.copy(
            parts = listOf(
                (streamed.parts.single() as UIMessagePart.Tool).copy(
                    interactionState = ToolInteractionState.AwaitingApproval,
                )
            ),
        )
        rt.applyCommand(ModelResponseCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = pending,
                turnStatus = TurnExecutionStatus.AWAITING_USER,
            )
        )
        rt.retainAwaitingUser(handle)

        val locator = loc(handle.assistantMessageId, 0)
        // 决策路径读到的必须是 checkpoint 提交的 Pending 版本，而不是流式的 Auto 版本。
        assertEquals(
            ToolInteractionState.AwaitingApproval,
            rt.snapshot.value.toPresentationSnapshot().currentMessages().last().getTools().single().interactionState,
        )
        assertEquals(
            ToolLivePhase.AWAITING_APPROVAL,
            rt.snapshot.value.stream?.toolLivePhases?.get(locator),
        )

        // 走与生产同一条 applyToolInteractionDecision：非 Pending 会在定位阶段就抛冲突异常，
        // 能提交即证明投影已对齐 checkpoint 的 committed 版本。
        val submitted = mutableListOf<ResolveToolInteraction>()
        var continuedTurnId: Uuid? = null
        applyToolInteractionDecision(
            locator = locator,
            decision = ToolInteractionDecision.Approve,
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
            ToolInteractionState.Approved,
            rt.snapshot.value.toPresentationSnapshot().currentMessages().last().getTools().single().interactionState,
        )
        assertEquals(
            ToolInteractionState.Approved,
            rt.snapshot.value.durable.nodes.last().currentMessage.getTools().single().interactionState,
        )
        assertEquals(ToolLivePhase.READY, rt.snapshot.value.stream?.toolLivePhases?.get(locator))
        assertEquals(handle.turnId, rt.snapshot.value.stream?.turnId)
        assertEquals(handle.turnId, continuedTurnId)
        scope.cancel()
    }

    @Test
    fun `same-worker approval wait resumes without replacing the active turn`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val waiting = UIMessage(
            id = handle.assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = stableId(0), stepId = stepId, providerCallId = "ask",
                    toolName = "ask_user",
                    input = "{}",
                    interactionState = ToolInteractionState.AwaitingApproval,
                )
            ),
        )
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, waiting))
        rt.applyCommand(ModelResponseCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = waiting,
                turnStatus = TurnExecutionStatus.AWAITING_USER,
            )
        )
        val worker = requireNotNull(rt.currentWorker())
        rt.retainAwaitingUser(handle)
        assertEquals(TurnLivePhase.AWAITING_USER, rt.currentTurnPresentation().phase)
        assertSame(worker, rt.currentWorker())
        assertEquals(handle.turnId, rt.currentGenerationTurnId())

        rt.applyCommand(ResolveToolInteraction(
                messageId = waiting.id,
                stepId = stepId, localCallId = stableId(0),
                decision = ToolInteractionDecision.Answer("keep going"),
                handle = handle,
            )
        )
        rt.markRunning(handle)
        assertEquals(TurnLivePhase.PREPARING, rt.currentTurnPresentation().phase)
        assertSame(worker, rt.currentWorker())
        assertEquals(handle.turnId, rt.snapshot.value.stream?.turnId)
        scope.cancel()
    }

    @Test
    fun `loop live phase advances the session and respects stopping and worker identity`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val identity = System.identityHashCode(requireNotNull(rt.currentWorker()))

        rt.updateLivePhase(handle.turnId, identity, TurnLivePhase.MODEL_WAITING)
        assertEquals(TurnLivePhase.MODEL_WAITING, rt.currentTurnPresentation().phase)
        // 非当前 worker 的迟到阶段事件被忽略，不推进。
        rt.updateLivePhase(handle.turnId, identity + 1, TurnLivePhase.TOOL_EXECUTING)
        assertEquals(TurnLivePhase.MODEL_WAITING, rt.currentTurnPresentation().phase)
        rt.updateLivePhase(handle.turnId, identity, TurnLivePhase.TOOL_PREPARING)
        assertEquals(TurnLivePhase.TOOL_PREPARING, rt.currentTurnPresentation().phase)
        rt.updateLivePhase(handle.turnId, identity, TurnLivePhase.TOOL_EXECUTING)
        assertEquals(TurnLivePhase.TOOL_EXECUTING, rt.currentTurnPresentation().phase)
        // 取消是终态方向：loop 阶段不得把 STOPPING 拉回生成态。
        rt.captureAndRequestStop("user_stop")
        assertEquals(TurnLivePhase.STOPPING, rt.currentTurnPresentation().phase)
        rt.updateLivePhase(handle.turnId, identity, TurnLivePhase.MODEL_STREAMING)
        assertEquals(TurnLivePhase.STOPPING, rt.currentTurnPresentation().phase)
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
                    localCallId = stableId(1), stepId = stepId, providerCallId = "image",
                    toolName = "generate_image",
                    input = "{}",
                    interactionState = ToolInteractionState.AwaitingApproval,
                ),
            ),
        )
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, waiting))
        rt.applyCommand(ModelResponseCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = waiting,
                turnStatus = TurnExecutionStatus.AWAITING_USER,
            )
        )

        val backfills = AttachmentRefs.planBackfills(rt.snapshot.value.durable.nodes)
        assertEquals(1, backfills.size)
        val failure = runCatching {
            rt.applyCommand(BackfillAttachmentRefs(backfills))
        }.exceptionOrNull()

        assertTrue(failure is ConversationCommandConflictException)
        assertTrue(failure?.message?.contains("requires the active turn to finish first") == true)
        assertEquals(null, AttachmentRefs.getRef(rt.snapshot.value.durable.nodes.first().currentMessage.parts.single()))
        assertEquals(
            ToolInteractionState.AwaitingApproval,
            rt.snapshot.value.stream?.assistantMessage?.getTools()?.single()?.interactionState,
        )
        assertEquals(handle.turnId, rt.snapshot.value.stream?.turnId)
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
        val backfills = AttachmentRefs.planBackfills(rt.snapshot.value.durable.nodes)

        rt.applyCommand(BackfillAttachmentRefs(backfills))

        assertNotNull(AttachmentRefs.getRef(rt.snapshot.value.durable.nodes.single().currentMessage.parts.single()))
        assertEquals(null, rt.snapshot.value.stream)
        scope.cancel()
    }

    @Test
    fun `tool call assembly and durable execution checkpoints have distinct phases`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val handle = rt.startTurn(Uuid.random(), Uuid.random())
        val locator = loc(handle.assistantMessageId, 0)
        val assembling = UIMessage(
            id = handle.assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = stableId(0), stepId = stepId, providerCallId = "call-1",
                    toolName = "generate_image",
                    input = "{\"prompt\":\"sky",
                ),
            ),
        )

        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, assembling))
        assertEquals(ToolLivePhase.CALL_STREAMING, rt.snapshot.value.stream?.toolLivePhases?.get(locator))

        val ready = assembling.copy(
            parts = listOf((assembling.parts.single() as UIMessagePart.Tool).copy(input = "{\"prompt\":\"sky\"}")),
        )
        rt.applyCommand(ModelResponseCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = ready,
                turnStatus = TurnExecutionStatus.RUNNING,
            )
        )
        assertEquals(ToolLivePhase.READY, rt.snapshot.value.stream?.toolLivePhases?.get(locator))

        val startedFact = ToolExecutionFact(
            executionId = "execution-1",
            assistantMessageId = handle.assistantMessageId,
            stepId = stepId,
            localCallId = stableId(0),
            providerCallId = "call-1",
            toolName = "generate_image",
            status = ToolExecutionStatus.STARTED,
        )
        rt.applyCommand(ToolExecutionStartedCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = ready,
                toolExecution = startedFact,
            )
        )
        assertEquals(ToolLivePhase.EXECUTING, rt.snapshot.value.stream?.toolLivePhases?.get(locator))

        val result = ready.copy(
            parts = listOf(
                (ready.parts.single() as UIMessagePart.Tool).copy(
                    output = listOf(UIMessagePart.Text("{\"status\":\"completed\"}")),
                ),
            ),
        )
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, result))
        assertEquals(
            ToolLivePhase.EXECUTING,
            rt.snapshot.value.stream?.toolLivePhases?.get(locator),
        )

        rt.applyCommand(ToolResultCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = result,
                toolExecution = startedFact.copy(status = ToolExecutionStatus.COMPLETED),
                toolResults = listOf(ToolResultFact(loc(result.id, 0), ToolResultStatus.COMPLETED)),
            )
        )
        assertEquals(ToolLivePhase.COMPLETED, rt.snapshot.value.stream?.toolLivePhases?.get(locator))
        scope.cancel()
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
                UIMessagePart.Tool(localCallId = stableId(0), stepId = stepId, providerCallId = "call-1", toolName = "first", input = "{}"),
                UIMessagePart.Tool(localCallId = stableId(1), stepId = stepId, providerCallId = "call-2", toolName = "second", input = "{}"),
            ),
        )
        val first = loc(message.id, 0)
        val second = loc(message.id, 1)
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, message))
        rt.applyCommand(ModelResponseCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = message,
                turnStatus = TurnExecutionStatus.RUNNING,
            )
        )
        val secondExecution = ToolExecutionFact(
            executionId = "execution-2",
            assistantMessageId = handle.assistantMessageId,
            stepId = stepId,
            localCallId = stableId(1),
            providerCallId = "call-2",
            toolName = "second",
            status = ToolExecutionStatus.STARTED,
        )
        rt.applyCommand(ToolExecutionStartedCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = message,
                toolExecution = secondExecution,
            )
        )
        assertEquals(ToolLivePhase.READY, rt.snapshot.value.stream?.toolLivePhases?.get(first))
        assertEquals(ToolLivePhase.EXECUTING, rt.snapshot.value.stream?.toolLivePhases?.get(second))

        val failedMessage = message.copy(
            parts = message.parts.mapIndexed { index, part ->
                if (index == 1 && part is UIMessagePart.Tool) {
                    part.copy(output = listOf(UIMessagePart.Text("{\"status\":\"failed\"}")))
                } else {
                    part
                }
            }
        )
        rt.applyCommand(ToolResultCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = failedMessage,
                toolExecution = secondExecution.copy(
                    status = ToolExecutionStatus.FAILED,
                ),
                toolResults = listOf(ToolResultFact(loc(message.id, 1), ToolResultStatus.FAILED)),
            )
        )
        assertEquals(ToolLivePhase.READY, rt.snapshot.value.stream?.toolLivePhases?.get(first))
        assertEquals(ToolLivePhase.FAILED, rt.snapshot.value.stream?.toolLivePhases?.get(second))
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
                    localCallId = stableId(0), stepId = stepId, providerCallId = "question",
                    toolName = "ask_user",
                    input = "{}",
                    interactionState = ToolInteractionState.AwaitingInput,
                ),
                UIMessagePart.Tool(
                    localCallId = stableId(1), stepId = stepId, providerCallId = "image",
                    toolName = "generate_image",
                    input = "{}",
                    interactionState = ToolInteractionState.AwaitingApproval,
                ),
            ),
        )
        assertEquals(StreamingDeltaResult.APPLIED, rt.applyStreamingDelta(handle, waiting))
        rt.applyCommand(ModelResponseCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = waiting,
                turnStatus = TurnExecutionStatus.AWAITING_USER,
            )
        )
        rt.retainAwaitingUser(handle)

        val answered = ToolInteractionState.Answered("keep the current assistant")
        val denied = ToolInteractionState.Denied("background change rejected")
        rt.applyCommand(ResolveToolInteraction(waiting.id, stepId, stableId(0), ToolInteractionDecision.Answer("keep the current assistant"), handle))
        rt.applyCommand(ResolveToolInteraction(waiting.id, stepId, stableId(1), ToolInteractionDecision.Deny("background change rejected"), handle))

        val projected = rt.snapshot.value.toPresentationSnapshot().currentMessages().last().getTools().map { it.interactionState }
        val durable = rt.snapshot.value.durable.nodes.last().currentMessage.getTools().map { it.interactionState }
        assertEquals(listOf(answered, denied), projected)
        assertEquals(projected, durable)
        assertEquals(
            mapOf(
                loc(waiting.id, 0) to ToolLivePhase.ANSWERED,
                loc(waiting.id, 1) to ToolLivePhase.DENIED,
            ),
            rt.snapshot.value.stream?.toolLivePhases,
        )
        assertEquals(handle.turnId, rt.snapshot.value.stream?.turnId)
        scope.cancel()
    }

    @Test
    fun `submit returns new conversation and updates state`() = runTest {
        val scope = CoroutineScope(Job())
        val rt = runtime(scope)
        val result = rt.applyCommand(AppendUserMessage(user("hello")))
        assertEquals(1, result.nodes.size)
        assertEquals(1, rt.snapshot.value.durable.nodes.size)
        assertEquals(rt.snapshot.value.durable, result)
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
        rt.installTurnWorker(Uuid.random(), firstJob)
        rt.installTurnWorker(Uuid.random(), replacementJob)
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
        rt.installTurnWorker(firstTurn, firstJob)
        rt.installTurnWorker(secondTurn, replacementJob, supersedeReason = "superseded_by_new_turn")

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
        rt.installTurnWorker(turnId, worker)

        rt.releaseTurnWorker(turnId, worker)

        val presentation = rt.currentTurnPresentation()
        assertEquals(null, presentation.phase)
        assertEquals(turnId, presentation.lastTerminatedRequestTurnId)
    }

    @Test
    fun `superseding request marks the replaced receipt as terminated`() = runTest {
        val rt = runtime(this)
        val firstTurn = Uuid.random()
        val secondTurn = Uuid.random()
        rt.installTurnWorker(firstTurn, Job())
        rt.installTurnWorker(secondTurn, Job())

        assertEquals(firstTurn, rt.currentTurnPresentation().lastTerminatedRequestTurnId)
        assertEquals(secondTurn, rt.currentTurnPresentation().activeTurnId)
    }

}
