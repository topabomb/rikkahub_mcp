package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.SyntheticMessageKind
import net.weero.measix.pilot.data.model.InjectionPosition
import net.weero.measix.pilot.service.turn.ResolvedPromptInjection

object PromptInjectionTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        transformMessages(
            messages = messages,
            injections = ctx.promptInputs.promptInjections,
            isHistory = { !ctx.requestOrigins.isSynthetic(it) },
            onInjection = { message, rules ->
                ctx.requestOrigins.markSynthetic(message, SyntheticMessageKind.PROMPT_INJECTION)
                message.parts.filterIsInstance<UIMessagePart.Text>().zip(rules).forEach { (part, rule) ->
                    ctx.requestOrigins.markPart(part, RequestPartSource.PromptRule(rule))
                }
            },
        )
}

/** Resolve every position against the same retained history before adding request projections. */
internal fun transformMessages(
    messages: List<UIMessage>,
    injections: List<ResolvedPromptInjection>,
    isHistory: (UIMessage) -> Boolean = { true },
    onInjection: (UIMessage, List<ResolvedPromptInjection>) -> Unit = { _, _ -> },
): List<UIMessage> {
    if (injections.isEmpty()) return messages
    val ordered = injections.sortedByDescending { it.priority }
    val historyIndices = messages.indices.filter {
        messages[it].role != MessageRole.SYSTEM && isHistory(messages[it]) &&
            messages[it].parts.any { part -> part !is UIMessagePart.Step }
    }
    val history = historyIndices.map(messages::get)
    val atBoundary = linkedMapOf<Int, MutableList<ResolvedPromptInjection>>()
    for (rule in ordered) {
        val historyIndex = when (rule.position) {
            InjectionPosition.TOP_OF_CHAT -> history.indexOfFirst { it.role == MessageRole.USER }
                .takeIf { it >= 0 } ?: history.size
            InjectionPosition.BOTTOM_OF_CHAT -> (history.size - 1).coerceAtLeast(0)
            InjectionPosition.AT_DEPTH -> (history.size - rule.injectDepth.coerceAtLeast(1)).coerceAtLeast(0)
            else -> continue
        }
        val safe = findSafeInsertIndex(history, historyIndex)
        var boundary = historyIndices.getOrNull(safe) ?: messages.size
        // Keep a time reminder next to its USER instead of inserting a rule between the pair.
        val precedingHistory = historyIndices.getOrNull(safe - 1) ?: -1
        while (boundary > precedingHistory + 1 && messages[boundary - 1].role != MessageRole.SYSTEM) boundary--
        atBoundary.getOrPut(boundary) { mutableListOf() } += rule
    }
    return buildList {
        for (index in 0..messages.size) {
            atBoundary[index]?.let { rules ->
                var start = 0
                while (start < rules.size) {
                    var end = start + 1
                    while (end < rules.size && rules[end].role == rules[start].role) end++
                    val group = rules.subList(start, end)
                    val injection = UIMessage(role = rules[start].role, parts = group.map { UIMessagePart.Text(it.content) })
                    onInjection(injection, group)
                    add(injection)
                    start = end
                }
            }
            if (index == messages.size) break
            add(messages[index])
        }
    }
}

internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)
    while (index > 0 && messages.getOrNull(index)?.let {
        it.role == MessageRole.ASSISTANT && it.getTools().isNotEmpty()
    } == true && messages[index - 1].role == MessageRole.USER) index--
    return index
}
