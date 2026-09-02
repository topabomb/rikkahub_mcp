package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.buildAskUserTool
import net.weero.measix.pilot.service.runtime.ToolCallPhase
import net.weero.measix.pilot.service.runtime.resolveToolCallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ToolCallRuntimeGateTest {
    private val runtime = ToolCallRuntime(Json)
    private val messageId = Uuid.random()
    private val failure = Json.parseToJsonElement("{\"error\":\"invalid_arguments\",\"field\":\"path\"}").jsonObject
    private val validatedTool = Tool(
        name = "validated",
        description = "validated",
        validateArguments = { failure },
        interactionRequirement = { error("Invalid arguments cannot request interaction") },
        execute = { error("Invalid arguments cannot execute") },
    )

    private fun prepare(
        calls: List<UIMessagePart.Tool>,
        definitions: List<Tool>,
        availability: ToolInteractionAvailability = ToolInteractionAvailability.FULL,
    ): ToolBatchPreparation = runtime.prepareBatch(
        messageId = messageId,
        calls = calls.mapIndexed { ordinal, tool -> LocatedToolCall(ordinal, tool) },
        toolIndex = freezeToolSet(definitions).bindingsByName,
        availability = availability,
    )

    /** The batch as it stands after replacements are applied to the message parts. */
    private fun List<UIMessagePart.Tool>.after(preparation: ToolBatchPreparation): List<UIMessagePart.Tool> =
        mapIndexed { ordinal, tool -> preparation.replacements[ordinal] ?: tool }

    @Test
    fun `invalid inputs fail in every availability mode for all undecided or approved states`() {
        ToolInteractionAvailability.entries.forEach { availability ->
            listOf(ToolApprovalState.Auto, ToolApprovalState.Pending, ToolApprovalState.Approved).forEach { state ->
                listOf("{}", "{", "[]", "null").forEach { input ->
                    val call = UIMessagePart.Tool("id", validatedTool.name, input, approvalState = state)
                    val preparation = prepare(listOf(call), listOf(validatedTool), availability)
                    val result = listOf(call).after(preparation).single()
                    assertTrue(preparation.pending.isEmpty())
                    assertFalse(result.isPending)
                    assertTrue(result.hasReplayResult)
                    val text = (result.output.single() as UIMessagePart.Text).text
                    assertTrue(text.contains("invalid_arguments"))
                    assertFalse(text.contains("approval_unavailable"))
                    assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(result, null))
                    assertEquals(
                        listOf(net.weero.measix.pilot.data.ai.ToolResultEventStatus.FAILED),
                        preparation.immediateResults.map { it.status },
                    )
                }
            }
        }
    }

    @Test
    fun `denied and answered calls resolve without execution in every run mode`() {
        val denied = UIMessagePart.Tool(
            "d",
            "removed",
            "{",
            approvalState = ToolApprovalState.Denied("no"),
            metadata = ToolRuntimeMetadata.withInteraction(
                null,
                ToolInteractionKind.APPROVAL,
                me.rerere.ai.core.ToolOutputPolicy.PRESERVE.name,
            ),
        )
        val answered = UIMessagePart.Tool(
            "a",
            "removed",
            "{",
            approvalState = ToolApprovalState.Answered("yes"),
            metadata = ToolRuntimeMetadata.withInteraction(
                null,
                ToolInteractionKind.USER_INPUT,
                me.rerere.ai.core.ToolOutputPolicy.PRESERVE.name,
            ),
        )
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
        assertEquals("denied", ToolRuntimeMetadata.terminalStatusOf(deniedResult.metadata))
        assertEquals("answered", ToolRuntimeMetadata.terminalStatusOf(answeredResult.metadata))
        assertTrue(preparation.pending.isEmpty())
    }

    @Test
    fun `missing tool rejects before parsing and clears stale pending`() {
        val call = UIMessagePart.Tool("id", "removed", "{", approvalState = ToolApprovalState.Pending)
        val preparation = prepare(listOf(call), emptyList())
        val result = listOf(call).after(preparation).single()
        assertFalse(result.isPending)
        assertTrue(preparation.pending.isEmpty())
        assertTrue((result.output.single() as UIMessagePart.Text).text.contains("tool_not_available"))
        assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(result, null))
    }

    @Test
    fun `invalid old pending does not survive beside a valid pending question`() {
        val valid = UIMessagePart.Tool("ask", "ask_user", """{"questions":[{"id":"q","question":"Continue?"}]}""")
        val bad = UIMessagePart.Tool("bad", "validated", "{}", approvalState = ToolApprovalState.Pending)
        val preparation = prepare(listOf(bad, valid), listOf(validatedTool, buildAskUserTool()))
        assertEquals(setOf(ToolInteractionKind.USER_INPUT), preparation.kinds)
        val updated = listOf(bad, valid).after(preparation)
        assertFalse(updated[0].isPending)
        assertEquals(ToolArgumentsException(failure).output, updated[0].output)
        assertTrue(updated[1].isPending)
    }

    @Test
    fun `a paused call keeps the interaction kind it paused for`() {
        val askUser = UIMessagePart.Tool("ask", "ask_user", """{"questions":[{"id":"q","question":"Continue?"}]}""")
        val preparation = prepare(listOf(askUser), listOf(buildAskUserTool()))
        val pending = preparation.replacements.getValue(0)
        assertEquals(ToolApprovalState.Pending, pending.approvalState)
        assertEquals(
            ToolInteractionKind.USER_INPUT,
            ToolRuntimeMetadata.interactionKindOf(pending.metadata),
        )
        assertEquals(ToolCallPhase.AWAITING_INPUT, resolveToolCallPhase(pending, null))
    }

    @Test
    fun `approval is not requested again after a valid approval`() {
        val definition = Tool(
            name = "approved",
            description = "approved",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { emptyList() },
        )
        val call = UIMessagePart.Tool("a", "approved", "{}", approvalState = ToolApprovalState.Approved)
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
        val call = UIMessagePart.Tool("a", approval.name, "{}")
        val preparation = prepare(
            listOf(call),
            listOf(approval),
            ToolInteractionAvailability.USER_INPUT_ONLY,
        )
        val result = listOf(call).after(preparation).single()
        val text = (result.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("approval_unavailable"))
        assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(result, null))

        val askUser = UIMessagePart.Tool("ask", "ask_user", """{"questions":[{"id":"q","question":"Continue?"}]}""")
        val bridged = prepare(
            listOf(askUser),
            listOf(buildAskUserTool()),
            ToolInteractionAvailability.USER_INPUT_ONLY,
        )
        assertEquals(setOf(ToolInteractionKind.USER_INPUT), bridged.kinds)
    }

    @Test
    fun `unattended runs reject both approval and user input without pausing`() {
        val approval = Tool(
            name = "requires_approval",
            description = "requires approval",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { error("Cannot execute") },
        )
        val askUser = UIMessagePart.Tool("ask", "ask_user", """{"questions":[{"id":"q","question":"Continue?"}]}""")
        val preparation = prepare(
            listOf(UIMessagePart.Tool("a", approval.name, "{}"), askUser),
            listOf(approval, buildAskUserTool()),
            ToolInteractionAvailability.NONE,
        )
        assertTrue(preparation.pending.isEmpty())
        assertTrue(preparation.resolvedCalls.isEmpty())
        val first = preparation.replacements.getValue(0)
        val second = preparation.replacements.getValue(1)
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
        val call = UIMessagePart.Tool(
            "a",
            "ask_user",
            "{}",
            approvalState = ToolApprovalState.Approved,
        )
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
        val call = UIMessagePart.Tool(
            "a",
            "changed",
            "{}",
            metadata = ToolRuntimeMetadata.withInteraction(
                null,
                ToolInteractionKind.USER_INPUT,
                me.rerere.ai.core.ToolOutputPolicy.PRESERVE.name,
            ),
        )
        listOf(none, approval).forEach { definition ->
            val preparation = prepare(listOf(call), listOf(definition))
            assertTrue(preparation.resolvedCalls.isEmpty())
            assertTrue(preparation.pending.isEmpty())
            assertTrue(
                (preparation.replacements.getValue(0).output.single() as UIMessagePart.Text).text
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
        val autoCall = UIMessagePart.Tool("1", "auto", "{}")
        val gateCall = UIMessagePart.Tool("2", "gate", "{}")
        val preparation = prepare(
            listOf(autoCall, gateCall),
            listOf(automatic, approval),
        )
        // The batch barrier is enforced by the loop: a non-empty pending means no
        // resolved call may execute this round, even the automatic one.
        assertEquals(setOf(ToolInteractionKind.APPROVAL), preparation.kinds)
        assertTrue(preparation.replacements.getValue(1).isPending)
    }

    @Test
    fun `a paused batch keeps one batch identity after earlier failures disappear`() {
        val automatic = Tool(name = "auto", description = "auto", execute = { emptyList() })
        val approval = Tool(
            name = "gate",
            description = "gate",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { emptyList() },
        )
        val original = listOf(
            UIMessagePart.Tool("bad", validatedTool.name, "{}"),
            UIMessagePart.Tool("auto", automatic.name, "{}"),
            UIMessagePart.Tool("gate", approval.name, "{}"),
        )
        val paused = prepare(original, listOf(validatedTool, automatic, approval))
        val durable = original.after(paused)
        assertEquals(0, ToolRuntimeMetadata.resultBatchOrdinalOf(durable[1].metadata))
        assertEquals(0, ToolRuntimeMetadata.resultBatchOrdinalOf(durable[2].metadata))

        val decided = durable.mapIndexedNotNull { ordinal, tool ->
            if (tool.hasReplayResult) {
                null
            } else {
                LocatedToolCall(
                    ordinal,
                    if (tool.toolName == approval.name) {
                        tool.copy(approvalState = ToolApprovalState.Approved)
                    } else {
                        tool
                    },
                )
            }
        }
        val resumed = runtime.prepareBatch(
            messageId = messageId,
            calls = decided,
            toolIndex = freezeToolSet(listOf(automatic, approval)).bindingsByName,
            availability = ToolInteractionAvailability.FULL,
        )
        assertTrue(resumed.pending.isEmpty())
        assertEquals(
            listOf(0, 0),
            resumed.resolvedCalls.filterIsInstance<ResolvedToolCall.Executable>()
                .map { it.call.resultBatchOrdinal },
        )
    }

    @Test
    fun `an automatic batch persists one identity before its first execution`() {
        val automatic = Tool(name = "auto", description = "auto", execute = { emptyList() })
        val original = listOf(
            UIMessagePart.Tool("first", automatic.name, "{}"),
            UIMessagePart.Tool("second", automatic.name, "{}"),
        )

        val prepared = prepare(original, listOf(automatic))
        val durable = original.after(prepared)

        assertTrue(prepared.pending.isEmpty())
        assertEquals(setOf(0, 1), prepared.replacements.keys)
        assertEquals(0, ToolRuntimeMetadata.resultBatchOrdinalOf(durable[0].metadata))
        assertEquals(0, ToolRuntimeMetadata.resultBatchOrdinalOf(durable[1].metadata))

        val resumed = runtime.prepareBatch(
            messageId = messageId,
            calls = listOf(LocatedToolCall(1, durable[1])),
            toolIndex = freezeToolSet(listOf(automatic)).bindingsByName,
            availability = ToolInteractionAvailability.FULL,
        )
        assertEquals(
            0,
            resumed.resolvedCalls.filterIsInstance<ResolvedToolCall.Executable>()
                .single().call.resultBatchOrdinal,
        )
    }

    @Test
    fun `runtime rejection and structured business failure both project as failed`() {
        val details = Json.parseToJsonElement("{\"status\":\"failed\",\"reason\":\"invalid_arguments\"}").jsonObject
        val definition = Tool(
            name = "domain", description = "domain", validateArguments = { details },
            execute = { emptyList() },
        )
        val call = UIMessagePart.Tool("a", definition.name, "{}")
        val preparation = prepare(listOf(call), listOf(definition))
        val rejected = listOf(call).after(preparation).single()
        val businessResult = call.copy(output = listOf(UIMessagePart.Text(details.toString())))
        assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(rejected, null))
        assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(businessResult, null))
    }

    @Test
    fun `arbitrary business result shapes cannot crash historical phase projection`() {
        listOf("[]", "text", "{\"status\":[]}", "{\"error\":{},\"type\":{}}", "{\"error\":{}}")
            .forEach { output ->
                val tool = UIMessagePart.Tool("a", "remote", "{}", output = listOf(UIMessagePart.Text(output)))
                assertEquals(ToolCallPhase.COMPLETED, resolveToolCallPhase(tool, null))
            }
        listOf("error", "timeout").forEach { type ->
            val tool = UIMessagePart.Tool(
                "a", "remote", "{}", output = listOf(UIMessagePart.Text("{\"error\":\"failed\",\"type\":\"$type\"}")),
            )
            assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(tool, null))
        }
    }

    @Test
    fun `tool metadata patches cannot overwrite the reserved runtime namespace`() {
        val patch = Json.parseToJsonElement(
            "{\"progress\":1,\"tool_runtime\":{\"interaction\":\"user_input\"}}",
        ).jsonObject
        assertThrows(IllegalArgumentException::class.java) {
            ToolRuntimeMetadata.requireToolOwnedPatch(patch)
        }
    }

    @Test
    fun `unknown or malformed runtime metadata fails closed instead of executing`() {
        val definition = Tool(name = "safe", description = "safe", execute = { emptyList() })
        listOf(
            """{"version":2}""",
            """{"version":1,"unexpected":true}""",
            """{"version":1,"interaction":"future"}""",
            """{"version":1,"outputPolicy":"FUTURE"}""",
            """{"version":1,"terminalStatus":"future"}""",
            """{"version":1,"archive":{"ref":0,"artifact":{"relativePath":"","mimeType":"text/plain"},"characters":1,"lines":1}}""",
            """{"version":1,"archive":{"ref":1,"artifact":{"relativePath":"upload/a.txt","mimeType":"text/plain"},"characters":1,"lines":1}}""",
        ).forEach { raw ->
            val metadata = buildJsonObject {
                put(ToolRuntimeMetadata.METADATA_KEY, Json.parseToJsonElement(raw))
            }
            val call = UIMessagePart.Tool("id", definition.name, "{}", metadata = metadata)
            val preparation = prepare(listOf(call), listOf(definition))
            assertTrue(preparation.resolvedCalls.isEmpty())
            assertEquals(
                listOf(net.weero.measix.pilot.data.ai.ToolResultEventStatus.FAILED),
                preparation.immediateResults.map { it.status },
            )
            assertTrue(
                (preparation.replacements.getValue(0).output.single() as UIMessagePart.Text).text
                    .contains("interaction_state_invalid"),
            )
        }
    }

    @Test
    fun `empty canonical archive metadata remains valid`() {
        val definition = Tool(name = "safe", description = "safe", execute = { emptyList() })
        val metadata = buildJsonObject {
            put(
                ToolRuntimeMetadata.METADATA_KEY,
                Json.parseToJsonElement(
                    """{"version":1,"archive":{"ref":7,"artifact":{"relativePath":"tool_outputs/empty.txt","mimeType":"text/plain"},"characters":0,"lines":0}}""",
                ),
            )
        }
        val preparation = prepare(
            listOf(UIMessagePart.Tool("id", definition.name, "{}", metadata = metadata)),
            listOf(definition),
        )
        val durable = listOf(UIMessagePart.Tool("id", definition.name, "{}", metadata = metadata))
            .after(preparation).single()
        assertEquals(7L, ToolRuntimeMetadata.archiveOf(durable.metadata)?.ref)
        assertEquals(0, ToolRuntimeMetadata.resultBatchOrdinalOf(durable.metadata))
        assertTrue(preparation.resolvedCalls.single() is ResolvedToolCall.Executable)
    }
}
