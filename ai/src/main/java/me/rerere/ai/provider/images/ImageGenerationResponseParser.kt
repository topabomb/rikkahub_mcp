package me.rerere.ai.provider.images

import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
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

@Serializable
private data class ImageGenerationResponseWire(
    val data: List<ImageGenerationItemWire>? = null,
    @SerialName("output_format") val outputFormat: String? = null,
    val error: JsonElement? = null,
    @SerialName("respect_moderation") val respectModeration: JsonElement? = null,
    val moderation: JsonElement? = null,
    @SerialName("moderation_blocked") val moderationBlocked: JsonElement? = null,
    val moderated: JsonElement? = null,
)

@Serializable
private data class ImageGenerationItemWire(
    @SerialName("b64_json") val b64Json: String? = null,
    val url: String? = null,
    @SerialName("output_format") val outputFormat: String? = null,
    @SerialName("respect_moderation") val respectModeration: JsonElement? = null,
    val moderation: JsonElement? = null,
    @SerialName("moderation_blocked") val moderationBlocked: JsonElement? = null,
    val moderated: JsonElement? = null,
)

fun parseImageGenerationResponseBody(body: String): ImageGenerationResponseParse =
    parseImageGenerationResponse(json.decodeFromString<ImageGenerationResponseWire>(body))

/** Decodes from the HTTP stream so routed base64 is not copied into a raw body and JSON tree. */
@OptIn(ExperimentalSerializationApi::class)
internal fun parseImageGenerationResponseStream(stream: InputStream): ImageGenerationResponseParse =
    parseImageGenerationResponse(json.decodeFromStream<ImageGenerationResponseWire>(stream))

private fun parseImageGenerationResponse(wire: ImageGenerationResponseWire): ImageGenerationResponseParse {
    val items = mutableListOf<ParsedImageGenerationItem>()
    var seen = 0
    var blocked = 0
    wire.data.orEmpty().forEach { item ->
        seen++
        if (item.isModerated()) {
            blocked++
        } else {
            item.toParsed(wire.outputFormat)?.let(items::add)
        }
    }
    if (items.isEmpty() && wire.error != null) {
        throw formatProviderHttpError(
            400,
            json.encodeToString(JsonObject(mapOf("error" to wire.error))),
        )
    }
    val allBlocked = (seen > 0 && blocked == seen) || (items.isEmpty() && wire.isModerated())
    return ImageGenerationResponseParse(items, allBlocked)
}

fun moderationBlockedImageException(): HttpException = HttpException(
    message = "Failed to generate image: blocked by safety system",
    statusCode = 200,
    errorCode = "moderation_blocked",
    errorType = "image_generation_user_error",
)

private fun ImageGenerationItemWire.toParsed(defaultFormat: String?): ParsedImageGenerationItem? {
    if (!b64Json.isNullOrBlank()) {
        return ParsedImageGenerationItem.Base64Json(
            data = b64Json,
            mimeType = (outputFormat ?: defaultFormat ?: "png").toImageMimeType(),
        )
    }
    return url?.takeIf(String::isNotBlank)?.let(ParsedImageGenerationItem::RemoteUrl)
}

private fun ImageGenerationResponseWire.isModerated(): Boolean = isModerated(
    respectModeration,
    moderation,
    moderationBlocked,
    moderated,
)

private fun ImageGenerationItemWire.isModerated(): Boolean = isModerated(
    respectModeration,
    moderation,
    moderationBlocked,
    moderated,
)

private fun isModerated(
    respectModeration: JsonElement?,
    moderation: JsonElement?,
    moderationBlocked: JsonElement?,
    moderated: JsonElement?,
): Boolean {
    if (isFalsey(respectModeration)) return true
    val moderationValue = (moderation as? JsonPrimitive)?.contentOrNull?.lowercase()
    if (moderationValue in setOf("blocked", "filtered", "rejected", "failed")) return true
    return isTruthy(moderationBlocked) || isTruthy(moderated)
}

private fun isFalsey(element: JsonElement?): Boolean {
    val primitive = element as? JsonPrimitive ?: return false
    return primitive.booleanOrNull == false || primitive.content.equals("false", ignoreCase = true)
}

private fun isTruthy(element: JsonElement?): Boolean {
    val primitive = element as? JsonPrimitive ?: return false
    return primitive.booleanOrNull == true || primitive.content.equals("true", ignoreCase = true)
}

internal fun String.toImageMimeType(): String = when (lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}
