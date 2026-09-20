package net.weero.measix.pilot.data.enterprise

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import me.rerere.common.http.RoutedHttpException
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
    private fun service(owner: EnterpriseSessionController) = PlatformEnterpriseService(
        owner, PlatformControlClient(OkHttpClient()), now = { Instant.ofEpochMilli(1000) })
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

    @Test fun `expired enrollment uses the current stable reason before network IO`() = runBlocking {
        val sessions = owner(temporary.newFolder())
        val error = runCatching {
            service(sessions).enroll(
                EnrollmentMaterial.Platform("https://platform.example", "expired", Instant.ofEpochMilli(1000)),
                "Phone",
                "1.0.0",
            )
        }.exceptionOrNull()
        assertTrue(error is EnterpriseConfigurationException)
        assertEquals("enrollment_expired", (error as EnterpriseConfigurationException).reason)
    }

    @Test fun `changing address from personal space preserves principal session data and freezes existing execution`() = runBlocking {
        val root = temporary.newFolder()
        owner(root).enrollFixture(exampleEnterprisePackage())
        val sessions = owner(root)
        val before = (sessions.recover() as EnterpriseState.Available).manifest
        val enterpriseSelection = requireNotNull(sessions.readPresentation().selection)
        val access = enterpriseSelection.access as RealmAccess.Enterprise
        val selection = sessions.switchRealm(RealmSwitchRequest(enterpriseSelection, RealmAccess.Personal)) {}
        val oldLease = sessions.captureExecution(access, before.applied)
        val oldOperation = sessions.capturePlatformOperation(access.sessionId)
        val oldExecution = oldLease.execution as EnterpriseExecution.Platform
        val session = requireNotNull(before.session)
        val discovery = PlatformWireCodec.decode<PlatformDiscovery>(fixture("discovery")).copy(
            deploymentId = session.identity.authority.deploymentId,
            deploymentName = session.identity.enterpriseName,
        )
        val bootstrap = PlatformWireCodec.decode<PlatformBootstrap>(fixture("bootstrap")).let { value ->
            value.copy(
                deployment = value.deployment.copy(
                    deploymentId = session.identity.authority.deploymentId,
                    name = session.identity.enterpriseName,
                ),
                user = value.user.copy(userId = session.identity.userId, displayName = session.identity.userName),
                device = value.device.copy(deviceId = requireNotNull(session.platform).deviceId),
                session = value.session.copy(sessionId = session.id),
            )
        }
        val refreshResponse = PlatformWireCodec.decode<PlatformRefreshResponse>(fixture("refresh-response"))
        val rejectedRefreshResponse = refreshResponse.copy(refreshToken = "rejected-candidate-refresh-token")
        val refreshes = AtomicInteger()
        val retainedCandidateCredential = AtomicBoolean(false)
        val server = server {
            when (requestURI.path) {
                "/.well-known/measix" -> reply(200, PlatformWireCodec.json.encodeToString(discovery))
                "/api/client/v1/sessions/refresh" -> {
                    refreshes.incrementAndGet()
                    val body = Json.parseToJsonElement(requestBody.bufferedReader().readText()).jsonObject
                    if (body.getValue("refreshToken").jsonPrimitive.content == "rejected-candidate-refresh-token") {
                        retainedCandidateCredential.set(true)
                    }
                    reply(200, fixture("refresh-response"))
                }
                "/api/client/v1/bootstrap" -> reply(200, PlatformWireCodec.json.encodeToString(bootstrap))
                else -> reply(404, "unexpected route")
            }
        }
        val other = server {
            when (requestURI.path) {
                "/.well-known/measix" -> reply(200, PlatformWireCodec.json.encodeToString(
                    discovery.copy(deploymentId = "dep_00000000-0000-4000-8000-000000000099"),
                ))
                else -> reply(404, "unexpected route")
            }
        }
        val mismatchedBootstrap = server {
            when (requestURI.path) {
                "/.well-known/measix" -> reply(200, PlatformWireCodec.json.encodeToString(discovery))
                "/api/client/v1/sessions/refresh" -> reply(200,
                    PlatformWireCodec.json.encodeToString(rejectedRefreshResponse))
                "/api/client/v1/bootstrap" -> reply(200, PlatformWireCodec.json.encodeToString(
                    bootstrap.copy(user = bootstrap.user.copy(
                        userId = "usr_00000000-0000-4000-8000-000000000099",
                    )),
                ))
                else -> reply(404, "unexpected route")
            }
        }
        try {
            val newOrigin = "http://127.0.0.1:${server.address.port}"
            service(sessions).changeAddress(EnterpriseAddressChangeRequest(access, selection), newOrigin)
            assertEquals(1, refreshes.get())
            val after = (sessions.state.value as EnterpriseState.Available).manifest
            assertEquals(before.session.id, after.session?.id)
            assertEquals(before.session.identity.scope, after.session?.identity?.scope)
            assertEquals(before.session.platform?.deviceId, after.session?.platform?.deviceId)
            assertEquals(before.applied, after.applied)
            assertEquals(newOrigin, after.session?.platform?.connection?.origin)
            assertEquals("https://platform.test", oldExecution.connection.origin)
            assertEquals("https://platform.test", oldOperation.context.platform.connection.origin)
            val newLease = sessions.captureExecution(access, after.applied)
            try {
                assertEquals(newOrigin, (newLease.execution as EnterpriseExecution.Platform).connection.origin)
            } finally {
                newLease.release()
            }

            val failure = runCatching {
                val currentSelection = requireNotNull(sessions.readPresentation().selection)
                service(sessions).changeAddress(
                    EnterpriseAddressChangeRequest(access, currentSelection),
                    "http://127.0.0.1:${other.address.port}",
                )
            }.exceptionOrNull()
            assertTrue(failure is EnterpriseConfigurationException)
            assertEquals("enterprise_address_deployment_mismatch", (failure as EnterpriseConfigurationException).reason)
            assertEquals(newOrigin, (sessions.state.value as EnterpriseState.Available)
                .manifest.session?.platform?.connection?.origin)

            val restarted = owner(root)
            val beforeFailure = (restarted.recover() as EnterpriseState.Available).manifest
            val failureSelection = requireNotNull(restarted.readPresentation().selection)
            val failureAccess = RealmAccess.Enterprise(
                requireNotNull(beforeFailure.session).identity.scope,
                beforeFailure.session.id,
            )
            val identityFailure = runCatching {
                service(restarted).changeAddress(
                    EnterpriseAddressChangeRequest(failureAccess, failureSelection),
                    "http://127.0.0.1:${mismatchedBootstrap.address.port}",
                )
            }.exceptionOrNull()
            assertTrue(identityFailure is EnterpriseConfigurationException)
            assertEquals("enterprise_address_identity_mismatch",
                (identityFailure as EnterpriseConfigurationException).reason)
            val afterFailure = (restarted.state.value as EnterpriseState.Available).manifest
            assertEquals(beforeFailure.session?.id, afterFailure.session?.id)
            assertEquals(beforeFailure.session?.identity?.scope, afterFailure.session?.identity?.scope)
            assertEquals(newOrigin, afterFailure.session?.platform?.connection?.origin)
            assertNotEquals(
                "a successful candidate refresh must retain its rotated credential even when Bootstrap is rejected",
                beforeFailure.session?.platform?.credential,
                afterFailure.session?.platform?.credential,
            )
            val recoveredAfterFailure = owner(root)
            recoveredAfterFailure.recover()
            val recoveredToken = service(recoveredAfterFailure).accessToken(requireNotNull(afterFailure.session).id)
            assertEquals(refreshResponse.accessToken, recoveredToken.value)
            assertEquals(2, refreshes.get())
            assertTrue(retainedCandidateCredential.get())
        } finally {
            oldOperation.release()
            oldLease.release()
            server.stop(0)
            other.stop(0)
            mismatchedBootstrap.stop(0)
        }
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

    @Test fun `principal deleted before Bootstrap retires pending credentials and permits a fresh enrollment`() = runBlocking {
        val exchanges = AtomicInteger()
        val bootstraps = AtomicInteger()
        val firstExchange = PlatformWireCodec.decode<PlatformEnrollmentExchangeResponse>(fixture("enrollment-response"))
        val firstBootstrap = PlatformWireCodec.decode<PlatformBootstrap>(fixture("bootstrap"))
        val freshUserId = "usr_00000000-0000-4000-8000-000000000099"
        val server = server {
            when (requestURI.path) {
                "/.well-known/measix" -> reply(200, fixture("discovery"))
                "/api/client/v1/enrollments/exchange" -> {
                    val body = if (exchanges.incrementAndGet() == 1) fixture("enrollment-response")
                    else fixture("enrollment-response").replace(firstExchange.userId, freshUserId)
                    reply(201, body)
                }
                "/api/client/v1/bootstrap" -> {
                    if (bootstraps.incrementAndGet() == 1) {
                        reply(401, """{"type":"about:blank","title":"Unauthorized","status":401,"code":"enterprise_identity_deleted","detail":"Enterprise identity was deleted"}""")
                    } else {
                        reply(200, fixture("bootstrap").replace(firstBootstrap.user.userId, freshUserId))
                    }
                }
                else -> reply(404, "unexpected route")
            }
        }
        try {
            val sessions = owner(temporary.newFolder())
            val platform = service(sessions)
            val material = EnrollmentMaterial.Platform(
                "http://127.0.0.1:${server.address.port}", "first-code", Instant.parse("2030-01-01T00:00:00Z"))

            val firstFailure = runCatching { platform.enroll(material, "Android test", "test") }.exceptionOrNull()
            assertTrue(firstFailure is PlatformHttpException)
            assertEquals(EnterpriseRuntimeProblemCodes.IDENTITY_DELETED,
                (firstFailure as PlatformHttpException).problem?.code)
            val deleted = (sessions.state.value as EnterpriseState.Available).manifest
            assertNull(deleted.pendingEnrollment)
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, deleted.phase)
            assertEquals(EnterpriseExitReason.IDENTITY_DELETED, deleted.exitReason)

            val access = platform.enroll(material.copy(code = "fresh-code"), "Android test", "test")
            assertEquals(freshUserId, access.scope.userId)
            assertEquals(2, exchanges.get())
            val active = (sessions.state.value as EnterpriseState.Available).manifest
            assertNull(active.pendingEnrollment)
            assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, active.phase)
            assertNull(active.exitReason)
            assertEquals(freshUserId, active.session?.identity?.userId)
        } finally { server.stop(0) }
    }

    @Test fun `terminal pending Bootstrap permits confirmed new code after restart`() = runBlocking {
        for (revokedCode in listOf("user_disabled", "device_revoked", "session_revoked")) {
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
                        2 -> reply(403, """{"type":"about:blank","title":"Forbidden","status":403,"code":"$revokedCode"}""")
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

    @Test fun `terminal active session signals the application exit owner with the exact reason`() = runBlocking {
        val cases = listOf(
            Triple(403, "user_disabled", EnterpriseExitReason.AUTHORIZATION_REVOKED),
            Triple(403, "device_revoked", EnterpriseExitReason.AUTHORIZATION_REVOKED),
            Triple(403, "session_revoked", EnterpriseExitReason.AUTHORIZATION_REVOKED),
            Triple(401, "session_expired", EnterpriseExitReason.AUTHORIZATION_EXPIRED),
            Triple(401, "invalid_credential", EnterpriseExitReason.AUTHORIZATION_EXPIRED),
        )
        val current = AtomicReference(cases.first())
        val server = server {
            assertEquals("/api/client/v1/sessions/refresh", requestURI.path)
            val (status, code) = current.get()
            reply(status, """{"type":"about:blank","title":"Unauthorized","status":$status,"code":"$code","forwarded":false}""")
        }
        try {
            for (case in cases) {
                current.set(case)
                val (status, code, reason) = case
                val sessions = owner(temporary.newFolder())
                val connection = PlatformConnection(
                    "http://127.0.0.1:${server.address.port}",
                    PlatformWireCodec.decode(fixture("discovery")),
                )
                val response = PlatformWireCodec.decode<PlatformEnrollmentExchangeResponse>(fixture("enrollment-response"))
                    .copy(accessTokenExpiresAt = "1970-01-01T00:00:01Z")
                val id = sessions.acceptPlatformEnrollment(sessions.beginPlatformEnrollment(), connection, response)
                val access = sessions.completePlatformBootstrap(id, PlatformWireCodec.decode(fixture("bootstrap")))
                val invalidated = mutableListOf<Pair<RealmAccess.Enterprise, EnterpriseExitReason>>()
                val platform = PlatformEnterpriseService(
                    sessions,
                    PlatformControlClient(OkHttpClient()),
                    { Instant.ofEpochMilli(1000) },
                    onSessionInvalidated = { captured, capturedReason -> invalidated += captured to capturedReason },
                )
                val failure = runCatching { platform.accessToken(access.sessionId) }.exceptionOrNull()
                assertTrue(failure is PlatformHttpException)
                assertEquals(status, (failure as PlatformHttpException).status)
                assertEquals(code, failure.problem?.code)
                assertEquals(listOf(access to reason), invalidated)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test fun `candidate refresh rejection while changing address does not invalidate the existing session`() = runBlocking {
        val discovery = PlatformWireCodec.decode<PlatformDiscovery>(fixture("discovery"))
        val current = AtomicReference(Triple(401, "session_expired", EnterpriseExitReason.AUTHORIZATION_EXPIRED))
        val server = server {
            when (requestURI.path) {
                "/.well-known/measix" -> reply(200, PlatformWireCodec.json.encodeToString(discovery))
                "/api/client/v1/sessions/refresh" -> {
                    val (status, code) = current.get()
                    reply(status, """{"type":"about:blank","title":"Unauthorized","status":$status,"code":"$code","forwarded":false}""")
                }
                else -> reply(404, "unexpected route")
            }
        }
        try {
            val sessions = owner(temporary.newFolder())
            val old = PlatformConnection("https://platform.example", discovery)
            val expired = PlatformWireCodec.decode<PlatformEnrollmentExchangeResponse>(fixture("enrollment-response"))
                .copy(accessTokenExpiresAt = "1970-01-01T00:00:01Z")
            val id = sessions.acceptPlatformEnrollment(sessions.beginPlatformEnrollment(), old, expired)
            val access = sessions.completePlatformBootstrap(id, PlatformWireCodec.decode(fixture("bootstrap")))
            sessions.synchronize(access, platformCandidate(exampleEnterprisePackage()))
            val selection = sessions.switchRealm(
                RealmSwitchRequest(requireNotNull(sessions.readPresentation().selection), access),
            ) {}
            val invalidated = mutableListOf<Pair<RealmAccess.Enterprise, EnterpriseExitReason>>()
            val platform = PlatformEnterpriseService(
                sessions,
                PlatformControlClient(OkHttpClient()),
                { Instant.ofEpochMilli(1000) },
                onSessionInvalidated = { captured, reason -> invalidated += captured to reason },
            )

            val failure = runCatching {
                platform.changeAddress(
                    EnterpriseAddressChangeRequest(access, selection),
                    "http://127.0.0.1:${server.address.port}",
                )
            }.exceptionOrNull()
            assertTrue(failure.toString(), failure is PlatformHttpException)
            assertTrue(invalidated.isEmpty())
            assertEquals(old.origin, sessions.platformContext(access.sessionId).platform.connection.origin)
            assertEquals(EnterpriseSessionPhase.READY,
                (sessions.state.value as EnterpriseState.Available).manifest.phase)
        } finally {
            server.stop(0)
        }
    }

    @Test fun `managed runtime revocation signals the application exit owner`() = runBlocking {
        val sessions = owner(temporary.newFolder())
        val connection = PlatformConnection("https://platform.example", PlatformWireCodec.decode(fixture("discovery")))
        val id = sessions.acceptPlatformEnrollment(
            sessions.beginPlatformEnrollment(),
            connection,
            PlatformWireCodec.decode(fixture("enrollment-response")),
        )
        val access = sessions.completePlatformBootstrap(id, PlatformWireCodec.decode(fixture("bootstrap")))
        val invalidations = mutableListOf<EnterpriseExitReason>()
        val platform = PlatformEnterpriseService(
            sessions,
            PlatformControlClient(OkHttpClient()),
            onSessionInvalidated = { _, reason -> invalidations += reason },
        )

        for (code in EnterpriseRuntimeProblemCodes.authorizationRevoked) {
            val body = """{"type":"about:blank","title":"Revoked","status":403,"code":"$code","forwarded":false}"""
            assertTrue(platform.managedRuntimeFailure(access, RoutedHttpException(403, body)) is EnterpriseRuntimeProblemException)
        }
        assertEquals(
            List(EnterpriseRuntimeProblemCodes.authorizationRevoked.size) { EnterpriseExitReason.AUTHORIZATION_REVOKED },
            invalidations,
        )
    }

    @Test fun `in flight applied report accepts identity deletion before releasing reset barrier`() = runBlocking {
        val snapshot = PlatformWireCodec.decode<PlatformManagedSnapshot>(fixture("v4-full"))
        val reportEntered = CountDownLatch(1)
        val releaseReport = CountDownLatch(1)
        val handlerFailure = AtomicReference<Throwable?>()
        val server = server {
            try {
                when (requestURI.path) {
                    "/api/client/v1/managed/state" -> reply(
                        200,
                        """{"runtimeStatus":"READY","activeManagedGeneration":${snapshot.managedGeneration},"managedStateRevision":1,"syncRequired":true,"targetManagedGeneration":${snapshot.managedGeneration},"runtimeBlocked":true}""",
                    )
                    "/api/client/v1/managed/snapshots/${snapshot.managedGeneration}" -> {
                        responseHeaders.set("ETag", "\"${snapshot.snapshotHash}\"")
                        reply(200, fixture("v4-full"))
                    }
                    "/api/client/v1/managed/applied" -> {
                        reportEntered.countDown()
                        check(releaseReport.await(5, TimeUnit.SECONDS))
                        reply(
                            401,
                            """{"type":"about:blank","title":"Deleted","status":401,"code":"enterprise_identity_deleted"}""",
                        )
                    }
                    else -> reply(404, "unexpected route")
                }
            } catch (error: Throwable) {
                handlerFailure.set(error)
                throw error
            }
        }
        try {
            val sessions = owner(temporary.newFolder())
            val connection = PlatformConnection(
                "http://127.0.0.1:${server.address.port}",
                PlatformWireCodec.decode(fixture("discovery")),
            )
            val id = sessions.acceptPlatformEnrollment(
                sessions.beginPlatformEnrollment(),
                connection,
                PlatformWireCodec.decode(fixture("enrollment-response")),
            )
            val access = sessions.completePlatformBootstrap(id, PlatformWireCodec.decode(fixture("bootstrap")))
            val accepted = CompletableDeferred<Unit>()
            val platform = PlatformEnterpriseService(
                sessions,
                PlatformControlClient(OkHttpClient()),
                now = { Instant.ofEpochMilli(1000) },
                onSessionInvalidated = { captured, reason ->
                    sessions.beginInvalidation(captured, reason)
                    accepted.complete(Unit)
                },
            )

            val syncing = async(Dispatchers.IO) { runCatching { platform.synchronize(access) } }
            if (!reportEntered.await(5, TimeUnit.SECONDS)) {
                fail("Applied report was not reached: handler=${handlerFailure.get()}, client=${syncing.await().exceptionOrNull()}")
            }
            sessions.beginLocalDataReset(requireNotNull(sessions.captureExitRequest()))
            val operationsReleased = async { sessions.awaitPlatformOperations(access) }
            assertFalse(operationsReleased.isCompleted)

            releaseReport.countDown()
            assertTrue(syncing.await().exceptionOrNull() is PlatformHttpException)
            accepted.await()
            operationsReleased.await()
            assertEquals(EnterpriseExitReason.IDENTITY_DELETED, sessions.pendingExit()?.reason)
        } finally {
            releaseReport.countDown()
            server.stop(0)
        }
    }

    @Test fun `in flight Client deletion upgrades local reset before its operation lease is released`() = runBlocking {
        val sessions = owner(temporary.newFolder())
        val connection = PlatformConnection("https://platform.example", PlatformWireCodec.decode(fixture("discovery")))
        val id = sessions.acceptPlatformEnrollment(
            sessions.beginPlatformEnrollment(),
            connection,
            PlatformWireCodec.decode(fixture("enrollment-response")),
        )
        val access = sessions.completePlatformBootstrap(id, PlatformWireCodec.decode(fixture("bootstrap")))
        val entered = CompletableDeferred<Unit>()
        val respond = CompletableDeferred<Unit>()
        val accepted = CompletableDeferred<Unit>()
        val platform = PlatformEnterpriseService(
            sessions,
            PlatformControlClient(OkHttpClient()),
            now = { Instant.ofEpochMilli(1000) },
            onSessionInvalidated = { captured, reason ->
                sessions.beginInvalidation(captured, reason)
                accepted.complete(Unit)
            },
        )

        val request = async {
            runCatching {
                platform.read<Unit>(id) { _, _ ->
                    entered.complete(Unit)
                    respond.await()
                    throw PlatformHttpException(
                        401,
                        PlatformProblem("about:blank", "Deleted", 401, "enterprise_identity_deleted"),
                        "deleted",
                    )
                }
            }
        }
        entered.await()
        sessions.beginLocalDataReset(requireNotNull(sessions.captureExitRequest()))
        val operationsReleased = async { sessions.awaitPlatformOperations(access) }
        assertFalse(operationsReleased.isCompleted)

        respond.complete(Unit)
        assertTrue(request.await().exceptionOrNull() is PlatformHttpException)
        accepted.await()
        operationsReleased.await()
        assertEquals(EnterpriseExitReason.IDENTITY_DELETED, sessions.pendingExit()?.reason)
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
