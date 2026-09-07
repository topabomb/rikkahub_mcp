package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.UsageCompleteness

/**
 * The terminal classification of one logical model Step. A Step is closed exactly once; the
 * outcome is durable and never re-derived from message content.
 *
 * `Continue` means the Step produced a replayable Tool batch that the next Step consumes.
 * `Final` means the Step produced the Turn's final text with no pending Tool batch. The remaining
 * outcomes are non-success closes; `AWAITING_USER` is deliberately absent because waiting for a
 * user is a Turn pause, not a Step outcome.
 */
@Serializable
sealed class StepOutcome {
    @Serializable
    @SerialName("continue")
    data object Continue : StepOutcome()

    @Serializable
    @SerialName("final")
    data object Final : StepOutcome()

    @Serializable
    @SerialName("failed")
    data object Failed : StepOutcome()

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : StepOutcome()

    @Serializable
    @SerialName("incomplete")
    data object Incomplete : StepOutcome()

    @Serializable
    @SerialName("interrupted")
    data object Interrupted : StepOutcome()
}

/**
 * Token accounting for a single Step (one logical model sampling). Turn totals remain the
 * accumulated [me.rerere.ai.core.TokenUsage] on the Assistant message; this is the per-Step
 * slice that lets usage, recovery and compaction receipts stop guessing boundaries.
 */
@Serializable
data class StepUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
    val toolUseInputTokens: Long? = null,
    val totalTokens: Long? = null,
)

/**
 * The model-side result of a completed Step: the finish reason, per-Step usage, the number of
 * real Provider request attempts folded into this logical Step, timing, and the opaque Provider
 * protocol state needed for lossless replay.
 */
@Serializable
data class StepModelResult(
    val finishReason: String?,
    val usage: StepUsage,
    val providerRequestCount: Int,
    val timeToFirstOutputMillis: Long?,
    val requestDurationMillis: Long?,
    val usageCompleteness: UsageCompleteness,
    val providerMetadata: JsonObject?,
)
