package net.weero.measix.pilot.service.runtime

/**
 * Stable presentation phase of one tool call inside an active Assistant message.
 *
 * Model call assembly and tool execution are separate phases: a tool can already have a
 * complete name/input while it is waiting for approval or running remotely, but still have no
 * protocol output. Keeping that distinction in the Runtime projection prevents UI code from
 * treating `output.isEmpty()` as the meaning of every in-flight state.
 */
enum class ToolCallPhase {
    CALL_STREAMING,
    READY,
    AWAITING_APPROVAL,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    DENIED,
    ANSWERED,
}

val ToolCallPhase.isBusy: Boolean
    get() = this == ToolCallPhase.CALL_STREAMING || this == ToolCallPhase.EXECUTING
