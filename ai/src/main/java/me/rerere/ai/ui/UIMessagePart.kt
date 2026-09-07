package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
sealed class UIMessagePart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override val metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        override val metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override val metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override val metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override val metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override val metadata: JsonObject? = null
    ) : UIMessagePart()

    /**
     * One logical model sampling plus the full Tool batch its response requested. A committed
     * Assistant Turn is an ordered sequence of these; the Step boundary is explicit, never inferred
     * from "the last Tool without a result".
     *
     * The Step part is a durable transcript fact: it is never rendered, never enters FTS, and is
     * dropped when the app-layer `RequestAssembler` converts the projection into `ModelRequestMessage`.
     */
    @Serializable
    @SerialName("step")
    data class Step(
        val stepId: Uuid,
        val ordinal: Int,
        val startedAt: Instant,
        val modelResult: StepModelResult? = null,
        val outcome: StepOutcome? = null,
        val finishedAt: Instant? = null,
        override val metadata: JsonObject? = null,
    ) : UIMessagePart()

    @Serializable
    @SerialName("tool")
    data class Tool(
        val localCallId: Uuid,
        val stepId: Uuid,
        val providerCallId: String,
        val toolName: String,
        val input: String,
        val output: List<UIMessagePart> = emptyList(),
        val interactionState: ToolInteractionState = ToolInteractionState.NotRequired,
        val resultStatus: ToolResultStatus? = null,
        val runtimeState: ToolRuntimeState = ToolRuntimeState(ToolOutputPolicy.ARCHIVABLE_TEXT),
        override val metadata: JsonObject? = null,
    ) : UIMessagePart() {
        /**
         * Whether a provider-replayable tool result exists.
         *
         * This is deliberately not the live execution state: a fully assembled tool call can be
         * waiting for approval or executing remotely while [output] is still empty. Active
         * lifecycle presentation is owned by the turn projection.
         */
        val hasReplayResult: Boolean get() = output.isNotEmpty()

        /** Whether the call is paused for a user gate (approval or user-input collection). */
        val isPending: Boolean
            get() = interactionState is ToolInteractionState.AwaitingApproval ||
                interactionState is ToolInteractionState.AwaitingInput

        /** Whether a resolved gate can resume assembly of the Provider replay result. */
        val canResumeResultAssembly: Boolean
            get() = !hasReplayResult && when (interactionState) {
                ToolInteractionState.Approved,
                is ToolInteractionState.Denied,
                is ToolInteractionState.Answered,
                -> true
                else -> false
            }

        /** Replay/display projection for partial input; execution must use Tool.parseArguments. */
        fun inputAsJson(): JsonElement = runCatching {
            json.parseToJsonElement(input.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }

        fun merge(other: Tool): Tool {
            return Tool(
                localCallId = localCallId,
                stepId = stepId,
                providerCallId = providerCallId.ifBlank { other.providerCallId },
                toolName = toolName + other.toolName,
                input = input + other.input,
                output = output + other.output,
                interactionState = interactionState,
                resultStatus = resultStatus ?: other.resultStatus,
                runtimeState = runtimeState,
                metadata = mergePartMetadata(metadata, other.metadata),
            )
        }
    }
}
