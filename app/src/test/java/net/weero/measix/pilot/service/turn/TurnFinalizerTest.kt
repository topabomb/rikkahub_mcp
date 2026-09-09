package net.weero.measix.pilot.service.turn

import me.rerere.common.configuration.ConfigurationReference

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
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
import me.rerere.ai.ui.ToolInteractionState
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.TurnStreamProjection
import net.weero.measix.pilot.service.runtime.ConversationChange
import net.weero.measix.pilot.service.runtime.ConversationCommand
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.StartTurn
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.TurnLivePhase
import net.weero.measix.pilot.service.runtime.currentTurnPresentation
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnFinalizerTest {
    @Test
    fun `child commit failure and timeout propagate infrastructure without staging parent metadata`() = runTest {
        for (timeout in listOf(false, true)) {
            val childId = Uuid.random()
            val turnId = Uuid.random()
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(net.weero.measix.pilot.service.runtime.TurnTransition.openStep(0)))
            val child = Conversation.ofId(childId).copy(messageNodes = listOf(MessageNode.of(message))).toSnapshot()
            val repository = mockk<ConversationRepository>(relaxed = true)
            val coordinator = mockk<ConversationCommandCoordinator>()
            val runtime = mockk<ConversationRuntime>()
            io.mockk.every { runtime.snapshot } returns kotlinx.coroutines.flow.MutableStateFlow(
                ConversationRuntimeSnapshot(child, TurnStreamProjection(1, turnId, message.id, message)),
            )
            coEvery { repository.getConversationHeader(childId) } returns child.header
            coEvery { coordinator.load(childId) } returns runtime
            coEvery { coordinator.executeOrThrow(childId, any()) } coAnswers {
                if (timeout) kotlinx.coroutines.awaitCancellation() else error("child database commit failed")
            }
            var metadataStaged = false
            val context = me.rerere.ai.core.ToolExecutionContext(
                locator = me.rerere.ai.core.ToolCallLocator(Uuid.random(), Uuid.random(), Uuid.random()),
                providerCallId = "parent-call", reportMetadata = { _, _ -> metadataStaged = true },
                resolveAttachments = { me.rerere.ai.core.ToolAttachmentResolution() }, reportChildRun = {},
                registerUnpublishedResource = {},
            )
            val finalizer = TurnFinalizer(repository, mockk(relaxed = true), coordinator, Json)
            val terminal = net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata("run", ConfigurationReference.random(), "Child")
                .copy(state = net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState.STOPPED)
            val failure = runCatching {
                finalizer.finalizeSubAssistantRun(childId, turnId, "user_stop", context, terminal)
            }.exceptionOrNull()
            assertTrue(failure is net.weero.measix.pilot.data.ai.tools.ToolRuntimeInfrastructureException)
            org.junit.Assert.assertFalse(metadataStaged)
            assertEquals(null, runtime.snapshot.value.durable.currentMessages().last().parts.filterIsInstance<UIMessagePart.Step>().single().outcome)
        }
    }

    @Test
    fun `failure preparation marks only unpersisted base64 and preserves published file parts`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val localImage = UIMessagePart.Image("file:///upload/published.png")
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            localImage,
            UIMessagePart.Image("data:image/png;base64,unfinished"),
            UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "image_tool", input = "{}", output = listOf(
                localImage,
                UIMessagePart.Image("data:image/png;base64,unfinished-tool-image"),
            ), resultStatus = me.rerere.ai.ui.ToolResultStatus.COMPLETED),
        ))
        val base = Conversation.ofId(conversationId).copy(messageNodes = listOf(MessageNode.of(assistant))).toSnapshot()
        val handle = TurnHandle(conversationId, 1, turnId, assistant.id)
        val snapshot = ConversationRuntimeSnapshot(
            durable = base,
            stream = TurnStreamProjection(1, turnId, assistant.id, assistant),
        )
        val finalization = TurnFinalizer(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), Json)

        val prepared = finalization.prepareOwnedAssistantForFailure(
            snapshot, handle, assistant, "user_stop",
        )!!

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
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "streamed-tool",
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
        val snapshot = ConversationRuntimeSnapshot(
            durable = base,
            stream = TurnStreamProjection(
                epoch = handle.epoch,
                turnId = turnId,
                assistantMessageId = durableAssistant.id,
                assistantMessage = streamedAssistant,
            ),
        )
        val finalization = TurnFinalizer(
            conversationRepository = mockk(relaxed = true),
            runtimeRegistry = mockk(relaxed = true),
            commandCoordinator = mockk(relaxed = true),
            json = Json,
        )

        val preparedAssistant = finalization.prepareOwnedAssistantForFailure(
            snapshot = snapshot,
            handle = handle,
            latestAssistant = streamedAssistant,
            reason = "provider_error",

        )!!

        assertEquals(
            "checkpoint + after checkpoint",
            (preparedAssistant.parts.first() as UIMessagePart.Text).text,
        )
        assertTrue(preparedAssistant.getTools().single().hasReplayResult)

        val staleFailure = runCatching {
            finalization.prepareOwnedAssistantForFailure(
                snapshot = snapshot,
                handle = handle.copy(epoch = handle.epoch + 1),
                latestAssistant = streamedAssistant,
                reason = "provider_error",

            )
        }.exceptionOrNull()
        assertTrue(staleFailure is IllegalArgumentException)
    }

    @Test
    fun `failure preparation closes a pending tool with interrupted output`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val pendingTool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
            toolName = "shell", input = "{}",
            interactionState = ToolInteractionState.AwaitingApproval,
        )
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("pre"), pendingTool),
        )
        val base = Conversation.ofId(conversationId).copy(messageNodes = listOf(MessageNode.of(assistant))).toSnapshot()
        val handle = TurnHandle(conversationId, 1, turnId, assistant.id)
        val snapshot = ConversationRuntimeSnapshot(
            durable = base,
            stream = TurnStreamProjection(1, turnId, assistant.id, assistant),
        )
        val finalization = TurnFinalizer(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), Json)

        val prepared = finalization.prepareOwnedAssistantForFailure(
            snapshot, handle, assistant, "provider_error",
        )!!

        // interrupt 语义：写入 interrupted output（hasReplayResult=true），保留原 interactionState
        val closedTool = prepared.getTools().single()
        assertTrue(closedTool.hasReplayResult)
        val output = closedTool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(output.contains("interrupted"))
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
            initial = base,
            scope = this,
            onIdle = {},
        )
        runtime.seedActiveTurn(epoch = 9, turnId = newerTurnId, assistantMessageId = newerAssistant.id)
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
        coEvery { coordinator.withResidentRuntime<Any?>(conversationId, any()) } coAnswers {
            secondArg<suspend (ConversationRuntime?) -> Any?>()(runtime)
        }

        TurnFinalizer(
            conversationRepository = repository,
            runtimeRegistry = mockk(relaxed = true),
            commandCoordinator = coordinator,
            json = Json,
        ).finalizeChild(conversationId, staleTurnId, "late_cleanup")

        coVerify(exactly = 0) { coordinator.executeOrThrow(any(), any()) }
        assertEquals(newerTurnId, runtime.snapshot.value.stream?.turnId)
    }

    @Test
    fun `late cancellation never overwrites a completed turn`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val runtime = mockk<ConversationRuntime>(relaxed = true)
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        coEvery { coordinator.load(conversationId) } returns runtime
        coEvery { coordinator.withResidentRuntime<Any?>(conversationId, any()) } coAnswers {
            secondArg<suspend (ConversationRuntime?) -> Any?>()(runtime)
        }
        coEvery { repository.getTurnExecution(turnId.toString()) } returns TurnExecutionEntity(
            turnId = turnId.toString(),
            conversationId = conversationId.toString(),
            assistantMessageId = Uuid.random().toString(),
            status = TurnExecutionStatus.COMPLETED,
            reason = null,
            createdAt = 1,
            updatedAt = 2,
        )

        TurnFinalizer(
            conversationRepository = repository,
            runtimeRegistry = mockk(relaxed = true),
            commandCoordinator = coordinator,
            json = Json,
        ).finalizeSupersededTurn(conversationId, turnId)

        coVerify(exactly = 0) { coordinator.executeOrThrow(any(), any()) }
    }

    @Test
    fun `resource barrier finalizes a completed approval-paused worker and awaits its MCP cleanup`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val step = net.weero.measix.pilot.service.runtime.TurnTransition.openStep(0)
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                step,
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = step.stepId, providerCallId = "pending-call",
                    toolName = "pending_tool",
                    interactionState = ToolInteractionState.AwaitingApproval,
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
        val runtime = ConversationRuntime(
            id = conversationId,
            initial = base,
            scope = this,
            onIdle = {},
        )
        runtime.seedActiveTurn(epoch = 7, turnId = turnId, assistantMessageId = assistant.id)
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        val command = slot<ConversationCommand>()
        coEvery { coordinator.load(conversationId) } returns runtime
        coEvery { coordinator.withResidentRuntime<Any?>(conversationId, any()) } coAnswers {
            secondArg<suspend (ConversationRuntime?) -> Any?>()(runtime)
        }
        coEvery { repository.getTurnExecution(turnId.toString()) } returns TurnExecutionEntity(
            turnId = turnId.toString(),
            conversationId = conversationId.toString(),
            assistantMessageId = assistant.id.toString(),
            status = TurnExecutionStatus.AWAITING_USER,
            reason = null,
            createdAt = 1,
            updatedAt = 2,
        )
        coEvery { coordinator.executeOrThrow(conversationId, capture(command)) } coAnswers {
            val captured = command.captured
            val old = runtime.durable
            val change = ConversationTransition.plan(old, captured, old.header.updateAt)
            runtime.publishCommitted(captured, (change as ConversationChange.Durable).snapshot)
        }

        coEvery { coordinator.withResidentRuntime<Any?>(conversationId, any()) } coAnswers {
            secondArg<suspend (ConversationRuntime?) -> Any?>()(runtime)
        }

        val worker = kotlinx.coroutines.Job()
        runtime.installTurnWorker(turnId, worker)
        val cleanupStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finishCleanup = kotlinx.coroutines.CompletableDeferred<Unit>()
        runtime.bindMcpExecution(turnId, worker, runtime.durable.header.assistantId,
            net.weero.measix.pilot.data.ai.mcp.McpExecutionLease {
                cleanupStarted.complete(Unit)
                finishCleanup.await()
            })
        runtime.retainAwaitingUser(TurnHandle(conversationId, 7, turnId, assistant.id))
        assertEquals(TurnLivePhase.AWAITING_USER, runtime.currentTurnPresentation().phase)

        worker.complete()
        val registry = mockk<ConversationRuntimeRegistry>()
        io.mockk.every { registry.findRuntime(conversationId) } returns runtime
        val finalizer = TurnFinalizer(
            conversationRepository = repository,
            runtimeRegistry = registry,
            commandCoordinator = coordinator,
            json = Json,
        )
        val stop = async { finalizer.stopInteraction(runtime, turnId, "managed_snapshot_required") }
        try {
            cleanupStarted.await()
            assertFalse(stop.isCompleted)
            assertNull(runtime.snapshot.value.stream)
        } finally { finishCleanup.complete(Unit) }
        stop.await()

        coVerify(exactly = 1) { coordinator.executeOrThrow(conversationId, any()) }
        val finalize = command.captured as FinalizeTurn
        assertEquals(TurnExecutionStatus.CANCELLED, finalize.terminalStatus)
        assertEquals(turnId, finalize.handle.turnId)
        assertEquals(7, finalize.handle.epoch)
        assertEquals(null, runtime.currentTurnPresentation().phase)
        assertNull(runtime.currentGenerationTurnId())
    }

    @Test
    fun `partial text turn without tools is also cancelled before replacement`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val assistant = UIMessage.assistant("partial answer").let {
            it.copy(parts = listOf(net.weero.measix.pilot.service.runtime.TurnTransition.openStep(0)) + it.parts)
        }
        val base = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(
                MessageNode.of(UIMessage.user("question")),
                MessageNode.of(assistant),
            ),
        ).toSnapshot()
        val runtime = ConversationRuntime(
            id = conversationId,
            initial = base,
            scope = this,
            onIdle = {},
        )
        runtime.seedActiveTurn(epoch = 4, turnId = turnId, assistantMessageId = assistant.id)
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        val command = slot<ConversationCommand>()
        coEvery { coordinator.load(conversationId) } returns runtime
        coEvery { coordinator.withResidentRuntime<Any?>(conversationId, any()) } coAnswers {
            secondArg<suspend (ConversationRuntime?) -> Any?>()(runtime)
        }
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

        TurnFinalizer(
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

    @Test
    fun `late stop cannot finalize or release a replacement worker with the same turn id`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val assistant = UIMessage.assistant("partial")
        val runtime = ConversationRuntime(conversationId,
            Conversation.ofId(conversationId).copy(messageNodes = listOf(MessageNode.of(assistant))).toSnapshot(), this, {})
        runtime.seedActiveTurn(1, turnId, assistant.id)
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        val registry = mockk<ConversationRuntimeRegistry>()
        io.mockk.every { registry.findRuntime(conversationId) } returns runtime
        coEvery { coordinator.withResidentRuntime<Any?>(conversationId, any()) } coAnswers {
            secondArg<suspend (ConversationRuntime?) -> Any?>()(runtime)
        }
        coEvery { repository.getTurnExecution(turnId.toString()) } returns TurnExecutionEntity(
            turnId.toString(), conversationId.toString(), assistant.id.toString(), TurnExecutionStatus.RUNNING,
            null, 1, 1,
        )
        val finalizer = TurnFinalizer(repository, registry, coordinator, Json)
        val original = kotlinx.coroutines.Job()
        runtime.installTurnWorker(turnId, original)
        val stop = requireNotNull(finalizer.captureStop(conversationId))
        val replacement = kotlinx.coroutines.Job()
        runtime.installTurnWorker(turnId, replacement)
        try {
            val failure = runCatching { finalizer.finishStop(stop) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            org.junit.Assert.assertSame(replacement, runtime.currentWorker())
            assertTrue(replacement.isActive)
            assertEquals(turnId, runtime.snapshot.value.stream?.turnId)
            coVerify(exactly = 0) { coordinator.load(any()) }
            coVerify(exactly = 0) { coordinator.executeOrThrow(any(), any()) }
        } finally { replacement.cancel() }
    }

    @Test
    fun `failed stop terminal commit retains its original owner for recovery`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val assistant = UIMessage.assistant("partial").let {
            it.copy(parts = listOf(net.weero.measix.pilot.service.runtime.TurnTransition.openStep(0)) + it.parts)
        }
        val runtime = ConversationRuntime(conversationId,
            Conversation.ofId(conversationId).copy(messageNodes = listOf(MessageNode.of(assistant))).toSnapshot(), this, {})
        runtime.seedActiveTurn(1, turnId, assistant.id)
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        val registry = mockk<ConversationRuntimeRegistry>()
        io.mockk.every { registry.findRuntime(conversationId) } returns runtime
        coEvery { coordinator.withResidentRuntime<Any?>(conversationId, any()) } coAnswers {
            secondArg<suspend (ConversationRuntime?) -> Any?>()(runtime)
        }
        coEvery { coordinator.load(conversationId) } returns runtime
        coEvery { coordinator.withResidentRuntime<Any?>(conversationId, any()) } coAnswers {
            secondArg<suspend (ConversationRuntime?) -> Any?>()(runtime)
        }
        coEvery { repository.getTurnExecution(turnId.toString()) } returns TurnExecutionEntity(
            turnId.toString(), conversationId.toString(), assistant.id.toString(), TurnExecutionStatus.RUNNING,
            null, 1, 1,
        )
        val failure = java.io.IOException("commit failed")
        coEvery { coordinator.executeOrThrow(conversationId, any()) } throws failure
        val worker = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { runtime.releaseTurnWorker(turnId, coroutineContext[Job]) }
        }
        runtime.installTurnWorker(turnId, worker)
        val finalizer = TurnFinalizer(repository, registry, coordinator, Json)
        val actualFailure = runCatching { finalizer.stopTurn(conversationId) }.exceptionOrNull()
        assertTrue(actualFailure is java.io.IOException)
        assertEquals(failure.message, actualFailure?.message)
        org.junit.Assert.assertSame(worker, runtime.currentWorker())
        assertEquals(TurnLivePhase.STOPPING, runtime.currentTurnPresentation().phase)
        assertEquals(turnId, runtime.snapshot.value.stream?.turnId)
        assertTrue(worker.isCancelled)

        coEvery { coordinator.executeOrThrow(conversationId, any()) } coAnswers {
            val command = secondArg<ConversationCommand>()
            val change = ConversationTransition.plan(runtime.durable, command, runtime.durable.header.updateAt)
            runtime.publishCommitted(command, (change as ConversationChange.Durable).snapshot)
        }
        finalizer.stopTurn(conversationId)
        assertNull(runtime.currentWorker())
        assertNull(runtime.snapshot.value.stream)
    }

    /** 通过真实 StartTurn 提交建立当前 active Turn 的流式投影（这些用例只依赖其身份）。 */
    private fun ConversationRuntime.seedActiveTurn(epoch: Long, turnId: Uuid, assistantMessageId: Uuid) {
        publishCommitted(
            StartTurn(
                turnId = turnId,
                assistantNodeId = Uuid.random(),
                assistantMessageId = assistantMessageId,
                anchorNodeId = Uuid.random(),
                anchorMessageId = Uuid.random(),
                expectedSelectedPrefixMessageIds = emptyList(),
                modelContextCandidate = "",
                epoch = epoch,
            ),
            durable,
        )
    }
}
