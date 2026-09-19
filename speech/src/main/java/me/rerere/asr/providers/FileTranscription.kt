package me.rerere.asr.providers

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

enum class FileTranscriptionProtocol { OPENAI, DASHSCOPE }

private fun openAiBody(model: String, language: String?, audio: RequestBody) = MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("model", model)
    .addFormDataPart("file", "recording.wav", audio)
    .apply { language?.let { addFormDataPart("language", it) } }.build()

private fun dashscopeContent(model: String, language: String?, encoded: String) = buildJsonObject {
    put("model", model)
    putJsonObject("input") { putJsonArray("messages") { addJsonObject {
        put("role", "user")
        putJsonArray("content") { addJsonObject {
            put("type", "input_audio")
            putJsonObject("input_audio") { put("data", "data:audio/wav;base64,$encoded") }
        } }
    } } }
    putJsonObject("parameters") {
        put("format", "wav")
        language?.let { putJsonArray("language_hints") { add(it) } }
    }
}.toString()

/** Recording limits include the WAV header and the actual protocol's UTF-8/base64/multipart overhead. */
fun maxFileTranscriptionAudioBytes(protocol: FileTranscriptionProtocol, model: String, language: String?, maxBodyBytes: Long): Long {
    val limit = when (protocol) {
        FileTranscriptionProtocol.OPENAI -> maxBodyBytes - openAiBody(model, language,
            byteArrayOf().toRequestBody("audio/wav".toMediaType())).contentLength()
        FileTranscriptionProtocol.DASHSCOPE -> (maxBodyBytes - dashscopeContent(model, language, "").toByteArray(Charsets.UTF_8).size) / 4 * 3
    }
    require(limit >= 44) { "asr_request_metadata_exceeds_body_limit" }
    return limit
}

fun fileTranscriptionBody(protocol: FileTranscriptionProtocol, model: String, language: String?, audio: File,
    maxBodyBytes: Long): RequestBody {
    require(audio.length() <= maxFileTranscriptionAudioBytes(protocol, model, language, maxBodyBytes)) {
        "platform_request_body_limit_exceeded"
    }
    return when (protocol) {
        FileTranscriptionProtocol.OPENAI -> openAiBody(model, language, audio.asRequestBody("audio/wav".toMediaType()))
        FileTranscriptionProtocol.DASHSCOPE -> dashscopeContent(model, language, Base64.getEncoder().encodeToString(audio.readBytes()))
            .toRequestBody("application/json".toMediaType())
    }.also { require(it.contentLength() <= maxBodyBytes) { "platform_request_body_limit_exceeded" } }
}

fun decodeFileTranscription(protocol: FileTranscriptionProtocol, body: String): String {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("asr_invalid_response")
    val text = when (protocol) {
        FileTranscriptionProtocol.OPENAI -> root["text"]
        FileTranscriptionProtocol.DASHSCOPE -> (root["output"] as? JsonObject)?.get("text")
    }
    return (text as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("asr_invalid_response: $body")
}
