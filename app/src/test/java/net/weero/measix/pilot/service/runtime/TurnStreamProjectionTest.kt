package net.weero.measix.pilot.service.runtime

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.test.TurnRunCapture
import net.weero.measix.pilot.test.turnRunInputsFixture
import net.weero.measix.pilot.service.turn.TurnRunner
import net.weero.measix.pilot.data.ai.ToolResultFact
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.StreamingMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.ai.ToolExecutionFact
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.ai.transformers.RegexOutputTransformer
import net.weero.measix.pilot.data.ai.transformers.RequestMessageOriginTracker
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.AssistantRegex
import net.weero.measix.pilot.service.turn.finishStreamingProjection
import net.weero.measix.pilot.service.turn.resolveTurnAssistantSnapshot
import net.weero.measix.pilot.test.testPromptInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * Structural contract of the single stream projection: the active Assistant draft is the only
 * message a chunk may carry, committed facts and the streaming draft stay separate, and the
 * presentation merge overlays exactly one node while every historical node keeps its identity.
 *
 * These are complexity facts (object identity, reference sharing, transform counts), not timing
 * or memory measurements — those belong to controlled benchmarks. The final case drives the
 * generation loop to lock the stream-scope invariant (only the active Assistant enters
 * transformStreaming).
 */
class TurnStreamProjectionTest {

    private val stepId = Uuid.random()
    private fun stableId(ordinal: Int): Uuid =
        Uuid.parse("00000000-0000-0000-0000-" + ordinal.toLong().toString(16).padStart(12, '0'))
    private fun loc(messageId: Uuid, ordinal: Int) = ToolCallLocator(messageId, stepId, stableId(ordinal))

    private fun assistant(id: Uuid, vararg parts: UIMessagePart) =
        UIMessage(id = id, role = MessageRole.ASSISTANT, parts = parts.toList())

    private fun tool(
        messageId: Uuid,
        ordinal: Int,
        name: String = "generate_image",
        output: List<UIMessagePart> = emptyList(),
        interaction: ToolInteractionState = ToolInteractionState.NotRequired,
    ) = UIMessagePart.Tool(
        localCallId = stableId(ordinal),
        stepId = stepId,
        providerCallId = "call-$ordinal",
        toolName = name,
        input = "{}",
        output = output,
        interactionState = interaction,
    )

    // ── UI live phases: the draft advances only from committed facts ──────────────────────

    @Test
    fun `streamed approval and output cannot advance lifecycle before a checkpoint`() {
        val assistantId = Uuid.random()
        val state = TurnStreamProjection(
            epoch = 1,
            turnId = Uuid.random(),
            assistantMessageId = assistantId,
            assistantMessage = null,
        )
        val streamed = assistant(
            assistantId,
            tool(
                assistantId, 0,
                output = listOf(UIMessagePart.Text("{\"status\":\"completed\"}")),
                interaction = ToolInteractionState.AwaitingApproval,
            ),
        )

        val projected = state.withStreamingAssistant(streamed)

        // A streamed tool part is only ever CALL_STREAMING: neither the pending interaction nor
        // the in-flight output may look like a committed lifecycle phase before a checkpoint.
        assertEquals(ToolLivePhase.CALL_STREAMING, projected.toolLivePhases[loc(assistantId, 0)])
        assertSame(streamed, projected.assistantMessage)
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
        val initial = TurnStreamProjection(
            epoch = handle.epoch,
            turnId = handle.turnId,
            assistantMessageId = assistantId,
            assistantMessage = null,
        )
        val toolMessage = assistant(assistantId, tool(assistantId, 0))
        val ready = initial.withStreamingAssistant(toolMessage).afterCheckpoint(
            ModelResponseCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = toolMessage,
                turnStatus = TurnExecutionStatus.RUNNING,
            ),
        )
        val failedMessage = toolMessage.copy(
            parts = listOf(
                (toolMessage.parts.single() as UIMessagePart.Tool).copy(
                    output = listOf(UIMessagePart.Text("{\"status\":\"failed\"}")),
                ),
            ),
        )
        val streamed = ready.withStreamingAssistant(failedMessage)
        val locator = loc(assistantId, 0)
        // Streaming the failed output does not by itself mark the call FAILED.
        assertEquals(ToolLivePhase.READY, streamed.toolLivePhases[locator])

        val committed = streamed.afterCheckpoint(
            ToolResultCheckpoint(
                turn = handle,
                step = StepHandle(stepId),
                assistantMessage = failedMessage,
                toolResults = listOf(ToolResultFact(loc(assistantId, 0), ToolResultStatus.FAILED)),
            ),
        )
        assertEquals(ToolLivePhase.FAILED, committed.toolLivePhases[locator])

        val invalidOrdinal = runCatching {
            streamed.afterCheckpoint(
                ToolResultCheckpoint(
                    turn = handle,
                    step = StepHandle(stepId),
                    assistantMessage = failedMessage,
                    toolResults = listOf(ToolResultFact(loc(assistantId, 1), ToolResultStatus.FAILED)),
                ),
            )
        }.exceptionOrNull()
        assertTrue(invalidOrdinal?.message?.contains("missing tool call") == true)

        val execution = ToolExecutionFact(
            executionId = "execution",
            assistantMessageId = assistantId,
            stepId = stepId,
            localCallId = stableId(0),
            providerCallId = "call",
            toolName = "tool",
            status = ToolExecutionStatus.COMPLETED,
        )
        val conflictingStatus = runCatching {
            streamed.afterCheckpoint(
                ToolResultCheckpoint(
                    turn = handle,
                    step = StepHandle(stepId),
                    assistantMessage = failedMessage,
                    toolResults = listOf(ToolResultFact(loc(assistantId, 0), ToolResultStatus.FAILED)),
                    toolExecution = execution,
                ),
            )
        }.exceptionOrNull()
        assertTrue(conflictingStatus?.message?.contains("conflicting terminal statuses") == true)
    }

    // ── owning-assistant identity: the draft is exactly the turn's Assistant ──────────────

    @Test
    fun `withStreamingAssistant rejects an assistant that is not the owning message`() {
        val projection = TurnStreamProjection(
            epoch = 1,
            turnId = Uuid.random(),
            assistantMessageId = Uuid.random(),
            assistantMessage = null,
        )
        val foreign = assistant(Uuid.random(), UIMessagePart.Text("tail"))

        val failure = runCatching { projection.withStreamingAssistant(foreign) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `matches accepts only the exact epoch turn and assistant identity`() {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        val projection = TurnStreamProjection(
            epoch = 7,
            turnId = turnId,
            assistantMessageId = assistantId,
            assistantMessage = null,
        )

        assertTrue(projection.matches(TurnHandle(conversationId, 7L, turnId, assistantId)))
        assertFalse(projection.matches(TurnHandle(conversationId, 8L, turnId, assistantId)))
        assertFalse(projection.matches(TurnHandle(conversationId, 7L, Uuid.random(), assistantId)))
        assertFalse(projection.matches(TurnHandle(conversationId, 7L, turnId, Uuid.random())))
    }

    @Test
    fun `afterResolve aligns the draft and phase for the owning assistant`() {
        val assistantId = Uuid.random()
        val handle = TurnHandle(Uuid.random(), 1L, Uuid.random(), assistantId)
        val pending = assistant(
            assistantId,
            tool(assistantId, 0, interaction = ToolInteractionState.AwaitingApproval),
        )
        val projection = TurnStreamProjection(
            epoch = handle.epoch,
            turnId = handle.turnId,
            assistantMessageId = assistantId,
            assistantMessage = null,
        ).withStreamingAssistant(pending)

        val resolved = projection.afterResolve(
            ResolveToolInteraction(
                messageId = assistantId,
                stepId = stepId,
                localCallId = stableId(0),
                decision = ToolInteractionDecision.Approve,
                handle = handle,
            ),
        )

        assertEquals(ToolLivePhase.READY, resolved.toolLivePhases[loc(assistantId, 0)])
        val tool = resolved.assistantMessage!!.getTools().single()
        assertEquals(ToolInteractionState.Approved, tool.interactionState)
    }

    // ── committed + stream + presentation merge, with structural sharing ──────────────────

    @Test
    fun `presentation overlays only the last node with the draft and shares every history node`() {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val history = (0 until 5).map { MessageNode.of(UIMessage.user("history-$it")) }
        val committedAssistant = assistant(assistantId, UIMessagePart.Text("committed"))
        val durable = Conversation.ofId(conversationId)
            .copy(messageNodes = history + MessageNode.of(committedAssistant))
            .toSnapshot()
        val draft = committedAssistant.copy(parts = listOf(UIMessagePart.Text("committed + streamed tail")))
        val stream = TurnStreamProjection(
            epoch = 1,
            turnId = Uuid.random(),
            assistantMessageId = assistantId,
            assistantMessage = draft,
        )

        val presentation = ConversationRuntimeSnapshot(durable = durable, stream = stream).toPresentationSnapshot()

        // Only the owning Assistant node is replaced; every historical node keeps its instance.
        assertEquals(draft, presentation.currentMessages().last())
        history.indices.forEach { index -> assertSame(history[index], presentation.nodes[index]) }
        assertNotSame(durable.nodes.last(), presentation.nodes.last())
        // Streaming never mutates the durable tree: committed and stream projections stay separate.
        assertEquals(committedAssistant, durable.currentMessages().last())
    }

    @Test
    fun `presentation without a draft is the durable tree verbatim`() {
        val conversationId = Uuid.random()
        val nodes = (0 until 4).map { MessageNode.of(UIMessage.user("history-$it")) }
        val durable = Conversation.ofId(conversationId).copy(messageNodes = nodes).toSnapshot()

        val presentation = ConversationRuntimeSnapshot(durable = durable, stream = null).toPresentationSnapshot()

        nodes.indices.forEach { index -> assertSame(nodes[index], presentation.nodes[index]) }
    }

    // ── stream chunk scope: only the active Assistant enters the streaming transform ───────

    private class CountingStreamingTransformer : OutputMessageTransformer, StreamingMessageTransformer {
        val callsPerMessageId = ConcurrentHashMap<Uuid, AtomicLong>()

        override suspend fun transformStreaming(
            ctx: TransformerContext,
            message: UIMessage,
            previousProjection: UIMessage?,
        ): UIMessage {
            callsPerMessageId.getOrPut(message.id) { AtomicLong() }.incrementAndGet()
            return message
        }
    }

    @Test
    fun `stream chunks transform only the active assistant and never history`() = runTest {
        val chunkCount = 8
        val counting = CountingStreamingTransformer()

        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider
        every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE

        val assistantMessage = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        coEvery {
            provider.streamText(providerSetting = providerSetting, messages = any(), params = any())
        } answers {
            flow {
                for (i in 0 until chunkCount) {
                    emit(
                        MessageChunk(
                            id = "stream",
                            model = model.modelId,
                            choices = listOf(
                                UIMessageChoice(
                                    index = 0,
                                    delta = UIMessage(
                                        role = MessageRole.ASSISTANT,
                                        parts = listOf(UIMessagePart.Text("chunk $i ")),
                                    ),
                                    message = null,
                                    finishReason = null,
                                ),
                            ),
                        ),
                    )
                }
                emit(
                    MessageChunk(
                        id = "stream",
                        model = model.modelId,
                        choices = listOf(
                            UIMessageChoice(index = 0, delta = null, message = null, finishReason = "stop"),
                        ),
                    ),
                )
            }
        }

        val handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false, streamOutput = true)
        val userMessage = UIMessage.user("hello")

        val capture = TurnRunCapture()
        handler.run(
            turnRunInputsFixture(
                conversationId = Uuid.random(),
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(userMessage, assistantMessage),
                inputTransformers = emptyList(),
                outputTransformers = listOf(counting),
                assistant = assistant,
                promptInputs = testPromptInputs(),
                assistantMessageId = assistantMessage.id,
                maxSteps = 1,
                capture = capture,
            ),
        )

        // Only the active Assistant enters transformStreaming: the text chunks, the finish chunk,
        // and the two usage projections (pre-send Context and request close). History never does.
        assertEquals(setOf(assistantMessage.id), counting.callsPerMessageId.keys)
        assertEquals((chunkCount + 3).toLong(), counting.callsPerMessageId[assistantMessage.id]?.get())
        assertFalse(counting.callsPerMessageId.containsKey(userMessage.id))

        // Every stream delta carries exactly the owning Assistant, never the full branch.
        val emitted = capture.streamDeltas
        assertTrue(emitted.isNotEmpty())
        emitted.forEach { message -> assertEquals(assistantMessage.id, message.id) }
    }

    @Test
    fun `terminal pipeline preserves think close time without applying regex twice`() = runTest {
        val raw = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>reasoning</think>a")),
        )
        val capturedAt = Instant.fromEpochMilliseconds(1_234)
        val previousProjection = raw.copy(
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "reasoning",
                    createdAt = Instant.DISTANT_PAST,
                    finishedAt = capturedAt,
                ),
                UIMessagePart.Text("aa"),
            ),
        )
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "a",
                    replaceString = "aa",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
            ),
        )

        val result = finishStreamingProjection(
            raw = raw,
            previousProjection = previousProjection,
            ctx = terminalTransformerContext(assistant),
            transformers = listOf(ThinkTagTransformer, RegexOutputTransformer),
        )

        assertEquals(
            capturedAt,
            result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt,
        )
        assertEquals("aa", result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `terminal pipeline closes an unclosed think projection`() = runTest {
        val raw = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>reasoning")),
        )
        val previousProjection = raw.copy(
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "reasoning",
                    createdAt = Instant.DISTANT_PAST,
                    finishedAt = null,
                ),
            ),
        )

        val result = finishStreamingProjection(
            raw = raw,
            previousProjection = previousProjection,
            ctx = terminalTransformerContext(Assistant()),
            transformers = listOf(ThinkTagTransformer),
        )

        assertNotNull(result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt)
    }

    private fun terminalTransformerContext(assistant: Assistant) = TransformerContext(
        context = mockk<Context>(relaxed = true),
        model = Model(modelId = "test", displayName = "Test"),
        assistant = resolveTurnAssistantSnapshot(assistant),
        promptInputs = testPromptInputs(),
        requestOrigins = RequestMessageOriginTracker(),
        registerUnpublishedResource = {},
    )
}
