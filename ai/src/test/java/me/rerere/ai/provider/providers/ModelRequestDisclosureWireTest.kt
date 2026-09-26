package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.testsupport.toModelRequests
import me.rerere.ai.testsupport.executedTool
import kotlin.uuid.Uuid
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.OpaqueReasoningReplay
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.resolveChatReasoningReplayPolicy
import me.rerere.ai.provider.providers.openai.resolveOpenAIEndpointVendor
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden contract：四种协议必须把同一个注入后的逻辑 USER turn 编码为
 * 「context 是第一个 text block/part，原始 parts 是完整同序 suffix」，不伪造 ASSISTANT、
 * 不把 context 提升为 system/developer、不逐字改写 Snapshot。
 *
 * `RequestContextPlanner.applyContextProjections` 已在全部 input transformers 之后把 context 作为 USER 消息的第一个
 * Text part；这里验证 provider wire 层不再重排、拆分或搬运这个顺序。
 *
 * 输入是 **transformers 之后**的投影：`Document/Audio/Video` 到这里已经是 transformer 产出的
 * 引用文本 part（见常量注释），本文件因此只断言 wire 层的 part 类型与顺序，不主张 provider
 * 原生编码任意媒体 part——原生媒体能力的证据在 `AttachmentProjectionTransformerTest`。
 */
private const val SNAPSHOT =
    """{"type":"conversation_disclosure_snapshot","format":1,""""" +
    """"memory":{"enabled":true,"scope":"local","header":["id","content"],"rows":[[3,"用户偏好深色主题"]]},""""" +
    """"sub_assistants":{"mode":"both","header":["id","name","description"],"rows":[]}}"""
private const val ORIGINAL = "Original user request"
private const val IMAGE = "data:image/png;base64,AQ=="

/** transformer 投影后的引用文本，不是 provider 原生媒体 part。 */
private const val DOCUMENT = "[Attachment path=/upload/document.pdf type=document]"
private const val AUDIO = "[Attachment path=/upload/audio.wav type=audio]"
private const val VIDEO = "[Attachment path=/upload/video.mp4 type=video]"

private fun JsonObject.field(name: String): JsonPrimitive? = this[name] as? JsonPrimitive

private fun injectedUserTurn(): UIMessage = UIMessage(
    role = MessageRole.USER,
    parts = listOf(
        UIMessagePart.Text(SNAPSHOT),
        UIMessagePart.Text(ORIGINAL),
        UIMessagePart.Image(IMAGE),
        UIMessagePart.Text(DOCUMENT),
        UIMessagePart.Text(AUDIO),
        UIMessagePart.Text(VIDEO),
    ),
)

private const val UPDATE = """{"type":"conversation_disclosure_snapshot","format":3,"memory":{"enabled":true,"scope":"local","header":["id","content"],"rows":[[3,"external change"]]}}"""
private val CALL_IDS = listOf("call_memory", "call_assistant")

/** Common application projection: both results precede the optional external-state contribution. */
private fun completedWriteBatch(externalChange: Boolean): List<ModelRequestMessage> {
    val step = Uuid.random()
    return buildList {
        add(UIMessage.system("Stable system prompt"))
        add(injectedUserTurn())
        add(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            executedTool(CALL_IDS[0], "memory_tool", """{"action":"edit","id":3,"content":"own change"}""",
                """{"success":true,"id":3}""", step).copy(metadata = GoogleThoughtMetadata(functionCallId = CALL_IDS[0]).toMetadata()),
            executedTool(CALL_IDS[1], "assistant_manage", """{"action":"delete","id":"assistant-2"}""",
                """{"action":"delete","id":"assistant-2"}""", step).copy(metadata = GoogleThoughtMetadata(functionCallId = CALL_IDS[1]).toMetadata()),
        )))
        if (externalChange) add(UIMessage.user(UPDATE))
        add(UIMessage.assistant("done"))
    }.toModelRequests()
}

private fun JsonArray.assertOriginalPartOrder(contentField: String = "content") {
    val first = entriesWithRole("user").first().getValue(contentField) as JsonArray
    assertEquals(6, first.size)
    val blocks = first.map { it.jsonObject }
    assertEquals(SNAPSHOT, blocks[0].field("text")?.content)
    assertEquals(ORIGINAL, blocks[1].field("text")?.content)
    assertTrue(blocks[2].keys.any { it in setOf("image_url", "source", "inlineData") } || blocks[2].field("type")?.content == "input_image")
    assertEquals(listOf(DOCUMENT, AUDIO, VIDEO), blocks.drop(3).map { it.field("text")?.content })
}

private val turnWithAnswer: List<ModelRequestMessage> = listOf(
    UIMessage.system("Stable system prompt"),
    injectedUserTurn(),
    UIMessage.assistant("answer"),
).toModelRequests()

private fun JsonArray.entriesWithRole(role: String): List<JsonObject> =
    map { it.jsonObject }.filter { it.field("role")?.content == role }

/** USER 条目的文本 blocks/parts，按 wire 顺序；断言 context 是第一个且原文逐字保留。 */
private fun JsonArray.assertSingleUserTurnWithLeadingContext(
    textField: String,
    contentField: String = "content",
) {
    val users = entriesWithRole("user")
    assertEquals(1, users.size)
    val blocks = users.single().getValue(contentField).let { it as JsonArray }.map { it.jsonObject }
    assertEquals(6, blocks.size)
    assertEquals(SNAPSHOT, blocks[0].field(textField)!!.content)
    assertEquals(ORIGINAL, blocks[1].field(textField)!!.content)
    assertEquals(
        listOf(DOCUMENT, AUDIO, VIDEO),
        blocks.drop(3).map { it.field(textField)!!.content },
    )
}

class DisclosureContextChatCompletionsTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

    private fun replayPolicy() = resolveChatReasoningReplayPolicy(
        endpointVendor = resolveOpenAIEndpointVendor("strict-compatible.example.com"),
        modelId = "gpt-test",
        requestHasTools = false,
        includeHistoryReasoning = false,
    ).let { it.copy(opaque = OpaqueReasoningReplay.NONE) }

    @Test
    fun `one user message carries context as first text block and originals as suffix`() {
        val wire = api.buildMessages(
            messages = turnWithAnswer,
            replayPolicy = replayPolicy(),
            mediaCapabilities = RequestMediaCapabilities(
                userImages = RequestImageSupport.STRUCTURED,
                assistantImages = RequestImageSupport.NONE,
                toolOutputImages = RequestImageSupport.STRUCTURED,
            ),
        )
        wire.assertSingleUserTurnWithLeadingContext("text")
        val blocks = wire.entriesWithRole("user").single().getValue("content").let { it as JsonArray }
            .map { it.jsonObject }
        assertEquals(
            listOf("text", "text", "image_url", "text", "text", "text"),
            blocks.map { it.field("type")?.content },
        )
        assertEquals(IMAGE, blocks[2].getValue("image_url").jsonObject.field("url")?.content)
        // 不伪造 assistant；context 不进入 system。
        assertEquals(1, wire.entriesWithRole("assistant").size)
        assertFalse(wire.entriesWithRole("system").single().toString().contains(SNAPSHOT))
    }

    @Test
    fun `format3 external state follows the entire tool batch while own writes add no user message`() {
        listOf(false, true).forEach { external ->
            val wire = api.buildMessages(completedWriteBatch(external), replayPolicy = replayPolicy(),
                mediaCapabilities = RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED))
            wire.assertOriginalPartOrder()
            val items = wire.map { it.jsonObject }
            val tools = items.filter { it.field("role")?.content == "tool" }
            assertEquals(CALL_IDS, tools.map { it.field("tool_call_id")?.content })
            val calls = items.flatMap { (it["tool_calls"] as? JsonArray).orEmpty() }.map { it.jsonObject.field("id")?.content }
            assertEquals(CALL_IDS, calls)
            assertEquals(if (external) 2 else 1, wire.entriesWithRole("user").size)
            if (external) {
                val updateIndex = items.indexOfFirst { it.toString().contains("external change") }
                assertTrue(updateIndex > items.indexOfLast { it.field("role")?.content == "tool" })
                assertEquals(UPDATE, items[updateIndex].getValue("content").jsonPrimitive.content)
            }
            assertFalse(wire.entriesWithRole("system").single().toString().contains("external change"))
        }
    }

    @Test
    fun `developer role system also excludes the disclosure snapshot`() {
        val wire = api.buildMessages(
            messages = turnWithAnswer,
            replayPolicy = replayPolicy(),
            useDeveloperRoleForSystemMessages = true,
            mediaCapabilities = RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED),
        )
        val developers = wire.entriesWithRole("developer")
        assertEquals(1, developers.size)
        assertFalse(developers.single().toString().contains(SNAPSHOT))
    }
}

class DisclosureContextResponsesTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `format3 follows all call outputs and own success requires no extra input item`() {
        listOf(false, true).forEach { external ->
            val wire = api.buildMessages(completedWriteBatch(external),
                mediaCapabilities = RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED))
            wire.assertOriginalPartOrder()
            val items = wire.map { it.jsonObject }
            assertEquals(CALL_IDS, items.filter { it.field("type")?.content == "function_call" }.map { it.field("call_id")?.content })
            assertEquals(CALL_IDS, items.filter { it.field("type")?.content == "function_call_output" }.map { it.field("call_id")?.content })
            assertEquals(if (external) 2 else 1, wire.entriesWithRole("user").size)
            if (external) {
                val updateIndex = items.indexOfFirst { it.field("role")?.content == "user" && it.toString().contains("external change") }
                assertTrue(updateIndex > items.indexOfLast { it.field("type")?.content == "function_call_output" })
                assertEquals(UPDATE, items[updateIndex].getValue("content").jsonPrimitive.content)
            }
        }
    }


    @Test
    fun `one user input item carries context as first input_text block`() {
        val wire = api.buildMessages(
            turnWithAnswer,
            mediaCapabilities = RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED),
        )
        val users = wire.map { it.jsonObject }.filter { it.field("role")?.content == "user" }
        assertEquals(1, users.size)
        val blocks = users.single().getValue("content").let { it as JsonArray }.map { it.jsonObject }
        assertEquals(
            listOf("input_text", "input_text", "input_image", "input_text", "input_text", "input_text"),
            blocks.map { it.field("type")?.content },
        )
        assertEquals(SNAPSHOT, blocks[0].field("text")?.content)
        assertEquals(ORIGINAL, blocks[1].field("text")?.content)
        assertEquals(IMAGE, blocks[2].field("image_url")?.content)
        assertEquals(listOf(DOCUMENT, AUDIO, VIDEO), blocks.drop(3).map { it.field("text")?.content })
        // 没有携带 snapshot 的 system/developer input item。
        assertTrue(wire.map { it.jsonObject }.none { it.toString().contains(SNAPSHOT) && it.field("role")?.content != "user" })
    }
}

class DisclosureContextClaudeTest {
    private val provider = ClaudeProvider(OkHttpClient())

    private fun invokeBuildMessages(messages: List<ModelRequestMessage>): JsonArray {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            ClaudePromptCacheTtl::class.java,
            RequestMediaCapabilities::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            provider,
            messages,
            false,
            ClaudePromptCacheTtl.FIVE_MINUTES,
            RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED),
        ) as JsonArray
    }

    @Test
    fun `format3 is after both tool result blocks and absent for own writes`() {
        listOf(false, true).forEach { external ->
            val wire = invokeBuildMessages(completedWriteBatch(external))
            wire.assertOriginalPartOrder()
            val users = wire.entriesWithRole("user")
            assertEquals(if (external) 3 else 2, users.size)
            val tail = (users[1].getValue("content") as JsonArray).map { it.jsonObject }
            assertEquals(CALL_IDS, tail.filter { it.field("type")?.content == "tool_result" }.map { it.field("tool_use_id")?.content })
            assertEquals(listOf("tool_result", "tool_result"), tail.map { it.field("type")?.content })
            assertEquals(if (external) listOf("user", "assistant", "user", "user", "assistant")
                else listOf("user", "assistant", "user", "assistant"), wire.map { it.jsonObject.field("role")?.content })
            if (external) {
                val update = (users[2].getValue("content") as JsonArray).single().jsonObject
                assertEquals("text", update.field("type")?.content)
                assertEquals(UPDATE, update.field("text")?.content)
            }
            val calls = wire.entriesWithRole("assistant").flatMap { (it.getValue("content") as JsonArray).map { block -> block.jsonObject } }
                .filter { it.field("type")?.content == "tool_use" }.map { it.field("id")?.content }
            assertEquals(CALL_IDS, calls)
        }
    }

    @Test
    fun `one user content block list carries context as first text block`() {
        val wire = invokeBuildMessages(turnWithAnswer)
        wire.assertSingleUserTurnWithLeadingContext("text")
        // 应用侧只产出一个 USER，adapter 保持对应消息且不得多出伪造 turn。
        assertEquals(1, wire.entriesWithRole("assistant").size)
        // System 在 Messages API 里是请求级独立字段，messages 数组只允许 user/assistant；
        // snapshot 只能出现在 anchor USER turn 内。
        assertEquals(setOf("user", "assistant"), wire.map { it.jsonObject.field("role")?.content }.toSet())
        assertTrue(
            wire.map { it.jsonObject }.none { it.toString().contains(SNAPSHOT) && it.field("role")?.content != "user" },
        )
    }
}

class DisclosureContextGeminiTest {
    private val provider = GoogleProvider(OkHttpClient())

    @Test
    fun `format3 stays after complete function responses when adjacent user contents merge`() {
        listOf(false, true).forEach { external ->
            val wire = provider.buildContents(completedWriteBatch(external),
                mediaCapabilities = RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED),
                modelId = "gemini-test", sourceProfile = "google:developer:test.example.com")
            wire.assertOriginalPartOrder("parts")
            assertEquals(listOf("user", "model", "user", "model"), wire.map { it.jsonObject.field("role")?.content })
            val tail = (wire.entriesWithRole("user").last().getValue("parts") as JsonArray).map { it.jsonObject }
            assertEquals(CALL_IDS, tail.filter { "functionResponse" in it }.map { it.getValue("functionResponse").jsonObject.field("id")?.content })
            assertEquals(if (external) listOf(UPDATE) else emptyList<String>(), tail.mapNotNull { it.field("text")?.content })
            assertEquals(if (external) 3 else 2, tail.size)
            assertTrue(tail.take(2).all { "functionResponse" in it })
            val calls = wire.entriesWithRole("model").flatMap { (it.getValue("parts") as JsonArray).map { block -> block.jsonObject } }
                .filter { "functionCall" in it }.map { it.getValue("functionCall").jsonObject.field("id")?.content }
            assertEquals(CALL_IDS, calls)
        }
    }


    @Test
    fun `one user content carries context as first part and keeps alternation`() {
        val wire = provider.buildContents(
            messages = turnWithAnswer,
            mediaCapabilities = RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED),
            modelId = "gemini-test",
            sourceProfile = "google:developer:test.example.com",
        )
        wire.assertSingleUserTurnWithLeadingContext(textField = "text", contentField = "parts")
        // System 走 systemInstruction，不进入 contents；user/model 必须交替。
        assertEquals(
            listOf("user", "model"),
            wire.map { it.jsonObject.field("role")?.content },
        )
        assertTrue(wire.entriesWithRole("model").none { it.toString().contains(SNAPSHOT) })
    }

    @Test
    fun `adjacent synthetic user merges with snapshot-bearing user and stays alternating`() {
        val wire = provider.buildContents(
            messages = listOf(
                UIMessage.system("Stable system prompt"),
                UIMessage.user("time reminder"),
                injectedUserTurn(),
                UIMessage.assistant("answer"),
            ).toModelRequests(),
            mediaCapabilities = RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED),
            modelId = "gemini-test",
            sourceProfile = "google:developer:test.example.com",
        )
        assertEquals(
            listOf("user", "model"),
            wire.map { it.jsonObject.field("role")?.content },
        )
        val userParts = wire.entriesWithRole("user").single()
            .getValue("parts").let { it as JsonArray }
            .map { it.jsonObject }
        assertEquals("time reminder", userParts[0].field("text")?.content)
        assertEquals(SNAPSHOT, userParts[1].field("text")?.content)
        assertEquals(ORIGINAL, userParts[2].field("text")?.content)
    }
}
