package net.weero.measix.pilot.data.enterprise

import java.io.IOException
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.common.http.PrivateRequest
import me.rerere.common.http.readResponse
import me.rerere.common.http.withSingleAttemptBody
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import net.weero.measix.pilot.utils.StrictJsonValue

internal data class WorkspaceContentMetadata(val etag: String?, val length: Long, val contentType: String?)
internal class WorkspaceLocalIOException(cause: IOException) : IOException(cause.message, cause)
internal class WorkspaceProtocolUnavailableException(reason: String) : IOException(reason)

internal inline fun <T> workspaceLocalIO(action: () -> T): T = try { action() }
catch (error: IOException) { throw WorkspaceLocalIOException(error) }

/** Streaming transport only; it neither chooses a Session nor retries a write. */
internal class PlatformWorkspaceClient(client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .callTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).cache(null).build()

    suspend fun state(connection: PlatformConnection, token: String): PlatformWorkspaceProjection =
        client.newCall(builder(connection.control("/workspace"), token).get().build()).readResponse { response ->
            val raw = body(response)
            // Pre-workspace Core uses chi's unmodified Go NotFound response. A domain Problem or
            // a proxy's HTML error is still a real failure; never hide every 404 as an old server.
            if (response.code == 404 && response.header("Content-Type")?.substringBefore(';')?.trim() == "text/plain" &&
                raw == "404 page not found\n") {
                throw WorkspaceProtocolUnavailableException("workspace_endpoint_not_supported")
            }
            requireStatus(response, raw)
            val value = StrictJsonValue.parse(raw, EnterpriseConfigurationCodec.MAX_BYTES)
            if (value is JsonObject && "serviceState" !in value) {
                requireLegacyProjection(value)
                // Core d7f310a has this exact projection but ignores the expected agentSpaceId on
                // file requests. Recognize it without inventing enable intent or enabling unsafe IO.
                throw WorkspaceProtocolUnavailableException("workspace_requires_fixed_space_protocol")
            }
            PlatformWireCodec.decode<PlatformWorkspaceProjection>(raw)
        }.also {
            require(!it.filesAvailable || (it.serviceState == PlatformWorkspaceProjectionServiceState.ENABLED &&
                it.state == PlatformWorkspaceProjectionState.CONNECTED && it.agentSpaceId != null)) {
                "workspace_projection_identity_conflict"
            }
        }

    /** Compatibility recognition only; legacy facts never become a current projection or file capability. */
    private fun requireLegacyProjection(value: JsonObject) {
        PlatformWireCodec.requireTypes(value, PlatformWorkspaceProjection.serializer().descriptor)
        val required = setOf("schemaVersion", "state", "bindingRevision", "mcpAvailable", "filesAvailable", "mcpReason", "filesReason")
        require(value.keys.containsAll(required)) { "invalid_workspace_legacy_projection" }
        require(value.getValue("schemaVersion").jsonPrimitive.long == 1L &&
            value.getValue("bindingRevision").jsonPrimitive.long >= 0 &&
            value.getValue("state").jsonPrimitive.content in PlatformWorkspaceProjectionState.entries.map { it.name }) {
            "invalid_workspace_legacy_projection"
        }
        for ((field, prefix) in listOf("agentSpaceId" to "spc", "mcpServerId" to "mcp", "operationId" to "wop")) {
            value[field]?.jsonPrimitive?.content?.let {
                require(it.matches(Regex("${prefix}_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) {
                    "invalid_workspace_legacy_$field"
                }
            }
        }
        value["observedAt"]?.jsonPrimitive?.content?.let { require(platformWireTimestamp(it)) { "invalid_workspace_legacy_observedAt" } }
        require(!value.getValue("filesAvailable").jsonPrimitive.boolean ||
            (value.getValue("state").jsonPrimitive.content == "CONNECTED" && value["agentSpaceId"] != null)) {
            "workspace_projection_identity_conflict"
        }
    }

    suspend fun list(connection: PlatformConnection, token: String, space: String, path: String): PlatformWorkspaceFileList {
        WorkspaceFileRules.path(path, rootAllowed = true)
        return json<PlatformWorkspaceFileList>(builder(url(connection, "files", space, path), token).get().build()).also {
            require(it.entries.map { entry -> entry.path }.distinct().size == it.entries.size) { "workspace_duplicate_entries" }
            it.entries.forEach { entry ->
                WorkspaceFileRules.path(entry.path)
                require(entry.path.substringBeforeLast('/', "") == path) { "workspace_entry_outside_directory" }
            }
        }
    }

    suspend fun download(connection: PlatformConnection, token: String, space: String, path: String,
        output: OutputStream, maxBytes: Long = Long.MAX_VALUE, progress: (Long, Long?) -> Unit = { _, _ -> },
    ): WorkspaceContentMetadata {
        WorkspaceFileRules.path(path)
        return withStreamCancellation { streams ->
        streams.register(output)
        client.newCall(builder(url(connection, "content", space, path), token).get().build()).readResponse { response ->
            requireStatus(response)
            val expected = response.body.contentLength().takeIf { it >= 0 }
            require(expected == null || expected <= maxBytes) { "workspace_preview_limit" }
            var total = 0L
            val input = response.body.byteStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count.toLong() <= maxBytes - total) { "workspace_preview_limit" }
                workspaceLocalIO { output.write(buffer, 0, count) }
                total += count
                progress(total, expected)
            }
            if (expected != null && total != expected) throw IOException("workspace_incomplete_content: $total/$expected")
            WorkspaceContentMetadata(response.header("ETag"), total, response.header("Content-Type"))
        }
        }
    }

    suspend fun upload(connection: PlatformConnection, token: String, space: String, path: String,
        etag: String?, length: Long?, input: () -> InputStream, progress: (Long, Long?) -> Unit = { _, _ -> },
    ): PlatformWorkspaceFileResult {
        WorkspaceFileRules.path(path)
        etag?.let(WorkspaceFileRules::requireEtag)
        require(length == null || length >= 0)
        return withStreamCancellation { streams ->
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length ?: -1
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                workspaceLocalIO { input() }.also(streams::register).use { source ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = workspaceLocalIO { source.read(buffer) }
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        total += count
                        progress(total, length)
                    }
                    if (length != null && total != length) throw IOException("workspace_upload_length_changed")
                }
            }
        }
        val request = builder(url(connection, "content", space, path), token)
            .apply { if (etag == null) header("If-None-Match", "*") else header("If-Match", etag) }
            .put(body).build().withSingleAttemptBody()
        result(request)
        }
    }

    suspend fun mutate(connection: PlatformConnection, token: String, space: String,
        mutation: PlatformWorkspaceFileMutation,
    ): PlatformWorkspaceFileResult {
        WorkspaceFileRules.path(mutation.path)
        mutation.destination?.let { WorkspaceFileRules.path(it) }
        mutation.sourceEtag?.let(WorkspaceFileRules::requireEtag)
        mutation.targetEtag?.let(WorkspaceFileRules::requireEtag)
        val body = PlatformWireCodec.json.encodeToString(mutation).toRequestBody("application/json".toMediaType())
        return result(builder(url(connection, "files", space), token).post(body).build().withSingleAttemptBody())
    }

    private suspend fun result(request: Request): PlatformWorkspaceFileResult = json<PlatformWorkspaceFileResult>(request).also {
        require(it.outcome != PlatformWorkspaceFileResultOutcome.SUCCEEDED || (it.failures.isEmpty() && !it.truncated)) {
            "workspace_inconsistent_write_result"
        }
        require(it.outcome != PlatformWorkspaceFileResultOutcome.PARTIAL || it.failures.isNotEmpty() || it.truncated) {
            "workspace_inconsistent_partial_result"
        }
    }

    private fun url(connection: PlatformConnection, endpoint: String, space: String, path: String? = null): String {
        require(space.matches(Regex("spc_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) {
            "invalid_workspace_space_id"
        }
        return connection.control("/workspace/$endpoint").toHttpUrl().newBuilder()
            .addQueryParameter("agentSpaceId", space).apply { path?.let { addQueryParameter("path", it) } }.build().toString()
    }

    private fun builder(url: String, token: String) = Request.Builder().url(url)
        .tag(PrivateRequest::class.java, PrivateRequest).header("Accept", "application/json")
        .header("Authorization", "Bearer $token")

    private suspend inline fun <reified T> json(request: Request): T =
        client.newCall(request).readResponse { response -> requireStatus(response); PlatformWireCodec.decode<T>(body(response)) }

    private fun body(response: Response): String {
        val source = response.body.source()
        if (source.request(EnterpriseConfigurationCodec.MAX_BYTES.toLong() + 1)) throw IOException("workspace_response_too_large")
        return source.readUtf8()
    }

    private fun requireStatus(response: Response, providedRaw: String? = null) {
        if (response.code == 200) return
        val raw = providedRaw ?: body(response)
        val problem = try { PlatformWireCodec.decode<PlatformProblem>(raw) }
        catch (_: IllegalArgumentException) { null }
        catch (_: IllegalStateException) { null }
        throw PlatformHttpException(response.code, problem, problem?.detail ?: problem?.title ?: raw.ifBlank { response.message })
    }

    /** Socket cancellation alone cannot unblock a cloud DocumentsProvider read/write. */
    private suspend fun <T> withStreamCancellation(block: suspend (TransferStreams) -> T): T = coroutineScope {
        val streams = TransferStreams()
        val finished = AtomicBoolean(false)
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { if (!finished.get()) withContext(NonCancellable) { streams.cancel() } }
        }
        try { block(streams) }
        finally {
            if (currentCoroutineContext().isActive) finished.set(true)
            withContext(NonCancellable) { closer.cancelAndJoin() }
        }
    }

    private class TransferStreams {
        private val lock = Any()
        private var cancelled = false
        private val streams = mutableListOf<Closeable>()
        fun register(stream: Closeable) {
            val closed = synchronized(lock) { if (cancelled) true else { streams += stream; false } }
            if (closed) { stream.close(); throw IOException("workspace_transfer_cancelled") }
        }
        fun cancel() {
            val owned = synchronized(lock) { cancelled = true; streams.toList().also { streams.clear() } }
            owned.forEach { stream ->
                try { stream.close() }
                catch (error: Exception) { android.util.Log.e("RemoteWorkspace", "Cancelled stream close failed", error) }
            }
        }
    }
}
