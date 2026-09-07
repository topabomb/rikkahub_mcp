package net.weero.measix.pilot.service.turn

/** Transient progress emitted by the Turn runner; durable Tool phases come from checkpoints. */
internal enum class TurnRunPhase {
    PREPARING,
    MODEL_WAITING,
    REASONING_STREAMING,
    ANSWER_STREAMING,
    TOOL_PREPARING,
    TOOL_EXECUTING,
    BETWEEN_STEPS,
}
