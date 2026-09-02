package net.weero.measix.pilot.test

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.DurableMessageLocator
import net.weero.measix.pilot.data.ai.GenerationCheckpoint
import net.weero.measix.pilot.data.ai.GenerationRequest
import net.weero.measix.pilot.data.ai.tools.ToolInteractionAvailability
import net.weero.measix.pilot.data.ai.transformers.InputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.service.runtime.FrozenTurnPromptInputs
import kotlin.uuid.Uuid

/** Test boundary that freezes ordinary request fixtures immediately; production has no legacy constructor. */
internal fun generationRequestFixture(
    conversationId: Uuid,
    settings: Settings,
    model: Model,
    mediaCapabilities: RequestMediaCapabilities,
    messages: List<UIMessage>,
    assistant: Assistant,
    promptInputs: FrozenTurnPromptInputs = testPromptInputs(),
    inputTransformers: List<InputMessageTransformer> = emptyList(),
    outputTransformers: List<OutputMessageTransformer> = emptyList(),
    tools: List<Tool> = emptyList(),
    maxSteps: Int = 256,
    reportProcessingText: (String?) -> Unit = {},
    interactionAvailability: ToolInteractionAvailability = ToolInteractionAvailability.FULL,
    assistantMessageId: Uuid? = null,
    modelContextEntries: List<ConversationModelContextEntry> = emptyList(),
    durableMessageLocators: Map<Uuid, DurableMessageLocator> = emptyMap(),
    onMessagesObserved: (List<UIMessage>) -> Unit = {},
    onCheckpoint: suspend (GenerationCheckpoint) -> Unit = {},
    providerSessionId: String? = null,
): GenerationRequest = GenerationRequest(
    conversationId = conversationId,
    requestContext = testTurnRequestContext(
        settings = settings,
        model = model,
        assistant = assistant,
        tools = tools,
        mediaCapabilities = mediaCapabilities,
        promptInputs = promptInputs,
    ),
    messages = messages,
    inputTransformers = inputTransformers,
    outputTransformers = outputTransformers,
    maxSteps = maxSteps,
    reportProcessingText = reportProcessingText,
    interactionAvailability = interactionAvailability,
    assistantMessageId = assistantMessageId,
    modelContextEntries = modelContextEntries,
    durableMessageLocators = durableMessageLocators,
    onMessagesObserved = onMessagesObserved,
    onCheckpoint = onCheckpoint,
    providerSessionId = providerSessionId,
)
