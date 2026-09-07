package net.weero.measix.pilot.service.turn

import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isCompleteImageUrl
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.mergeMessageMetadata
import me.rerere.ai.ui.mergeReasoningPartMetadata
import me.rerere.ai.ui.renderableImageUrl
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The single streaming merge owner for one durable Turn's active Assistant message.
 *
 * It replaces the old `UIMessage.appendChunk` / `handleMessageChunk` free functions and makes the
 * Step boundary explicit: [beginStep] opens a new logical model Step (one Provider sampling), and
 * every merged chunk lands inside the current Step region. A pending Tool Call is stamped with the
 * current `stepId` and a freshly assigned random `localCallId` the first time its `providerCallId`
 * is seen within the Step; later deltas for the same call merge into that identity. Providers only
 * ever emit `stepId = Uuid.NIL` / `localCallId = Uuid.NIL` placeholders — real ids exist solely here
 * and in the committed transcript, never in a transient delta that reaches storage.
 *
 * The class is stateful across one Step and reused across the Turn; it owns no coroutine and touches
 * no durable store.
 */
internal class StepOutputAccumulator(
    private val assistantMessageId: Uuid,
    initialStepId: Uuid = Uuid.NIL,
    initialStepOrdinal: Int = -1,
    initialStepOpened: Boolean = false,
    private var preopenedStepPending: Boolean = false,
) {
    private var stepId: Uuid = initialStepId
    private var stepOrdinal: Int = initialStepOrdinal
    private var stepOpened = initialStepOpened

    /**
     * Open the next logical Step. When `START` pre-opened the trailing Step and it has not yet been
     * sampled, the first call reuses that Step (no new `Step` part is emitted); every later call
     * advances the ordinal and assigns a fresh id, so ordinals stay strictly increasing across
     * continuations instead of restarting at 0.
     */
    fun beginStep() {
        if (preopenedStepPending) {
            preopenedStepPending = false
            return
        }
        stepOrdinal += 1
        stepId = Uuid.random()
        stepOpened = false
    }

    /** The id of the Step currently being streamed; `NIL` before the first [beginStep]. */
    val currentStepId: Uuid get() = stepId

    companion object {
        /**
         * Seed the accumulator from the durable Assistant transcript already present in the draft.
         * A `START`-pre-opened, still-empty trailing Step is reused for the first sampling; otherwise
         * the ordinal continues after the last committed Step so a continuation never re-emits an
         * existing ordinal.
         */
        fun fromDraft(assistantMessageId: Uuid, activeMessage: UIMessage?): StepOutputAccumulator {
            val parts = activeMessage?.parts.orEmpty()
            val lastStepIndex = parts.indexOfLast { it is UIMessagePart.Step }
            if (lastStepIndex < 0) {
                return StepOutputAccumulator(assistantMessageId)
            }
            val step = parts[lastStepIndex] as UIMessagePart.Step
            val preopenedAndUnsampled = parts.drop(lastStepIndex + 1).isEmpty() && step.outcome == null
            return StepOutputAccumulator(
                assistantMessageId = assistantMessageId,
                initialStepId = step.stepId,
                initialStepOrdinal = step.ordinal,
                initialStepOpened = true,
                preopenedStepPending = preopenedAndUnsampled,
            )
        }
    }

    /**
     * Merge one Provider chunk into the active Assistant message (the last element of [messages]).
     * Historical messages are immutable and never re-scanned: only the trailing Assistant variant is
     * copied and rebuilt, so a chunk costs O(current Step parts), not O(branch).
     */
    fun accumulate(messages: List<UIMessage>, chunk: MessageChunk, model: Model?): List<UIMessage> {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        val active = messages.last()
        return messages.dropLast(1) + mergeIntoAssistant(active, chunk, model)
    }

    private fun mergeIntoAssistant(active: UIMessage, chunk: MessageChunk, model: Model?): UIMessage {
        val choice = chunk.choices.getOrNull(0) ?: return active
        val delta = choice.delta ?: choice.message ?: return active

        var parts = active.parts
        if (!stepOpened) {
            parts = parts + UIMessagePart.Step(
                stepId = stepId,
                ordinal = stepOrdinal,
                startedAt = Clock.System.now(),
            )
            stepOpened = true
        }

        parts = delta.parts.fold(parts) { acc, deltaPart -> mergePart(acc, deltaPart) }

        // Close reasoning that stopped receiving deltas this chunk.
        if (active.parts.filterIsInstance<UIMessagePart.Reasoning>().isNotEmpty() &&
            delta.parts.filterIsInstance<UIMessagePart.Reasoning>().isEmpty()
        ) {
            parts = parts.map { part ->
                if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                    part.copy(finishedAt = Clock.System.now())
                } else {
                    part
                }
            }
        }

        val annotations = delta.annotations.ifEmpty { active.annotations }
        return active.copy(
            modelId = active.modelId ?: model?.id,
            parts = parts,
            annotations = annotations,
            providerMetadata = mergeMessageMetadata(active.providerMetadata, delta.providerMetadata),
        )
    }

    private fun mergePart(acc: List<UIMessagePart>, deltaPart: UIMessagePart): List<UIMessagePart> {
        val stepStart = acc.currentStepStart()
        return when (deltaPart) {
            is UIMessagePart.Text -> {
                if (deltaPart.text.isEmpty() && deltaPart.metadata == null) {
                    acc
                } else {
                    val insertIndex = acc.firstPendingToolIndex(stepStart)
                    val lastPart = acc.getOrNull(insertIndex - 1)
                    if (lastPart is UIMessagePart.Text &&
                        !lastPart.hasProviderPartBoundary() &&
                        !deltaPart.hasProviderPartBoundary()
                    ) {
                        acc.mapIndexed { index, part ->
                            if (index == insertIndex - 1) {
                                lastPart.copy(text = lastPart.text + deltaPart.text)
                            } else {
                                part
                            }
                        }
                    } else {
                        acc.insertAt(insertIndex, deltaPart)
                    }
                }
            }

            is UIMessagePart.Image -> {
                val insertIndex = acc.firstPendingToolIndex(stepStart)
                val lastPart = acc.getOrNull(insertIndex - 1)
                val incomingComplete = isCompleteImageUrl(deltaPart.url)
                if (lastPart is UIMessagePart.Image &&
                    !lastPart.hasProviderPartBoundary() &&
                    !deltaPart.hasProviderPartBoundary() &&
                    !incomingComplete
                ) {
                    acc.mapIndexed { index, part ->
                        if (index == insertIndex - 1) {
                            lastPart.copy(
                                url = lastPart.url + deltaPart.url,
                                metadata = deltaPart.metadata ?: lastPart.metadata,
                            )
                        } else {
                            part
                        }
                    }
                } else {
                    acc.insertAt(
                        insertIndex,
                        UIMessagePart.Image(
                            url = renderableImageUrl(deltaPart.url),
                            metadata = deltaPart.metadata,
                        ),
                    )
                }
            }

            is UIMessagePart.Reasoning -> {
                if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) {
                    acc
                } else {
                    val reasoningIndex = (acc.lastIndex downTo stepStart).firstOrNull { index ->
                        acc[index] is UIMessagePart.Reasoning
                    }
                    if (reasoningIndex != null &&
                        !acc[reasoningIndex].hasProviderPartBoundary() &&
                        !deltaPart.hasProviderPartBoundary()
                    ) {
                        val existing = acc[reasoningIndex] as UIMessagePart.Reasoning
                        acc.mapIndexed { index, part ->
                            if (index == reasoningIndex) {
                                UIMessagePart.Reasoning(
                                    reasoning = existing.reasoning + deltaPart.reasoning,
                                    createdAt = existing.createdAt,
                                    finishedAt = null,
                                    metadata = mergeReasoningPartMetadata(
                                        existing.metadata,
                                        deltaPart.metadata,
                                    ),
                                )
                            } else {
                                part
                            }
                        }
                    } else {
                        acc.insertAt(reasoningIndex?.plus(1) ?: stepStart, deltaPart)
                    }
                }
            }

            is UIMessagePart.Tool -> mergeTool(acc, stepStart, deltaPart)

            else -> acc
        }
    }

    private fun mergeTool(
        acc: List<UIMessagePart>,
        stepStart: Int,
        deltaPart: UIMessagePart.Tool,
    ): List<UIMessagePart> {
        if (deltaPart.providerCallId.isBlank()) {
            // A blank-ID delta continues the latest pending tool in this Step.
            val lastTool = acc.subList(stepStart, acc.size)
                .lastOrNull { it is UIMessagePart.Tool && !it.hasReplayResult } as? UIMessagePart.Tool
            return if (lastTool != null) {
                acc.map { part -> if (part === lastTool) part.merge(deltaPart) else part }
            } else {
                acc + stampNewTool(deltaPart)
            }
        }
        // Has an id: only merge inside the current Step; an executed tool from an earlier Step is
        // immutable history and must never be reopened.
        val existingIndex = (stepStart until acc.size).firstOrNull { index ->
            (acc[index] as? UIMessagePart.Tool)?.providerCallId == deltaPart.providerCallId
        }
        return if (existingIndex == null) {
            acc + stampNewTool(deltaPart)
        } else {
            acc.mapIndexed { index, part ->
                if (index == existingIndex) {
                    (part as UIMessagePart.Tool).merge(deltaPart)
                } else {
                    part
                }
            }
        }
    }

    /** Assign the current Step id and a fresh random local call id to a newly seen tool call. */
    private fun stampNewTool(tool: UIMessagePart.Tool): UIMessagePart.Tool =
        tool.copy(stepId = stepId, localCallId = Uuid.random())

    private fun List<UIMessagePart>.currentStepStart(): Int =
        indexOfLast { it is UIMessagePart.Step } + 1

    private fun List<UIMessagePart>.firstPendingToolIndex(stepStart: Int): Int {
        val relativeIndex = subList(stepStart, size).indexOfFirst {
            it is UIMessagePart.Tool && !it.hasReplayResult
        }
        return if (relativeIndex >= 0) stepStart + relativeIndex else size
    }

    private fun List<UIMessagePart>.insertAt(index: Int, part: UIMessagePart): List<UIMessagePart> =
        toMutableList().apply { add(index, part) }

    private fun UIMessagePart.hasProviderPartBoundary(): Boolean {
        val googleMetadata = metadataAs<GoogleThoughtMetadata>()
        val claudeMetadata = metadataAs<ClaudeReasoningMetadata>()
        return googleMetadata?.thoughtSignature != null ||
            googleMetadata?.inlineData != null ||
            claudeMetadata?.redactedData != null
    }
}
