@file:Suppress("REDUNDANT_ELSE_IN_WHEN")
package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val FORMATTED_HTTP_MESSAGE_MAX_CHARS = 2 * 1024
private const val ENVELOPE_WALK_DEPTH = 6

class HttpException(
    message: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val errorType: String? = null,
) : RuntimeException(message)

data class ProviderErrorEnvelope(
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
) {
    val hasSignal: Boolean
        get() = !message.isNullOrBlank() || !code.isNullOrBlank() || !type.isNullOrBlank()
}

fun formatProviderHttpError(code: Int, body: String?): HttpException {
    val envelope = body?.let(::parseProviderErrorEnvelope)
    val extracted = envelope?.message
        ?.let(::redactProviderSecrets)
        ?.let(::collapseProviderErrorText)
        ?.takeIf { it.isNotEmpty() }
        ?: body?.let(::parseStructuredHttpError)?.let(::redactProviderSecrets)
    val clipped = extracted?.let { clipProviderDetail(it, FORMATTED_HTTP_MESSAGE_MAX_CHARS) }
    val suffix = buildString {
        envelope?.code?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append(' ') }
        if (!clipped.isNullOrBlank() && clipped != envelope?.code) append(clipped)
    }.trim()
    val message = if (suffix.isEmpty()) {
        "Failed to get response: $code"
    } else {
        "Failed to get response: $code $suffix"
    }
    return HttpException(
        message = message,
        statusCode = code,
        errorCode = envelope?.code?.trim()?.takeIf { it.isNotEmpty() },
        errorType = envelope?.type?.trim()?.takeIf { it.isNotEmpty() },
    )
}

fun parseStructuredHttpError(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("<")) return null
    return runCatching {
        Json.parseToJsonElement(trimmed).parseErrorDetail().message
    }.getOrNull()
        ?.let(::collapseProviderErrorText)
        ?.takeIf { it.isNotEmpty() }
}

fun parseProviderErrorEnvelope(body: String): ProviderErrorEnvelope? {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("<")) return null
    return runCatching {
        extractProviderErrorEnvelope(Json.parseToJsonElement(trimmed))
    }.getOrNull()?.takeIf { it.hasSignal }
}

fun extractProviderErrorEnvelope(element: JsonElement, depth: Int = 0): ProviderErrorEnvelope {
    if (depth > ENVELOPE_WALK_DEPTH) return ProviderErrorEnvelope()
    return when (element) {
        is JsonObject -> {
            val nestedElement = element["error"] ?: element["detail"]
            val nested = when (nestedElement) {
                null -> ProviderErrorEnvelope()
                is JsonObject, is JsonArray -> extractProviderErrorEnvelope(nestedElement, depth + 1)
                is JsonPrimitive -> ProviderErrorEnvelope(message = nestedElement.contentOrNull?.trim())
                else -> ProviderErrorEnvelope()
            }
            ProviderErrorEnvelope(
                message = firstNonBlank(nested.message, stringField(element, "message"), stringField(element, "description")),
                code = firstNonBlank(nested.code, stringField(element, "code")),
                type = firstNonBlank(nested.type, stringField(element, "type")),
            )
        }

        is JsonArray -> {
            if (element.isEmpty()) ProviderErrorEnvelope()
            else extractProviderErrorEnvelope(element.first(), depth + 1)
        }

        is JsonPrimitive -> ProviderErrorEnvelope(message = element.contentOrNull?.trim())

        else -> ProviderErrorEnvelope()
    }
}

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail()
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this))
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this))
        }
    }
}

private fun stringField(obj: JsonObject, key: String): String? {
    val value = obj[key] ?: return null
    return when (value) {
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }
