package me.rerere.ai.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.internal.http.RealResponseBody

fun List<CustomHeader>.toHeaders(): Headers {
    return Headers.Builder().apply {
        this@toHeaders
            .filter { it.name.isNotBlank() }
            .forEach {
                add(it.name, it.value)
            }
    }.build()
}

fun Request.Builder.configureReferHeaders(url: String): Request.Builder {
    val httpUrl = url.toHttpUrl()
    return when (httpUrl.host) {
        "aihubmix.com" -> {
            addHeader("APP-Code", "DKHA9468")
        }

        "openrouter.ai" -> {
            this
                .addHeader("X-Title", "MeasixPilot")
                .addHeader("HTTP-Referer", "https://measix-pilot.weero.net")
        }

        else -> this
    }
}

fun ResponseBody.stringSafe(): String? {
    return when (this) {
        is RealResponseBody -> string()
        else -> null
    }
}

/**
 * Ownership contract for a Provider request body, declaring which top-level keys are owned
 * exclusively by the Provider adapter and cannot be overridden through [CustomBody].
 *
 * Each Provider builder declares its own reserved-key set; there is no global mega-table.
 * If a custom body conflicts with a reserved key, [mergeCustomBody] throws a typed local
 * error before any HTTP request is sent, instead of silently replacing or ignoring the
 * structural field.
 */
data class RequestBodyOwnership(
    val protocol: String,
    val reservedKeys: Set<String>,
    /** Nested response-shape paths that remain adapter-owned while sibling settings stay extensible. */
    val reservedPaths: Set<List<String>> = emptySet(),
)

/**
 * Typed local error raised when a [CustomBody] entry attempts to override a reserved key
 * owned by the Provider adapter.
 *
 * The stable [reason] is `custom_body_reserved_key`; the message lists the protocol and the
 * sorted conflicting keys for deterministic local diagnostics. This error is produced before
 * the HTTP request and must not be recorded as a remote 400.
 */
class CustomBodyReservedKeyException(
    val protocol: String,
    conflictingKeys: List<String>,
) : Exception(
    "custom_body_reserved_key: protocol=$protocol, conflicting keys=${conflictingKeys.sorted().joinToString(", ")}"
) {
    val reason: String = "custom_body_reserved_key"
    val conflictingKeys: List<String> = conflictingKeys.sorted()
}

fun JsonObject.mergeCustomBody(
    bodies: List<CustomBody>,
    ownership: RequestBodyOwnership,
): JsonObject {
    if (bodies.isEmpty()) return this

    val conflicts = buildSet {
        bodies.forEach { body ->
            if (body.key in ownership.reservedKeys) add(body.key)
            val baseValue = this@mergeCustomBody[body.key]
            if (baseValue is JsonObject) {
                addAll(baseValue.findObjectShapeConflicts(body.value, listOf(body.key)))
            }
            ownership.reservedPaths
                .filter { path -> path.firstOrNull() == body.key }
                .filter { path -> body.value.containsJsonPath(path.drop(1)) }
                .forEach { path -> add(path.joinToString(".")) }
        }
    }
    if (conflicts.isNotEmpty()) {
        throw CustomBodyReservedKeyException(
            protocol = ownership.protocol,
            conflictingKeys = conflicts.toList(),
        )
    }

    val content = toMutableMap()
    bodies.forEach { body ->
        if (body.key.isNotBlank()) {
            val existingValue = content[body.key]
            val newValue = body.value

            if (existingValue is JsonObject && newValue is JsonObject) {
                content[body.key] = mergeJsonObjects(existingValue, newValue)
            } else {
                content[body.key] = newValue
            }
        }
    }
    return JsonObject(content)
}

private fun JsonObject.findObjectShapeConflicts(
    overlay: JsonElement,
    path: List<String>,
): Set<String> {
    val overlayObject = overlay as? JsonObject ?: return setOf(path.joinToString("."))
    return buildSet {
        this@findObjectShapeConflicts.forEach { (key, baseValue) ->
            if (baseValue is JsonObject && key in overlayObject) {
                addAll(
                    baseValue.findObjectShapeConflicts(
                        overlay = requireNotNull(overlayObject[key]),
                        path = path + key,
                    )
                )
            }
        }
    }
}

private fun JsonElement.containsJsonPath(path: List<String>): Boolean {
    if (path.isEmpty()) return true
    val objectValue = this as? JsonObject ?: return false
    val child = objectValue[path.first()] ?: return false
    return child.containsJsonPath(path.drop(1))
}

/**
 * 递归合并两个JsonObject
 */
private fun mergeJsonObjects(base: JsonObject, overlay: JsonObject): JsonObject {
    val result = base.toMutableMap()

    for ((key, value) in overlay) {
        val baseValue = result[key]

        result[key] = if (baseValue is JsonObject && value is JsonObject) {
            // 如果两者都是JsonObject，递归合并
            mergeJsonObjects(baseValue, value)
        } else {
            // 否则使用新值替换旧值
            value
        }
    }

    return JsonObject(result)
}

/**
 * 从 JsonElement 中移除或保留指定的键
 * @param keys 要操作的键列表
 * @param keepOnly 如果为 true，则只保留指定的键；如果为 false，则移除指定的键
 * @return 处理后的 JsonElement
 */
fun JsonElement.removeElements(keys: List<String>, keepOnly: Boolean = false): JsonElement {
    return when (this) {
        is JsonObject -> {
            val newContent = if (keepOnly) {
                // 只保留指定的键（且键存在）
                keys.mapNotNull { key ->
                    get(key)?.let { key to it }
                }.toMap()
            } else {
                // 移除指定的键
                toMap().filterKeys { key -> key !in keys }
            }

            // 递归处理嵌套的 JsonElement
            JsonObject(newContent.mapValues { (_, value) ->
                value.removeElements(keys, keepOnly)
            })
        }

        is JsonArray -> {
            JsonArray(map { it.removeElements(keys, keepOnly) })
        }

        else -> this // 基本类型直接返回
    }
}
