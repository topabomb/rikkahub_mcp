package me.rerere.ai.provider.images

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.formatProviderHttpError
import me.rerere.ai.util.json

sealed class ParsedImageGenerationItem {
    data class Base64Json(val data: String, val mimeType: String) : ParsedImageGenerationItem()
    data class RemoteUrl(val url: String) : ParsedImageGenerationItem()
}

data class ImageGenerationResponseParse(
    val items: List<ParsedImageGenerationItem>,
    val allBlockedByModeration: Boolean,
)

fun parseImageGenerationResponseBody(body: String): ImageGenerationResponseParse {
    val root = json.parseToJsonElement(body)
    val obj = root as? JsonObject ?: error("Image response is not a JSON object")
    val data = obj["data"]?.jsonArray
    val items = mutableListOf<ParsedImageGenerationItem>()
    var seen = 0
    var blocked = 0
    if (data != null) {
        for (element in data) {
            val item = element as? JsonObject ?: continue
            seen++
            if (isImageItemModerated(item)) {
                blocked++
                continue
            }
            val parsed = parseImageItem(item, obj) ?: continue
            items += parsed
        }
    }
    val topLevelBlocked = isImageItemModerated(obj)
    if (items.isEmpty() && obj["error"] != null) {
        throw formatProviderHttpError(400, body)
    }
    val allBlocked = (seen > 0 && blocked == seen) || (items.isEmpty() && topLevelBlocked)
    return ImageGenerationResponseParse(
        items = items,
        allBlockedByModeration = allBlocked,
    )
}

fun moderationBlockedImageException(): HttpException = HttpException(
    message = "Failed to generate image: blocked by safety system",
    statusCode = 200,
    errorCode = "moderation_blocked",
    errorType = "image_generation_user_error",
)

private fun parseImageItem(item: JsonObject, root: JsonObject): ParsedImageGenerationItem? {
    val defaultFormat = root["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
    val b64Json = item["b64_json"]?.jsonPrimitive?.contentOrNull
    if (!b64Json.isNullOrBlank()) {
        val outputFormat = item["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
        return ParsedImageGenerationItem.Base64Json(
            data = b64Json,
            mimeType = outputFormat.toImageMimeType(),
        )
    }
    val url = item["url"]?.jsonPrimitive?.contentOrNull
    return url?.takeIf { it.isNotBlank() }?.let(ParsedImageGenerationItem::RemoteUrl)
}

internal fun isImageItemModerated(obj: JsonObject): Boolean {
    if (isFalsey(obj["respect_moderation"])) return true
    val moderation = obj["moderation"]?.jsonPrimitive?.contentOrNull?.lowercase()
    if (moderation in setOf("blocked", "filtered", "rejected", "failed")) return true
    if (isTruthy(obj["moderation_blocked"]) || isTruthy(obj["moderated"])) return true
    return false
}

private fun isFalsey(element: kotlinx.serialization.json.JsonElement?): Boolean {
    val primitive = element as? JsonPrimitive ?: return false
    return primitive.booleanOrNull == false || primitive.content.equals("false", ignoreCase = true)
}

private fun isTruthy(element: kotlinx.serialization.json.JsonElement?): Boolean {
    val primitive = element as? JsonPrimitive ?: return false
    return primitive.booleanOrNull == true || primitive.content.equals("true", ignoreCase = true)
}

internal fun String.toImageMimeType(): String = when (lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}
