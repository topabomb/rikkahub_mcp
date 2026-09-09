package net.weero.measix.pilot.data.enterprise

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.provider.providers.buildOpenAiSpeechRequest
import net.weero.measix.pilot.utils.StrictJsonValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.uuid.Uuid

/** Installed example service consumes actual request bytes; only external speech I/O is simulated. */
internal class LocalEnterpriseSpeechService(
    private val sources: LocalEnterpriseSource,
    private val sessions: EnterpriseSessionController,
    private val readAudio: () -> ByteArray,
) {
    suspend fun execute(target: EnterpriseSpeechTarget, request: Request): Response = withContext(Dispatchers.IO) {
        fun response(code: Int, body: ByteArray = ByteArray(0), type: String = "application/json") = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Local enterprise speech")
            .body(body.toResponseBody(type.toMediaType())).build()
        fun json(code: Int, body: JsonObject) = response(code, body.toString().toByteArray(),
            if (code == 428) "application/problem+json" else "application/json")
        currentCoroutineContext().ensureActive()
        if (!target.access.scope.authority.isLocal || target.binding.protocol != EnterpriseRuntimeProtocol.EXAMPLE ||
            request.header("X-Measix-Managed-Generation") != target.version.generation.toString() ||
            request.header("X-Measix-Interaction-Id") != target.interactionId ||
            request.header("X-Measix-Resource-Id") != null) return@withContext response(401)
        val candidate = sources.candidate(target.access.scope)?.packet ?: return@withContext response(503)
        val authorized = try {
            sessions.withAppliedConfiguration(target.access) { it.manifest.phase == EnterpriseSessionPhase.READY }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: EnterpriseConfigurationException) { false }
        if (!authorized) return@withContext response(401)
        if (candidate.configuration.generation != target.version.generation) return@withContext json(428, buildJsonObject {
            put("type", "about:blank")
            put("title", "Managed snapshot required")
            put("status", 428)
            put("code", "managed_snapshot_required")
            put("targetManagedGeneration", candidate.configuration.generation)
            put("requestId", "req_${Uuid.random()}")
            put("forwarded", false)
        })
        if (candidate.runtimeBindings.none { it.resourceId == target.id.id && it.protocol == EnterpriseRuntimeProtocol.EXAMPLE }) {
            return@withContext response(404)
        }
        if (request.method != "POST" || request.url.host != "local-enterprise.invalid") return@withContext response(400)
        val definition = candidate.configuration
        val tts = definition.tts.singleOrNull { it.id == target.id.id && it.enabled }
        val asr = definition.asr.singleOrNull { it.id == target.id.id && it.enabled }
        val body = request.body ?: return@withContext response(400)
        val encoded = Buffer().also { body.writeTo(it) }
        try {
            when {
                tts != null && request.url.encodedPath == "/runtime/v1/resources/${tts.id}/audio/speech" -> {
                    val value = StrictJsonValue.parse(encoded.readUtf8(), 16 * 1024 * 1024) as? JsonObject
                        ?: return@withContext response(400)
                    val text = (value["input"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.takeIf { it.isNotBlank() } ?: return@withContext response(400)
                    if (value != buildOpenAiSpeechRequest(tts.modelId, tts.voice, text)) return@withContext response(400)
                    response(200, readAudio(), "audio/mpeg")
                }
                asr != null && request.url.encodedPath == "/runtime/v1/resources/${asr.id}/audio/transcriptions" -> {
                    val boundary = body.contentType()?.parameter("boundary") ?: return@withContext response(400)
                    val fields = linkedMapOf<String, ByteArray>()
                    MultipartReader(encoded, boundary).use { reader ->
                        while (true) {
                            val part = reader.nextPart() ?: break
                            val disposition = part.headers["Content-Disposition"]
                            val name = when (disposition) {
                                "form-data; name=\"model\"" -> "model"
                                "form-data; name=\"language\"" -> "language"
                                "form-data; name=\"file\"; filename=\"recording.wav\"" -> "file"
                                else -> return@withContext response(400)
                            }
                            if (name in fields || name == "file" && part.headers["Content-Type"] != "audio/wav") {
                                return@withContext response(400)
                            }
                            fields[name] = part.body.readByteArray()
                        }
                    }
                    val expected = if (asr.language == null) setOf("model", "file") else setOf("model", "file", "language")
                    if (fields.keys != expected || fields["model"]?.decodeToString() != asr.modelId ||
                        fields["language"]?.decodeToString() != asr.language) return@withContext response(400)
                    val audio = requireNotNull(fields["file"])
                    if (!isRecordedWave(audio)) return@withContext response(400)
                    json(200, buildJsonObject { put("text", "这是本地模拟企业语音转写，已收到 ${audio.size - 44} 字节录音。") })
                }
                else -> response(404)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { response(400) }
        catch (_: IllegalStateException) { response(400) }
        catch (_: java.io.IOException) { response(400) }
    }

    private fun isRecordedWave(bytes: ByteArray): Boolean {
        if (bytes.size <= 44 || bytes.size % 2 != 0) return false
        val value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun marker(offset: Int, text: String) = bytes.copyOfRange(offset, offset + text.length).decodeToString() == text
        return marker(0, "RIFF") && marker(8, "WAVE") && marker(12, "fmt ") && marker(36, "data") &&
            value.getInt(4) == bytes.size - 8 && value.getInt(16) == 16 && value.getShort(20).toInt() == 1 &&
            value.getShort(22).toInt() == 1 && value.getShort(34).toInt() == 16 &&
            value.getInt(24) > 0 && value.getInt(28) == value.getInt(24) * 2 &&
            value.getShort(32).toInt() == 2 && value.getInt(40) == bytes.size - 44
    }
}
