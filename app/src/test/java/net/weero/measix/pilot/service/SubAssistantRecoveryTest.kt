package net.weero.measix.pilot.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.PendingAssistantDeletion
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantRecoveryTest {
    private val json = JsonInstant
    private val masterId = Uuid.random()
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()
    private val childId = Uuid.random()
    private val task = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text("task")),
    )
    private val child = Conversation(
        id = childId,
        assistantId = targetId,
        parentConversationId = masterId,
        messageNodes = listOf(
            task.toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("partial answer")),
            ).toMessageNode(),
        ),
    )

    @Test
    fun `stop reason priority handles caller model and unknown reasons deterministically`() {
        assertEquals(
            "caller_model_unavailable",
            chooseMoreSpecificStopReason("app_restarted", "caller_model_unavailable"),
        )
        assertEquals(
            "target_access_revoked",
            chooseMoreSpecificStopReason("target_access_revoked", "unknown_reason"),
        )
    }

    @Test
    fun `valid stale run is stopped with rebuilt preview and retains child`() {
        val call = callTool("run-1")
        val result = recoverMasterSubAssistantCalls(
            master = masterWith(call),
            settings = validSettings(),
            childrenById = mapOf(child.id to child),
            json = json,
        )

        val recovered = result.master.messageNodes.single().currentMessage.getTools().single()
        val metadata = recovered.getSubAssistantCallMetadata(json)!!
        assertEquals(SubAssistantCallState.STOPPED, metadata.state)
        assertEquals("app_restarted", metadata.reason)
        assertEquals("partial answer", metadata.preview)
        assertTrue(recovered.isExecuted)
        assertEquals(setOf(child.id), result.referencedChildIds)
        assertEquals("app_restarted", result.childStopReasons[child.id])
    }

    @Test
    fun `recovery reason follows deterministic configuration priority`() {
        val valid = validSettings()
        val caller = valid.assistants.first { it.id == callerId }
        val target = valid.assistants.first { it.id == targetId }
        val cases = listOf(
            valid.copy(
                pendingAssistantDeletions = listOf(PendingAssistantDeletion(targetId)),
            ) to "target_removed",
            valid.copy(assistants = listOf(caller, target.copy(allowAsSubAssistant = false))) to "target_disabled",
            valid.copy(assistants = listOf(caller.copy(allowedSubAssistantIds = emptySet()), target)) to
                "target_access_revoked",
            valid.copy(providers = emptyList()) to "caller_model_unavailable",
        )

        cases.forEachIndexed { index, (settings, expectedReason) ->
            val result = recoverMasterSubAssistantCalls(
                masterWith(callTool("run-$index")),
                settings,
                mapOf(child.id to child),
                json,
            )
            val metadata = result.master.messageNodes.single().currentMessage.getTools().single()
                .getSubAssistantCallMetadata(json)!!
            assertEquals(expectedReason, metadata.reason)
        }
    }

    @Test
    fun `missing link is child missing after valid configuration checks`() {
        val result = recoverMasterSubAssistantCalls(
            masterWith(callTool("run-1")),
            validSettings(),
            emptyMap(),
            json,
        )

        val metadata = result.master.messageNodes.single().currentMessage.getTools().single()
            .getSubAssistantCallMetadata(json)!!
        assertEquals("child_missing", metadata.reason)
        assertTrue(result.referencedChildIds.isEmpty())
    }

    @Test
    fun `duplicate run id is corrupted and does not retain ambiguous child`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(callTool("duplicate"), callTool("duplicate")),
        )
        val result = recoverMasterSubAssistantCalls(
            masterWithNode(message),
            validSettings(),
            mapOf(child.id to child),
            json,
        )

        assertTrue(result.referencedChildIds.isEmpty())
        result.master.messageNodes.single().currentMessage.getTools().forEach { tool ->
            assertEquals("child_missing", tool.getSubAssistantCallMetadata(json)?.reason)
            assertTrue(tool.isExecuted)
        }
    }

    @Test
    fun `recovery includes tts_stats but not bulky extras by default`() {
        val result = recoverMasterSubAssistantCalls(
            master = masterWith(callTool("run-tts")),
            settings = validSettings(),
            childrenById = mapOf(child.id to childWithTts()),
            json = json,
        )
        val text = (result.master.messageNodes.single().currentMessage.getTools().single()
            .output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("\"tts_stats\""))
        assertFalse(text.contains("\"tool_calls\""))
        assertFalse(text.contains("Spoken answer."))
    }

    @Test
    fun `recovery returns extras when requested`() {
        val result = recoverMasterSubAssistantCalls(
            master = masterWith(callTool("run-tts", input = """{"extras":["tts","tool_calls"]}""")),
            settings = validSettings(),
            childrenById = mapOf(child.id to childWithTts()),
            json = json,
        )
        val text = (result.master.messageNodes.single().currentMessage.getTools().single()
            .output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("\"tts_stats\""))
        assertTrue(text.contains("\"tool_calls\""))
        assertTrue(text.contains("search_web"))
        assertTrue(text.contains("Spoken answer."))
    }

    @Test
    fun `terminal broken link is not rewritten but cannot retain orphan`() {
        val terminal = callTool("done", SubAssistantCallState.COMPLETED).copy(
            output = listOf(UIMessagePart.Text("done")),
        )
        val master = masterWith(terminal)
        val result = recoverMasterSubAssistantCalls(master, validSettings(), emptyMap(), json)

        assertEquals(master, result.master)
        assertFalse(result.referencedChildIds.contains(child.id))
    }

    private fun validSettings(): Settings {
        val model = Model(id = Uuid.random(), displayName = "model", type = ModelType.CHAT)
        val caller = Assistant(
            id = callerId,
            chatModelId = model.id,
            localTools = listOf(LocalToolOption.AssistantDelegation),
            allowedSubAssistantIds = setOf(targetId),
        )
        val target = Assistant(
            id = targetId,
            name = "Target",
            description = "Handles tasks",
            allowAsSubAssistant = true,
            chatModelId = null,
        )
        return Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistants = listOf(caller, target),
        )
    }

    private fun childWithTts(): Conversation = child.copy(
        messageNodes = listOf(
            task.toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "s1",
                        toolName = "search_web",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("ok")),
                    ),
                    UIMessagePart.Tool(
                        toolCallId = "t1",
                        toolName = "text_to_speech",
                        input = """{"text":"Spoken answer."}""",
                        output = listOf(UIMessagePart.Text("""{"success":true}""")),
                    ),
                ),
            ).toMessageNode(),
        ),
    )

    private fun callTool(
        runId: String,
        state: SubAssistantCallState = SubAssistantCallState.RUNNING,
        input: String = "{}",
    ): UIMessagePart.Tool {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = runId,
            targetAssistantId = targetId,
            targetNameSnapshot = "Target",
        ).copy(
            childConversationId = childId.toString(),
            childTaskNodeId = task.id.toString(),
            state = state,
        )
        return UIMessagePart.Tool(
            toolCallId = runId,
            toolName = "assistant_call",
            input = input,
        ).mergeSubAssistantCallMetadata(json, metadata)
    }

    private fun masterWith(tool: UIMessagePart.Tool): Conversation = masterWithNode(
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
    )

    private fun masterWithNode(message: UIMessage): Conversation = Conversation(
        id = masterId,
        assistantId = callerId,
        messageNodes = listOf(message.toMessageNode()),
    )
}
