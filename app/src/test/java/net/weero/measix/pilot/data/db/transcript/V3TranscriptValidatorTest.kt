package net.weero.measix.pilot.data.db.transcript

import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class V3TranscriptValidatorTest {
    private val first = UIMessagePart.Step(
        stepId = Uuid.random(), ordinal = 0, startedAt = Instant.fromEpochMilliseconds(0),
        outcome = StepOutcome.Continue,
    )
    private val last = first.copy(stepId = Uuid.random(), ordinal = 1, outcome = StepOutcome.Final)
    private val tool = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = first.stepId, providerCallId = "call", toolName = "read",
        input = "{}", output = listOf(UIMessagePart.Text("result")), resultStatus = ToolResultStatus.COMPLETED,
    )

    @Test
    fun `only Continue permits another Step in both live and terminal transcripts`() {
        listOf(false, true).forEach { allowOpen ->
            V3TranscriptValidator.validateParts(listOf(first, tool, last), allowOpen)
            listOf(null, StepOutcome.Final, StepOutcome.Failed, StepOutcome.Cancelled,
                StepOutcome.Incomplete, StepOutcome.Interrupted).forEach { outcome ->
                assertThrows(IllegalArgumentException::class.java) {
                    V3TranscriptValidator.validateParts(listOf(first.copy(outcome = outcome), tool, last), allowOpen)
                }
            }
        }
    }

    @Test
    fun `tool output cannot contain Step at any nesting depth`() {
        listOf(listOf(first), listOf(tool.copy(output = listOf(first)))).forEach { output ->
            assertThrows(IllegalArgumentException::class.java) {
                V3TranscriptValidator.validateParts(listOf(first, tool.copy(output = output), last), allowOpen = false)
            }
        }
    }
}
