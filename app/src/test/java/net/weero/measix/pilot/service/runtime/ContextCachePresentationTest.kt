package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.ProviderRequestOutcome
import net.weero.measix.pilot.data.ai.RequestUsageReducer
import net.weero.measix.pilot.data.ai.TurnUsageAccumulator
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ContextCachePresentationTest {
    @Test
    fun `new turn seeds the last valid pair on its selected branch only`() {
        val selected = assistant(20_000, 15_000)
        val unselected = assistant(90_000, 90_000)
        val branch = MessageNode(messages = listOf(selected, unselected), selectIndex = 0)
        val laterUnknown = assistant(25_000, null)
        val user = UIMessage.user("next").copy(usage = usage(99_000, 99_000))
        val initial = snapshot(branch, MessageNode.of(laterUnknown), MessageNode.of(user))

        val first = start(initial)
        assertEquals(selected.contextCacheDisplay(), first.activeContextCache()?.value)
        assertNull(first.nodes.last().currentMessage.usage)

        val changedBranch = ConversationTransition.apply(initial, SelectNodeVariant(branch.id, 1))
        assertEquals(unselected.contextCacheDisplay(), start(changedBranch).activeContextCache()?.value)
    }

    @Test
    fun `regeneration does not seed an unselected old variant of its new slot`() {
        val earlier = assistant(10_000, 5_000)
        val replaced = assistant(30_000, 29_000)
        val initial = snapshot(
            MessageNode.of(earlier),
            MessageNode.of(UIMessage.user("regenerate")),
            MessageNode.of(replaced),
        )

        val started = start(initial)

        assertEquals(2, started.nodes.last().messages.size)
        assertEquals(1, started.nodes.last().selectIndex)
        assertEquals(earlier.contextCacheDisplay(), started.activeContextCache()?.value)
        assertNull(start(snapshot(MessageNode.of(replaced))).activeContextCache())
    }

    @Test
    fun `unknown requests retain an atomic pair until a valid pair including zero arrives`() = runTest {
        val previous = assistant(20_000, 15_000)
        val rt = runtime(backgroundScope, MessageNode.of(previous), MessageNode.of(UIMessage.user("next")))
        val handle = rt.start()
        val durableNodes = rt.snapshot.value.nodes
        val known = assistant(25_000, 20_000, handle.assistantMessageId)
        rt.applyStreamingDelta(handle, listOf(known))
        assertEquals(known.contextCacheDisplay(), rt.snapshot.value.activeContextCache()?.value)

        listOf(
            usage(40_000, null),
            usage(null, null),
            usage(null, 0),
            usage(0, 0),
            usage(20_000, 20_001),
            usage(20_000, -1),
        ).forEach { unknown ->
            val message = known.copy(usage = unknown)
            rt.applyStreamingDelta(handle, listOf(message))
            assertEquals(known.contextCacheDisplay(), rt.snapshot.value.activeContextCache()?.value)
            assertSame(unknown, rt.snapshot.value.activeTurn?.messages?.last()?.usage)
            assertSame(durableNodes, rt.snapshot.value.nodes)
        }

        val zero = known.copy(usage = usage(30_000, 0))
        rt.applyStreamingDelta(handle, listOf(zero))
        assertEquals(zero.contextCacheDisplay(), rt.snapshot.value.activeContextCache()?.value)
        rt.applyStreamingDelta(handle, listOf(zero.copy(usage = usage(40_000, null))))
        assertEquals(0.0, rt.snapshot.value.activeContextCache()!!.value.cachePercent, 0.0)
    }

    @Test
    fun `real request reducer boundaries separate temporary zero missing usage and final zero`() = runTest {
        val previous = assistant(20_000, 15_000)
        val rt = runtime(backgroundScope, MessageNode.of(previous), MessageNode.of(UIMessage.user("next")))
        val handle = rt.start()
        val accumulator = TurnUsageAccumulator.from(null)
        val message = UIMessage.assistant("reply").copy(id = handle.assistantMessageId)
        val first = RequestUsageReducer(accumulator.nextRequestOrdinal())
        first.accept(ProviderUsageSnapshot(inputTokens = 25_000, outputTokens = 1, cacheReadInputTokens = 0))
        val inFlight = accumulator.preview(first.preview(10))
        rt.applyStreamingDelta(handle, listOf(message.copy(usage = inFlight)))
        assertNull(inFlight.latestRequestCacheReadInputTokens)
        assertEquals(previous.contextCacheDisplay(), rt.snapshot.value.activeContextCache()?.value)

        first.accept(ProviderUsageSnapshot(cacheReadInputTokens = 20_000))
        val firstClosed = accumulator.apply(first.close(ProviderRequestOutcome.COMPLETED, 20)).usage
        rt.applyStreamingDelta(handle, listOf(message.copy(usage = firstClosed)))
        assertEquals(20_000L, rt.snapshot.value.activeContextCache()?.value?.cacheReadInputTokens)
        assertEquals(25_000L, firstClosed.inputTokens)

        val missing = RequestUsageReducer(accumulator.nextRequestOrdinal())
        val missingClosed = accumulator.apply(missing.close(ProviderRequestOutcome.FAILED, 5)).usage
        rt.applyStreamingDelta(handle, listOf(message.copy(usage = missingClosed)))
        assertNull(missingClosed.latestRequestContextTokens)
        assertNull(missingClosed.latestRequestCacheReadInputTokens)
        assertEquals(20_000L, rt.snapshot.value.activeContextCache()?.value?.cacheReadInputTokens)
        assertEquals(2, missingClosed.observedProviderRequestCount)

        val zero = RequestUsageReducer(accumulator.nextRequestOrdinal())
        zero.accept(ProviderUsageSnapshot(inputTokens = 30_000, outputTokens = 1, cacheReadInputTokens = 0))
        rt.applyStreamingDelta(handle, listOf(message.copy(usage = accumulator.preview(zero.preview(10)))))
        assertEquals(20_000L, rt.snapshot.value.activeContextCache()?.value?.cacheReadInputTokens)
        val zeroClosed = accumulator.apply(zero.close(ProviderRequestOutcome.COMPLETED, 20)).usage
        rt.applyStreamingDelta(handle, listOf(message.copy(usage = zeroClosed)))
        assertEquals(0L, rt.snapshot.value.activeContextCache()?.value?.cacheReadInputTokens)
        assertEquals(30_000L, rt.snapshot.value.activeContextCache()?.value?.contextTokens)
        assertEquals(55_000L, zeroClosed.inputTokens)
        assertEquals(3, zeroClosed.observedProviderRequestCount)
    }

    @Test
    fun `checkpoint updates the display without replacing the streaming message or usage`() = runTest {
        val rt = runtime(backgroundScope)
        val handle = rt.start()
        val oldStream = assistant(20_000, 15_000, handle.assistantMessageId)
        rt.applyStreamingDelta(handle, listOf(oldStream))
        val closed = oldStream.copy(usage = usage(30_000, 27_000))

        rt.commit(checkpoint(handle, closed))

        assertEquals(closed.contextCacheDisplay(), rt.snapshot.value.activeContextCache()?.value)
        assertSame(oldStream, rt.snapshot.value.activeTurn?.messages?.last())
        assertSame(closed.usage, rt.snapshot.value.nodes.last().currentMessage.usage)
        val missing = closed.copy(usage = usage(40_000, null))
        rt.commit(checkpoint(handle, missing))
        assertEquals(closed.contextCacheDisplay(), rt.snapshot.value.activeContextCache()?.value)
        assertNull(rt.snapshot.value.nodes.last().currentMessage.usage?.latestRequestCacheReadInputTokens)
    }

    @Test
    fun `checkpoint planned before newer stream observation cannot roll back the pair`() = runTest {
        val rt = runtime(backgroundScope)
        val handle = rt.start()
        val initial = assistant(20_000, 15_000, handle.assistantMessageId)
        rt.applyStreamingDelta(handle, listOf(initial))
        val old = rt.snapshot.value
        val command = checkpoint(handle, initial.copy(usage = usage(25_000, 24_000)))
        val committed = ConversationTransition.apply(old, command)
        val newer = initial.copy(usage = usage(30_000, 0))
        rt.applyStreamingDelta(handle, listOf(newer))
        rt.applyStreamingDelta(handle, listOf(newer.copy(usage = usage(null, null))))

        rt.publishCommitted(old, command, committed)

        assertEquals(newer.contextCacheDisplay(), rt.snapshot.value.activeContextCache()?.value)
        assertNull(rt.snapshot.value.activeTurn?.messages?.last()?.usage?.latestRequestContextTokens)
        assertEquals(25_000L, rt.snapshot.value.nodes.last().currentMessage.usage?.latestRequestContextTokens)
    }

    @Test
    fun `only the owning active assistant can consume a carried value`() {
        val previous = assistant(20_000, 15_000)
        val started = start(snapshot(MessageNode.of(previous), MessageNode.of(UIMessage.user("next"))))
        val projection = resolveConversationPresentation(null, started).activeContextCache!!

        assertEquals(previous.id, projection.value.sourceAssistantMessageId)
        assertEquals(projection.value, projection.forMessage(started.activeTurn!!.assistantMessageId))
        assertNull(projection.forMessage(previous.id))
        assertNull(projection.forMessage(Uuid.random()))
        assertNull(start(snapshot()).activeContextCache())
    }

    @Test
    fun `approval continuation keeps the same owners last valid pair`() = runTest {
        val rt = runtime(backgroundScope)
        val handle = rt.start()
        val worker = Job()
        rt.installActiveRequest(handle.turnId, worker, handle)
        rt.markRunning(handle)
        val known = assistant(20_000, 15_000, handle.assistantMessageId)
        rt.applyStreamingDelta(handle, listOf(known))
        val unknown = known.copy(usage = usage(30_000, null))
        rt.applyStreamingDelta(handle, listOf(unknown))
        rt.commit(checkpoint(handle, unknown).copy(
            kind = CheckpointKind.AWAITING_APPROVAL,
            turnStatus = TurnExecutionStatus.AWAITING_APPROVAL,
        ))
        rt.retainAwaitingApproval(handle)
        worker.complete()
        val continuation = Job()

        rt.continueAwaitingApproval(handle, continuation)
        rt.markRunning(handle)

        assertEquals(handle.epoch, rt.snapshot.value.activeTurn?.epoch)
        assertEquals(known.contextCacheDisplay(), rt.currentTurnPresentation().activeContextCache?.value)
        continuation.cancel()
    }

    @Test
    fun `reentry uses only durable selected values and cannot reconstruct discarded same turn requests`() {
        val previous = assistant(10_000, 5_000)
        val current = assistant(30_000, null)
        val persisted = snapshot(MessageNode.of(previous), MessageNode.of(current))

        val resumed = ConversationTransition.apply(persisted, StartTurn(Uuid.random(), current.id, true, 1))
        assertEquals(previous.contextCacheDisplay(), resumed.activeContextCache()?.value)

        val withoutHistory = snapshot(MessageNode.of(current))
        assertNull(ConversationTransition.apply(
            withoutHistory, StartTurn(Uuid.random(), current.id, true, 1),
        ).activeContextCache())

        val knownCurrent = current.copy(usage = usage(30_000, 0))
        val withKnownCurrent = snapshot(MessageNode.of(previous), MessageNode.of(knownCurrent))
        assertEquals(knownCurrent.contextCacheDisplay(), ConversationTransition.apply(
            withKnownCurrent, StartTurn(Uuid.random(), current.id, true, 1),
        ).activeContextCache()?.value)
    }

    @Test
    fun `all terminal outcomes clear display carry without rewriting unknown usage`() = runTest {
        listOf(
            TurnExecutionStatus.COMPLETED,
            TurnExecutionStatus.CANCELLED,
            TurnExecutionStatus.FAILED,
            TurnExecutionStatus.INCOMPLETE,
            TurnExecutionStatus.INTERRUPTED,
        ).forEach { status ->
            val rt = runtime(backgroundScope)
            val handle = rt.start()
            val known = assistant(20_000, 15_000, handle.assistantMessageId)
            rt.applyStreamingDelta(handle, listOf(known))
            val unknown = known.copy(usage = usage(30_000, null))
            rt.commit(FinalizeTurn(handle, listOf(unknown), status, "test", false))

            assertNull(rt.snapshot.value.activeTurn)
            assertNull(rt.currentTurnPresentation().activeContextCache)
            assertSame(unknown.usage, rt.snapshot.value.nodes.last().currentMessage.usage)
            assertEquals(StreamingDeltaResult.STALE_TURN, rt.applyStreamingDelta(handle, listOf(known)))
            assertNull(rt.snapshot.value.activeContextCache())
        }
    }

    @Test
    fun `cancellation retains the pair until finalization and a replaced owner rejects late usage`() = runTest {
        val rt = runtime(backgroundScope)
        val first = rt.start()
        val worker = Job()
        rt.installActiveRequest(first.turnId, worker, first)
        rt.markRunning(first)
        val known = assistant(20_000, 15_000, first.assistantMessageId)
        rt.applyStreamingDelta(first, listOf(known))
        rt.requestCancel(first.turnId, "user_stop")

        assertTrue(worker.isCancelled)
        assertEquals(known.contextCacheDisplay(), rt.snapshot.value.activeContextCache()?.value)
        rt.commit(FinalizeTurn(first, null, TurnExecutionStatus.CANCELLED, "user_stop", false))
        val second = rt.start()
        assertEquals(StreamingDeltaResult.STALE_TURN, rt.applyStreamingDelta(first, listOf(known)))
        assertEquals(second.assistantMessageId, rt.snapshot.value.activeTurn?.assistantMessageId)
        assertNull(rt.snapshot.value.activeContextCache())
    }

    private fun usage(context: Long?, cache: Long?) = TokenUsage(
        inputTokens = 99_000,
        outputTokens = 1_000,
        latestRequestContextTokens = context,
        latestRequestCacheReadInputTokens = cache,
        observedProviderRequestCount = 3,
    )

    private fun assistant(context: Long?, cache: Long?, id: Uuid = Uuid.random()) =
        UIMessage.assistant("reply").copy(id = id, usage = usage(context, cache))

    private fun snapshot(vararg nodes: MessageNode): ConversationSnapshot =
        Conversation.ofId(Uuid.random(), Uuid.random()).copy(messageNodes = nodes.toList()).toSnapshot()

    private fun start(snapshot: ConversationSnapshot): ConversationSnapshot =
        ConversationTransition.apply(snapshot, StartTurn(Uuid.random(), Uuid.random(), false, 1))

    private fun runtime(scope: CoroutineScope, vararg nodes: MessageNode): ConversationRuntime {
        val initial = snapshot(*nodes)
        return ConversationRuntime(initial.conversationId, initial, scope, onIdle = {})
    }

    private fun ConversationRuntime.start(): TurnHandle {
        commit(StartTurn(Uuid.random(), Uuid.random(), false, nextTurnEpoch()))
        val active = snapshot.value.activeTurn!!
        return TurnHandle(id, active.epoch, active.turnId, active.assistantMessageId)
    }

    private fun ConversationRuntime.commit(command: ConversationCommand) {
        val old = snapshot.value
        publishCommitted(old, command, ConversationTransition.apply(old, command))
    }

    private fun checkpoint(handle: TurnHandle, message: UIMessage) = CommitCheckpoint(
        handle = handle,
        kind = CheckpointKind.STEP_COMPLETED,
        messages = listOf(message),
        turnStatus = TurnExecutionStatus.RUNNING,
        turnReason = null,
        toolExecution = null,
    )
}
