package net.weero.measix.pilot.benchmark

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.freeze
import android.os.Bundle
import android.widget.TextView
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.UsageCompleteness
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.StepModelResult
import me.rerere.ai.ui.StepUsage
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.ModelRequestReceipt
import net.weero.measix.pilot.data.ai.request.RequestAssembler
import net.weero.measix.pilot.data.ai.request.RequestContextPlanner
import net.weero.measix.pilot.data.ai.tools.ToolOutputCompactionPlanner
import net.weero.measix.pilot.service.turn.StepOutputAccumulator
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Installed only in the Macrobenchmark target; production APKs contain no workload entry point. */
class TurnWorkloadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workload = requireNotNull(intent.getStringExtra("workload"))
        if (workload == "compose_100") {
            setContent { ComposeTurnWorkload() }
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            when (workload) {
                "stream_10000" -> streamChunks()
                "request_1000" -> assembleHistory()
                "compaction_100" -> planCompaction()
                "schema_50" -> freezeFiftySchemas()
                "migration_1000" -> TurnPersistenceWorkloads(this@TurnWorkloadActivity).use { it.migrateLargeRoom() }
                "output_100mb" -> TurnPersistenceWorkloads(this@TurnWorkloadActivity).use { it.readAndGrepHundredMegabytes() }
                else -> error("Unknown workload: $workload")
            }
            withContext(Dispatchers.Main) {
                setContentView(TextView(this@TurnWorkloadActivity).apply { text = "done:$workload" })
            }
        }
    }

    private fun freezeFiftySchemas() {
        val definitions = (1..50).map { index ->
            Tool(name = "tool_$index", description = "Tool $index", parameters = {
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        repeat(20) { field -> put("field_$field", buildJsonObject {
                            put("type", "string")
                            put("description", "A stable parameter description for tool $index field $field")
                        }) }
                    })
                }
            }, execute = { error("Schema benchmark must not execute tools") })
        }
        val planner = RequestContextPlanner()
        val messages = listOf(UIMessage.user("Choose the appropriate tool."))
        val tokens = measureWorkload("turn_schema_50") {
            val frozen = definitions.map { it.freeze() }
            check(frozen.size == 50)
            planner.estimateRequestContextTokens(messages, frozen)
        }
        check(tokens > 0)
    }

    private fun streamChunks() {
        val accumulator = StepOutputAccumulator()
        var active = UIMessage(
            id = id(1),
            role = MessageRole.ASSISTANT,
            parts = listOf(step(1)),
        )
        val chunk = MessageChunk(
            id = "chunk",
            model = "benchmark",
            choices = listOf(UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("x"))),
                message = null,
                finishReason = null,
            )),
        )
        measureWorkload("turn_stream_10000") {
            repeat(10_000) { active = accumulator.accumulate(active, chunk, null) }
        }
        check(active.parts.filterIsInstance<UIMessagePart.Text>().single().text.length == 10_000)
        check(active.parts.filterIsInstance<UIMessagePart.Step>().single().stepId == id(1))
    }

    private fun assembleHistory() {
        val messages = (1..1_000).map { index ->
            if (index % 2 == 1) UIMessage(
                id = id(index), role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("Question $index: ${"context ".repeat(32)}")),
            ) else UIMessage(
                id = id(index), role = MessageRole.ASSISTANT,
                parts = listOf(step(index, closed = true), UIMessagePart.Text("Answer $index")),
            )
        }
        val planner = RequestContextPlanner()
        val assembler = RequestAssembler()
        val request = measureWorkload("turn_request_1000") {
            assembler.assemble(planner.planRequest(messages, messageLimit = 0).messages)
        }
        check(request.providerMessages.size == 1_000)
        check(request.providerMessages.none { message -> message.parts.any { it is UIMessagePart.Step } })
    }

    private fun planCompaction() {
        val payload = "tool output line\n".repeat(4_096)
        val messages = (1..100).map { index ->
            UIMessage(
                id = id(index), role = MessageRole.ASSISTANT,
                parts = listOf(
                    step(index, closed = true),
                    UIMessagePart.Tool(
                        localCallId = id(index + 1_000), stepId = id(index),
                        providerCallId = "call_$index", toolName = "read_file", input = "{}",
                        output = listOf(UIMessagePart.Text(payload)),
                        resultStatus = ToolResultStatus.COMPLETED,
                        runtimeState = ToolRuntimeState(outputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT),
                    ),
                ),
            )
        }
        val receipt = ModelRequestReceipt(messages.map { message ->
            val tool = message.getTools().single()
            ToolCallLocator(message.id, tool.stepId, tool.localCallId)
        }.toSet())
        val planner = ToolOutputCompactionPlanner()
        val plan = measureWorkload("turn_compaction_100") { planner.planAfterSuccessfulRequest(messages, receipt) }
        check(plan.candidates.isNotEmpty() && plan.candidates.size < 100)
        check(plan.netReclaimedEstimatedTokens > 0)
        check(messages.all { it.getTools().single().output.single() == UIMessagePart.Text(payload) })
    }

    private fun step(value: Int, closed: Boolean = false) = UIMessagePart.Step(
        stepId = id(value), ordinal = 0, startedAt = Instant.fromEpochMilliseconds(0),
        modelResult = if (closed) StepModelResult(
            finishReason = "stop", usage = StepUsage(), providerRequestCount = 1,
            timeToFirstOutputMillis = null, requestDurationMillis = null,
            usageCompleteness = UsageCompleteness.NONE, providerMetadata = null,
        ) else null,
        outcome = if (closed) StepOutcome.Final else null,
        finishedAt = if (closed) Instant.fromEpochMilliseconds(1) else null,
    )

    private fun id(value: Int): Uuid = Uuid.parse("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
}
