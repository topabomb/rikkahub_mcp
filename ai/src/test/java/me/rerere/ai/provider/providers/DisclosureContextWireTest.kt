package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.OpaqueReasoningReplay
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.resolveChatReasoningReplayPolicy
import me.rerere.ai.provider.providers.openai.resolveOpenAIEndpointVendor
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权威方案 §9.6 golden contract：四种协议必须把同一个注入后的逻辑 USER turn 编码为
 * 「context 是第一个 text block/part，原始 parts 是完整同序 suffix」，不伪造 ASSISTANT、
 * 不把 context 提升为 system/developer、不逐字改写 Snapshot。
 *
 * GenerationLoop 已在全部 input transformers 之后把 context 作为 USER 消息的第一个 Text
 * part；这里验证 provider wire 层不再重排、拆分或搬运这个顺序。
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

private val turnWithAnswer: List<UIMessage> = listOf(
    UIMessage.system("Stable system prompt"),
    injectedUserTurn(),
    UIMessage.assistant("answer"),
)

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

    private fun invokeBuildMessages(messages: List<UIMessage>): JsonArray {
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
    fun `one user content block list carries context as first text block`() {
        val wire = invokeBuildMessages(turnWithAnswer)
        wire.assertSingleUserTurnWithLeadingContext("text")
        // Claude 会合并连续 user turns；应用侧只产出一个 USER，wire 上不得多出伪造 turn。
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
            ),
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
