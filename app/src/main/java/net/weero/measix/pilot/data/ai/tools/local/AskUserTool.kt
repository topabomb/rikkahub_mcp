package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal const val ASK_USER_TOOL_NAME = "ask_user"
internal const val MAX_ASK_USER_INPUT_CHARS = 16 * 1024
private const val MAX_ASK_USER_QUESTIONS = 4

internal const val ASK_USER_OPTIONS_HINT =
    "options must be an array of strings, e.g. [\"Yes\", \"No\"]. Do not send objects."

internal data class AskUserArgumentError(
    val field: String,
    val expected: String,
    val hint: String? = null,
)

internal fun validateAskUserArguments(args: JsonElement): AskUserArgumentError? {
    if (args.toString().length > MAX_ASK_USER_INPUT_CHARS) {
        return AskUserArgumentError("arguments", "at most $MAX_ASK_USER_INPUT_CHARS characters")
    }
    val obj = args as? JsonObject
        ?: return AskUserArgumentError("arguments", "object")

    val questionsElement = obj["questions"]
        ?: return AskUserArgumentError("questions", "non-empty array")
    val questions = questionsElement as? JsonArray
        ?: return AskUserArgumentError("questions", "array")
    if (questions.isEmpty()) {
        return AskUserArgumentError("questions", "non-empty array")
    }
    if (questions.size > MAX_ASK_USER_QUESTIONS) {
        return AskUserArgumentError("questions", "at most $MAX_ASK_USER_QUESTIONS items")
    }

    val seenIds = mutableSetOf<String>()
    questions.forEachIndexed { index, item ->
        val prefix = "questions[$index]"
        val questionObj = item as? JsonObject
            ?: return AskUserArgumentError(prefix, "object")

        val id = (questionObj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        if (id.isNullOrEmpty()) {
            return AskUserArgumentError("$prefix.id", "non-empty string")
        }
        if (!seenIds.add(id)) {
            return AskUserArgumentError("$prefix.id", "unique string")
        }

        val question = (questionObj["question"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
        if (question.isNullOrEmpty()) {
            return AskUserArgumentError("$prefix.question", "non-empty string")
        }

        val selectionRaw = questionObj["selection_type"]
        val selectionType = when {
            selectionRaw == null -> "text"
            selectionRaw is JsonPrimitive && selectionRaw.isString -> selectionRaw.content
            else -> return AskUserArgumentError(
                "$prefix.selection_type",
                """"text", "single", or "multi"""",
            )
        }
        if (selectionType !in setOf("text", "single", "multi")) {
            return AskUserArgumentError(
                "$prefix.selection_type",
                """"text", "single", or "multi"""",
            )
        }

        val optionsError = validateAskUserOptions(
            fieldPrefix = "$prefix.options",
            optionsElement = questionObj["options"],
            required = selectionType == "single" || selectionType == "multi",
        )
        if (optionsError != null) {
            return optionsError
        }
    }
    return null
}

private fun validateAskUserOptions(
    fieldPrefix: String,
    optionsElement: JsonElement?,
    required: Boolean,
): AskUserArgumentError? {
    if (optionsElement == null) {
        return if (required) {
            AskUserArgumentError(fieldPrefix, "non-empty array of strings", ASK_USER_OPTIONS_HINT)
        } else {
            null
        }
    }
    val options = optionsElement as? JsonArray
        ?: return AskUserArgumentError(fieldPrefix, "array of strings", ASK_USER_OPTIONS_HINT)
    if (required && options.isEmpty()) {
        return AskUserArgumentError(fieldPrefix, "non-empty array of strings", ASK_USER_OPTIONS_HINT)
    }
    options.forEachIndexed { optionIndex, option ->
        val primitive = option as? JsonPrimitive
        val text = primitive?.takeIf { it.isString }?.contentOrNull?.trim()
        if (text.isNullOrEmpty()) {
            return AskUserArgumentError(
                "$fieldPrefix[$optionIndex]",
                "non-empty string",
                ASK_USER_OPTIONS_HINT,
            )
        }
    }
    return null
}

internal fun AskUserArgumentError.toErrorJson(): JsonObject = buildJsonObject {
    put("error", "invalid_arguments")
    put("field", field)
    put("expected", expected)
    hint?.let { put("hint", it) }
}

internal fun AskUserArgumentError.toToolResult(): List<UIMessagePart> =
    listOf(UIMessagePart.Text(toErrorJson().toString()))

internal fun buildAskUserTool(): Tool = Tool(
    name = ASK_USER_TOOL_NAME,
    description = "Ask the user one or more questions when you need clarification or confirmation.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", MAX_ASK_USER_QUESTIONS)
                    put("description", "List of questions to ask the user")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put("description", "Suggested string choices, not objects.")
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    kotlinx.serialization.json.buildJsonArray {
                                        add("text")
                                        add("single")
                                        add("multi")
                                    }
                                )
                                put(
                                    "description",
                                    "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                )
                            })
                        })
                        put("required", kotlinx.serialization.json.buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    validateArguments = { validateAskUserArguments(it)?.toErrorJson() },
    execute = { args ->
        validateAskUserArguments(args)?.toToolResult()
            ?: error("ask_user tool should be handled by HITL flow")
    }
)
