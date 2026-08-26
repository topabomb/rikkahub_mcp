package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema

/**
 * Normalizes a nullable tool parameters schema to a non-null OpenAI function tool `parameters` value.
 *
 * Some OpenAI-compatible endpoints (including MiMo Responses) require `parameters` to be at least
 * an object schema. When [Tool.parameters] returns `null` (the open-schema default), this helper
 * substitutes an empty object schema equivalent to [InputSchema.Obj] with no properties.
 *
 * Non-null schemas are returned as-is so that `$defs`, `$ref`, `oneOf`, `additionalProperties`
 * and provider extensions are preserved losslessly across both Chat Completions and Responses.
 */
internal fun normalizeToolParameters(schema: JsonObject?): JsonObject {
    return schema ?: InputSchema.Obj(JsonObject(emptyMap()))
}
