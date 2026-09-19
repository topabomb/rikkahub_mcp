package net.weero.measix.pilot.data.enterprise

import java.io.File
import java.util.concurrent.TimeUnit
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.http.withExplicitRoute
import me.rerere.common.http.readResponse
import me.rerere.asr.providers.FileTranscriptionProtocol
import me.rerere.asr.providers.fileTranscriptionBody
import me.rerere.asr.providers.decodeFileTranscription
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Builds platform-routed speech transports while the application operation retains its execution lease. */
internal class EnterpriseSpeechTransport {
    private val platformClient = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()

    fun platformRealtimeTarget(execution: EnterpriseExecution.Platform, reference: ConfigurationReference.Enterprise,
        definition: EnterpriseAsrResource, version: EnterpriseAppliedVersion, interactionId: String,
        token: String): me.rerere.asr.providers.RealtimeAsrTransport {
        require(reference.authority == execution.connection.authority && reference.id == definition.id)
        val url = execution.connection.runtime(reference.id, execution.runtimePaths.getValue(reference.id)).toHttpUrl()
            .newBuilder().apply {
                when (definition.protocol) {
                    EnterpriseAsrProtocol.OPENAI_REALTIME -> addQueryParameter("intent", "transcription")
                    EnterpriseAsrProtocol.DASHSCOPE -> addQueryParameter("model", definition.modelId)
                    else -> error("realtime_asr_protocol_required")
                }
            }.build()
        // OkHttp represents ws/wss requests as http/https internally and owns the upgrade handshake.
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Measix-Managed-Generation", version.generation.toString())
            .header("X-Measix-Interaction-Id", interactionId)
            .tag(me.rerere.common.http.PrivateRequest::class.java, me.rerere.common.http.PrivateRequest)
            .build()
        val sockets = me.rerere.common.http.SingleAttemptWebSocketFactory(platformClient, token,
            me.rerere.common.http.MAX_ROUTED_REQUEST_BYTES)
        return me.rerere.asr.providers.RealtimeAsrTransport(sockets, request)
    }

    fun platformTarget(execution: EnterpriseExecution.Platform, reference: ConfigurationReference.Enterprise,
        version: EnterpriseAppliedVersion, interactionId: String, token: String): me.rerere.speech.SpeechHttpTransport {
        require(reference.authority == execution.connection.authority)
        val endpoint = execution.connection.runtime(reference.id, execution.runtimePaths.getValue(reference.id))
        return me.rerere.speech.SpeechHttpTransport(platformClient.withExplicitRoute(endpoint, token), endpoint, mapOf(
            "Authorization" to "Bearer $token",
            "X-Measix-Managed-Generation" to version.generation.toString(),
            "X-Measix-Interaction-Id" to interactionId,
        ))
    }

    suspend fun transcribe(target: me.rerere.speech.SpeechHttpTransport, definition: EnterpriseAsrResource, audio: File): String {
        val protocol = definition.fileProtocol()
        val body = fileTranscriptionBody(protocol, definition.modelId, definition.language, audio, me.rerere.common.http.MAX_ROUTED_REQUEST_BYTES)
        return target.client.newCall(target.request(body)).readResponse { response ->
            requireSuccess(response)
            decodeFileTranscription(protocol, readJsonBody(response))
        }
    }

    private fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        val detail = readJsonBody(response)
        val error = me.rerere.common.http.RoutedHttpException(response.code, detail.ifBlank { response.message })
        throw ManagedSnapshotRequired.find(error) ?: error
    }

    private fun readJsonBody(response: Response): String {
        val body = response.body.source()
        check(!body.request(MAX_JSON_BYTES.toLong() + 1)) { "enterprise_speech_response_too_large" }
        return body.readByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private companion object { const val MAX_JSON_BYTES = 16 * 1024 * 1024 }
}
