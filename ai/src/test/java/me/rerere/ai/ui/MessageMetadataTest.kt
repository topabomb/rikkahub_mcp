package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证类型化 metadata 与旧版手写 JsonObject 数据的双向兼容
 */
class MessageMetadataTest {

    private fun reasoningWith(metadata: JsonObject?) = UIMessagePart.Reasoning(
        reasoning = "thinking...",
        metadata = metadata,
    )

    // ===== 读取旧数据(各 provider 旧代码写入的确切格式) =====

    @Test
    fun `parses legacy claude metadata`() {
        // 旧代码: buildJsonObject { put("signature", signature) }
        val part = reasoningWith(buildJsonObject { put("signature", "sig-abc") })
        assertEquals("sig-abc", part.metadataAs<ClaudeReasoningMetadata>()?.signature)
    }

    @Test
    fun `parses legacy openai metadata`() {
        // 旧代码: buildJsonObject { put("encrypted_content", ...); put("reasoning_id", ...) }
        val part = reasoningWith(buildJsonObject {
            put("encrypted_content", "enc-xyz")
            put("reasoning_id", "rs_123")
        })
        val meta = part.metadataAs<OpenAIReasoningMetadata>()
        assertEquals("rs_123", meta?.reasoningId)
        assertEquals("enc-xyz", meta?.encryptedContent)
    }

    @Test
    fun `parses legacy openai metadata with json null encrypted content`() {
        // 旧代码 put("encrypted_content", null) 会写入 JsonNull
        val part = reasoningWith(buildJsonObject {
            put("encrypted_content", null as String?)
            put("reasoning_id", "rs_123")
        })
        val meta = part.metadataAs<OpenAIReasoningMetadata>()
        assertEquals("rs_123", meta?.reasoningId)
        assertNull(meta?.encryptedContent)
    }

    @Test
    fun `parses legacy google metadata with json null thought signature`() {
        // 旧代码无条件写 metadata, thoughtSignature 为空时为 JsonNull
        val withValue = reasoningWith(buildJsonObject { put("thoughtSignature", "ts-1") })
        assertEquals("ts-1", withValue.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)

        val withNull = reasoningWith(buildJsonObject { put("thoughtSignature", null as String?) })
        assertNull(withNull.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
    }

    // ===== 容错 =====

    @Test
    fun `returns null when metadata is absent`() {
        assertNull(reasoningWith(null).metadataAs<ClaudeReasoningMetadata>())
    }

    @Test
    fun `cross provider metadata does not interfere`() {
        // 切换 provider 后, OpenAI 写入的 metadata 被 Claude 解析: 不抛异常, signature 为 null
        val part = reasoningWith(buildJsonObject {
            put("encrypted_content", "enc-xyz")
            put("reasoning_id", "rs_123")
        })
        assertNull(part.metadataAs<ClaudeReasoningMetadata>()?.signature)
    }

    @Test
    fun `malformed metadata returns null instead of throwing`() {
        // 类型不匹配 (signature 是 object 而非 string)
        val part = reasoningWith(buildJsonObject {
            put("signature", buildJsonObject { put("nested", "value") })
        })
        assertNull(part.metadataAs<ClaudeReasoningMetadata>())
    }

    // ===== 写入格式与旧 key 保持一致(新写的数据可被旧式取值读取) =====

    @Test
    fun `written metadata uses legacy keys`() {
        val openai = OpenAIReasoningMetadata(reasoningId = "rs_1", encryptedContent = "enc").toMetadata()
        assertEquals("rs_1", openai["reasoning_id"]?.jsonPrimitive?.content)
        assertEquals("enc", openai["encrypted_content"]?.jsonPrimitive?.content)

        val claude = ClaudeReasoningMetadata(signature = "sig").toMetadata()
        assertEquals("sig", claude["signature"]?.jsonPrimitive?.content)

        val google = GoogleThoughtMetadata(thoughtSignature = "ts").toMetadata()
        assertEquals("ts", google["thoughtSignature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `null fields are omitted when writing`() {
        // explicitNulls = false: null 字段不写入, 不会出现 "thoughtSignature": null
        val metadata = GoogleThoughtMetadata(thoughtSignature = null).toMetadata()
        assertFalse(metadata.containsKey("thoughtSignature"))
    }

    @Test
    fun `metadata round trip via persistence is stable`() {
        // 模拟持久化: 部件序列化为 JSON 字符串再读回, metadata 不丢失不变形
        val json = Json { ignoreUnknownKeys = true }
        val part: UIMessagePart = reasoningWith(
            OpenAIReasoningMetadata(reasoningId = "rs_1", encryptedContent = "enc").toMetadata()
        )
        val restored = json.decodeFromString<UIMessagePart>(json.encodeToString(part))
        val meta = restored.metadataAs<OpenAIReasoningMetadata>()
        assertEquals("rs_1", meta?.reasoningId)
        assertEquals("enc", meta?.encryptedContent)
    }

    @Test
    fun `response output metadata round trip via persistence is stable`() {
        val json = Json { ignoreUnknownKeys = true }
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer")),
            providerMetadata = OpenAIResponseMetadata(
                wireFormat = OpenAIResponseWireFormat.DEEPSEEK,
                outputItemGroups = listOf(
                    listOf(buildJsonObject {
                        put("id", "rs_1")
                        put("type", "reasoning")
                        put("provider_field", buildJsonObject { put("kept", true) })
                    })
                ),
            ).toMetadata(),
        )

        val restored = json.decodeFromString<UIMessage>(json.encodeToString(message))
        val metadata = restored.metadataAs<OpenAIResponseMetadata>()

        assertEquals(OpenAIResponseWireFormat.DEEPSEEK, metadata?.wireFormat)
        assertEquals("rs_1", metadata?.outputItemGroups?.single()?.single()?.get("id")?.jsonPrimitive?.content)
        assertEquals(
            "true",
            metadata?.outputItemGroups?.single()?.single()?.get("provider_field")
                ?.jsonObject?.get("kept")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `response output metadata accumulates tool steps in order`() {
        fun metadata(
            id: String,
            format: OpenAIResponseWireFormat,
            source: OpenAIResponseSourceProfile? = null,
        ) = OpenAIResponseMetadata(
            wireFormat = format,
            outputItemGroups = listOf(
                listOf(buildJsonObject {
                    put("id", id)
                    put("type", "message")
                })
            ),
            sourceProfile = source,
        ).toMetadata()

        val merged = mergeMessageMetadata(
            metadata("msg_1", OpenAIResponseWireFormat.OPENAI),
            metadata("msg_2", OpenAIResponseWireFormat.OPENAI),
        )
        val decoded = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            providerMetadata = merged,
        ).metadataAs<OpenAIResponseMetadata>()

        assertEquals(
            listOf(listOf("msg_1"), listOf("msg_2")),
            decoded?.outputItemGroups?.map { group ->
                group.map { it["id"]?.jsonPrimitive?.content }
            },
        )
        assertEquals(
            OpenAIResponseWireFormat.DEEPSEEK,
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
                providerMetadata = mergeMessageMetadata(
                    merged,
                    metadata("msg_3", OpenAIResponseWireFormat.DEEPSEEK),
                ),
            ).metadataAs<OpenAIResponseMetadata>()?.wireFormat,
        )
    }

    @Test
    fun `response metadata does not merge conflicting endpoint sources`() {
        fun metadata(id: String, source: OpenAIResponseSourceProfile) = OpenAIResponseMetadata(
            wireFormat = OpenAIResponseWireFormat.OPENAI,
            outputItemGroups = listOf(listOf(buildJsonObject { put("id", id) })),
            sourceProfile = source,
        ).toMetadata()

        val merged = mergeMessageMetadata(
            metadata("openai", OpenAIResponseSourceProfile.OPENAI),
            metadata("ark", OpenAIResponseSourceProfile.VOLC_ARK),
        )
        val decoded = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            providerMetadata = merged,
        ).metadataAs<OpenAIResponseMetadata>()

        assertEquals(OpenAIResponseSourceProfile.VOLC_ARK, decoded?.sourceProfile)
        assertEquals("ark", decoded?.outputItemGroups?.single()?.single()?.get("id")?.jsonPrimitive?.content)
    }

    @Test
    fun `response metadata upgrades legacy source while preserving groups`() {
        val legacy = OpenAIResponseMetadata(
            wireFormat = OpenAIResponseWireFormat.OPENAI,
            outputItemGroups = listOf(listOf(buildJsonObject { put("id", "legacy") })),
        ).toMetadata()
        val current = OpenAIResponseMetadata(
            wireFormat = OpenAIResponseWireFormat.OPENAI,
            outputItemGroups = listOf(listOf(buildJsonObject { put("id", "current") })),
            sourceProfile = OpenAIResponseSourceProfile.OPENAI,
        ).toMetadata()

        val decoded = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            providerMetadata = mergeMessageMetadata(legacy, current),
        ).metadataAs<OpenAIResponseMetadata>()

        assertEquals(OpenAIResponseSourceProfile.OPENAI, decoded?.sourceProfile)
        assertEquals(listOf("legacy", "current"), decoded?.outputItemGroups?.flatten()?.map {
            it["id"]?.jsonPrimitive?.content
        })
    }

    @Test
    fun `legacy json null thought signature does not survive rewrite`() {
        // 旧数据含 JsonNull -> 解析 -> 重新写出: JsonNull 被清理而非保留
        val legacy = reasoningWith(buildJsonObject { put("thoughtSignature", JsonNull) })
        val rewritten = legacy.metadataAs<GoogleThoughtMetadata>()?.toMetadata()
        assertEquals(JsonObject(emptyMap()), rewritten)
    }

    @Test
    fun `signed empty text part remains a separate streaming boundary`() {
        val initial = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        fun chunk(part: UIMessagePart) = MessageChunk(
            id = "chunk",
            model = "gemini-3-pro",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
                    message = null,
                    finishReason = null,
                )
            ),
        )

        val merged = initial + chunk(UIMessagePart.Text("answer")) + chunk(
            UIMessagePart.Text(
                text = "",
                metadata = GoogleThoughtMetadata(thoughtSignature = "signature").toMetadata(),
            )
        )

        assertEquals(2, merged.parts.size)
        assertEquals("answer", (merged.parts[0] as UIMessagePart.Text).text)
        assertEquals(
            "signature",
            merged.parts[1].metadataAs<GoogleThoughtMetadata>()?.thoughtSignature,
        )
    }

    @Test
    fun `redacted claude reasoning remains separate from visible thinking`() {
        val initial = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        fun chunk(part: UIMessagePart) = MessageChunk(
            id = "chunk",
            model = "claude",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
                    message = null,
                    finishReason = null,
                )
            ),
        )

        val merged = initial + chunk(UIMessagePart.Reasoning("visible thinking")) + chunk(
            UIMessagePart.Reasoning(
                reasoning = "",
                metadata = ClaudeReasoningMetadata(redactedData = "opaque").toMetadata(),
            )
        )

        assertEquals(2, merged.parts.filterIsInstance<UIMessagePart.Reasoning>().size)
        assertEquals(
            "opaque",
            merged.parts.last().metadataAs<ClaudeReasoningMetadata>()?.redactedData,
        )
    }

    @Test
    fun `diff metadata round trip`() {
        val diff = "--- a/file.txt\n+++ b/file.txt\n@@ -1,1 +1,1 @@\n-old\n+new"
        val part = UIMessagePart.Text(
            text = "{}",
            metadata = DiffMetadata(diff = diff).toMetadata(),
        )
        assertEquals(diff, part.metadataAs<DiffMetadata>()?.diff)
    }
}
