package net.weero.measix.pilot.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.buildAskUserTool
import net.weero.measix.pilot.service.runtime.ToolCallPhase
import net.weero.measix.pilot.service.runtime.resolveToolCallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalPreflightTest {
    private val failure = Json.parseToJsonElement("{\"error\":\"invalid_arguments\",\"field\":\"path\"}").jsonObject
    private val validatedTool = Tool(
        name = "validated",
        description = "validated",
        validateArguments = { failure },
        needsApproval = { error("Invalid arguments cannot request approval") },
        execute = { error("Invalid arguments cannot execute") },
    )

    private fun resolve(calls: List<UIMessagePart.Tool>, definitions: List<Tool>, child: Boolean = false) =
        resolveToolApprovals(calls, buildToolIndex(definitions), child, setOf("ask_user"), Json)

    @Test
    fun `invalid inputs fail in both interactive and child runs for every undecided or approved state`() {
        listOf(false, true).forEach { child ->
            listOf(ToolApprovalState.Auto, ToolApprovalState.Pending, ToolApprovalState.Approved).forEach { state ->
                listOf("{}", "{", "[]", "null").forEach { input ->
                    val resolution = resolve(
                        listOf(UIMessagePart.Tool("id", validatedTool.name, input, approvalState = state)),
                        listOf(validatedTool), child,
                    )
                    val result = resolution.tools.single()
                    assertFalse(resolution.hasPendingApproval)
                    assertFalse(result.isPending)
                    assertTrue(result.hasReplayResult)
                    val text = (result.output.single() as UIMessagePart.Text).text
                    assertTrue(text.contains("invalid_arguments"))
                    assertFalse(text.contains("approval_unavailable"))
                    assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(result, null))
                }
            }
        }
    }

    @Test
    fun `denied answered and completed calls preserve their original decision even if removed or malformed`() {
        val denied = UIMessagePart.Tool("d", "removed", "{", approvalState = ToolApprovalState.Denied("no"))
        val answered = UIMessagePart.Tool("a", "removed", "{", approvalState = ToolApprovalState.Answered("yes"))
        val completed = UIMessagePart.Tool("c", "removed", "{", output = listOf(UIMessagePart.Text("done")))
        val calls = listOf(denied, answered, completed)
        val resolution = resolve(calls, emptyList())
        assertEquals(calls, resolution.tools)
        assertFalse(resolution.hasPendingApproval)
    }

    @Test
    fun `missing tool rejects before parsing and clears stale pending`() {
        val resolution = resolve(
            listOf(UIMessagePart.Tool("id", "removed", "{", approvalState = ToolApprovalState.Pending)),
            emptyList(),
        )
        val result = resolution.tools.single()
        assertFalse(result.isPending)
        assertFalse(resolution.hasPendingApproval)
        assertTrue((result.output.single() as UIMessagePart.Text).text.contains("tool_not_available"))
        assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(result, null))
    }

    @Test
    fun `invalid old pending does not survive beside a valid pending question`() {
        val valid = UIMessagePart.Tool("ask", "ask_user", """{"questions":[{"id":"q","question":"Continue?"}]}""")
        val bad = UIMessagePart.Tool("bad", "validated", "{}", approvalState = ToolApprovalState.Pending)
        val resolution = resolve(listOf(bad, valid), listOf(validatedTool, buildAskUserTool()))
        assertTrue(resolution.hasPendingApproval)
        assertFalse(resolution.tools[0].isPending)
        assertEquals(ToolArgumentsException(failure).output, resolution.tools[0].output)
        assertTrue(resolution.tools[1].isPending)
    }

    @Test
    fun `approval is not requested again after a valid approval`() {
        val definition = Tool(
            name = "approved", description = "approved",
            needsApproval = { error("Approved decision must not be repeated") }, execute = { emptyList() },
        )
        val call = UIMessagePart.Tool("a", "approved", "{}", approvalState = ToolApprovalState.Approved)
        assertEquals(listOf(call), resolve(listOf(call), listOf(definition)).tools)
    }

    @Test
    fun `child approval rejection remains failed after the active turn ends`() {
        val definition = Tool(
            name = "requires_approval", description = "requires approval",
            needsApproval = { true }, execute = { error("Cannot execute") },
        )
        val call = UIMessagePart.Tool("a", definition.name, "{}")
        val result = resolve(listOf(call), listOf(definition), child = true).tools.single()
        assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(result, null))
    }

    @Test
    fun `runtime domain rejection and an executed business failure remain distinct in history`() {
        val details = Json.parseToJsonElement("{\"status\":\"failed\",\"reason\":\"invalid_arguments\"}").jsonObject
        val definition = Tool(
            name = "domain", description = "domain", validateArguments = { details },
            execute = { emptyList() },
        )
        val call = UIMessagePart.Tool("a", definition.name, "{}")
        val rejected = resolve(listOf(call), listOf(definition)).tools.single()
        val businessResult = call.copy(output = listOf(UIMessagePart.Text(details.toString())))
        assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(rejected, null))
        assertEquals(ToolCallPhase.COMPLETED, resolveToolCallPhase(businessResult, null))
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
}
