package net.weero.measix.pilot.data.enterprise

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.http.readResponse
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.providers.buildOpenAiSpeechRequest
import net.weero.measix.pilot.utils.StrictJsonValue
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Frozen private transport input; the application operation retains its binding lease and admission. */
internal class EnterpriseSpeechTarget(
    val access: RealmAccess.Enterprise,
    val id: ConfigurationReference.Enterprise,
    val binding: EnterpriseRuntimeBinding,
    val version: EnterpriseAppliedVersion,
    val interactionId: String,
) {
    init {
        require(id.authority == access.scope.authority && id.id == binding.resourceId)
        require(binding.protocol in setOf(EnterpriseRuntimeProtocol.EXAMPLE,
            EnterpriseRuntimeProtocol.OPENAI_TTS, EnterpriseRuntimeProtocol.OPENAI_HTTP_ASR))
        require(interactionId.matches(Regex("int_[0-9a-f-]{36}")))
    }

    fun request(path: String, body: RequestBody): Request = Request.Builder()
        .url(binding.endpoint ?: "https://local-enterprise.invalid/runtime/v1/resources/${id.id}$path")
        .apply {
            binding.headers.forEach { (name, value) -> header(name, value) }
            binding.credential?.let { header("Authorization", "Bearer $it") }
            header("X-Measix-Managed-Generation", version.generation.toString())
            header("X-Measix-Interaction-Id", interactionId)
        }
        .post(body)
        .build()
}

/** Same encoded requests and response decoders for installed example I/O and private HTTP bindings. */
internal class EnterpriseSpeechTransport(
    private val local: suspend (EnterpriseSpeechTarget, Request) -> Response,
    private val calls: Call.Factory = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .readTimeout(120, TimeUnit.SECONDS).build(),
) {
    suspend fun synthesize(target: EnterpriseSpeechTarget, definition: EnterpriseTtsResource, text: String): TTSResponse {
        require(definition.id == target.id.id && definition.voice.isNotBlank())
        require(target.binding.protocol in setOf(EnterpriseRuntimeProtocol.EXAMPLE, EnterpriseRuntimeProtocol.OPENAI_TTS))
        val body = buildOpenAiSpeechRequest(definition.modelId, definition.voice, text)
            .toString().toRequestBody("application/json".toMediaType())
        return execute(target, target.request("/audio/speech", body)) { response ->
            requireSuccess(response)
            val audio = response.body.bytes()
            check(audio.isNotEmpty()) { "enterprise_tts_empty_response" }
            TTSResponse(audioData = audio, format = AudioFormat.MP3)
        }
    }

    suspend fun transcribe(target: EnterpriseSpeechTarget, definition: EnterpriseAsrResource, audio: File): String {
        require(definition.id == target.id.id)
        require(target.binding.protocol in setOf(EnterpriseRuntimeProtocol.EXAMPLE, EnterpriseRuntimeProtocol.OPENAI_HTTP_ASR))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", definition.modelId)
            .addFormDataPart("file", "recording.wav", audio.asRequestBody("audio/wav".toMediaType()))
            .apply { definition.language?.let { addFormDataPart("language", it) } }
            .build()
        return execute(target, target.request("/audio/transcriptions", body)) { response ->
            requireSuccess(response)
            val json = StrictJsonValue.parse(readJsonBody(response), MAX_JSON_BYTES) as? JsonObject
                ?: error("enterprise_asr_invalid_response")
            (json["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("enterprise_asr_invalid_response")
        }
    }

    private suspend fun <T> execute(target: EnterpriseSpeechTarget, request: Request, decode: (Response) -> T): T {
        currentCoroutineContext().ensureActive()
        return if (target.binding.protocol == EnterpriseRuntimeProtocol.EXAMPLE) local(target, request).use(decode)
        else calls.newCall(request).readResponse(decode)
    }

    private fun requireSuccess(response: Response) {
        if (response.code == 428) throw ManagedSnapshotRequired.parse(readJsonBody(response))
        check(response.isSuccessful) { "enterprise_speech_http_${response.code}" }
    }

    private fun readJsonBody(response: Response): String {
        val body = response.body.source()
        check(!body.request(MAX_JSON_BYTES.toLong() + 1)) { "enterprise_speech_response_too_large" }
        return body.readByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private companion object { const val MAX_JSON_BYTES = 16 * 1024 * 1024 }
}
