package net.weero.measix.pilot.data.ai.tools

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ToolCallRuntimeExecutionTest {
    private val runtime = ToolCallRuntime(Json)

    @Test
    fun `generic implementation error becomes a compact stable failure`() = runTest {
        val outcome = runtime.execute(prepared(Tool("boom", "boom", execute = { error("secret path") })), hooks())

        assertTrue(outcome.executionFailed)
        assertEquals(
            "{\"status\":\"failed\",\"reason\":\"tool_failed\"}",
            (outcome.output.single() as UIMessagePart.Text).text,
        )
        assertEquals("failed", ToolRuntimeMetadata.terminalStatusOf(
            ToolRuntimeMetadata.applyTo(null, outcome.runtimeMetadata),
        ))
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
        assertEquals(
            ToolOutputPolicy.ARCHIVABLE_TEXT.name,
            ToolRuntimeMetadata.outputPolicyOf(ToolRuntimeMetadata.applyTo(null, outcome.runtimeMetadata)),
        )
        assertEquals(
            "failed",
            ToolRuntimeMetadata.terminalStatusOf(ToolRuntimeMetadata.applyTo(null, outcome.runtimeMetadata)),
        )
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
        val successMetadata = ToolRuntimeMetadata.applyTo(null, success.runtimeMetadata)
        assertEquals(
            ToolOutputPolicy.ARCHIVABLE_TEXT.name,
            ToolRuntimeMetadata.outputPolicyOf(successMetadata),
        )

        val failedTool = successfulTool.copy(
            execute = { throw ToolExecutionFailure(listOf(UIMessagePart.Text("failed")), "failed") },
        )
        val failure = runtime.execute(prepared(failedTool), hooks())
        val failureMetadata = ToolRuntimeMetadata.applyTo(null, failure.runtimeMetadata)
        assertEquals(
            ToolOutputPolicy.PRESERVE.name,
            ToolRuntimeMetadata.outputPolicyOf(failureMetadata),
        )

        val brokenResolver = successfulTool.copy(
            outputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT,
            successfulOutputPolicy = { error("policy bug") },
        )
        val preservedSuccess = runtime.execute(prepared(brokenResolver), hooks())
        assertEquals("result", (preservedSuccess.output.single() as UIMessagePart.Text).text)
        assertEquals(
            ToolOutputPolicy.PRESERVE.name,
            ToolRuntimeMetadata.outputPolicyOf(
                ToolRuntimeMetadata.applyTo(null, preservedSuccess.runtimeMetadata),
            ),
        )
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
        assertEquals(
            ToolOutputPolicy.PRESERVE.name,
            ToolRuntimeMetadata.outputPolicyOf(ToolRuntimeMetadata.applyTo(null, success.runtimeMetadata)),
        )

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
        assertEquals(
            ToolOutputPolicy.PRESERVE.name,
            ToolRuntimeMetadata.outputPolicyOf(ToolRuntimeMetadata.applyTo(null, failure.runtimeMetadata)),
        )
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
        val source = UIMessagePart.Tool("call", tool.name, "{}")
        val preparation = runtime.prepareBatch(
            messageId = Uuid.random(),
            calls = listOf(LocatedToolCall(0, source)),
            toolIndex = freezeToolSet(listOf(tool)).bindingsByName,
            availability = ToolInteractionAvailability.FULL,
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
