package net.weero.measix.pilot.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Matches a think tag that opens at the very start of the text (after optional leading whitespace).
 *
 * The pattern is anchored with \A so a literal `</think>` used as an example or discussion in the
 * middle of the body is never mistaken for reasoning. Only the first non-whitespace Text part of
 * the current assistant-to-tool step is eligible; subsequent Text parts are left untouched.
 */
private val THINKING_OPEN_REGEX = Regex("\\A\\s*<think>([\\s\\S]*?)(?:</think>|\\z)", RegexOption.DOT_MATCHES_ALL)
private const val THINKING_OPEN_TAG = "<think>"

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer, StreamingMessageTransformer {
    internal data class PhaseContent(
        val hasReasoning: Boolean,
        val hasAnswer: Boolean,
        val undecided: Boolean = false,
    )

    /** Classifies the raw accumulated provider message using the same tag rule as projection. */
    internal fun classifyPhase(message: UIMessage): PhaseContent? {
        if (message.role != MessageRole.ASSISTANT) return null
        val currentStep = message.currentStepParts()
        if (currentStep.any { it is UIMessagePart.Reasoning }) return null
        val textParts = currentStep.filterIsInstance<UIMessagePart.Text>()
        val text = textParts.firstOrNull { it.text.isNotBlank() }?.text
            ?: return PhaseContent(hasReasoning = false, hasAnswer = false, undecided = textParts.isNotEmpty())
        if (text.isPendingThinkPrefix()) {
            return PhaseContent(hasReasoning = false, hasAnswer = false, undecided = true)
        }
        val match = THINKING_OPEN_REGEX.find(text) ?: return null
        val strippedText = text.substring(match.range.last + 1).trimStart()
        return PhaseContent(hasReasoning = true, hasAnswer = strippedText.isNotEmpty())
    }

    override suspend fun transformStreaming(
        ctx: TransformerContext,
        message: UIMessage,
        previousProjection: UIMessage?,
    ): UIMessage = handleThinkTag(
        message = message,
        hidePendingPrefix = true,
        previousClosedAt = previousProjection
            ?.takeIf { it.id == message.id }
            ?.currentStepParts()
            ?.filterIsInstance<UIMessagePart.Reasoning>()
            ?.firstOrNull()
            ?.finishedAt,
    )

    override suspend fun onStreamingFinish(
        ctx: TransformerContext,
        message: UIMessage,
        previousProjection: UIMessage?,
    ): UIMessage = handleThinkTag(
        message = message,
        previousClosedAt = previousProjection
            ?.takeIf { it.id == message.id }
            ?.currentStepParts()
            ?.filterIsInstance<UIMessagePart.Reasoning>()
            ?.firstOrNull()
            ?.finishedAt,
        terminalFinishedAt = Clock.System.now(),
    )

    /**
     * Extract think-tag reasoning from the first non-whitespace Text part of the current assistant
     * step (the parts after the last executed Tool).
     *
     * - Only the first non-whitespace Text part is examined; literal tags in later parts are never
     *   mistaken for reasoning.
     * - If the current step already carries Provider-native [UIMessagePart.Reasoning], the tag
     *   fallback is suppressed for that step. Reasoning in a completed tool step does not suppress
     *   a later step.
     * - Idempotent: re-applying to a message whose tag was already stripped is a no-op because the
     *   remaining text no longer starts with `<think>`.
     * - A received closing tag closes reasoning immediately. [onStreamingFinish] supplies a
     *   timestamp only when the stream ends without a closing tag.
     */
    private fun handleThinkTag(
        message: UIMessage,
        hidePendingPrefix: Boolean = false,
        previousClosedAt: Instant? = null,
        terminalFinishedAt: Instant? = null,
    ): UIMessage {
        if (message.role != MessageRole.ASSISTANT) return message
        val stepStart = message.currentStepStart()
        val currentStep = message.parts.subList(stepStart, message.parts.size)
        if (currentStep.none { it is UIMessagePart.Text }) return message
        // Suppress tag-derived reasoning only when this Provider step already has native reasoning.
        if (currentStep.any { it is UIMessagePart.Reasoning }) return message

        val relativeTextIndex = currentStep.indexOfFirst { it is UIMessagePart.Text && it.text.isNotBlank() }
        if (relativeTextIndex < 0) return message
        val firstTextIndex = stepStart + relativeTextIndex

        val firstTextPart = message.parts[firstTextIndex] as UIMessagePart.Text
        if (hidePendingPrefix && firstTextPart.text.isPendingThinkPrefix()) {
            return message.copy(parts = message.parts.filterIndexed { index, _ -> index != firstTextIndex })
        }
        val match = THINKING_OPEN_REGEX.find(firstTextPart.text) ?: return message

        val reasoningText = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val strippedText = firstTextPart.text.substring(match.range.last + 1).trimStart()
        val effectiveFinishedAt = if (match.value.contains("</think>")) {
            previousClosedAt ?: Clock.System.now()
        } else {
            terminalFinishedAt
        }

        val reasoningPart = UIMessagePart.Reasoning(
            reasoning = reasoningText,
            createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
            finishedAt = effectiveFinishedAt,
        )
        val replacementTextPart = if (strippedText.isEmpty()) null else firstTextPart.copy(text = strippedText)

        val newParts = buildList {
            message.parts.forEachIndexed { index, part ->
                if (index != firstTextIndex) {
                    add(part)
                    return@forEachIndexed
                }
                add(reasoningPart)
                if (replacementTextPart != null) add(replacementTextPart)
            }
        }

        return message.copy(parts = newParts)
    }

    private fun String.isPendingThinkPrefix(): Boolean {
        val candidate = trimStart()
        return candidate.length < THINKING_OPEN_TAG.length && THINKING_OPEN_TAG.startsWith(candidate)
    }

    private fun UIMessage.currentStepStart(): Int =
        parts.indexOfLast { it is UIMessagePart.Tool && it.hasReplayResult } + 1

    private fun UIMessage.currentStepParts(): List<UIMessagePart> =
        parts.subList(currentStepStart(), parts.size)
}
