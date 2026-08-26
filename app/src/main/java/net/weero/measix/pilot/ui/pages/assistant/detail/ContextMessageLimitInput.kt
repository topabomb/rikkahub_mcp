package net.weero.measix.pilot.ui.pages.assistant.detail

import net.weero.measix.pilot.data.model.MAX_CONTEXT_MESSAGE_LIMIT
import net.weero.measix.pilot.data.model.MIN_CONTEXT_MESSAGE_LIMIT
import net.weero.measix.pilot.data.model.DEFAULT_CONTEXT_MESSAGE_LIMIT

internal fun parseContextMessageLimitInput(text: String): Int? {
    if (text.isEmpty() || text.any { !it.isDigit() }) return null
    val value = text.toIntOrNull() ?: return null
    return value.takeIf { it == 0 || it in MIN_CONTEXT_MESSAGE_LIMIT..MAX_CONTEXT_MESSAGE_LIMIT }
}

internal data class ContextMessageLimitEditState(
    val input: String,
    val focused: Boolean,
    val lastSubmitted: Int,
    val suppressNextFocusCommit: Boolean = false,
) {
    fun edit(text: String) = copy(input = text)

    fun observe(value: Int): ContextMessageLimitEditState = copy(
        input = if (focused) input else value.toString(),
        lastSubmitted = value,
    )

    fun toggle(enabled: Boolean): ContextMessageLimitTransition {
        val value = if (enabled) DEFAULT_CONTEXT_MESSAGE_LIMIT else 0
        return ContextMessageLimitTransition(
            copy(
                input = value.toString(),
                lastSubmitted = value,
                suppressNextFocusCommit = focused,
            ),
            value,
        )
    }

    fun focusChanged(hasFocus: Boolean): ContextMessageLimitTransition {
        if (hasFocus) return ContextMessageLimitTransition(copy(focused = true))
        if (suppressNextFocusCommit) {
            return ContextMessageLimitTransition(copy(focused = false, suppressNextFocusCommit = false))
        }
        return copy(focused = false).commit()
    }

    fun done(): ContextMessageLimitTransition = commit()

    private fun commit(): ContextMessageLimitTransition {
        val parsed = parseContextMessageLimitInput(input)
        return if (parsed == null || parsed == lastSubmitted) {
            ContextMessageLimitTransition(this)
        } else {
            ContextMessageLimitTransition(copy(lastSubmitted = parsed), parsed)
        }
    }

    companion object {
        fun initial(value: Int) = ContextMessageLimitEditState(value.toString(), false, value)
    }
}

internal data class ContextMessageLimitTransition(
    val state: ContextMessageLimitEditState,
    val submission: Int? = null,
)
