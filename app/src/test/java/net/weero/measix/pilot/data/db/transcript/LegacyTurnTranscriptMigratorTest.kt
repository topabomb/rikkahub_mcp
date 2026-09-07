package net.weero.measix.pilot.data.db.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Locks the legacy→V3 transcript contract: deterministic uuid5 identity, the approval
 * mapping matrix, Step splitting evidence, terminal closure, idempotent replay and fail-closed decode.
 */
class LegacyTurnTranscriptMigratorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val messageId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    // Golden bytes computed with RFC 4122 UUID v5 (SHA-1) over the contract namespace.
    private val step0 = "6903ba77-0564-5d1a-a95a-a073b19e70e6"
    private val step1 = "759448d4-3e04-5525-b2fa-4e4e4c902036"
    private val tool0 = "d19ddf1a-2849-5143-91b2-01e97c820f99"
    private val tool1 = "757f0355-1e72-58b3-8d29-9741bdc7652c"

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue("expected ${T::class.simpleName} but was ${value?.javaClass?.simpleName}", value is T)
        return value as T
    }

    private fun legacyMessage(parts: String, role: String = "assistant") =
        """[{"id":"$messageId","role":"$role","parts":$parts}]"""

    private fun convert(parts: String, turnStatus: Map<Uuid, String> = emptyMap()): List<UIMessagePart> {
        val out = LegacyTurnTranscriptMigrator.convertNode(legacyMessage(parts), turnStatus, json)
        return JsonArray(json.parseToJsonElement(out).jsonArray.first().jsonObject["parts"]!!.jsonArray)
            .map { json.decodeFromJsonElement(UIMessagePart.serializer(), it) }
    }

    @Test
    fun `deterministic uuid5 identity for step and tool`() {
        val parts = convert(
            """[{"type":"tool","toolCallId":"call_1","toolName":"read","input":"{}",""" +
                """"output":[{"type":"text","text":"r"}],"approvalState":{"type":"auto"}}]""",
        )
        val step = assertIs<UIMessagePart.Step>(parts[0])
        assertEquals(Uuid.parse(step0), step.stepId)
        val tool = assertIs<UIMessagePart.Tool>(parts[1])
        assertEquals(Uuid.parse(tool0), tool.localCallId)
        assertEquals(Uuid.parse(step0), tool.stepId)
        assertEquals("call_1", tool.providerCallId)
    }

    @Test
    fun `approval mapping matrix`() {
        val cases = listOf(
            """{"type":"auto"}""" to ToolInteractionState.NotRequired,
            """{"type":"approved"}""" to ToolInteractionState.Approved,
            """{"type":"denied","reason":"nope"}""" to ToolInteractionState.Denied("nope"),
            """{"type":"answered","answer":"42"}""" to ToolInteractionState.Answered("42"),
        )
        for ((approval, expected) in cases) {
            val parts = convert(
                """[{"type":"tool","toolCallId":"c","toolName":"t","input":"{}",""" +
                    """"output":[],"approvalState":$approval}]""",
                turnStatus = mapOf(messageId to "RUNNING"),
            )
            val tool = assertIs<UIMessagePart.Tool>(parts[1])
            assertEquals("for approval $approval", expected, tool.interactionState)
        }
    }

    @Test
    fun `pending approval vs user input from tool runtime interaction`() {
        val approval = convert(
            """[{"type":"tool","toolCallId":"c","toolName":"t","input":"{}","output":[],""" +
                """"approvalState":{"type":"pending"},"metadata":{"tool_runtime":{"interaction":"approval"}}}]""",
            turnStatus = mapOf(messageId to "AWAITING_APPROVAL"),
        )
        val input = convert(
            """[{"type":"tool","toolCallId":"c","toolName":"t","input":"{}","output":[],""" +
                """"approvalState":{"type":"pending"},"metadata":{"tool_runtime":{"interaction":"user_input"}}}]""",
            turnStatus = mapOf(messageId to "AWAITING_APPROVAL"),
        )
        assertIs<UIMessagePart.Tool>(approval[1]).also { assertEquals(ToolInteractionState.AwaitingApproval, it.interactionState) }
        assertIs<UIMessagePart.Tool>(input[1]).also { assertEquals(ToolInteractionState.AwaitingInput, it.interactionState) }
    }

    @Test
    fun `tool runtime metadata is lifted to typed fields and removed from metadata`() {
        val parts = convert(
            """[{"type":"tool","toolCallId":"c","toolName":"t","input":"{}",""" +
                """"output":[{"type":"text","text":"r"}],"approvalState":{"type":"auto"},""" +
                """"metadata":{"tool_runtime":{"outputPolicy":"PRESERVE","terminalStatus":"completed"},""" +
                """"keep_me":"x"}}]""",
        )
        val tool = assertIs<UIMessagePart.Tool>(parts[1])
        assertEquals(ToolOutputPolicy.PRESERVE, tool.runtimeState.outputPolicy)
        assertEquals(ToolResultStatus.COMPLETED, tool.resultStatus)
        assertTrue(tool.metadata?.containsKey("keep_me") == true)
        assertFalse(tool.metadata?.containsKey("tool_runtime") == true)
    }

    @Test
    fun `step splits only on evidence - content after a completed tool then a new tool`() {
        val parts = convert(
            """[{"type":"tool","toolCallId":"a","toolName":"t","input":"{}",""" +
                """"output":[{"type":"text","text":"r1"}],"approvalState":{"type":"auto"}},""" +
                """{"type":"tool","toolCallId":"b","toolName":"t","input":"{}",""" +
                """"output":[{"type":"text","text":"r2"}],"approvalState":{"type":"auto"}}]""",
        )
        // Two tools with results and no intervening content stay in one batch (one Step).
        assertEquals(1, parts.count { it is UIMessagePart.Step })
        assertIs<UIMessagePart.Tool>(parts[2]).also { assertEquals(Uuid.parse(step0), it.stepId) }
    }

    @Test
    fun `reasoning after a completed tool opens a new step`() {
        val parts = convert(
            """[{"type":"tool","toolCallId":"a","toolName":"t","input":"{}",""" +
                """"output":[{"type":"text","text":"r1"}],"approvalState":{"type":"auto"}},""" +
                """{"type":"reasoning","reasoning":"think"}, """ +
                """{"type":"tool","toolCallId":"b","toolName":"t","input":"{}",""" +
                """"output":[{"type":"text","text":"r2"}],"approvalState":{"type":"auto"}}]""",
        )
        val steps = parts.filterIsInstance<UIMessagePart.Step>()
        assertEquals(2, steps.size)
        assertEquals(Uuid.parse(step1), steps[1].stepId)
        assertIs<UIMessagePart.Tool>(parts.last()).also { assertEquals(Uuid.parse(step1), it.stepId) }
    }

    @Test
    fun `step splits on differing resultBatchOrdinal without intervening content`() {
        val parts = convert(
            """[{"type":"tool","toolCallId":"a","toolName":"t","input":"{}",""" +
                """"output":[{"type":"text","text":"r1"}],"approvalState":{"type":"auto"},""" +
                """"metadata":{"tool_runtime":{"resultBatchOrdinal":0}}},""" +
                """{"type":"tool","toolCallId":"b","toolName":"t","input":"{}",""" +
                """"output":[{"type":"text","text":"r2"}],"approvalState":{"type":"auto"},""" +
                """"metadata":{"tool_runtime":{"resultBatchOrdinal":1}}}]""",
        )
        // Splitting evidence (a): a different resultBatchOrdinal splits even with no Reasoning/Text between.
        val steps = parts.filterIsInstance<UIMessagePart.Step>()
        assertEquals(2, steps.size)
        assertIs<UIMessagePart.Tool>(parts.last()).also { assertEquals(Uuid.parse(step1), it.stepId) }
    }

    @Test
    fun `equal resultBatchOrdinal keeps tools in one step`() {
        val parts = convert(
            """[{"type":"tool","toolCallId":"a","toolName":"t","input":"{}",""" +
                """"output":[{"type":"text","text":"r1"}],"approvalState":{"type":"auto"},""" +
                """"metadata":{"tool_runtime":{"resultBatchOrdinal":3}}},""" +
                """{"type":"tool","toolCallId":"b","toolName":"t","input":"{}",""" +
                """"output":[{"type":"text","text":"r2"}],"approvalState":{"type":"auto"},""" +
                """"metadata":{"tool_runtime":{"resultBatchOrdinal":3}}}]""",
        )
        assertEquals(1, parts.count { it is UIMessagePart.Step })
    }

    @Test
    fun `terminal turn closes trailing pending as denied interrupted`() {
        val parts = convert(
            """[{"type":"tool","toolCallId":"a","toolName":"t","input":"{}","output":[],""" +
                """"approvalState":{"type":"pending"},"metadata":{"tool_runtime":{"interaction":"approval"}}}]""",
            turnStatus = mapOf(messageId to "COMPLETED"),
        )
        val tool = assertIs<UIMessagePart.Tool>(parts[1])
        // A terminal turn never leaves a call awaiting the user — the interaction records the
        // user never answered (Denied) while the result marks the upgrade interruption.
        assertEquals(ToolInteractionState.Denied("schema_upgrade"), tool.interactionState)
        assertEquals(ToolResultStatus.INTERRUPTED, tool.resultStatus)
        val step = assertIs<UIMessagePart.Step>(parts[0])
        assertEquals(StepOutcome.Final, step.outcome)
    }

    @Test
    fun `non terminal turn keeps pending open for recovery`() {
        val parts = convert(
            """[{"type":"tool","toolCallId":"a","toolName":"t","input":"{}","output":[],""" +
                """"approvalState":{"type":"pending"},"metadata":{"tool_runtime":{"interaction":"approval"}}}]""",
            turnStatus = mapOf(messageId to "AWAITING_APPROVAL"),
        )
        val tool = assertIs<UIMessagePart.Tool>(parts[1])
        assertEquals(ToolInteractionState.AwaitingApproval, tool.interactionState)
        assertEquals(null, tool.resultStatus)
        assertIs<UIMessagePart.Step>(parts[0]).also { assertEquals(null, it.outcome) }
    }

    @Test
    fun `empty parts get a step zero`() {
        val parts = convert("[]", turnStatus = mapOf(messageId to "COMPLETED"))
        assertEquals(1, parts.size)
        assertIs<UIMessagePart.Step>(parts[0]).also {
            assertEquals(Uuid.parse(step0), it.stepId)
            assertEquals(StepOutcome.Final, it.outcome)
        }
    }

    @Test
    fun `sub assistant user interaction migrates ordinal to local call id and bumps schema`() {
        val parts = convert(
            """[{"type":"tool","toolCallId":"a","toolName":"assistant_call","input":"{}",""" +
                """"output":[{"type":"text","text":"r"}],"approvalState":{"type":"auto"},""" +
                """"metadata":{"sub_assistant_call":{"schema_version":1,"run_id":"r1",""" +
                """"user_interaction":{"interaction_id":"i","message_id":"m","tool_ordinal":0,""" +
                """"tool_name":"ask_user","input":"q"}}}}]""",
        )
        val tool = assertIs<UIMessagePart.Tool>(parts[1])
        val call = tool.metadata?.get("sub_assistant_call")?.jsonObject!!
        assertEquals(2, call["schema_version"]!!.jsonPrimitive.content.toInt())
        val ui = call["user_interaction"]!!.jsonObject
        assertFalse("tool_ordinal" in ui)
        assertEquals(tool0, ui["local_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `already v3 node is returned unchanged (idempotent)`() {
        val once = LegacyTurnTranscriptMigrator.convertNode(
            legacyMessage(
                """[{"type":"tool","toolCallId":"a","toolName":"t","input":"{}",""" +
                    """"output":[{"type":"text","text":"r"}],"approvalState":{"type":"auto"}}]""",
            ),
            emptyMap(),
            json,
        )
        val twice = LegacyTurnTranscriptMigrator.convertNode(once, emptyMap(), json)
        assertEquals(once, twice)
    }

    @Test
    fun `user and system messages are untouched`() {
        val out = LegacyTurnTranscriptMigrator.convertNode(
            legacyMessage("""[{"type":"text","text":"hi"}]""", role = "user"),
            emptyMap(),
            json,
        )
        val parts = json.parseToJsonElement(out).jsonArray.first().jsonObject["parts"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals("text", parts.first().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown approval state fails closed`() {
        val ex = runCatching {
            convert(
                """[{"type":"tool","toolCallId":"a","toolName":"t","input":"{}","output":[],""" +
                    """"approvalState":{"type":"weird"}}]""",
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("unknown legacy approvalState"))
    }

    @Test
    fun `explicit null metadata and approvalState are treated as absent`() {
        // Real legacy rows serialize nullable fields with kotlinx explicitNulls ("metadata":null,
        // "approvalState":null). The converter must treat a null field exactly like an absent one,
        // never throw "JsonNull is not a JsonObject" (this crashed startup migration on device data).
        val parts = convert(
            """[{"type":"tool","toolCallId":"c","toolName":"t","input":"{}",""" +
                """"output":[{"type":"text","text":"r"}],"approvalState":null,"metadata":null}]""",
        )
        val tool = assertIs<UIMessagePart.Tool>(parts[1])
        assertEquals(ToolInteractionState.NotRequired, tool.interactionState)
        assertEquals(ToolOutputPolicy.ARCHIVABLE_TEXT, tool.runtimeState.outputPolicy)
        assertEquals(null, tool.metadata)
        assertEquals(ToolResultStatus.COMPLETED, tool.resultStatus)
    }

    @Test
    fun `null sub assistant user_interaction bumps schema without crashing`() {
        val parts = convert(
            """[{"type":"tool","toolCallId":"a","toolName":"assistant_call","input":"{}",""" +
                """"output":[{"type":"text","text":"r"}],"approvalState":{"type":"auto"},""" +
                """"metadata":{"sub_assistant_call":{"schema_version":1,"run_id":"r1","user_interaction":null}}}]""",
        )
        val tool = assertIs<UIMessagePart.Tool>(parts[1])
        val call = tool.metadata?.get("sub_assistant_call")?.jsonObject!!
        assertEquals(2, call["schema_version"]!!.jsonPrimitive.content.toInt())
    }
}
