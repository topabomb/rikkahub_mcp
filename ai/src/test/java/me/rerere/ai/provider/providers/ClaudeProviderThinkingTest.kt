package me.rerere.ai.provider.providers

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeProviderThinkingTest {
    @Test
    fun `adaptive models use adaptive thinking and effort`() {
        val opus48 = buildClaudeThinkingFields(
            modelId = "claude-opus-4.8",
            level = ReasoningLevel.XHIGH,
            maxTokens = 64_000,
        )
        val sonnet46 = buildClaudeThinkingFields(
            modelId = "claude-sonnet-4-6",
            level = ReasoningLevel.XHIGH,
            maxTokens = 64_000,
        )

        assertEquals("adaptive", opus48["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", opus48["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content)
        assertEquals("xhigh", opus48["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("max", sonnet46["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        val sonnetMax = buildClaudeThinkingFields(
            modelId = "claude-sonnet-4-6",
            level = ReasoningLevel.MAX,
            maxTokens = 64_000,
        )
        assertEquals("max", sonnetMax["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy models use bounded manual thinking budgets`() {
        val low = buildClaudeThinkingFields("claude-3.7-sonnet", ReasoningLevel.LOW, 64_000)
        val capped = buildClaudeThinkingFields("claude-sonnet-4.5", ReasoningLevel.XHIGH, 4_096)
        val opus45 = buildClaudeThinkingFields("claude-opus-4-5", ReasoningLevel.XHIGH, 64_000)

        assertEquals("enabled", low["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1_024, low["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(4_095, capped["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertFalse(capped.containsKey("output_config"))
        assertEquals("high", opus45["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy auto omits thinking and off disables it`() {
        val auto = buildClaudeThinkingFields("claude-3.7-sonnet", ReasoningLevel.AUTO, 64_000)
        val off = buildClaudeThinkingFields("claude-opus-4.8", ReasoningLevel.OFF, 64_000)

        assertTrue(auto.isEmpty())
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(off.containsKey("output_config"))
    }
}
