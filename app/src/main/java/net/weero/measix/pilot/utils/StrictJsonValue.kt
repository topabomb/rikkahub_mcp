package net.weero.measix.pilot.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Strict, bounded JSON with duplicate decoded keys rejected before they can collapse into a map. */
internal class StrictJsonValue private constructor(private val input: String) {
    private var position = 0

    private fun value(depth: Int): JsonElement {
        check(depth <= 128) { "frame nesting is too deep" }
        whitespace()
        return when (input.getOrNull(position)) {
            '{' -> {
                position++
                val fields = linkedMapOf<String, JsonElement>()
                whitespace()
                if (!take('}')) {
                    do {
                        whitespace()
                        val key = scalar() as? JsonPrimitive ?: error("Invalid object key")
                        check(key.isString) { "object key must be a string" }
                        check(key.content !in fields) { "Duplicate object key" }
                        whitespace()
                        check(take(':')) { "Invalid object separator" }
                        fields[key.content] = value(depth + 1)
                        whitespace()
                    } while (take(','))
                    check(take('}')) { "Invalid object" }
                }
                JsonObject(fields)
            }
            '[' -> {
                position++
                val values = mutableListOf<JsonElement>()
                whitespace()
                if (!take(']')) {
                    do {
                        values += value(depth + 1)
                        whitespace()
                    } while (take(','))
                    check(take(']')) { "Invalid array" }
                }
                JsonArray(values)
            }
            else -> scalar()
        }
    }

    private fun scalar(): JsonElement {
        val start = position
        if (take('"')) {
            var closed = false
            while (position < input.length) {
                when (input[position++]) {
                    '\\' -> position++
                    '"' -> { closed = true; break }
                }
            }
            check(closed) { "Unterminated JSON string" }
        } else {
            while (position < input.length && input[position] !in ",]}: \t\r\n") position++
        }
        check(position > start) { "Invalid JSON value" }
        val token = input.substring(start, position)
        check(token.startsWith('"') || token in setOf("true", "false", "null") || NUMBER.matches(token)) {
            "Invalid JSON scalar"
        }
        return Json.parseToJsonElement(token).also {
            check(it is JsonPrimitive) { "Invalid JSON scalar" }
        }
    }

    private fun whitespace() {
        while (position < input.length && input[position] in " \t\r\n") position++
    }

    private fun take(char: Char): Boolean = (input.getOrNull(position) == char).also { if (it) position++ }

    companion object {
        private val NUMBER = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

        fun parse(text: String, maxBytes: Int): JsonElement {
            require(maxBytes > 0)
            check(text.length <= maxBytes && text.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                "frame exceeds byte limit"
            }
            val reader = StrictJsonValue(text)
            val result = reader.value(0)
            reader.whitespace()
            check(reader.position == text.length) { "Trailing JSON input" }
            return result
        }
    }
}
