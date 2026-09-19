package net.weero.measix.pilot.data.enterprise

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.weero.measix.pilot.service.PlatformEnterpriseService
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformSessionNetworkTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cipher = EnterpriseCredentialCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    private fun owner(root: File) = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root, credentialCipher = cipher)) { 1000L }
    private fun service(owner: EnterpriseSessionController) = PlatformEnterpriseService(owner, PlatformControlClient(OkHttpClient())) { Instant.ofEpochMilli(1000) }
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/cases.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        .first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject.getValue("value").toString()
    private fun server(handler: HttpExchange.() -> Unit) = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> exchange.use { it.handler() } }
        start()
    }
    private fun HttpExchange.reply(status: Int, value: String) {
        val bytes = value.toByteArray()
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    @Test fun `closing session replays pending refresh before server logout`() = runBlocking {
        val response = PlatformWireCodec.decode<PlatformRefreshResponse>(fixture("refresh-response"))
        val calls = mutableListOf<String>()
        lateinit var pending: PlatformRefreshAttempt
        val server = server {
            when (requestURI.path) {
                "/api/client/v1/sessions/refresh" -> {
                    calls += "refresh"
                    assertEquals(pending.credential.pendingIdempotencyKey, requestHeaders.getFirst("Idempotency-Key"))
                    val body = Json.parseToJsonElement(requestBody.bufferedReader().readText()).jsonObject
                    assertEquals(pending.credential.refreshToken, body.getValue("refreshToken").jsonPrimitive.content)
                    reply(200, fixture("refresh-response"))
                }
                "/api/client/v1/sessions/logout" -> {
                    calls += "logout"
                    val body = Json.parseToJsonElement(requestBody.bufferedReader().readText()).jsonObject
                    assertEquals(response.refreshToken, body.getValue("refreshToken").jsonPrimitive.content)
                    sendResponseHeaders(204, -1)
                }
                else -> reply(404, "unexpected route")
            }
        }
        try {
            val sessions = owner(temporary.newFolder())
            val connection = PlatformConnection("http://127.0.0.1:${server.address.port}", PlatformWireCodec.decode(fixture("discovery")))
            val id = sessions.acceptPlatformEnrollment(sessions.beginPlatformEnrollment(), connection,
                PlatformWireCodec.decode(fixture("enrollment-response")))
            sessions.completePlatformBootstrap(id, PlatformWireCodec.decode(fixture("bootstrap")))
            pending = sessions.beginPlatformRefresh(id)
            val token = sessions.beginExit(requireNotNull(sessions.captureExitRequest()))
            service(sessions).logout(token)
            assertEquals(listOf("refresh", "logout"), calls)
            assertEquals(EnterpriseSessionPhase.CLOSING, (sessions.state.value as EnterpriseState.Available).manifest.phase)
        } finally { server.stop(0) }
    }

    @Test fun `failed Bootstrap resumes saved exchange after process recreation without reusing enrollment code`() = runBlocking {
        val exchanges = AtomicInteger()
        var failBootstrap = true
        val server = server {
            when (requestURI.path) {
                "/.well-known/measix" -> reply(200, fixture("discovery"))
                "/api/client/v1/enrollments/exchange" -> { exchanges.incrementAndGet(); reply(201, fixture("enrollment-response")) }
                "/api/client/v1/sessions/refresh" -> reply(200, fixture("refresh-response"))
                "/api/client/v1/bootstrap" -> if (failBootstrap) reply(503, "bootstrap temporarily unavailable") else reply(200, fixture("bootstrap"))
                else -> reply(404, "unexpected route")
            }
        }
        try {
            val root = temporary.newFolder()
            val first = owner(root)
            val material = EnrollmentMaterial.Platform("http://127.0.0.1:${server.address.port}", "one-use-code", Instant.parse("2030-01-01T00:00:00Z"))
            try { service(first).enroll(material, "Android test", "test"); fail("Bootstrap failure hidden") }
            catch (error: PlatformHttpException) { assertEquals(503, error.status) }
            assertNotNull(first.pendingPlatformEnrollment())
            try { service(first).enroll(material.copy(code = "another-code"), "Android test", "test"); fail("Temporary failure hidden") }
            catch (error: PlatformHttpException) { assertEquals(503, error.status) }
            assertEquals(1, exchanges.get())
            failBootstrap = false
            val recovered = owner(root)
            val access = service(recovered).recoverPlatformAccess()
            assertNotNull(access)
            assertEquals(1, exchanges.get())
            assertNull(recovered.pendingPlatformEnrollment())
            assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, (recovered.state.value as EnterpriseState.Available).manifest.phase)
        } finally { server.stop(0) }
    }

    @Test fun `terminal pending Bootstrap permits confirmed new code after restart`() = runBlocking {
        val exchanges = AtomicInteger()
        val bootstraps = AtomicInteger()
        val replacementId = "ses_aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val originalId = "ses_550e8400-e29b-41d4-a716-446655440000"
        val server = server {
            when (requestURI.path) {
                "/.well-known/measix" -> reply(200, fixture("discovery"))
                "/api/client/v1/enrollments/exchange" -> {
                    val number = exchanges.incrementAndGet()
                    reply(201, if (number == 1) fixture("enrollment-response") else fixture("enrollment-response").replace(originalId, replacementId))
                }
                "/api/client/v1/sessions/refresh" -> reply(200, fixture("refresh-response"))
                "/api/client/v1/bootstrap" -> when (bootstraps.incrementAndGet()) {
                    1 -> reply(503, "temporarily unavailable")
                    2 -> reply(403, """{"type":"about:blank","title":"Forbidden","status":403,"code":"session_revoked"}""")
                    else -> reply(200, fixture("bootstrap").replace(originalId, replacementId))
                }
                else -> reply(404, "unexpected route")
            }
        }
        try {
            val root = temporary.newFolder()
            val material = EnrollmentMaterial.Platform("http://127.0.0.1:${server.address.port}", "first-code", Instant.parse("2030-01-01T00:00:00Z"))
            assertThrows(PlatformHttpException::class.java) { runBlocking { service(owner(root)).enroll(material, "Android", "test") } }
            val recovered = owner(root)
            val access = service(recovered).enroll(material.copy(code = "fresh-code"), "Android", "test")
            assertEquals(replacementId, access.sessionId)
            assertEquals(2, exchanges.get())
            assertEquals(3, bootstraps.get())
            assertNull(recovered.pendingPlatformEnrollment())
        } finally { server.stop(0) }
    }

    @Test fun `confirmed different origin replaces offline pending enrollment and reports uncertain logout`() = runBlocking {
        val old = server {
            when (requestURI.path) {
                "/.well-known/measix" -> reply(200, fixture("discovery"))
                "/api/client/v1/enrollments/exchange" -> reply(201, fixture("enrollment-response"))
                "/api/client/v1/bootstrap" -> reply(503, "temporarily unavailable")
                else -> reply(404, "unexpected route")
            }
        }
        val exchanges = AtomicInteger()
        val failNewExchange = AtomicBoolean(true)
        val next = server {
            when (requestURI.path) {
                "/.well-known/measix" -> reply(200, fixture("discovery"))
                "/api/client/v1/enrollments/exchange" -> {
                    exchanges.incrementAndGet()
                    if (failNewExchange.get()) reply(503, "temporarily unavailable") else reply(201, fixture("enrollment-response"))
                }
                "/api/client/v1/bootstrap" -> reply(200, fixture("bootstrap"))
                else -> reply(404, "unexpected route")
            }
        }
        try {
            val sessions = owner(temporary.newFolder())
            val oldMaterial = EnrollmentMaterial.Platform("http://127.0.0.1:${old.address.port}", "old-code", Instant.parse("2030-01-01T00:00:00Z"))
            try { service(sessions).enroll(oldMaterial, "Android", "test"); fail("Bootstrap failure hidden") }
            catch (error: PlatformHttpException) { assertEquals(503, error.status) }
            old.stop(0)
            val nextMaterial = oldMaterial.copy(platformOrigin = "http://127.0.0.1:${next.address.port}", code = "new-code")
            val platform = service(sessions)
            try { platform.enroll(nextMaterial, "Android", "test"); fail("New exchange failure hidden") }
            catch (error: PlatformHttpException) { assertEquals(503, error.status) }
            assertNotNull(platform.pendingLogoutFailure.value)
            assertNull(sessions.pendingPlatformEnrollment())
            failNewExchange.set(false)
            platform.enroll(nextMaterial, "Android", "test")
            assertEquals(2, exchanges.get())
            assertNull(platform.pendingLogoutFailure.value)
            assertEquals(nextMaterial.platformOrigin, (sessions.state.value as EnterpriseState.Available).manifest.session?.platform?.connection?.origin)
        } finally { old.stop(0); next.stop(0) }
    }

    @Test fun `snapshot commits before report and restart retries report using validated cache`() = runBlocking {
        val root = temporary.newFolder()
        val snapshot = PlatformWireCodec.decode<PlatformManagedSnapshot>(fixture("v4-full"))
        val store = net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root, credentialCipher = cipher)
        val reads = mutableListOf<String?>()
        val appliedHeaders = mutableListOf<String?>()
        var reportFails = true
        val server = server {
            when (requestURI.path) {
                "/api/client/v1/sessions/refresh" -> reply(200, fixture("refresh-response"))
                "/api/client/v1/managed/state" -> {
                    appliedHeaders += requestHeaders.getFirst("X-Measix-Applied-Managed-Generation")
                    reply(200, """{"runtimeStatus":"READY","activeManagedGeneration":${snapshot.managedGeneration},"managedStateRevision":1,"syncRequired":true,"targetManagedGeneration":${snapshot.managedGeneration},"runtimeBlocked":true}""")
                }
                "/api/client/v1/managed/snapshots/${snapshot.managedGeneration}" -> {
                    reads += requestHeaders.getFirst("If-None-Match")
                    if (reads.last() != null) sendResponseHeaders(304, -1)
                    else { responseHeaders.set("ETag", "\"${snapshot.snapshotHash}\""); reply(200, fixture("v4-full")) }
                }
                "/api/client/v1/managed/applied" -> {
                    assertEquals(snapshot.managedGeneration, store.load().configuration?.generation)
                    val report = PlatformWireCodec.decode<PlatformManagedAppliedReport>(requestBody.bufferedReader().readText())
                    assertEquals(snapshot.snapshotHash, report.snapshotHash)
                    if (reportFails) reply(503, "report unavailable") else sendResponseHeaders(204, -1)
                }
                else -> reply(404, "unexpected route")
            }
        }
        try {
            val first = owner(root)
            val connection = PlatformConnection("http://127.0.0.1:${server.address.port}", PlatformWireCodec.decode(fixture("discovery")))
            val id = first.acceptPlatformEnrollment(first.beginPlatformEnrollment(), connection, PlatformWireCodec.decode(fixture("enrollment-response")))
            val access = first.completePlatformBootstrap(id, PlatformWireCodec.decode(fixture("bootstrap")))
            try { service(first).synchronize(access); fail("report failure hidden") }
            catch (error: PlatformHttpException) { assertEquals(503, error.status) }
            assertEquals(snapshot.managedGeneration, store.load().configuration?.generation)
            reportFails = false
            val recoveredOwner = owner(root)
            assertEquals(access, service(recoveredOwner).recoverPlatformAccess())
            val restored = service(recoveredOwner).synchronize(access)
            assertEquals(snapshot.managedGeneration, restored.configuration?.generation)
            assertEquals(listOf(null, "\"${snapshot.snapshotHash}\""), reads)
            assertEquals(listOf(null, snapshot.managedGeneration.toString()), appliedHeaders)
        } finally { server.stop(0) }
    }

    @Test fun `generation zero stays pending and performs no snapshot request or report`() = runBlocking {
        val requests = AtomicInteger()
        val server = server {
            requests.incrementAndGet()
            assertEquals("/api/client/v1/managed/state", requestURI.path)
            reply(200, """{"runtimeStatus":"READY","activeManagedGeneration":0,"managedStateRevision":0,"syncRequired":true,"runtimeBlocked":true}""")
        }
        try {
            val first = owner(temporary.newFolder())
            val connection = PlatformConnection("http://127.0.0.1:${server.address.port}", PlatformWireCodec.decode(fixture("discovery")))
            val id = first.acceptPlatformEnrollment(first.beginPlatformEnrollment(), connection, PlatformWireCodec.decode(fixture("enrollment-response")))
            val access = first.completePlatformBootstrap(id, PlatformWireCodec.decode(fixture("bootstrap")))
            val result = service(first).synchronize(access)
            assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, result.manifest.phase)
            assertNull(result.configuration)
            assertEquals(1, requests.get())
        } finally { server.stop(0) }
    }

    @Test fun `revoked active session signals the application exit owner`() = runBlocking {
        val server = server {
            assertEquals("/api/client/v1/managed/state", requestURI.path)
            reply(403, """{"type":"about:blank","title":"Forbidden","status":403,"code":"session_revoked"}""")
        }
        try {
            val sessions = owner(temporary.newFolder())
            val connection = PlatformConnection("http://127.0.0.1:${server.address.port}", PlatformWireCodec.decode(fixture("discovery")))
            val id = sessions.acceptPlatformEnrollment(sessions.beginPlatformEnrollment(), connection,
                PlatformWireCodec.decode(fixture("enrollment-response")))
            val access = sessions.completePlatformBootstrap(id, PlatformWireCodec.decode(fixture("bootstrap")))
            val revoked = mutableListOf<RealmAccess.Enterprise>()
            val platform = PlatformEnterpriseService(sessions, PlatformControlClient(OkHttpClient()), { Instant.ofEpochMilli(1000) },
                onSessionRevoked = { revoked += it })
            try { platform.synchronize(access); fail("Revocation was ignored") }
            catch (error: PlatformHttpException) { assertEquals("session_revoked", error.problem?.code) }
            assertEquals(listOf(access), revoked)
        } finally { server.stop(0) }
    }

    @Test fun `every interaction preflight checks authority and degraded status rejects retained configuration`() = runBlocking {
        val snapshot = PlatformWireCodec.decode<PlatformManagedSnapshot>(fixture("v4-full"))
        val checks = AtomicInteger()
        val status = java.util.concurrent.atomic.AtomicReference("READY")
        val server = server {
            checks.incrementAndGet()
            assertEquals("/api/client/v1/managed/state", requestURI.path)
            assertEquals(snapshot.managedGeneration.toString(), requestHeaders.getFirst("X-Measix-Applied-Managed-Generation"))
            reply(200, """{"runtimeStatus":"${status.get()}","activeManagedGeneration":${snapshot.managedGeneration},"managedStateRevision":1,"syncRequired":false,"runtimeBlocked":${status.get() != "READY"}}""")
        }
        try {
            val first = owner(temporary.newFolder())
            val connection = PlatformConnection("http://127.0.0.1:${server.address.port}", PlatformWireCodec.decode(fixture("discovery")))
            val id = first.acceptPlatformEnrollment(first.beginPlatformEnrollment(), connection, PlatformWireCodec.decode(fixture("enrollment-response")))
            val access = first.completePlatformBootstrap(id, PlatformWireCodec.decode(fixture("bootstrap")))
            val input = first.platformConfiguration(access)
            val candidate = PlatformSnapshotMapper.map(connection, input.session.identity, snapshot)
            val applied = first.synchronize(access, candidate)
            val platform = service(first)
            repeat(2) { assertEquals(applied.manifest.applied, platform.prepareExecution(access) { fail("unexpected synchronization") }) }
            try { first.captureExecution(access); fail("platform lease issued without checked version") }
            catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_configuration_changed_during_preflight", error.reason) }
            val lease = first.captureExecution(access, applied.manifest.applied)
            assertEquals(candidate.execution, lease.execution)
            assertEquals(candidate.configuration, lease.configuration)
            assertTrue(lease.execution is EnterpriseExecution.Platform)
            status.set("DEGRADED")
            try { platform.prepareExecution(access) { fail("unexpected synchronization") }; fail("degraded runtime admitted") }
            catch (error: EnterpriseConfigurationException) { assertEquals("platform_runtime_degraded", error.reason) }
            assertEquals(3, checks.get())
            assertEquals(applied.configuration, (first.state.value as EnterpriseState.Available).configuration)
            lease.release()
            assertThrows(EnterpriseConfigurationException::class.java) { lease.execution }
            Unit
        } finally { server.stop(0) }
    }

    @Test fun `uncertain refresh retains request across restart and concurrent consumers share rotation`() = runBlocking {
        val requests = mutableListOf<Pair<String?, String>>()
        var failRefresh = true
        val server = server {
            requests += requestHeaders.getFirst("Idempotency-Key") to requestBody.bufferedReader().readText()
            if (failRefresh) reply(503, "refresh result unavailable") else reply(200, fixture("refresh-response"))
        }
        try {
            val root = temporary.newFolder()
            val first = owner(root)
            val connection = PlatformConnection("http://127.0.0.1:${server.address.port}", PlatformWireCodec.decode(fixture("discovery")))
            val id = first.acceptPlatformEnrollment(first.beginPlatformEnrollment(), connection, PlatformWireCodec.decode(fixture("enrollment-response")))
            try { service(owner(root)).accessToken(id); fail("refresh failure hidden") }
            catch (error: PlatformHttpException) { assertEquals(503, error.status) }
            failRefresh = false
            val recovered = service(owner(root))
            val tokens = (1..16).map { async { recovered.accessToken(id) } }.awaitAll()
            assertTrue(tokens.all { it === tokens.first() })
            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1])
            assertNotNull(requests.first().first)
        } finally { server.stop(0) }
    }
}
