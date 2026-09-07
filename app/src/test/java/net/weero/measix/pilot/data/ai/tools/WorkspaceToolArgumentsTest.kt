package net.weero.measix.pilot.data.ai.tools

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.WorkspaceFileEntry
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceToolSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class WorkspaceToolArgumentsTest {
    private val runtime = ToolCallRuntime(Json)

    private fun pendingBatch(
        tools: List<me.rerere.ai.core.Tool>,
        calls: List<UIMessagePart.Tool>,
    ): ToolBatchPreparation = runtime.prepareBatch(
        messageId = Uuid.random(),
        calls = calls.mapIndexed { ordinal, tool -> LocatedToolCall(ordinal, tool) },
        toolIndex = freezeToolSet(tools).bindingsByName,
        availability = TurnInteractionCapability.FULL,
    )

    @Test
    fun validationReturnsStructuredDomainDetailsWithoutReplayEnvelope() {
        val error = validateWorkspaceArguments { parseWorkspaceWriteArguments(buildJsonObject {}) }
        assertEquals(buildJsonObject {
            put("error", "invalid_arguments")
            put("detail", "path is required")
        }, error)
    }

    @Test
    fun assembledToolsRejectMissingPathBeforeApprovalAndHonorNormalizedPolicy() = runTest {
        val tools = createWorkspaceTools("workspace", mockk(), emptyMap(), mockk())
        val missingPath = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "bad", toolName = "workspace_write_file", input = """{"text":"x"}""")
        val rejected = pendingBatch(tools, listOf(missingPath))
        assertTrue(rejected.pending.isEmpty())
        assertTrue(rejected.immediateResults.isNotEmpty())
        val output = (rejected.replacements.getValue(missingPath.localCallId).output.single() as UIMessagePart.Text).text
        assertTrue(output.contains("invalid_arguments"))
        assertTrue(output.contains("path is required"))

        val safe = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "safe", toolName = "workspace_write_file", input = """{"path":"/tmp/x","text":"x"}""")
        val outside = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "outside", toolName = "workspace_write_file", input = """{"path":"/tmp/../etc/x","text":"x"}""")
        assertTrue(pendingBatch(tools, listOf(safe)).pending.isEmpty())
        assertTrue(pendingBatch(tools, listOf(outside)).pending.single().requiresApproval)
    }

    @Test
    fun executionForwardsOnlyContextApprovalNotModelFields() = runTest {
        val service = mockk<WorkspaceApplicationService>()
        val session = mockk<WorkspaceToolSession>()
        val context = mockk<ToolExecutionContext>()
        every { context.approvedByUser } returns false
        coEvery { service.executeTool<WorkspaceFileEntry>(any(), any()) } coAnswers {
            secondArg<suspend WorkspaceToolSession.() -> WorkspaceFileEntry>().invoke(session)
        }
        coEvery { session.writeRootfsText(any(), any(), any(), any()) } returns WorkspaceFileEntry("/etc/a", "a", false, 1, 1)
        val tool = createWorkspaceTools("workspace", service, emptyMap(), mockk<ArtifactStore>())
            .single { it.name == "workspace_write_file" }
        val args = Json.parseToJsonElement("""{"path":"/tmp/../etc/a","text":"x","approvedByUser":true}""")
        tool.executeWithContext(context, args)
        coVerify(exactly = 1) { session.writeRootfsText("/etc/a", "x", true, false) }
        every { context.approvedByUser } returns true
        tool.executeWithContext(context, args)
        coVerify(exactly = 1) { session.writeRootfsText("/etc/a", "x", true, true) }
    }

    @Test
    fun writeRejectsMissingFieldsWrongTypesAndInvalidOptionalValues() {
        listOf(
            "{}", """{"path":"/tmp/a"}""", """{"path":42,"text":"x"}""",
            """{"path":"/tmp/a","text":null}""", """{"path":"/tmp/a","text":"x","overwrite":"false"}""",
            """{"path":"/tmp/a","text":"x","overwrite":null}""", """{"path":[],"text":"x"}""",
        ).forEach { input ->
            assertNotNull(input, validateWorkspaceArguments { parseWorkspaceWriteArguments(Json.parseToJsonElement(input)) })
        }
    }

    @Test
    fun writeApprovalAndExecutionShareNormalizedPath() {
        val escaped = parseWorkspaceWriteArguments(Json.parseToJsonElement("""{"path":"/tmp/../etc/a","text":"x"}"""))
        assertEquals("/etc/a", escaped.path.value)
        assertTrue(escaped.path.requiresWriteApproval)
        assertTrue(escaped.overwrite)
        val safe = parseWorkspaceWriteArguments(Json.parseToJsonElement("""{"path":"/etc/../workspace/a","text":"","overwrite":false}"""))
        assertEquals("/workspace/a", safe.path.value)
        assertFalse(safe.path.requiresWriteApproval)
        assertFalse(safe.overwrite)
        assertEquals("", safe.text)
    }

    @Test
    fun editRequiresNonemptyOldTextAndStrictBoolean() {
        listOf(
            "{}", """{"path":"/tmp/a","old_text":"","new_text":"x"}""",
            """{"path":"/tmp/a","old_text":"x","new_text":false}""",
            """{"path":"/tmp/a","old_text":"x","new_text":"","replace_all":"true"}""",
        ).forEach { input ->
            assertNotNull(input, validateWorkspaceArguments { parseWorkspaceEditArguments(Json.parseToJsonElement(input)) })
        }
        val valid = parseWorkspaceEditArguments(Json.parseToJsonElement("""{"path":"/tmp/a","old_text":"x","new_text":""}"""))
        assertEquals("", valid.newText)
        assertFalse(valid.replaceAll)
    }

    @Test
    fun readRejectsMissingNonStringAndTraversalAboveRoot() {
        listOf("{}", """{"path":false}""", """{"path":"/../../x"}""", """{"path":"relative"}""").forEach {
            assertNotNull(it, validateWorkspaceArguments { parseWorkspaceReadArguments(Json.parseToJsonElement(it)) })
        }
        assertNull(validateWorkspaceArguments { parseWorkspaceReadArguments(Json.parseToJsonElement("""{"path":"/tmp/./a"}""")) })
    }

    @Test
    fun shellRejectsInvalidArgumentsBeforeApprovalAndNormalizesCwd() {
        listOf(
            "{}", """{"command":""}""", """{"command":42}""",
            """{"command":"pwd","timeout":"30"}""", """{"command":"pwd","timeout":0}""",
            """{"command":"pwd","timeout":601}""", """{"command":"pwd","timeout":1.5}""",
            """{"command":"pwd","cwd":true}""", """{"command":"pwd","cwd":"../etc"}""",
            """{"command":"pwd","cwd":"/workspace-other"}""",
        ).forEach {
            assertNotNull(it, validateWorkspaceArguments { parseWorkspaceShellArguments(Json.parseToJsonElement(it)) })
        }
        val valid = parseWorkspaceShellArguments(Json.parseToJsonElement("""{"command":"pwd","cwd":"/workspace/a/../b","timeout":5}"""))
        assertEquals("b", valid.cwd)
        assertEquals(5000L, valid.timeoutMillis)
        assertEquals("skills", parseWorkspaceShellArguments(Json.parseToJsonElement("""{"command":"pwd"}"""), "/workspace/skills").cwd)
    }
}
