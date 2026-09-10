package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.dao.ScopedTurnExecution
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationCommand
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.RecoverInterruptedTurn
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.service.turn.TurnRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.uuid.Uuid

class TurnRecoveryTest {
    @Test
    fun `master recovery fails closed when a non-terminal turn has no owning assistant message`() = runTest {
        val conversationId = Uuid.random()
        val execution = execution(conversationId, Uuid.random().toString())
        val conversation = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(MessageNode.of(UIMessage.user("preserve me"))),
        )
        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>(relaxed = true)
        coEvery { repository.getRecoverableTurnExecutionsByConversation() } returns
            mapOf(conversationId to listOf(execution))
        coEvery { repository.getConversationSnapshotById(conversationId) } returns conversation.toSnapshot()
        val scope = CoroutineScope(Job())
        coEvery { coordinator.load(conversationId) } returns
            ConversationRuntime(conversationId, conversation.toSnapshot(), scope, {})

        try {
            val error = try {
                recovery(repository, coordinator).recoverInterruptedTurns()
                null
            } catch (error: IllegalArgumentException) {
                error
            }

            assertTrue(error?.message.orEmpty().contains("missing owning assistant message"))
            coVerify(exactly = 0) { coordinator.executeRecovery(any(), any()) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `child recovery fails closed when a non-terminal turn has no owning assistant message`() = runTest {
        val masterId = Uuid.random()
        val childId = Uuid.random()
        val execution = execution(childId, assistantMessageId = null)
        val master = Conversation.ofId(masterId)
        val child = Conversation.ofId(childId).copy(
            parentConversationId = masterId,
            messageNodes = listOf(MessageNode.of(UIMessage.user("preserve child"))),
        )
        val repository = mockk<ConversationRepository>(relaxed = true)
        val coordinator = mockk<ConversationCommandCoordinator>(relaxed = true)
        coEvery { repository.getNonTerminalTurnExecutionsWithScope() } returns listOf(
            ScopedTurnExecution(
                execution = execution,
                isChild = true,
                parentConversationId = masterId.toString(),
            ),
        )
        coEvery { repository.getConversationById(masterId) } returns master
        coEvery { repository.getConversationById(childId) } returns child
        coEvery { repository.getTurnExecutions(childId) } returns listOf(execution)

        val error = try {
            recovery(repository, coordinator).recoverInterruptedRuns()
            null
        } catch (error: IllegalArgumentException) {
            error
        }

        assertTrue(error?.message.orEmpty().contains("missing owning assistant message"))
        coVerify(exactly = 0) { coordinator.executeRecovery(any(), any()) }
    }

    @Test
    fun `master recovery closes open steps, STARTED tools, and pending tools using typed outcomes`() = runTest {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val assistantMessageId = Uuid.random()
        val stepId = Uuid.random()
        val startedCallId = Uuid.random()
        val pendingCallId = Uuid.random()

        val assistantMessage = UIMessage(
            id = assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Step(
                    stepId = stepId,
                    ordinal = 0,
                    startedAt = Clock.System.now(),
                    outcome = null, // open Step
                ),
                UIMessagePart.Tool(
                    localCallId = startedCallId,
                    stepId = stepId,
                    providerCallId = "call-started",
                    toolName = "bash",
                    input = "{}",
                    output = emptyList(),
                    resultStatus = null,
                ),
                UIMessagePart.Tool(
                    localCallId = pendingCallId,
                    stepId = stepId,
                    providerCallId = "call-pending",
                    toolName = "ask_user",
                    input = "{}",
                    output = emptyList(),
                    interactionState = ToolInteractionState.AwaitingApproval,
                    resultStatus = null,
                ),
            ),
        )
        val conversation = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(MessageNode.of(assistantMessage)),
        )
        val execution = execution(conversationId, assistantMessageId.toString()).copy(
            turnId = turnId.toString(),
            status = TurnExecutionStatus.AWAITING_USER,
        )
        val startedToolExecution = ToolExecutionEntity(
            executionId = "exec-started",
            turnId = turnId.toString(),
            stepId = stepId.toString(),
            localCallId = startedCallId.toString(),
            status = ToolExecutionStatus.STARTED,
            reason = null,
            createdAt = 10L,
            updatedAt = 10L,
        )

        val repository = mockk<ConversationRepository>()
        val coordinator = mockk<ConversationCommandCoordinator>(relaxed = true)
        coEvery { repository.getRecoverableTurnExecutionsByConversation() } returns
            mapOf(conversationId to listOf(execution))
        coEvery { repository.getConversationSnapshotById(conversationId) } returns conversation.toSnapshot()
        coEvery { repository.getToolExecutions(turnId.toString()) } returns listOf(startedToolExecution)

        val scope = CoroutineScope(Job())
        coEvery { coordinator.load(conversationId) } returns
            ConversationRuntime(conversationId, conversation.toSnapshot(), scope, {})

        val recoverySlot = slot<ConversationCommand>()
        coEvery { coordinator.executeRecovery(conversationId, capture(recoverySlot)) } returns Unit

        try {
            recovery(repository, coordinator).recoverInterruptedTurns()

            val command = recoverySlot.captured as RecoverInterruptedTurn
            assertEquals(turnId, command.turnId)
            assertEquals(assistantMessageId, command.assistantMessageId)
            assertEquals(TurnTerminalReasons.PROCESS_RESTARTED, command.terminalReason)

            val recoveredMessage = requireNotNull(command.assistantMessage)
            val parts = recoveredMessage.parts

            // 1. Open Step -> Interrupted
            val step = parts.filterIsInstance<UIMessagePart.Step>().single()
            assertEquals(StepOutcome.Interrupted, step.outcome)
            assertNotNull(step.finishedAt)

            // 2. STARTED Tool -> Unknown (output status unknown, resultStatus = UNKNOWN)
            val startedTool = parts.filterIsInstance<UIMessagePart.Tool>().first { it.localCallId == startedCallId }
            assertEquals(ToolResultStatus.UNKNOWN, startedTool.resultStatus)
            assertTrue(startedTool.hasReplayResult)
            val startedOutput = (startedTool.output.single() as UIMessagePart.Text).text
            assertTrue(startedOutput.contains(""""status":"unknown""""))

            // 3. Open / Pending Tool -> Interrupted result (output status interrupted, resultStatus = INTERRUPTED)
            val pendingTool = parts.filterIsInstance<UIMessagePart.Tool>().first { it.localCallId == pendingCallId }
            assertEquals(ToolResultStatus.INTERRUPTED, pendingTool.resultStatus)
            assertTrue(pendingTool.hasReplayResult)
            val pendingOutput = (pendingTool.output.single() as UIMessagePart.Text).text
            assertTrue(pendingOutput.contains(""""status":"interrupted""""))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `child recovery closes open steps, STARTED tools, and pending tools using typed outcomes`() = runTest {
        val masterId = Uuid.random()
        val childId = Uuid.random()
        val turnId = Uuid.random()
        val assistantMessageId = Uuid.random()
        val stepId = Uuid.random()
        val startedCallId = Uuid.random()
        val pendingCallId = Uuid.random()

        val childAssistantMessage = UIMessage(
            id = assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Step(
                    stepId = stepId,
                    ordinal = 0,
                    startedAt = Clock.System.now(),
                    outcome = null,
                ),
                UIMessagePart.Tool(
                    localCallId = startedCallId,
                    stepId = stepId,
                    providerCallId = "child-started",
                    toolName = "read_file",
                    input = "{}",
                    output = emptyList(),
                    resultStatus = null,
                ),
                UIMessagePart.Tool(
                    localCallId = pendingCallId,
                    stepId = stepId,
                    providerCallId = "child-pending",
                    toolName = "ask_user",
                    input = "{}",
                    output = emptyList(),
                    interactionState = ToolInteractionState.AwaitingInput,
                    resultStatus = null,
                ),
            ),
        )
        val master = Conversation.ofId(masterId)
        val child = Conversation.ofId(childId).copy(
            parentConversationId = masterId,
            messageNodes = listOf(MessageNode.of(childAssistantMessage)),
        )
        val execution = execution(childId, assistantMessageId.toString()).copy(
            turnId = turnId.toString(),
            status = TurnExecutionStatus.RUNNING,
        )
        val startedToolExecution = ToolExecutionEntity(
            executionId = "child-exec-started",
            turnId = turnId.toString(),
            stepId = stepId.toString(),
            localCallId = startedCallId.toString(),
            status = ToolExecutionStatus.STARTED,
            reason = null,
            createdAt = 10L,
            updatedAt = 10L,
        )

        val repository = mockk<ConversationRepository>(relaxed = true)
        val coordinator = mockk<ConversationCommandCoordinator>(relaxed = true)
        coEvery { repository.getNonTerminalTurnExecutionsWithScope() } returns listOf(
            ScopedTurnExecution(
                execution = execution,
                isChild = true,
                parentConversationId = masterId.toString(),
            ),
        )
        coEvery { repository.getConversationById(masterId) } returns master
        coEvery { repository.getConversationSnapshotById(masterId) } returns master.toSnapshot()
        coEvery { repository.getConversationById(childId) } returns child
        coEvery { repository.getConversationSnapshotById(childId) } returns child.toSnapshot()
        coEvery { repository.getTurnExecutions(childId) } returns listOf(execution)
        coEvery { repository.getToolExecutions(turnId.toString()) } returns listOf(startedToolExecution)

        val recoverySlot = slot<ConversationCommand>()
        coEvery { coordinator.executeRecovery(childId, capture(recoverySlot)) } returns Unit

        recovery(repository, coordinator).recoverInterruptedRuns()

        val command = recoverySlot.captured as RecoverInterruptedTurn
        assertEquals(turnId, command.turnId)
        assertEquals(assistantMessageId, command.assistantMessageId)
        assertEquals("app_restarted", command.terminalReason)

        val recoveredMessage = requireNotNull(command.assistantMessage)
        val parts = recoveredMessage.parts

        // 1. Open Step -> Interrupted
        val step = parts.filterIsInstance<UIMessagePart.Step>().single()
        assertEquals(StepOutcome.Interrupted, step.outcome)
        assertNotNull(step.finishedAt)

        // 2. STARTED Tool -> Unknown
        val startedTool = parts.filterIsInstance<UIMessagePart.Tool>().first { it.localCallId == startedCallId }
        assertEquals(ToolResultStatus.UNKNOWN, startedTool.resultStatus)
        assertTrue(startedTool.hasReplayResult)
        val startedOutput = (startedTool.output.single() as UIMessagePart.Text).text
        assertTrue(startedOutput.contains(""""status":"unknown""""))

        // 3. The interrupted result closes the call while preserving its original input gate.
        val pendingTool = parts.filterIsInstance<UIMessagePart.Tool>().first { it.localCallId == pendingCallId }
        assertEquals(ToolResultStatus.INTERRUPTED, pendingTool.resultStatus)
        assertTrue(pendingTool.hasReplayResult)
        assertEquals(ToolInteractionState.AwaitingInput, pendingTool.interactionState)
        val pendingOutput = (pendingTool.output.single() as UIMessagePart.Text).text
        assertTrue(pendingOutput.contains(""""status":"interrupted""""))
    }

    @Test
    fun `child recovery commits before parent terminal metadata and child failure leaves parent untouched`() = runTest {
        for (failChild in listOf(false, true)) {
            val masterId = Uuid.random()
            val childId = Uuid.random()
            val task = UIMessage.user("child task")
            val childStep = UIMessagePart.Step(Uuid.random(), 0, Clock.System.now())
            val childMessage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(childStep))
            val childTurn = execution(childId, childMessage.id.toString())
            val parentStep = UIMessagePart.Step(Uuid.random(), 0, Clock.System.now())
            val metadata = net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata(
                runId = "run", targetAssistantId = Uuid.random().toString(), targetNameSnapshot = "child",
                childConversationId = childId.toString(), childTaskNodeId = task.id.toString(),
                state = net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState.RUNNING,
            )
            val tool = UIMessagePart.Tool(Uuid.random(), parentStep.stepId, "call", "assistant_call", "{}",
                metadata = kotlinx.serialization.json.buildJsonObject {
                    put("sub_assistant_call", Json.encodeToJsonElement(
                        net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata.serializer(), metadata))
                })
            val parentMessage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(parentStep, tool))
            val parentTurn = execution(masterId, parentMessage.id.toString())
            val parent = Conversation.ofId(masterId).copy(messageNodes = listOf(MessageNode.of(parentMessage)))
            val child = Conversation.ofId(childId).copy(parentConversationId = masterId,
                assistantId = ConfigurationReference.parse(metadata.targetAssistantId),
                messageNodes = listOf(MessageNode.of(task), MessageNode.of(childMessage)))
            val linked = ToolExecutionEntity("tool:${tool.localCallId}", parentTurn.turnId, parentStep.stepId.toString(),
                tool.localCallId.toString(), ToolExecutionStatus.STARTED, null, childId.toString(), childTurn.turnId, "run", 1, 1)
            val repository = mockk<ConversationRepository>(relaxed = true)
            val coordinator = mockk<ConversationCommandCoordinator>(relaxed = true)
            coEvery { repository.getNonTerminalTurnExecutionsWithScope() } returns listOf(
                ScopedTurnExecution(parentTurn, false, null), ScopedTurnExecution(childTurn, true, masterId.toString()))
            coEvery { repository.getRecoverableTurnExecutionsByConversation() } returns mapOf(masterId to listOf(parentTurn))
            coEvery { repository.getConversationSnapshotById(masterId) } returns parent.toSnapshot()
            coEvery { repository.getConversationSnapshotById(childId) } returns child.toSnapshot()
            coEvery { repository.getTurnExecutions(childId) } returns listOf(childTurn)
            coEvery { repository.getToolExecutions(parentTurn.turnId) } returns listOf(linked)
            coEvery { repository.getToolExecutions(childTurn.turnId) } returns emptyList()
            val scope = CoroutineScope(Job())
            coEvery { coordinator.load(masterId) } returns ConversationRuntime(masterId, parent.toSnapshot(), scope, {})
            val committed = mutableListOf<Pair<Uuid, RecoverInterruptedTurn>>()
            coEvery { coordinator.executeRecovery(any(), any()) } coAnswers {
                val id = firstArg<Uuid>()
                if (id == childId && failChild) error("child write failed")
                committed += id to (secondArg<ConversationCommand>() as RecoverInterruptedTurn)
            }
            try {
                val recovery = recovery(repository, coordinator)
                val outcome = runCatching { recovery.recoverInterruptedRuns(); recovery.recoverInterruptedTurns() }
                if (failChild) {
                    assertTrue(outcome.isFailure)
                    assertTrue(committed.isEmpty())
                } else {
                    outcome.getOrThrow()
                    assertEquals(listOf(childId, masterId), committed.map { it.first })
                    val recoveredTool = committed.last().second.assistantMessage!!.getTools().single()
                    assertEquals(ToolResultStatus.UNKNOWN, recoveredTool.resultStatus)
                    val recoveredMetadata = Json.decodeFromJsonElement(
                        net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata.serializer(),
                        recoveredTool.metadata!!.getValue("sub_assistant_call"))
                    assertEquals(net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState.STOPPED, recoveredMetadata.state)
                }
            } finally { scope.cancel() }
        }
    }

    @Test
    fun `malformed and mismatched execution locators stop recovery without publishing a terminal`() = runTest {
        val id = Uuid.random()
        val step = UIMessagePart.Step(Uuid.random(), 0, Clock.System.now())
        val tool = UIMessagePart.Tool(Uuid.random(), step.stepId, "call", "bash", "{}")
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(step, tool))
        val snapshot = Conversation.ofId(id).copy(messageNodes = listOf(MessageNode.of(message))).toSnapshot()
        val turn = execution(id, message.id.toString())
        val fact = ToolExecutionEntity("execution", turn.turnId, step.stepId.toString(), tool.localCallId.toString(),
            ToolExecutionStatus.STARTED, null, createdAt = 1, updatedAt = 1)
        for (invalid in listOf(fact.copy(localCallId = "invalid"), fact.copy(stepId = Uuid.random().toString()))) {
            val repository = mockk<ConversationRepository>(relaxed = true)
            val coordinator = mockk<ConversationCommandCoordinator>(relaxed = true)
            val scope = CoroutineScope(Job())
            val runtime = ConversationRuntime(id, snapshot, scope, {})
            coEvery { repository.getRecoverableTurnExecutionsByConversation() } returns mapOf(id to listOf(turn))
            coEvery { repository.getConversationSnapshotById(id) } returns snapshot
            coEvery { repository.getToolExecutions(turn.turnId) } returns listOf(invalid)
            coEvery { coordinator.load(id) } returns runtime
            try {
                assertTrue(runCatching { recovery(repository, coordinator).recoverInterruptedTurns() }.isFailure)
                org.junit.Assert.assertSame(snapshot, runtime.durable)
                coVerify(exactly = 0) { coordinator.executeRecovery(any(), any()) }
            } finally {
                scope.cancel()
            }
        }
    }

    private fun recovery(
        repository: ConversationRepository,
        coordinator: ConversationCommandCoordinator,
    ): TurnRecovery {
        return TurnRecovery(
            conversationRepo = repository,
            commandCoordinator = coordinator,
            json = Json,
            runGate = mockk(relaxed = true),
        )
    }

    private fun execution(
        conversationId: Uuid,
        assistantMessageId: String?,
    ) = TurnExecutionEntity(
        turnId = Uuid.random().toString(),
        conversationId = conversationId.toString(),
        assistantMessageId = assistantMessageId,
        status = TurnExecutionStatus.RUNNING,
        reason = null,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
