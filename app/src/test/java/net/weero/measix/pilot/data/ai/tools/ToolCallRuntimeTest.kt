package net.weero.measix.pilot.data.ai.tools

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.buildAskUserTool
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.service.runtime.resolveToolLivePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * [ToolCallRuntime] 的逐工具语义 owner：参数解析与契约拒绝、审批/交互门控与可用性降级、
 * 暂停与决策种类失配的 fail-closed、批次身份稳定投影，以及执行包装的失败/策略/超时/能力边界。
 * 整批编排（checkpoint、TurnPause、串行执行）归 `ToolBatchRunnerTest`。
 */
class ToolCallRuntimeTest {
    private val runtime = ToolCallRuntime(Json)
    private val messageId = Uuid.random()
    private val stepId = Uuid.random()
    private val failure = Json.parseToJsonElement("{\"error\":\"invalid_arguments\",\"field\":\"path\"}").jsonObject
    private val validatedTool = Tool(
        name = "validated",
        description = "validated",
        validateArguments = { failure },
        interactionRequirement = { error("Invalid arguments cannot request interaction") },
        execute = { error("Invalid arguments cannot execute") },
    )

    /** A Tool part with a stable identity inside [stepId]; the caller keeps the returned localCallId. */
    private fun tool(
        providerCallId: String,
        toolName: String,
        input: String,
        interactionState: ToolInteractionState = ToolInteractionState.NotRequired,
        output: List<UIMessagePart> = emptyList(),
        runtimeState: ToolRuntimeState = ToolRuntimeState(ToolOutputPolicy.ARCHIVABLE_TEXT),
    ) = UIMessagePart.Tool(
        localCallId = Uuid.random(),
        stepId = stepId,
        providerCallId = providerCallId,
        toolName = toolName,
        input = input,
        output = output,
        interactionState = interactionState,
        runtimeState = runtimeState,
    )

    private fun prepare(
        calls: List<UIMessagePart.Tool>,
        definitions: List<Tool>,
        availability: TurnInteractionCapability = TurnInteractionCapability.FULL,
    ): ToolBatchPreparation = runtime.prepareBatch(
        messageId = messageId,
        calls = calls.mapIndexed { ordinal, tool -> LocatedToolCall(ordinal, tool) },
        toolIndex = freezeToolSet(definitions).bindingsByName,
        availability = availability,
    )

    /** The batch as it stands after replacements (keyed by stable localCallId) are applied. */
    private fun List<UIMessagePart.Tool>.after(preparation: ToolBatchPreparation): List<UIMessagePart.Tool> =
        map { tool -> preparation.replacements[tool.localCallId] ?: tool }

    @Test
    fun `invalid inputs fail in every availability mode for all undecided or approved states`() {
        TurnInteractionCapability.entries.forEach { availability ->
            listOf(ToolInteractionState.NotRequired, ToolInteractionState.AwaitingApproval, ToolInteractionState.Approved).forEach { state ->
                listOf("{}", "{", "[]", "null").forEach { input ->
                    val call = tool("id", validatedTool.name, input, interactionState = state)
                    val preparation = prepare(listOf(call), listOf(validatedTool), availability)
                    val result = listOf(call).after(preparation).single()
                    assertTrue(preparation.pending.isEmpty())
                    assertFalse(result.isPending)
                    assertTrue(result.hasReplayResult)
                    val text = (result.output.single() as UIMessagePart.Text).text
                    assertTrue(text.contains("invalid_arguments"))
                    assertFalse(text.contains("approval_unavailable"))
                    assertEquals(ToolLivePhase.FAILED, resolveToolLivePhase(result, null))
                    assertEquals(listOf(ToolResultStatus.FAILED), preparation.immediateResults.map { it.status })
                }
            }
        }
    }

    @Test
    fun `denied and answered calls resolve without execution in every run mode`() {
        val denied = tool("d", "removed", "{", interactionState = ToolInteractionState.Denied("no"))
        val answered = tool("a", "removed", "{", interactionState = ToolInteractionState.Answered("yes"))
        val preparation = prepare(listOf(denied, answered), emptyList())
        assertEquals(2, preparation.resolvedCalls.size)
        assertTrue(preparation.resolvedCalls[0] is ResolvedToolCall.Denied)
        assertTrue(preparation.resolvedCalls[1] is ResolvedToolCall.Answered)
        val deniedResult = (preparation.resolvedCalls[0] as ResolvedToolCall.Denied).result
        assertTrue(
            (deniedResult.output.single() as UIMessagePart.Text).text
                .contains("Tool execution denied by user. Reason: no"),
        )
        val answeredResult = (preparation.resolvedCalls[1] as ResolvedToolCall.Answered).result
        assertEquals("yes", (answeredResult.output.single() as UIMessagePart.Text).text)
        assertEquals(ToolResultStatus.DENIED, deniedResult.resultStatus)
        assertEquals(ToolResultStatus.ANSWERED, answeredResult.resultStatus)
        assertTrue(preparation.pending.isEmpty())
    }

    @Test
    fun `missing tool rejects before parsing and clears stale pending`() {
        val call = tool("id", "removed", "{", interactionState = ToolInteractionState.AwaitingApproval)
        val preparation = prepare(listOf(call), emptyList())
        val result = listOf(call).after(preparation).single()
        assertFalse(result.isPending)
        assertTrue(preparation.pending.isEmpty())
        assertTrue((result.output.single() as UIMessagePart.Text).text.contains("tool_not_available"))
        assertEquals(ToolLivePhase.FAILED, resolveToolLivePhase(result, null))
    }

    @Test
    fun `invalid old pending does not survive beside a valid pending question`() {
        val valid = tool("ask", "ask_user", """{"questions":[{"id":"q","question":"Continue?"}]}""")
        val bad = tool("bad", validatedTool.name, "{}", interactionState = ToolInteractionState.AwaitingApproval)
        val preparation = prepare(listOf(bad, valid), listOf(validatedTool, buildAskUserTool()))
        assertTrue(preparation.pending.single().requiresUserInput)
        val updated = listOf(bad, valid).after(preparation)
        assertFalse(updated[0].isPending)
        assertEquals(ToolArgumentsException(failure).output, updated[0].output)
        assertTrue(updated[1].isPending)
    }

    @Test
    fun `a paused call keeps the interaction state it paused for`() {
        val askUser = tool("ask", "ask_user", """{"questions":[{"id":"q","question":"Continue?"}]}""")
        val preparation = prepare(listOf(askUser), listOf(buildAskUserTool()))
        val pending = preparation.replacements.getValue(askUser.localCallId)
        assertEquals(ToolInteractionState.AwaitingInput, pending.interactionState)
        assertEquals(ToolLivePhase.AWAITING_INPUT, resolveToolLivePhase(pending, null))
    }

    @Test
    fun `approval is not requested again after a valid approval`() {
        val definition = Tool(
            name = "approved",
            description = "approved",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { emptyList() },
        )
        val call = tool("a", "approved", "{}", interactionState = ToolInteractionState.Approved)
        val preparation = prepare(listOf(call), listOf(definition))
        val executable = preparation.resolvedCalls.single() as ResolvedToolCall.Executable
        assertTrue(executable.call.approvedByUser)
        assertEquals(call, executable.call.source)
        assertTrue(preparation.pending.isEmpty())
    }

    @Test
    fun `user input only runs reject approval tools and keep user input pausable`() {
        val approval = Tool(
            name = "requires_approval",
            description = "requires approval",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { error("Cannot execute") },
        )
        val call = tool("a", approval.name, "{}")
        val preparation = prepare(listOf(call), listOf(approval), TurnInteractionCapability.USER_INPUT_ONLY)
        val result = listOf(call).after(preparation).single()
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("approval_unavailable"))
        assertEquals(ToolLivePhase.FAILED, resolveToolLivePhase(result, null))

        val askUser = tool("ask", "ask_user", """{"questions":[{"id":"q","question":"Continue?"}]}""")
        val bridged = prepare(listOf(askUser), listOf(buildAskUserTool()), TurnInteractionCapability.USER_INPUT_ONLY)
        assertTrue(bridged.pending.single().requiresUserInput)
    }

    @Test
    fun `unattended runs reject both approval and user input without pausing`() {
        val approval = Tool(
            name = "requires_approval",
            description = "requires approval",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { error("Cannot execute") },
        )
        val askUser = tool("ask", "ask_user", """{"questions":[{"id":"q","question":"Continue?"}]}""")
        val approvalCall = tool("a", approval.name, "{}")
        val preparation = prepare(
            listOf(approvalCall, askUser),
            listOf(approval, buildAskUserTool()),
            TurnInteractionCapability.NONE,
        )
        assertTrue(preparation.pending.isEmpty())
        assertTrue(preparation.resolvedCalls.isEmpty())
        val first = preparation.replacements.getValue(approvalCall.localCallId)
        val second = preparation.replacements.getValue(askUser.localCallId)
        assertTrue((first.output.single() as UIMessagePart.Text).text.contains("approval_unavailable"))
        assertTrue((second.output.single() as UIMessagePart.Text).text.contains("input_unavailable"))
    }

    @Test
    fun `a decision kind that no longer matches the requirement fails closed`() {
        val definition = Tool(
            name = "ask_user",
            description = "still interactive",
            interactionRequirement = { ToolInteractionRequirement.UserInput },
            execute = { emptyList() },
        )
        val call = tool("a", "ask_user", "{}", interactionState = ToolInteractionState.Approved)
        val preparation = prepare(listOf(call), listOf(definition))
        val result = listOf(call).after(preparation).single()
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("interaction_state_invalid"))
    }

    @Test
    fun `automatic call with captured interaction cannot change meaning`() {
        val none = Tool(name = "changed", description = "changed", execute = { emptyList() })
        val approval = Tool(
            name = "changed",
            description = "changed",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { emptyList() },
        )
        // A captured AwaitingInput decision (requirement UserInput) must not be reinterpreted as
        // None or Approval; the gate fails it closed instead of silently changing its meaning.
        val call = tool("a", "changed", "{}", interactionState = ToolInteractionState.AwaitingInput)
        listOf(none, approval).forEach { definition ->
            val preparation = prepare(listOf(call), listOf(definition))
            assertTrue(preparation.resolvedCalls.isEmpty())
            assertTrue(preparation.pending.isEmpty())
            assertTrue(
                (preparation.replacements.getValue(call.localCallId).output.single() as UIMessagePart.Text).text
                    .contains("interaction_state_invalid"),
            )
        }
    }

    @Test
    fun `pending interactions block the whole batch including automatic calls`() {
        val automatic = Tool(name = "auto", description = "auto", execute = { emptyList() })
        val approval = Tool(
            name = "gate",
            description = "gate",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { error("Cannot execute") },
        )
        val autoCall = tool("1", "auto", "{}")
        val gateCall = tool("2", "gate", "{}")
        val preparation = prepare(listOf(autoCall, gateCall), listOf(automatic, approval))
        // The batch barrier is enforced by the loop: a non-empty pending means no
        // resolved call may execute this round, even the automatic one.
        assertTrue(preparation.pending.single().requiresApproval)
        assertTrue(preparation.replacements.getValue(gateCall.localCallId).isPending)
    }

    @Test
    fun `a paused batch keeps stable call identity after earlier failures disappear`() {
        val automatic = Tool(name = "auto", description = "auto", execute = { emptyList() })
        val approval = Tool(
            name = "gate",
            description = "gate",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { emptyList() },
        )
        val original = listOf(
            tool("bad", validatedTool.name, "{}"),
            tool("auto", automatic.name, "{}"),
            tool("gate", approval.name, "{}"),
        )
        val paused = prepare(original, listOf(validatedTool, automatic, approval))
        val durable = original.after(paused)
        // Only the gate call pauses; its identity is the locator, not a positional ordinal.
        assertEquals(1, paused.pending.size)
        assertEquals(original[2].localCallId, paused.pending.single().locator.localCallId)

        // Re-pass the surviving calls with fresh (different) ordinals; identity must not drift.
        val decided = durable.filterNot { it.hasReplayResult }.mapIndexed { position, t ->
            LocatedToolCall(
                position,
                if (t.toolName == approval.name) t.copy(interactionState = ToolInteractionState.Approved) else t,
            )
        }
        val resumed = runtime.prepareBatch(
            messageId = messageId,
            calls = decided,
            toolIndex = freezeToolSet(listOf(automatic, approval)).bindingsByName,
            availability = TurnInteractionCapability.FULL,
        )
        assertTrue(resumed.pending.isEmpty())
        assertEquals(
            listOf(original[1], original[2]).map { ToolCallLocator(messageId, stepId, it.localCallId) },
            resumed.resolvedCalls.filterIsInstance<ResolvedToolCall.Executable>().map { it.call.locator },
        )
    }

    @Test
    fun `an automatic batch keeps stable call identity before its first execution`() {
        val automatic = Tool(name = "auto", description = "auto", execute = { emptyList() })
        val original = listOf(tool("first", automatic.name, "{}"), tool("second", automatic.name, "{}"))

        val prepared = prepare(original, listOf(automatic))
        assertTrue(prepared.pending.isEmpty())
        // No runtime-state change is required, so no replacement is emitted; identity lives on the parts.
        assertTrue(prepared.replacements.isEmpty())
        original.forEach { assertEquals(ToolRuntimeState(ToolOutputPolicy.ARCHIVABLE_TEXT), it.runtimeState) }

        val resumed = runtime.prepareBatch(
            messageId = messageId,
            calls = listOf(LocatedToolCall(1, original[1])),
            toolIndex = freezeToolSet(listOf(automatic)).bindingsByName,
            availability = TurnInteractionCapability.FULL,
        )
        assertEquals(
            ToolCallLocator(messageId, stepId, original[1].localCallId),
            resumed.resolvedCalls.filterIsInstance<ResolvedToolCall.Executable>().single().call.locator,
        )
    }

    @Test
    fun `runtime rejection and structured business failure both project as failed`() {
        val details = Json.parseToJsonElement("{\"status\":\"failed\",\"reason\":\"invalid_arguments\"}").jsonObject
        val definition = Tool(
            name = "domain", description = "domain", validateArguments = { details },
            execute = { emptyList() },
        )
        val call = tool("a", definition.name, "{}")
        val preparation = prepare(listOf(call), listOf(definition))
        val rejected = listOf(call).after(preparation).single()
        val businessResult = call.copy(
            output = listOf(UIMessagePart.Text(details.toString())),
            resultStatus = ToolResultStatus.FAILED,
        )
        assertEquals(ToolLivePhase.FAILED, resolveToolLivePhase(rejected, null))
        assertEquals(ToolLivePhase.FAILED, resolveToolLivePhase(businessResult, null))
    }

    @Test
    fun `arbitrary business result shapes cannot crash historical phase projection`() {
        listOf("[]", "text", "{\"status\":[]}", "{\"error\":{},\"type\":{}}", "{\"error\":{}}")
            .forEach { output ->
                val tool = tool("a", "remote", "{}", output = listOf(UIMessagePart.Text(output)))
                assertEquals(ToolLivePhase.COMPLETED, resolveToolLivePhase(tool, null))
            }
        listOf("error", "timeout").forEach { type ->
            val tool = tool(
                "a", "remote", "{}", output = listOf(UIMessagePart.Text("{\"error\":\"failed\",\"type\":\"$type\"}")),
            ).copy(resultStatus = ToolResultStatus.FAILED)
            assertEquals(ToolLivePhase.FAILED, resolveToolLivePhase(tool, null))
        }
    }

    @Test
    fun `generic implementation error becomes a compact stable failure`() = runTest {
        val outcome = runtime.execute(prepared(Tool("boom", "boom", execute = { error("secret path") })), hooks())

        assertTrue(outcome.executionFailed)
        assertEquals(
            "{\"status\":\"failed\",\"reason\":\"tool_failed\"}",
            (outcome.output.single() as UIMessagePart.Text).text,
        )
        assertEquals(ToolResultStatus.FAILED, outcome.resultStatus)
    }

    @Test
    fun `domain failure keeps the tool owned replay result`() = runTest {
        val expected = listOf(UIMessagePart.Text("domain explanation"))
        val tool = Tool(
            "domain",
            "domain",
            execute = { throw ToolExecutionFailure(expected, "domain failure") },
        )

        val outcome = runtime.execute(prepared(tool), hooks())

        assertTrue(outcome.executionFailed)
        assertEquals(expected, outcome.output)
        assertEquals(ToolOutputPolicy.ARCHIVABLE_TEXT, outcome.outputPolicy)
        assertEquals(ToolResultStatus.FAILED, outcome.resultStatus)
    }

    @Test
    fun `only a successful result may resolve a narrower archive policy`() = runTest {
        val successfulTool = Tool(
            name = "dynamic",
            description = "dynamic",
            outputPolicy = ToolOutputPolicy.PRESERVE,
            successfulOutputPolicy = { ToolOutputPolicy.ARCHIVABLE_TEXT },
            execute = { listOf(UIMessagePart.Text("result")) },
        )
        val success = runtime.execute(prepared(successfulTool), hooks())
        assertEquals(ToolOutputPolicy.ARCHIVABLE_TEXT, success.outputPolicy)

        val failedTool = successfulTool.copy(
            execute = { throw ToolExecutionFailure(listOf(UIMessagePart.Text("failed")), "failed") },
        )
        val failure = runtime.execute(prepared(failedTool), hooks())
        assertEquals(ToolOutputPolicy.PRESERVE, failure.outputPolicy)

        val brokenResolver = successfulTool.copy(
            outputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT,
            successfulOutputPolicy = { error("policy bug") },
        )
        val preservedSuccess = runtime.execute(prepared(brokenResolver), hooks())
        assertEquals("result", (preservedSuccess.output.single() as UIMessagePart.Text).text)
        assertEquals(ToolOutputPolicy.PRESERVE, preservedSuccess.outputPolicy)
    }

    @Test
    fun `a registered artifact forces preserve for successful and failed results`() = runTest {
        val lease = ToolResourceLease(publish = {}, discard = {})
        val successful = Tool(
            name = "artifact-success",
            description = "artifact-success",
            successfulOutputPolicy = { ToolOutputPolicy.ARCHIVABLE_TEXT },
            execute = { emptyList() },
            contextualExecute = {
                registerUnpublishedResource(lease)
                listOf(UIMessagePart.Text("delivered artifact"))
            },
        )
        val success = runtime.execute(
            prepared(successful),
            hooks(registerUnpublishedResource = {}),
        )
        assertEquals(ToolOutputPolicy.PRESERVE, success.outputPolicy)

        val failed = successful.copy(
            name = "artifact-failure",
            contextualExecute = {
                registerUnpublishedResource(lease)
                throw ToolExecutionFailure(listOf(UIMessagePart.Text("failed after artifact")), "failed")
            },
        )
        val failure = runtime.execute(
            prepared(failed),
            hooks(registerUnpublishedResource = {}),
        )
        assertEquals(ToolOutputPolicy.PRESERVE, failure.outputPolicy)
    }

    @Test
    fun `inner timeout is a tool failure while outer timeout remains cancellation`() = runTest {
        val inner = Tool(
            "inner",
            "inner",
            execute = { withTimeout(1) { awaitCancellation() } },
        )
        val innerOutcome = runtime.execute(prepared(inner), hooks())
        assertEquals(
            "{\"status\":\"failed\",\"reason\":\"tool_timeout\"}",
            (innerOutcome.output.single() as UIMessagePart.Text).text,
        )

        val outer = Tool("outer", "outer", execute = { awaitCancellation() })
        var cancelled = false
        try {
            withTimeout(1) { runtime.execute(prepared(outer), hooks()) }
        } catch (_: TimeoutCancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `runtime capability failure propagates instead of becoming a tool result`() {
        val tool = Tool(
            name = "contextual",
            description = "contextual",
            execute = { emptyList() },
            contextualExecute = {
                reportMetadata(buildJsonObject { put("progress", 1) }, ToolMetadataDelivery.CHECKPOINT)
                emptyList()
            },
        )

        assertThrows(ToolRuntimeInfrastructureException::class.java) {
            runTest {
                runtime.execute(
                    prepared(tool),
                    hooks(reportMetadata = { _, _ -> error("checkpoint unavailable") }),
                )
            }
        }
    }

    /** 通过正式 gate 构造 PreparedToolCall，避免测试绕过参数解析与批次身份规则。 */
    private fun prepared(tool: Tool): PreparedToolCall {
        val source = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
            toolName = tool.name, input = "{}",
        )
        val preparation = runtime.prepareBatch(
            messageId = Uuid.random(),
            calls = listOf(LocatedToolCall(0, source)),
            toolIndex = freezeToolSet(listOf(tool)).bindingsByName,
            availability = TurnInteractionCapability.FULL,
        )
        return (preparation.resolvedCalls.single() as ResolvedToolCall.Executable).call
    }

    private fun hooks(
        reportMetadata: suspend (kotlinx.serialization.json.JsonObject, ToolMetadataDelivery) -> Unit = { _, _ -> },
        registerUnpublishedResource: (ToolResourceLease) -> Unit = {},
    ) = ToolExecutionHooks(
        resolveAttachments = { ToolAttachmentResolution() },
        reportMetadata = reportMetadata,
        reportChildConversation = {},
        registerUnpublishedResource = registerUnpublishedResource,
    )
}
