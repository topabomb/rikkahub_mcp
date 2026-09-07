package net.weero.measix.pilot.service
import net.weero.measix.pilot.service.turn.TurnFinalizer

import net.weero.measix.pilot.test.turnRunInputsFixture

import net.weero.measix.pilot.test.testPromptInputs

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.service.turn.TurnRunner
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.runtime.TurnStreamProjection
import net.weero.measix.pilot.service.runtime.ConversationCommand
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.StreamingDeltaResult
import net.weero.measix.pilot.service.turn.TurnCommitter
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TurnFinalizationAssistantCallTest {
    @Test
    fun `call without metadata can be interrupted before a child link is committed`() = runTest {
        for (status in listOf(null, ToolExecutionStatus.STARTED)) {
            val call = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "assistant_call", input = "{\"task\":\"partial")
            val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call))
            val harness = harness(assistant, status)

            val prepared = harness.prepare(assistant).getTools().single()

            assertEquals(call.input, prepared.input)
            assertNull(prepared.metadata)
            assertTrue(prepared.hasReplayResult)
            assertTrue((prepared.output.single() as UIMessagePart.Text).text.contains("interrupted"))
        }
    }

    @Test
    fun `committed child link rejects absent malformed or mismatched run metadata`() = runTest {
        val linkedChild = Uuid.random().toString()
        val bareCall = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "assistant_call", input = "{}")
        val mismatched = bareCall.mergeSubAssistantCallMetadata(
            Json,
            buildInitialSubAssistantCallMetadata("run", Uuid.random(), "Target").copy(
                state = SubAssistantCallState.RUNNING,
                childConversationId = Uuid.random().toString(),
            ),
        )
        for (call in listOf(
            bareCall,
            bareCall.copy(metadata = JsonObject(mapOf("sub_assistant_call" to JsonPrimitive("invalid")))),
            mismatched,
        )) {
            val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call))
            val harness = harness(assistant, ToolExecutionStatus.STARTED, linkedChild)
            val failure = try {
                harness.prepare(assistant)
                null
            } catch (error: IllegalArgumentException) {
                error
            }
            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test
    fun `malformed metadata and terminal execution are not treated as an unstarted call`() = runTest {
        val bareCall = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "assistant_call", input = "{}")
        val cases = listOf(
            bareCall.copy(metadata = JsonObject(mapOf("sub_assistant_call" to JsonPrimitive("invalid")))) to null,
            bareCall to ToolExecutionStatus.COMPLETED,
        )
        for ((call, status) in cases) {
            val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call))
            val harness = harness(assistant, status)
            val failure = try {
                harness.prepare(assistant)
                null
            } catch (error: IllegalArgumentException) {
                error
            }
            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test
    fun `real provider cancellation preserves usage and closes an unexecuted assistant call`() = runTest {
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val harness = harness(assistant)
        val model = Model(modelId = "test-model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        val providerManager = mockk<ProviderManager>()
        every { providerManager.getProviderByType(providerSetting) } returns provider
        coEvery { provider.streamText(providerSetting, any(), any()) } returns flow {
            emit(MessageChunk(
                id = "response",
                model = model.modelId,
                choices = listOf(UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                        UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "assistant_call", input = "{\"task\":\"partial"),
                    )),
                    message = null,
                    finishReason = null,
                )),
                usage = ProviderUsageSnapshot(inputTokens = 100, outputTokens = 20, totalTokens = 120),
            ))
            awaitCancellation()
        }
        val loop = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val turnCommitter = TurnCommitter(harness.coordinator, harness.runtime, harness.handle, harness.finalization)
        val callObserved = CompletableDeferred<Unit>()
        val collector = launch {
            loop.run(turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = harness.snapshot.toPresentationSnapshot().currentMessages(),
                assistant = Assistant(enableMemory = false, streamOutput = true),
                promptInputs = testPromptInputs(),
                assistantMessageId = assistant.id,
                handle = harness.handle,
                onCheckpoint = turnCommitter::onCheckpoint,
                onAssistantObserved = turnCommitter::observeAssistant,
                onStreamDelta = { message ->
                    turnCommitter.publishStream(message)
                    if (message.getTools().isNotEmpty()) {
                        callObserved.complete(Unit)
                    }
                },
                onResult = turnCommitter::commitRunResult,
                cancelReason = { harness.runtime.peekCancelReason(harness.handle.turnId) },
            ))
        }
        callObserved.await()
        collector.cancelAndJoin()

        assertTrue(collector.isCancelled)
        val finalized = harness.commands.single() as FinalizeTurn
        assertEquals(TurnExecutionStatus.CANCELLED, finalized.terminalStatus)
        val message = finalized.assistantMessage!!
        assertTrue(message.getTools().single().hasReplayResult)
        assertNull(message.getTools().single().metadata)
        assertEquals(100L, message.usage!!.latestRequestContextTokens)
        assertEquals(1, message.usage!!.observedProviderRequestCount)
    }

    private fun harness(
        assistant: UIMessage,
        executionStatus: ToolExecutionStatus? = null,
        linkedChild: String? = null,
    ): Harness {
        val conversationId = Uuid.random()
        val handle = TurnHandle(conversationId, 1, Uuid.random(), assistant.id)
        val callTool = assistant.getTools().firstOrNull()
        val base = Conversation.ofId(conversationId).copy(messageNodes = listOf(
            MessageNode.of(UIMessage.user("question")),
            MessageNode.of(assistant),
        )).toSnapshot()
        val snapshot = ConversationRuntimeSnapshot(
            durable = base,
            stream = TurnStreamProjection(
                handle.epoch, handle.turnId, assistant.id, assistant,
            ),
        )
        val runtime = mockk<ConversationRuntime>()
        every { runtime.id } returns conversationId
        every { runtime.snapshot } returns MutableStateFlow(snapshot)
        every { runtime.durable } returns base
        every { runtime.applyStreamingDelta(any(), any()) } returns StreamingDeltaResult.APPLIED
        every { runtime.peekCancelReason(any()) } returns "user_stop"
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.getConversationById(any()) } returns null
        coEvery { repository.getToolExecutions(handle.turnId.toString()) } returns listOfNotNull(
            executionStatus?.let { status -> ToolExecutionEntity(
                executionId = "execution",
                turnId = handle.turnId.toString(),
                stepId = (callTool?.stepId ?: Uuid.random()).toString(),
                localCallId = (callTool?.localCallId ?: Uuid.random()).toString(),
                status = status,
                reason = null,
                childConversationId = linkedChild,
                createdAt = 1,
                updatedAt = 1,
            ) },
        )
        val commands = mutableListOf<ConversationCommand>()
        val coordinator = mockk<ConversationCommandCoordinator>()
        coEvery { coordinator.executeOrThrow(conversationId, capture(commands)) } returns Unit
        val registry = mockk<ConversationRuntimeRegistry>()
        every { registry.findRuntime(any()) } returns null
        val finalization = TurnFinalizer(repository, registry, coordinator, Json)
        return Harness(handle, snapshot, runtime, coordinator, finalization, commands)
    }

    private data class Harness(
        val handle: TurnHandle,
        val snapshot: ConversationRuntimeSnapshot,
        val runtime: ConversationRuntime,
        val coordinator: ConversationCommandCoordinator,
        val finalization: TurnFinalizer,
        val commands: List<ConversationCommand>,
    ) {
        suspend fun prepare(assistant: UIMessage): UIMessage = finalization.prepareOwnedAssistantForFailure(
            snapshot, handle, assistant, "user_stop", true,
        )!!
    }
}
