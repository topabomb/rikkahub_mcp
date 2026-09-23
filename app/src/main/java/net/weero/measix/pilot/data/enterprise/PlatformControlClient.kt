package net.weero.measix.pilot.data.enterprise

import java.io.IOException
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import me.rerere.common.configuration.EnterpriseAuthority
import me.rerere.common.http.PrivateRequest
import me.rerere.common.http.readResponse
import me.rerere.common.http.withSingleAttemptBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Serializable
internal data class PlatformConnection(val origin: String, val discovery: PlatformDiscovery) {
    init {
        require(EnrollmentMaterialParser.normalizeOrigin(origin) == origin) { "noncanonical_platform_origin" }
        require(4L in discovery.supportedSnapshotSchemaVersions) { "unsupported_platform_snapshot" }
        requirePlatformPath(discovery.clientApiBase)
        requirePlatformPath(discovery.runtimeApiBase)
    }

    val authority: EnterpriseAuthority get() = EnterpriseAuthority(discovery.deploymentId)

    fun control(path: String): String {
        requirePlatformPath(path)
        return origin + discovery.clientApiBase.trimEnd('/') + path
    }

    fun runtime(resourceId: String, path: String): String {
        require(resourceId.matches(Regex("[a-z]+_[A-Za-z0-9-]+"))) { "invalid_platform_resource_id" }
        requirePlatformPath(path)
        return origin + discovery.runtimeApiBase.trimEnd('/') + "/resources/" + resourceId + path
    }
}

internal fun requirePlatformPath(value: String) {
    val uri = URI(value)
    require(value.startsWith('/') && !value.startsWith("//") && uri.rawAuthority == null &&
        uri.scheme == null && uri.rawQuery == null && uri.rawFragment == null &&
        '\\' !in value && '\\' !in uri.path &&
        uri.path.split('/').none { it == "." || it == ".." } &&
        !value.contains(Regex("%2f|%5c", RegexOption.IGNORE_CASE))) { "invalid_platform_path" }
}

internal class PlatformHttpException(val status: Int, val problem: PlatformProblem?, detail: String) :
    IOException("HTTP $status: ${problem?.code ?: "platform_http_error"}: $detail")

internal sealed interface PlatformSnapshotResponse {
    data class Downloaded(val snapshot: PlatformManagedSnapshot) : PlatformSnapshotResponse
    data object NotModified : PlatformSnapshotResponse
}

/** Transport only. Session, refresh serialization, and publication remain with the enterprise owner. */
internal class PlatformControlClient(client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    suspend fun discover(origin: String): PlatformConnection {
        val normalized = EnrollmentMaterialParser.normalizeOrigin(origin)
        val discovery = request<PlatformDiscovery>(builder(normalized + "/.well-known/measix").get().build(), 200)
        return PlatformConnection(normalized, discovery)
    }

    suspend fun enroll(connection: PlatformConnection, material: PlatformEnrollmentExchangeRequest): PlatformEnrollmentExchangeResponse =
        request(post(connection.origin + "/api/client/v1/enrollments/exchange", material), 201)

    suspend fun refresh(connection: PlatformConnection, refreshToken: String, idempotencyKey: String): PlatformRefreshResponse {
        require(idempotencyKey.matches(Regex("idem_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) { "invalid_refresh_idempotency_key" }
        return request(post(connection.control("/sessions/refresh"), PlatformRefreshRequest(refreshToken))
            .newBuilder().header("Idempotency-Key", idempotencyKey).build(), 200, callTimeoutSeconds = 15)
    }

    suspend fun bootstrap(connection: PlatformConnection, accessToken: String): PlatformBootstrap =
        request(builder(connection.control("/bootstrap"), accessToken).get().build(), 200, callTimeoutSeconds = 15)

    suspend fun state(connection: PlatformConnection, accessToken: String, appliedGeneration: Long?): PlatformManagedState =
        request(builder(connection.control("/managed/state"), accessToken).apply {
            appliedGeneration?.let {
                require(it > 0) { "invalid_applied_generation" }
                header("X-Measix-Applied-Managed-Generation", it.toString())
            }
        }.get().build(), 200)

    suspend fun snapshot(connection: PlatformConnection, accessToken: String, generation: Long, cachedHash: String?): PlatformSnapshotResponse {
        require(generation > 0) { "invalid_snapshot_generation" }
        cachedHash?.let { require(it.matches(Regex("sha256:[0-9a-f]{64}"))) { "invalid_snapshot_hash" } }
        val request = builder(connection.control("/managed/snapshots/$generation"), accessToken)
            .apply { cachedHash?.let { header("If-None-Match", "\"$it\"") } }.get().build()
        return client.newCall(request).readResponse { response ->
            if (response.code == 304) {
                require(cachedHash != null) { "snapshot_304_without_cache" }
                PlatformSnapshotResponse.NotModified
            } else {
                requireStatus(response, 200)
                val snapshot = PlatformWireCodec.decode<PlatformManagedSnapshot>(body(response))
                require(snapshot.deploymentId == connection.discovery.deploymentId && snapshot.managedGeneration == generation) {
                    "platform_snapshot_identity_mismatch"
                }
                require(response.header("ETag") == "\"${snapshot.snapshotHash}\"") { "platform_snapshot_etag_mismatch" }
                PlatformSnapshotResponse.Downloaded(snapshot)
            }
        }
    }

    suspend fun reportApplied(connection: PlatformConnection, accessToken: String, report: PlatformManagedAppliedReport) {
        val body = PlatformWireCodec.json.encodeToString(report).toRequestBody("application/json".toMediaType())
        client.newCall(builder(connection.control("/managed/applied"), accessToken).put(body).build().withSingleAttemptBody())
            .readResponse { requireStatus(it, 204) }
    }

    suspend fun createPortalGrant(connection: PlatformConnection, accessToken: String): PlatformPortalGrant {
        val httpRequest = builder(connection.control("/portal/grants"), accessToken)
            .post(ByteArray(0).toRequestBody(null)).build().withSingleAttemptBody()
        return request<PlatformPortalGrant>(httpRequest, 201, callTimeoutSeconds = 15).also { grant ->
            if (grant.exchangeUrl != connection.origin + "/portal/session/exchange") {
                throw EnterpriseConfigurationException("platform_portal_exchange_url_mismatch")
            }
            if (grant.ticket.isBlank() || grant.ticket.length > 128) {
                throw EnterpriseConfigurationException("invalid_platform_portal_grant_ticket")
            }
        }
    }

    suspend fun recentUpdates(connection: PlatformConnection, accessToken: String): PlatformEnterpriseUpdateFeed =
        request(builder(connection.control("/enterprise/updates") + "?limit=5", accessToken).get().build(),
            200, callTimeoutSeconds = 15)

    suspend fun budgets(connection: PlatformConnection, accessToken: String): PlatformUserBudgetView =
        request(builder(connection.control("/budgets"), accessToken).get().build(), 200, callTimeoutSeconds = 15)

    suspend fun logout(connection: PlatformConnection, refreshToken: String) {
        client.newCall(post(connection.control("/sessions/logout"), PlatformRefreshRequest(refreshToken))).also {
            it.timeout().timeout(15, java.util.concurrent.TimeUnit.SECONDS)
        }.readResponse { requireStatus(it, 204) }
    }

    private fun builder(url: String, token: String? = null) = Request.Builder().url(url)
        .tag(PrivateRequest::class.java, PrivateRequest).header("Accept", "application/json")
        .apply { token?.let { require(it.isNotBlank()); header("Authorization", "Bearer $it") } }

    private inline fun <reified T> post(url: String, value: T): Request = builder(url)
        .post(PlatformWireCodec.json.encodeToString(value).toRequestBody("application/json".toMediaType())).build()
        .withSingleAttemptBody()

    private suspend inline fun <reified T> request(request: Request, status: Int, callTimeoutSeconds: Long? = null): T =
        client.newCall(request).also { call ->
            callTimeoutSeconds?.let { call.timeout().timeout(it, java.util.concurrent.TimeUnit.SECONDS) }
        }.readResponse { response ->
            requireStatus(response, status)
            PlatformWireCodec.decode<T>(body(response))
        }

    private fun body(response: Response): String {
        val source = response.body.source()
        if (source.request(EnterpriseConfigurationCodec.MAX_BYTES.toLong() + 1)) throw IOException("platform_response_too_large")
        return source.readUtf8()
    }

    private fun requireStatus(response: Response, expected: Int) {
        if (response.code == expected) return
        val raw = body(response)
        val problem = try { PlatformWireCodec.decode<PlatformProblem>(raw) }
            catch (_: IllegalArgumentException) { null }
            catch (_: IllegalStateException) { null }
        throw PlatformHttpException(response.code, problem,
            problem?.detail ?: problem?.title ?: raw.ifBlank { response.message })
    }
}
