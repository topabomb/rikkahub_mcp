package net.weero.measix.pilot.data.ai.tools.local

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal fun buildCalendarQueryTool(context: Context): Tool = Tool(
    name = "calendar_query",
    description = """
        Query device calendar events (`begin`/`end`, or `range`: today/week/month).
        Device timezone: '${ZoneId.systemDefault()}' (UTC ${OffsetDateTime.now().offset}); naive times use this zone.
        Requires Calendar permission; if missing, an error asks the user to enable it in local tools settings.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                            "When provided, 'range' is ignored."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "End time (exclusive), same formats as 'begin'. Defaults to now."
                    )
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("today")
                            add("week")
                            add("month")
                        }
                    )
                    put(
                        "description",
                        "Convenience preset, used only when 'begin' is omitted: today, week, or month. Default today."
                    )
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional keyword to filter events by title (case-insensitive substring match).")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of events to return. Default 20.")
                })
            }
        )
    },
    execute = { args ->
        if (!hasCalendarReadPermission(context)) {
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Calendar read permission is not granted. Please ask the user to enable " +
                        "the calendar permission in the assistant's local tools settings."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = args.jsonObject
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val query = params["query"]?.jsonPrimitive?.contentOrNull

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            startTime = if (beginRaw != null) {
                parseCalendarTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
                "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
            endTime = if (endRaw != null) {
                parseCalendarTime(endRaw, zone)
            } else when (rangePreset) {
                "week" -> startTime.plusDays(7)
                "month" -> startTime.plusMonths(1)
                else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
            }
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format for begin/end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "begin must be earlier than end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val startMs = startTime.toInstant().toEpochMilli()
        val endMs = endTime.toInstant().toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        val selection = if (query != null) {
            "${CalendarContract.Instances.TITLE} LIKE ?"
        } else null
        val selectionArgs = if (query != null) {
            arrayOf("%$query%")
        } else null

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()

        val events = buildJsonArray {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(0))
                        put("title", cursor.getString(1) ?: "")
                        put("description", cursor.getString(2) ?: "")
                        put("location", cursor.getString(3) ?: "")
                        val dtStart = cursor.getLong(4)
                        val dtEnd = cursor.getLong(5)
                        val allDay = cursor.getInt(6) == 1
                        if (allDay) {
                            put("start", Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate().toString())
                            put(
                                "end",
                                if (dtEnd > 0) {
                                    Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().toString()
                                } else {
                                    ""
                                }
                            )
                        } else {
                            put("start", Instant.ofEpochMilli(dtStart).atZone(zone).withNano(0).toString())
                            put(
                                "end",
                                if (dtEnd > 0) {
                                    Instant.ofEpochMilli(dtEnd).atZone(zone).withNano(0).toString()
                                } else {
                                    ""
                                }
                            )
                        }
                        put("all_day", allDay)
                        put("calendar", cursor.getString(7) ?: "")
                    })
                    count++
                }
            }
        }

        val payload = buildJsonObject {
            put("range_start", startTime.withNano(0).toString())
            put("range_end", endTime.withNano(0).toString())
            put("count", events.size)
            put("events", events)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

internal data class CalendarCreateArguments(
    val title: String,
    val description: String,
    val location: String,
    val allDay: Boolean,
    val startTime: ZonedDateTime,
    val endTime: ZonedDateTime,
    val startMillis: Long,
    val endMillis: Long,
    val timeZone: String,
)

internal sealed interface CalendarCreateParseResult {
    data class Valid(val event: CalendarCreateArguments) : CalendarCreateParseResult
    data class Invalid(val error: String, val message: String) : CalendarCreateParseResult {
        fun toErrorJson(): JsonObject = buildJsonObject {
            put("error", error)
            put("message", message)
        }

        fun toToolResult(): List<UIMessagePart> = listOf(UIMessagePart.Text(toErrorJson().toString()))
    }
}

/** Same pure input parser for approval and execution; permissions and Calendar IO stay outside. */
internal fun parseCalendarCreateArguments(args: JsonElement, zone: ZoneId): CalendarCreateParseResult {
    fun invalid(error: String, message: String) = CalendarCreateParseResult.Invalid(error, message)
    val obj = args as? JsonObject ?: return invalid("INVALID_ARGUMENTS", "Arguments must be an object.")
    val strings = listOf("title", "start", "end", "description", "location")
    if (strings.any { it in obj && (obj[it] as? JsonPrimitive)?.isString != true }) {
        return invalid("INVALID_ARGUMENTS", "title, start, end, description and location must be strings when provided.")
    }
    val allDayValue = obj["all_day"]
    if (allDayValue != null && (
        allDayValue !is JsonPrimitive || allDayValue.isString || allDayValue.booleanOrNull == null
    )) return invalid("INVALID_ARGUMENTS", "all_day must be a boolean when provided.")
    val title = obj["title"]?.jsonPrimitive?.content
    val startRaw = obj["start"]?.jsonPrimitive?.content
    if (title.isNullOrBlank() || startRaw.isNullOrBlank()) {
        return invalid("MISSING_REQUIRED", "Both 'title' and 'start' are required.")
    }
    val allDay = allDayValue?.booleanOrNull ?: false
    val endRaw = obj["end"]?.jsonPrimitive?.content
    return try {
        val startTime = parseCalendarTime(startRaw, zone)
        val endTime = when {
            endRaw != null -> parseCalendarTime(endRaw, zone)
            allDay -> startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
            else -> startTime.plusHours(1)
        }
        if (!startTime.isBefore(endTime)) {
            return invalid("INVALID_RANGE", "end must be later than start.")
        }
        val startMillis: Long
        val endMillis: Long
        if (allDay) {
            val startDate = startTime.toLocalDate()
            val endDate = endTime.toLocalDate()
            if (!startDate.isBefore(endDate)) {
                return invalid("INVALID_RANGE", "all-day event end date must be later than start date.")
            }
            startMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            endMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            startMillis = startTime.toInstant().toEpochMilli()
            endMillis = endTime.toInstant().toEpochMilli()
        }
        CalendarCreateParseResult.Valid(CalendarCreateArguments(
            title = title,
            description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
            location = obj["location"]?.jsonPrimitive?.content.orEmpty(),
            allDay = allDay,
            startTime = startTime,
            endTime = endTime,
            startMillis = startMillis,
            endMillis = endMillis,
            timeZone = if (allDay) "UTC" else zone.id,
        ))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: java.time.DateTimeException) {
        invalid("INVALID_TIME", error.message ?: "Invalid time format.")
    } catch (error: IllegalStateException) {
        invalid("INVALID_TIME", error.message ?: "Invalid time format.")
    } catch (error: ArithmeticException) {
        invalid("INVALID_TIME", error.message ?: "Invalid time range.")
    }
}

internal fun buildCalendarCreateTool(context: Context): Tool {
    val zone = ZoneId.systemDefault()
    return Tool(
        name = "calendar_create",
        description = """
            Create a calendar event (title and start required). End defaults to 1 hour after start, or the next day if all-day.
            Device timezone: '$zone' (UTC ${OffsetDateTime.now(zone).offset}).
            Requires Calendar permission; if missing, an error asks the user to enable it in local tools settings.
        """.trimIndent().replace("\n", " "),
        needsApproval = { true },
        validateArguments = { args ->
            (parseCalendarCreateArguments(args, zone) as? CalendarCreateParseResult.Invalid)?.toErrorJson()
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "Event title.")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Event description or notes.")
                    })
                    put("location", buildJsonObject {
                        put("type", "string")
                        put("description", "Event location.")
                    })
                    put("start", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Start time. Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                                "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds."
                        )
                    })
                    put("end", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "End time, same formats as 'start'. Defaults to 1 hour after start."
                        )
                    })
                    put("all_day", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Whether this is an all-day event. Default false.")
                    })
                },
                required = listOf("title", "start")
            )
        },
        execute = { args ->
            val event = when (val parsed = parseCalendarCreateArguments(args, zone)) {
                is CalendarCreateParseResult.Invalid -> return@Tool parsed.toToolResult()
                is CalendarCreateParseResult.Valid -> parsed.event
            }
            if (!hasCalendarWritePermission(context)) {
                val payload = buildJsonObject {
                    put("error", "NO_PERMISSION")
                    put(
                        "message",
                        "Calendar write permission is not granted. Please ask the user to enable " +
                            "the calendar permission in the assistant's local tools settings."
                    )
                }
                return@Tool listOf(UIMessagePart.Text(payload.toString()))
            }

            val calendarId = getDefaultCalendarId(context)
            if (calendarId == null) {
                val payload = buildJsonObject {
                    put("error", "NO_CALENDAR")
                    put("message", "No calendar account found on this device. Please add a calendar account first.")
                }
                return@Tool listOf(UIMessagePart.Text(payload.toString()))
            }

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, event.title)
                put(CalendarContract.Events.DESCRIPTION, event.description)
                put(CalendarContract.Events.EVENT_LOCATION, event.location)
                put(CalendarContract.Events.DTSTART, event.startMillis)
                put(CalendarContract.Events.DTEND, event.endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, event.timeZone)
                if (event.allDay) {
                    put(CalendarContract.Events.ALL_DAY, 1)
                }
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                val payload = buildJsonObject {
                    put("error", "INSERT_FAILED")
                    put("message", "Failed to insert calendar event.")
                }
                return@Tool listOf(UIMessagePart.Text(payload.toString()))
            }

            val eventId = ContentUris.parseId(uri)
            val payload = buildJsonObject {
                put("success", true)
                put("event_id", eventId)
                put("start", event.startTime.withNano(0).toString())
                put("end", event.endTime.withNano(0).toString())
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

}

private fun hasCalendarReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun hasCalendarWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun getDefaultCalendarId(context: Context): Long? {
    val projection = arrayOf(CalendarContract.Calendars._ID)
    val writableSelection =
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
    val writableArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        "$writableSelection AND ${CalendarContract.Calendars.IS_PRIMARY} = 1",
        writableArgs,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        writableSelection,
        writableArgs,
        "${CalendarContract.Calendars.VISIBLE} DESC"
    )?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getLong(0)
    }
    return null
}

private fun parseCalendarTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Invalid time format: '$raw'. Use ISO-8601 date/date-time or epoch milliseconds.")
}
