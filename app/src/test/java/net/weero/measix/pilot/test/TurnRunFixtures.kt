package net.weero.measix.pilot.test

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.DurableMessageLocator
import net.weero.measix.pilot.service.turn.TurnRunInputs
import net.weero.measix.pilot.service.turn.TurnRunResult
import net.weero.measix.pilot.data.ai.tools.TurnInteractionCapability
import net.weero.measix.pilot.data.ai.transformers.InputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.service.runtime.TurnCheckpoint
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.turn.TurnPromptSnapshot
import kotlin.uuid.Uuid

/**
 * 记录 loop 各输出汇的测试捕获器。
 * loop 不返回 Flow，而是把流式投影、阶段、checkpoint、草稿交接与运行结果分别回调到汇。
 * [results] 保留每次 onResult 交出，用于锁定"一次运行恰好一个结果"的不变量。
 */
internal class TurnRunCapture {
    val streamDeltas = mutableListOf<UIMessage>()
    val phases = mutableListOf<Pair<String, String?>>()
    val checkpoints = mutableListOf<TurnCheckpoint>()
    val observations = mutableListOf<UIMessage>()
    val results = mutableListOf<TurnRunResult>()
    val result: TurnRunResult? get() = results.lastOrNull()
}

/** Test boundary that freezes ordinary request fixtures immediately; production has no legacy constructor. */
internal fun turnRunInputsFixture(
    conversationId: Uuid,
    settings: Settings,
    model: Model,
    mediaCapabilities: RequestMediaCapabilities,
    messages: List<UIMessage>,
    assistant: Assistant,
    promptInputs: TurnPromptSnapshot = testPromptInputs(),
    inputTransformers: List<InputMessageTransformer> = emptyList(),
    outputTransformers: List<OutputMessageTransformer> = emptyList(),
    tools: List<Tool> = emptyList(),
    maxSteps: Int = 256,
    reportProcessingText: (String?) -> Unit = {},
    interactionAvailability: TurnInteractionCapability = TurnInteractionCapability.FULL,
    assistantMessageId: Uuid? = null,
    /** Synthetic authoritative handle; loop tests capture the emitted variants without running the reducer. */
    handle: TurnHandle = TurnHandle(
        conversationId = conversationId,
        epoch = 1,
        turnId = Uuid.NIL,
        assistantMessageId = assistantMessageId ?: Uuid.NIL,
    ),
    modelContextEntries: List<ConversationModelContextEntry> = emptyList(),
    durableMessageLocators: Map<Uuid, DurableMessageLocator> = emptyMap(),
    providerSessionId: String? = null,
    capture: TurnRunCapture = TurnRunCapture(),
    onAssistantObserved: (UIMessage) -> Unit = {},
    onCheckpoint: suspend (TurnCheckpoint) -> Unit = {},
    onStreamDelta: suspend (UIMessage) -> Unit = {},
    onPhase: suspend (String, String?) -> Unit = { _, _ -> },
    onResult: suspend (TurnRunResult) -> Unit = {},
    cancelReason: () -> String? = { null },
): TurnRunInputs = TurnRunInputs(
    turnContext = testTurnContext(
        settings = settings,
        model = model,
        assistant = assistant,
        tools = tools,
        mediaCapabilities = mediaCapabilities,
        promptInputs = promptInputs,
    ),
    handle = handle,
    messages = messages,
    inputTransformers = inputTransformers,
    outputTransformers = outputTransformers,
    maxSteps = maxSteps,
    reportProcessingText = reportProcessingText,
    interactionAvailability = interactionAvailability,
    assistantMessageId = assistantMessageId,
    modelContextEntries = modelContextEntries,
    durableMessageLocators = durableMessageLocators,
    providerSessionId = providerSessionId,
    onAssistantObserved = { message ->
        capture.observations += message
        onAssistantObserved(message)
    },
    onCheckpoint = { checkpoint ->
        capture.checkpoints += checkpoint
        onCheckpoint(checkpoint)
    },
    onStreamDelta = { message ->
        capture.streamDeltas += message
        onStreamDelta(message)
    },
    onPhase = { phase, toolName ->
        capture.phases += phase to toolName
        onPhase(phase, toolName)
    },
    onResult = { result ->
        capture.results += result
        onResult(result)
    },
    cancelReason = cancelReason,
)

/** Locate a Tool by its stable [UIMessagePart.Tool.localCallId], never by position. */
internal fun UIMessage.replaceToolByLocalCallId(tool: UIMessagePart.Tool): UIMessage =
    copy(parts = parts.map { if (it is UIMessagePart.Tool && it.localCallId == tool.localCallId) tool else it })
