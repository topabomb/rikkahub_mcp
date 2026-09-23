package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart
import me.rerere.search.SearchHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchToolsTest {
    @Test
    fun `search and scrape reject known malformed arguments`() {
        fun input(value: String) = Json.parseToJsonElement(value)
        assertEquals("invalid_arguments", validateSearchArguments(input("{}"), true)!!
            .getValue("reason").jsonPrimitive.content)
        assertEquals("topic must be general, news, or finance.", validateSearchArguments(
            input("{\"query\":\"factory\",\"topic\":\"invalid\"}"), true,
        )!!.getValue("detail").jsonPrimitive.content)
        assertEquals("invalid_arguments", requireWebString(input("{}"), "url")!!
            .getValue("reason").jsonPrimitive.content)
        assertNull(validateSearchArguments(input("{\"query\":\"factory\"}"), true))
    }

    @Test
    fun `provider HTTP refusals have stable actionable reasons`() {
        for ((code, reason) in listOf(400 to "invalid_request", 401 to "auth_failed",
            429 to "rate_limited", 503 to "search_provider_error")) {
            val failure = kotlin.runCatching {
                Result.failure<String>(SearchHttpException("Tavily", code)).searchToolValue()
            }.exceptionOrNull() as ToolExecutionFailure
            val envelope = Json.parseToJsonElement((failure.output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals(reason, envelope.getValue("reason").jsonPrimitive.content)
            assertEquals("Tavily returned HTTP $code", envelope.getValue("detail").jsonPrimitive.content)
        }
    }
}
