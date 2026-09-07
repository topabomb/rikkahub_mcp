package me.rerere.ai.testsupport

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolResultStatus
import kotlin.uuid.Uuid

/**
 * Provider request-contract fixtures. Each adapter serializes the SAME canonical input into its
 * own wire; keeping the shared construction here means the common case is maintained once, and each
 * adapter test only expresses its provider-specific assertions (no per-adapter common-case dup).
 */

/** An executed tool part with a completed replay result — identical shape every adapter consumes. */
fun executedTool(
    callId: String,
    name: String,
    input: String,
    output: String,
    stepId: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
): UIMessagePart.Tool = UIMessagePart.Tool(
    localCallId = Uuid.random(),
    stepId = stepId,
    providerCallId = callId,
    toolName = name,
    input = input,
    resultStatus = ToolResultStatus.COMPLETED,
    output = listOf(UIMessagePart.Text(output)),
)

/**
 * Canonical multi-round tool turn: a user prompt followed by one assistant message that interleaves
 * prose with two executed tool calls (search → calculate). Claude, Google and the Responses adapter
 * all assert their own wire against this exact input.
 */
fun canonicalMultiRoundToolTurn(): List<UIMessage> = listOf(
    UIMessage.user("Calculate something"),
    UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Text("Let me search"),
            executedTool("call_1", "search", """{"query": "test"}""", "Search result"),
            UIMessagePart.Text("Now calculating"),
            executedTool("call_2", "calculate", """{"expr": "2+2"}""", "4", stepId = Uuid.random()),
            UIMessagePart.Text("The answer is 4"),
        ),
    ),
)

/** No prose separates these Steps: the durable tool identity must retain their causal order. */
fun consecutiveToolSteps(): List<UIMessage> = listOf(
    UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            executedTool("call_1", "lookup", "{}", "one", stepId = Uuid.random()),
            executedTool("call_2", "lookup", "{}", "two", stepId = Uuid.random()),
        ),
    ),
)
