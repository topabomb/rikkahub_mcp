package net.weero.mersix.pilot.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.serializer

val JsonInstant by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

val JsonInstantPretty by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}

val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

/**
 * 逐元素反序列化 JSON 数组，遇到未知类型鉴别器时静默跳过。
 * 用于替代直接的 decodeFromString<List<T>>()，优雅处理已移除的密封类子类。
 */
inline fun <reified T> Json.decodeListLenient(json: String): List<T> {
    val array = parseToJsonElement(json).jsonArray
    return array.mapNotNull { element ->
        runCatching { decodeFromJsonElement(serializer<T>(), element) }.getOrNull()
    }
}
