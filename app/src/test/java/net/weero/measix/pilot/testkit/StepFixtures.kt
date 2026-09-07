package net.weero.measix.pilot.testkit

import me.rerere.ai.core.UsageCompleteness
import me.rerere.ai.ui.StepModelResult
import me.rerere.ai.ui.StepUsage

/** An explicitly observed test sampling boundary; no wall-clock or provider usage is fabricated. */
internal fun sampledModelResult(finishReason: String = "tool_calls") = StepModelResult(
    finishReason = finishReason, usage = StepUsage(), providerRequestCount = 1,
    timeToFirstOutputMillis = null, requestDurationMillis = null,
    usageCompleteness = UsageCompleteness.NONE, providerMetadata = null,
)
