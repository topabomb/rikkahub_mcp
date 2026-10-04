package net.weero.measix.pilot.data.ai.request

import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterInitialContext
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterOpeningSnapshot
import net.weero.measix.pilot.data.model.ConversationContextSource
import org.junit.Assert.*
import org.junit.Test

class ContextModelTextTest {
    @Test fun `new starter input contains only ordered literal content while saved rendering stays reproducible`() {
        val content = "  {{literal}}\r\n{\"format\":1,\"title\":\"source text\"}"
        val opening = EnterpriseStarterOpeningSnapshot(format = 1, systemPrompt = "", initialContexts = listOf(
            EnterpriseStarterInitialContext("second", content, "Old title"),
            EnterpriseStarterInitialContext("first", "")))
        val current = Json.parseToJsonElement(renderStarterContext(opening, 2)).jsonObject
        assertEquals(setOf("type", "blocks"), current.keys)
        assertEquals(listOf(content, ""), current.getValue("blocks").jsonArray.map { it.jsonPrimitive.content })
        val old = Json.parseToJsonElement(renderStarterContext(opening, 1)).jsonObject
        assertEquals(1, old.getValue("format").jsonPrimitive.int)
        assertEquals("Old title", old.getValue("blocks").jsonArray.first().jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("second", old.getValue("blocks").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test fun `model disclosure drops only top level format and preserves explicit clears and literal payload`() {
        val text = """{"type":"conversation_disclosure_snapshot","format":1,"memory":{"rows":[],"content":"format title id"}}"""
        val source = ConversationContextSource.Disclosure(null)
        assertEquals(text, renderContextModelText(source, text, 1))
        val current = Json.parseToJsonElement(renderContextModelText(source, text, 2)).jsonObject
        assertFalse("format" in current)
        assertEquals(Json.parseToJsonElement(text).jsonObject.getValue("memory"), current.getValue("memory"))
        assertFalse("sub_assistants" in current)
    }

    @Test fun `summary rendering preserves content and historical wrapper without leaking new format`() {
        val source = ConversationContextSource.HistorySummary(null, "saved instruction")
        val text = "original\r\n{{literal}}"
        val current = Json.parseToJsonElement(renderContextModelText(source, text, 2)).jsonObject
        assertEquals(setOf("type", "content"), current.keys)
        assertEquals(text, current.getValue("content").jsonPrimitive.content)
        assertTrue("format" in Json.parseToJsonElement(renderContextModelText(source, text, 1)).jsonObject)
    }
}
