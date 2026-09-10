package net.weero.measix.pilot.service.subassistant

import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.service.turn.finalizeInterruptedRunSafely
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 子助手中断收口与恢复投影集成测试。
 *
 * 覆盖 [finalizeInterruptedRunSafely] 的超时/失败收口，以及 [reconcileMasterSubAssistantCalls]
 * 在重启后对 Master `assistant_call` 的停止投影、lineage 与 extras 策略。
 */
class SubAssistantFinalizationIntegrationTest {

    // ── finalizeInterruptedRunSafely：Child 超时与 durable 失败收口 ──

    @Test
    fun `child timeout prevents terminal parent metadata from advancing`() = runTest {
        var metadataStaged = false
        val failure = runCatching {
            finalizeInterruptedRunSafely(
                timeoutMillis = 100,
                finalizeChild = { awaitCancellation() },
                finalizeMetadata = { metadataStaged = true },
            )
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertFalse(metadataStaged)
    }

    @Test
    fun `child commit failure prevents metadata and metadata failure preserves committed child`() = runTest {
        for (failChild in listOf(true, false)) {
            val events = mutableListOf<String>()
            val childFailure = IllegalStateException("child commit failed")
            val metadataFailure = IllegalArgumentException("metadata staging failed")
            val failure = runCatching {
                finalizeInterruptedRunSafely(
                    timeoutMillis = 100,
                    finalizeChild = {
                        events += "child-attempt"
                        if (failChild) throw childFailure
                        events += "child-committed"
                    },
                    finalizeMetadata = {
                        events += "metadata-attempt"
                        throw metadataFailure
                    },
                )
            }.exceptionOrNull()
            val expectedFailure = if (failChild) childFailure else metadataFailure
            assertEquals(expectedFailure.javaClass, failure?.javaClass)
            assertEquals(expectedFailure.message, failure?.message)
            assertEquals(
                if (failChild) listOf("child-attempt")
                else listOf("child-attempt", "child-committed", "metadata-attempt"),
                events,
            )
        }
    }

    // ── reconcileMasterSubAssistantCalls：重启恢复投影 ──

    private val json = JsonInstant
    private val masterId = Uuid.random()
    private val callerId = ConfigurationReference.random()
    private val targetId = ConfigurationReference.random()
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
    fun `valid stale run is stopped with rebuilt preview and retains child`() {
        val call = callTool("run-1")
        val result = reconcileCalls(
            master = masterWith(call),
            childrenById = mapOf(child.id to child),
            json = json,
        )

        val recovered = result.master.messageNodes.single().currentMessage.getTools().single()
        val metadata = recovered.getSubAssistantCallMetadata(json)!!
        assertEquals(SubAssistantCallState.STOPPED, metadata.state)
        assertEquals("app_restarted", metadata.reason)
        assertEquals("partial answer", metadata.preview)
        assertTrue(recovered.hasReplayResult)
        assertEquals(setOf(child.id), result.referencedChildIds)
        assertEquals("app_restarted", result.childStopReasons[child.id])
    }

    @Test
    fun `enterprise child is interrupted without requiring a personal assistant definition`() {
        val authority = EnterpriseAuthority("local:recovery", "dep_recovery")
        val scope = ConfigurationScope.Enterprise(authority, "user")
        val target = ConfigurationReference.Enterprise(authority, "asd_child")
        val enterpriseChild = child.copy(assistantId = target, scope = scope)
        val result = reconcileCalls(
            masterWith(callTool("enterprise", target = target)).copy(scope = scope),
            mapOf(child.id to enterpriseChild), json,
        )
        assertEquals("app_restarted", result.childStopReasons[child.id])
        assertEquals("app_restarted", result.master.messageNodes.single().currentMessage.getTools().single()
            .getSubAssistantCallMetadata(json)!!.reason)
    }

    @Test
    fun `recovery does not project artifacts even when extras request them`() {
        val call = callTool("run-artifacts", input = """{"extras":["artifacts"]}""")
        val result = reconcileCalls(
            master = masterWith(call),
            childrenById = mapOf(child.id to child),
            json = json,
        )
        val recovered = result.master.messageNodes.single().currentMessage.getTools().single()
        assertEquals(1, recovered.output.size)
        val text = (recovered.output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("\"status\":\"stopped\""))
        assertFalse(text.contains("\"artifacts\""))
        assertFalse(text.contains("artifact_delivery"))
        assertTrue(recovered.output.none { it is UIMessagePart.Image })
    }

    @Test
    fun `missing link is child missing independently of current configuration`() {
        val result = reconcileCalls(
            masterWith(callTool("run-1")),
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
        val result = reconcileCalls(
            masterWithNode(message),
            mapOf(child.id to child),
            json,
        )

        assertTrue(result.referencedChildIds.isEmpty())
        result.master.messageNodes.single().currentMessage.getTools().forEach { tool ->
            assertEquals("child_missing", tool.getSubAssistantCallMetadata(json)?.reason)
            assertTrue(tool.hasReplayResult)
        }
    }

    @Test
    fun `recovery includes tts_stats but not bulky extras by default`() {
        val result = reconcileCalls(
            master = masterWith(callTool("run-tts")),
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
        val result = reconcileCalls(
            master = masterWith(callTool("run-tts", input = """{"extras":["tts","tool_calls"]}""")),
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
        val result = reconcileCalls(master, emptyMap(), json)

        assertEquals(master, result.master)
        assertFalse(result.referencedChildIds.contains(child.id))
    }

    private fun childWithTts(): Conversation = child.copy(
        messageNodes = listOf(
            task.toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "s1",
                        toolName = "search_web",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("ok")),
                    ),
                    UIMessagePart.Tool(
                        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "t1",
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
        target: ConfigurationReference = targetId,
    ): UIMessagePart.Tool {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = runId,
            targetAssistantId = target,
            targetNameSnapshot = "Target",
        ).copy(
            childConversationId = childId.toString(),
            childTaskNodeId = task.id.toString(),
            state = state,
        )
        return UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = runId,
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

private data class ReconciledConversation(
    val master: Conversation,
    val referencedChildIds: Set<Uuid>,
    val childStopReasons: Map<Uuid, String>,
)

private fun reconcileCalls(
    master: Conversation,
    childrenById: Map<Uuid, Conversation>,
    json: kotlinx.serialization.json.Json,
): ReconciledConversation {
    val result = reconcileMasterSubAssistantCalls(
        masterId = master.id,
        masterNodes = master.messageNodes,
        childrenById = childrenById.mapValues { it.value.toSnapshot() },
        json = json,
    )
    return ReconciledConversation(
        master = master.copy(messageNodes = result.masterNodes),
        referencedChildIds = result.referencedChildIds,
        childStopReasons = result.childStopReasons,
    )
}
