package net.weero.measix.pilot.service.turn

import me.rerere.ai.ui.UIMessage

/** Immutable view: each streamed draft shares the frozen history without copying its elements. */
private class AssistantMessageView(
    val history: List<UIMessage>,
    val assistant: UIMessage,
) : AbstractList<UIMessage>() {
    override val size: Int get() = history.size + 1
    override fun get(index: Int): UIMessage = when {
        index == history.size -> assistant
        else -> history[index]
    }
}

internal fun List<UIMessage>.withAssistant(assistant: UIMessage): List<UIMessage> {
    require(isNotEmpty())
    val history = if (this is AssistantMessageView) history else subList(0, lastIndex)
    return AssistantMessageView(history, assistant)
}
