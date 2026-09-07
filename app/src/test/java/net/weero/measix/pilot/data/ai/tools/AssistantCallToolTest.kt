package net.weero.measix.pilot.data.ai.tools

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.subassistant.SubAssistantRunCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantCallToolTest {
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()
    private val masterConversationId = Uuid.random()

    private val caller = Assistant(
        id = callerId,
        localTools = listOf(LocalToolOption.AssistantDelegation),
        allowedSubAssistantIds = setOf(targetId),
    )
    private val target = Assistant(
        id = targetId,
        allowAsSubAssistant = true,
        description = "Target",
    )

    private fun createTool(coordinator: SubAssistantRunCoordinator): me.rerere.ai.core.Tool {
        val effectiveSettings = MutableStateFlow(
            Settings(
                assistants = listOf(caller, target),
                assistantId = callerId,
            ).toEffectiveSettingsSnapshot(),
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns effectiveSettings
        return AssistantToolFactory(
            settingsStore = settingsStore,
            assistantManagementService = mockk<AssistantManagementService>(relaxed = true),
            json = Json,
            subAssistantRunCoordinator = coordinator,
            toolSetFactory = mockk(relaxed = true),
        ).buildTools(caller, masterConversationId).single { it.name == "assistant_call" }
    }

    private fun executionContext() = ToolExecutionContext(
        locator = ToolCallLocator(Uuid.random(), Uuid.random(), Uuid.random()),
        providerCallId = "provider-call",
        reportMetadata = { _, _ -> },
        resolveAttachments = { ToolAttachmentResolution(failureReason = "not_used") },
        reportChildConversation = { },
        registerUnpublishedResource = {},
    )

    private suspend fun failurePayload(block: suspend () -> List<UIMessagePart>): kotlinx.serialization.json.JsonObject {
        val failure = try {
            block()
            throw AssertionError("expected ToolExecutionFailure")
        } catch (error: ToolExecutionFailure) {
            error
        }
        return Json.parseToJsonElement(
            (failure.output.single() as UIMessagePart.Text).text,
        ).jsonObject
    }

    private fun completedOutput(content: String = "done") = listOf(
        UIMessagePart.Text(
            """{"status":"completed","assistant_name":"Target","content":"$content"}""",
        ),
    )

    @Test
    fun `assistant call keeps preserve as its fail closed default`() {
        val tool = createTool(mockk(relaxed = true))
        assertEquals(ToolOutputPolicy.PRESERVE, tool.outputPolicy)
    }

    @Test
    fun `assistant call archives successful path free text but preserves fork sensitive results`() {
        val tool = createTool(mockk(relaxed = true))

        assertEquals(
            ToolOutputPolicy.ARCHIVABLE_TEXT,
            tool.successfulOutputPolicy(
                listOf(
                    UIMessagePart.Text(
                        """{"status":"completed","assistant_name":"Quiz","content":"answer"}""",
                    ),
                ),
            ),
        )
        assertEquals(
            ToolOutputPolicy.PRESERVE,
            tool.successfulOutputPolicy(
                listOf(
                    UIMessagePart.Text(
                        """{"status":"completed","assistant_name":"Quiz","content":"answer","artifacts":[{"path":"/upload/a.png"}]}""",
                    ),
                ),
            ),
        )
        assertEquals(
            ToolOutputPolicy.PRESERVE,
            tool.successfulOutputPolicy(listOf(UIMessagePart.Text("not-json"))),
        )
        assertEquals(
            ToolOutputPolicy.PRESERVE,
            tool.successfulOutputPolicy(
                listOf(
                    UIMessagePart.Text(
                        """{"status":"completed","assistant_name":"Quiz","content":"answer"}""",
                    ),
                    UIMessagePart.Image("https://example.com/result.png"),
                ),
            ),
        )
        listOf("failed", "stopped", "unavailable").forEach { status ->
            assertEquals(
                ToolOutputPolicy.PRESERVE,
                tool.successfulOutputPolicy(
                    listOf(UIMessagePart.Text("""{"status":"$status","reason":"test"}""")),
                ),
            )
        }
        listOf(
            """{"status":"completed","content":"answer"}""",
            """{"status":"completed","assistant_name":"Quiz"}""",
            """{"status":"completed","assistant_name":"Quiz","content":"answer","artifacts":{}}""",
        ).forEach { malformedCompleted ->
            assertEquals(
                ToolOutputPolicy.PRESERVE,
                tool.successfulOutputPolicy(listOf(UIMessagePart.Text(malformedCompleted))),
            )
        }
    }

    @Test
    fun `assistant call description keeps isolation guidance`() {
        val tool = createTool(mockk(relaxed = true))
        assertTrue(tool.description.contains("Do not prescribe how it must work"))
        assertFalse(tool.description.contains("concise, high-value reply"))
        val requestDescription = tool.parameters()!!["properties"]!!
            .jsonObject["request"]!!
            .jsonObject["description"]!!
            .jsonPrimitive
            .content
        assertTrue(requestDescription.contains("It cannot see this chat"))
        assertTrue(requestDescription.contains("concise, high-value reply is usually enough"))
        val extrasDescription = tool.parameters()!!["properties"]!!
            .jsonObject["extras"]!!
            .jsonObject["description"]!!
            .jsonPrimitive
            .content
        assertTrue(extrasDescription.contains("Extra result content for the caller model"))
        assertTrue(extrasDescription.contains("artifacts"))
        assertTrue(extrasDescription.contains("tts"))
        assertTrue(extrasDescription.contains("tool_calls"))
    }

    @Test
    fun `blank request is rejected before coordinator starts a run`() = runTest {
        val coordinator = mockk<SubAssistantRunCoordinator>(relaxed = true)
        val tool = createTool(coordinator)

        val payload = failurePayload {
            tool.executeWithContext(
                executionContext(),
                buildJsonObject {
                    put("assistant_id", targetId.toString())
                    put("request", "  \n")
                },
            )
        }
        assertEquals("failed", payload["status"]?.jsonPrimitive?.content)
        assertEquals("request_required", payload["reason"]?.jsonPrimitive?.content)
        coVerify(exactly = 0) { coordinator.executeCall(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `obsolete task argument is not accepted as request`() = runTest {
        val coordinator = mockk<SubAssistantRunCoordinator>(relaxed = true)
        val tool = createTool(coordinator)

        val payload = failurePayload {
            tool.executeWithContext(
                executionContext(),
                buildJsonObject {
                    put("assistant_id", targetId.toString())
                    put("task", "old protocol")
                },
            )
        }
        assertEquals("request_required", payload["reason"]?.jsonPrimitive?.content)
        coVerify(exactly = 0) { coordinator.executeCall(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `more than four attachments is rejected before coordinator starts a run`() = runTest {
        val coordinator = mockk<SubAssistantRunCoordinator>(relaxed = true)
        val tool = createTool(coordinator)
        val payload = failurePayload {
            tool.executeWithContext(
                executionContext(),
                buildJsonObject {
                    put("assistant_id", targetId.toString())
                    put("request", "Look at these")
                    put("attachments", buildJsonArray {
                        add(JsonPrimitive("/upload/1.png"))
                        add(JsonPrimitive("/upload/2.png"))
                        add(JsonPrimitive("/upload/3.png"))
                        add(JsonPrimitive("/upload/4.png"))
                        add(JsonPrimitive("/upload/5.png"))
                    })
                },
            )
        }
        assertEquals("failed", payload["status"]?.jsonPrimitive?.content)
        assertEquals("invalid_attachments", payload["reason"]?.jsonPrimitive?.content)
        coVerify(exactly = 0) { coordinator.executeCall(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `mixed non string attachments are rejected before coordinator starts a run`() = runTest {
        val coordinator = mockk<SubAssistantRunCoordinator>(relaxed = true)
        val tool = createTool(coordinator)
        val payload = failurePayload {
            tool.executeWithContext(
                executionContext(),
                buildJsonObject {
                    put("assistant_id", targetId.toString())
                    put("request", "Look at these")
                    put("attachments", buildJsonArray {
                        add(JsonPrimitive("/upload/b.png"))
                        add(JsonPrimitive(42))
                    })
                },
            )
        }
        assertEquals("failed", payload["status"]?.jsonPrimitive?.content)
        assertEquals("invalid_attachments", payload["reason"]?.jsonPrimitive?.content)
        coVerify(exactly = 0) { coordinator.executeCall(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `attachments are forwarded after dedup`() = runTest {
        val coordinator = mockk<SubAssistantRunCoordinator>()
        val tool = createTool(coordinator)
        val expected = completedOutput()
        val path = "/upload/b.png"
        coEvery {
            coordinator.executeCall(
                callerAssistantId = callerId,
                masterConversationId = masterConversationId,
                targetAssistantId = targetId,
                task = "Look",
                execContext = any(),
                extras = emptySet(),
                attachments = listOf(path, "/upload/a.png"),
            )
        } returns expected

        val result = tool.executeWithContext(
            executionContext(),
            buildJsonObject {
                put("assistant_id", targetId.toString())
                put("request", "Look")
                put("attachments", buildJsonArray {
                    add(JsonPrimitive(path))
                    add(JsonPrimitive(" $path "))
                    add(JsonPrimitive("/upload/a.png"))
                })
            },
        )
        assertEquals(expected, result)
    }

    @Test
    fun `description mentions attachments without vision capability claims`() {
        val tool = createTool(mockk(relaxed = true))
        val attachmentsDescription = tool.parameters()!!["properties"]!!
            .jsonObject["attachments"]!!
            .jsonObject["description"]!!
            .jsonPrimitive
            .content
        assertTrue(attachmentsDescription.contains("Up to 4"))
        assertTrue(attachmentsDescription.contains("/upload/<file>"))
        assertTrue(attachmentsDescription.contains("[Attachment path=...]"))
        assertTrue(attachmentsDescription.contains("file.path"))
        assertTrue(attachmentsDescription.contains("artifacts[].path"))
        assertFalse(attachmentsDescription.contains("attachment:<uuid>"))
        assertTrue(attachmentsDescription.contains("cannot see this chat"))
        assertFalse(attachmentsDescription.contains("vision", ignoreCase = true))
        assertFalse(attachmentsDescription.contains("ocr", ignoreCase = true))
    }

    @Test
    fun `nonblank request delegates with caller and master context`() = runTest {
        val coordinator = mockk<SubAssistantRunCoordinator>()
        val tool = createTool(coordinator)
        val expected = completedOutput()
        coEvery {
            coordinator.executeCall(
                callerAssistantId = callerId,
                masterConversationId = masterConversationId,
                targetAssistantId = targetId,
                task = "Do the work",
                execContext = any(),
            )
        } returns expected

        val result = tool.executeWithContext(
            executionContext(),
            buildJsonObject {
                put("assistant_id", targetId.toString())
                put("request", "Do the work")
            },
        )

        assertEquals(expected, result)
        coVerify(exactly = 1) {
            coordinator.executeCall(callerId, masterConversationId, targetId, "Do the work", any())
        }
    }

    @Test
    fun `extras are forwarded and unknown values dropped`() = runTest {
        val coordinator = mockk<SubAssistantRunCoordinator>()
        val tool = createTool(coordinator)
        val expected = completedOutput()
        coEvery {
            coordinator.executeCall(
                callerAssistantId = callerId,
                masterConversationId = masterConversationId,
                targetAssistantId = targetId,
                task = "Speak",
                execContext = any(),
                extras = setOf("artifacts", "tts", "tool_calls"),
            )
        } returns expected

        val result = tool.executeWithContext(
            executionContext(),
            buildJsonObject {
                put("assistant_id", targetId.toString())
                put("request", "Speak")
                put("extras", buildJsonArray {
                    add(JsonPrimitive("artifacts"))
                    add(JsonPrimitive("tts"))
                    add(JsonPrimitive("tool_calls"))
                    add(JsonPrimitive("preview"))
                })
            },
        )

        assertEquals(expected, result)
        coVerify(exactly = 1) {
            coordinator.executeCall(
                callerAssistantId = callerId,
                masterConversationId = masterConversationId,
                targetAssistantId = targetId,
                task = "Speak",
                execContext = any(),
                extras = setOf("artifacts", "tts", "tool_calls"),
            )
        }
    }
}
