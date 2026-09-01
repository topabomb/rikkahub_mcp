package net.weero.measix.pilot.data.ai.tools.local

import android.content.Context
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CalendarCreateToolTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `missing and wrong typed fields are rejected before permission checks`() = runTest {
        val context = mockk<Context>()
        val tool = buildCalendarCreateTool(context)
        val invalid = listOf(
            "{}",
            """{"title":" ","start":"2026-08-31"}""",
            """{"title":9,"start":"2026-08-31"}""",
            """{"title":"Event","start":1788134400000}""",
            """{"title":"Event","start":"2026-08-31","end":null}""",
            """{"title":"Event","start":"2026-08-31","all_day":"true"}""",
            """{"title":"Event","start":"2026-08-31","all_day":1}""",
            """{"title":"Event","start":"2026-08-31","description":{}}""",
            """{"title":"Event","start":"2026-08-31","location":[]}""",
        )
        for (raw in invalid) {
            val args = Json.parseToJsonElement(raw)
            val rejection = requireNotNull(tool.validateArguments(args))
            val failure = try {
                tool.execute(args)
                throw AssertionError("expected ToolExecutionFailure")
            } catch (error: ToolExecutionFailure) {
                error
            }
            val execution = Json.parseToJsonElement((failure.output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("failed", execution["status"]!!.jsonPrimitive.content)
            assertEquals(
                rejection["error"]!!.jsonPrimitive.content.lowercase(),
                execution["reason"]!!.jsonPrimitive.content,
            )
            assertNull(rejection["type"])
            try {
                tool.parseArguments(raw, Json)
                throw AssertionError("invalid input must not reach approval")
            } catch (failure: ToolArgumentsException) {
                val replay = Json.parseToJsonElement((failure.output.single() as UIMessagePart.Text).text).jsonObject
                assertEquals(rejection, JsonObject(replay.filterKeys { it != "type" }))
                assertEquals("error", replay["type"]!!.jsonPrimitive.content)
            }
        }
        verify { context wasNot Called }
    }

    @Test
    fun `valid calendar arguments still require approval without reading device state`() {
        val context = mockk<Context>()
        val tool = buildCalendarCreateTool(context)
        val args = buildJsonObject {
            put("title", "Review")
            put("start", "2026-08-31T10:00:00+08:00")
        }
        assertNull(tool.validateArguments(args))
        assertEquals(ToolInteractionRequirement.Approval, tool.interactionRequirement(args))
        assertEquals(args, tool.parseArguments(args.toString(), Json))
        verify { context wasNot Called }
    }

    @Test
    fun `all supported time forms produce the same event and one hour default`() {
        val instant = Instant.parse("2026-08-31T00:00:00Z")
        val inputs = listOf(
            "2026-08-31T08:00:00",
            "2026-08-31T08:00:00+08:00",
            "2026-08-31T00:00:00Z",
            instant.toEpochMilli().toString(),
        )
        for (start in inputs) {
            val event = parseValid(start)
            assertEquals(instant.toEpochMilli(), event.startMillis)
            assertEquals(3_600_000L, event.endMillis - event.startMillis)
            assertEquals(zone.id, event.timeZone)
        }
    }

    @Test
    fun `all day defaults to next date with UTC midnight storage`() {
        val event = parseValid("2026-08-31", allDay = true)

        assertEquals(Instant.parse("2026-08-31T00:00:00Z").toEpochMilli(), event.startMillis)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), event.endMillis)
        assertEquals("UTC", event.timeZone)
        assertTrue(event.allDay)
    }

    @Test
    fun `malformed reversed equal and same-date all-day ranges have explicit errors`() {
        val invalid = listOf(
            Triple("not-time", null, false) to "INVALID_TIME",
            Triple("2026-08-31T10:00:00", "bad", false) to "INVALID_TIME",
            Triple("2026-08-31T10:00:00", "2026-08-31T09:00:00", false) to "INVALID_RANGE",
            Triple("2026-08-31T10:00:00", "2026-08-31T10:00:00", false) to "INVALID_RANGE",
            Triple("2026-08-31T10:00:00", "2026-08-31T11:00:00", true) to "INVALID_RANGE",
            Triple("+999999999-12-31T23:59:59", null, false) to "INVALID_TIME",
        )
        for ((input, error) in invalid) {
            val args = buildJsonObject {
                put("title", "Review")
                put("start", input.first)
                input.second?.let { put("end", it) }
                put("all_day", input.third)
            }
            val result = parseCalendarCreateArguments(args, zone) as CalendarCreateParseResult.Invalid
            assertEquals(error, result.error)
            val failure = assertThrows(ToolExecutionFailure::class.java) { result.toToolResult() }
            val json = Json.parseToJsonElement((failure.output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("failed", json["status"]!!.jsonPrimitive.content)
            assertEquals(error.lowercase(), json["reason"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `timezone is an explicit pure parser input`() {
        val args = buildJsonObject {
            put("title", "Review")
            put("start", "2026-08-31T10:00:00")
        }
        val shanghai = (parseCalendarCreateArguments(args, zone) as CalendarCreateParseResult.Valid).event
        val utc = (parseCalendarCreateArguments(args, ZoneOffset.UTC) as CalendarCreateParseResult.Valid).event

        assertEquals(8 * 3_600_000L, utc.startMillis - shanghai.startMillis)
    }

    @Test
    fun `each step captures current timezone while previously built tool keeps its parser zone`() {
        val previousTimeZone = TimeZone.getDefault()
        try {
            val localTools = LocalTools(mockk(), mockk(), mockk(), mockk(), mockk())
            val args = buildJsonObject {
                put("title", "Timezone-sensitive range")
                put("start", "2026-08-31T10:00:00")
                put("end", "2026-08-31T04:00:00Z")
            }
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val firstStep = localTools.getTools(listOf(LocalToolOption.Calendar))
            val firstCreate = firstStep.single { it.name == "calendar_create" }
            assertTrue(firstCreate.description.contains("Asia/Shanghai"))
            assertNull(firstCreate.validateArguments(args))

            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val nextStep = localTools.getTools(listOf(LocalToolOption.Calendar))
            val nextCreate = nextStep.single { it.name == "calendar_create" }
            assertNotSame(firstCreate, nextCreate)
            assertTrue(nextCreate.description.contains("'UTC'"))
            val rejected = requireNotNull(nextCreate.validateArguments(args))
            assertEquals("INVALID_RANGE", rejected["error"]!!.jsonPrimitive.content)
            assertNull(firstCreate.validateArguments(args))
            assertSame(
                firstStep.single { it.name == "calendar_query" },
                nextStep.single { it.name == "calendar_query" },
            )
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }

    private fun parseValid(start: String, allDay: Boolean = false): CalendarCreateArguments =
        (parseCalendarCreateArguments(buildJsonObject {
            put("title", "Review")
            put("start", start)
            put("all_day", allDay)
        }, zone) as CalendarCreateParseResult.Valid).event
}
