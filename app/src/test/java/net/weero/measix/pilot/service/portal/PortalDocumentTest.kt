package net.weero.measix.pilot.service.portal

import java.time.Instant
import java.util.concurrent.Executors
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.EnterpriseSynchronizationService
import net.weero.measix.pilot.service.EnterpriseExitService
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ConversationApplicationService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortalDocumentTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = Instant.parse("2029-01-01T00:00:00Z").toEpochMilli()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Before fun installMain() { Dispatchers.setMain(main) }
    @After fun resetMain() { Dispatchers.resetMain(); main.close() }

    @Test
    fun `local context and Feed use original Session and shared owner without advancing generation`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val doc = h.open()
        try {
            val before = h.sessions.state.value as EnterpriseState.Available
            val context = h.call(doc, "getLocalContext").getValue("result").jsonObject
            assertEquals(doc.id, context.getValue("documentId").jsonPrimitive.content)
            assertEquals(before.manifest.session!!.id, context.getValue("sessionId").jsonPrimitive.content)
            assertEquals(Instant.ofEpochMilli(now + 600_000).toString(), context.getValue("expiresAt").jsonPrimitive.content)
            val list = h.call(doc, "listLocalUpdates").getValue("result").jsonObject
            val etag = list.getValue("etag")
            assertEquals("modified", list.getValue("kind").jsonPrimitive.content)
            val cached = h.call(doc, "listLocalUpdates", buildJsonObject { put("ifNoneMatch", etag) }).getValue("result").jsonObject
            assertEquals(setOf("kind", "etag"), cached.keys)
            assertEquals("notModified", cached.getValue("kind").jsonPrimitive.content)
            val entry = list.getValue("feed").jsonObject.getValue("items").jsonArray.single().jsonObject
            val id = entry.getValue("enterpriseUpdateId").jsonPrimitive.content
            assertEquals(entry, h.call(doc, "getLocalUpdate", buildJsonObject { put("enterpriseUpdateId", id) }).getValue("result"))
            h.sessions.changeFeed(doc.selection.access as RealmAccess.Enterprise, before.manifest.feeds.single().revision, EnterpriseFeedCommand.Withdraw(id))
            assertEquals("enterprise_update_not_found", h.call(doc, "getLocalUpdate", buildJsonObject { put("enterpriseUpdateId", id) })
                .getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            val withdrawn = h.call(doc, "listLocalUpdates", buildJsonObject { put("ifNoneMatch", etag) }).getValue("result").jsonObject
            assertEquals("modified", withdrawn.getValue("kind").jsonPrimitive.content)
            assertNotEquals(etag, withdrawn.getValue("etag"))
            val status = h.call(doc, "getStatus").getValue("result").jsonObject
            assertEquals(before.manifest.applied!!.generation, status.getValue("appliedManagedGeneration").jsonPrimitive.long)
            assertEquals(before.manifest.session, (h.sessions.state.value as EnterpriseState.Available).manifest.session)
            assertEquals(Instant.ofEpochMilli(now).toString(), status.getValue("lastEnterpriseUpdateRefresh").jsonPrimitive.content)
        } finally { doc.close() }
    }

    @Test
    fun `foreign frames stale documents duplicate IDs and rapid realm round trips never receive a reply`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val doc = h.open()
        try {
            var replies = 0
            val raw = h.raw(doc, "getStatus", requestId = "unique")
            assertNull(doc.receive(raw, "https://foreign.invalid", true) { replies++ })
            assertNull(doc.receive(raw, PortalProtocol.LOCAL_ORIGIN, false) { replies++ })
            assertNull(doc.receive(raw.replace(doc.id, "old-document"), PortalProtocol.LOCAL_ORIGIN, true) { replies++ })
            doc.receive(raw, PortalProtocol.LOCAL_ORIGIN, true) { replies++ }!!.join()
            assertEquals(1, replies)
            assertNull(doc.receive(raw, PortalProtocol.LOCAL_ORIGIN, true) { replies++ })
            h.sessions.selectPersonalFixture()
            h.sessions.selectEnterpriseFixture()
            doc.receive(h.raw(doc, "getStatus"), PortalProtocol.LOCAL_ORIGIN, true) { replies++ }?.join()
            assertEquals(1, replies)
            assertTrue(doc.isClosed)
            val next = h.open()
            try {
                assertNotEquals(doc.id, next.id)
                assertNull(next.receive(raw, PortalProtocol.LOCAL_ORIGIN, true) { replies++ })
                assertTrue(h.call(next, "getStatus").getValue("result").jsonObject.getValue("managedReady").jsonPrimitive.boolean)
            } finally { next.close() }
        } finally { doc.close() }
    }

    @Test
    fun `document expiry rejects matching ETag without renewing or logging out the mother Session`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val doc = h.open()
        try {
            val original = (h.sessions.state.value as EnterpriseState.Available).manifest.session
            val etag = h.call(doc, "listLocalUpdates").getValue("result").jsonObject.getValue("etag")
            now += 600_000
            val expired = h.call(doc, "listLocalUpdates", buildJsonObject { put("ifNoneMatch", etag) })
            assertEquals("session_expired", expired.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(original, (h.sessions.state.value as EnterpriseState.Available).manifest.session)
        } finally { doc.close() }
    }

    @Test
    fun `configuration refresh awaits the same committed sync and close retains enterprise login`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val doc = h.open()
        try {
            val original = h.source.candidate((doc.selection.access as RealmAccess.Enterprise).scope)!!
            h.source.setPolicy(original.packet.identity.scope, original.revision, original.packet.configuration.policy.copy(allowLocalProviders = false))
            val result = h.call(doc, "refresh").getValue("result").jsonObject
            assertEquals(original.packet.configuration.generation + 1, result.getValue("appliedManagedGeneration").jsonPrimitive.long)
            assertEquals((h.sessions.state.value as EnterpriseState.Available).manifest.applied!!.generation,
                result.getValue("appliedManagedGeneration").jsonPrimitive.long)
            val before = h.sessions.state.value
            var replied = false
            doc.receive(h.raw(doc, "close"), PortalProtocol.LOCAL_ORIGIN, true) { replied = true }!!.join()
            assertTrue(doc.isClosed)
            assertFalse(replied)
            assertEquals(before, h.sessions.state.value)
        } finally { doc.close() }
    }

    @Test
    fun `a completed old synchronization cannot reply to a replacement document`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = mockk<EnterpriseSynchronizationService>()
        coEvery { delayed.synchronize(any()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            h.sessions.state.value as EnterpriseState.Available
        }
        val doc = h.open(delayed)
        var oldReplies = 0
        val request = doc.receive(h.raw(doc, "refresh"), PortalProtocol.LOCAL_ORIGIN, true) { oldReplies++ }!!
        try {
            started.await()
            h.sessions.selectPersonalFixture()
            h.sessions.selectEnterpriseFixture()
            doc.close(PortalCloseReason.AUTHORIZATION_REVOKED)
            val replacement = h.open()
            try {
                val response = h.call(replacement, "getStatus")
                assertEquals(replacement.id, response.getValue("documentId").jsonPrimitive.content)
                release.complete(Unit)
                request.join()
                assertTrue(doc.isClosed)
                assertEquals(0, oldReplies)
                assertFalse(replacement.isClosed)
            } finally { replacement.close() }
        } finally { release.complete(Unit); doc.close(); request.join() }
    }

    @Test
    fun `enterprise exit waits for original Portal cleanup and rejects a late document admission`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val hostClosed = CompletableDeferred<Unit>()
        val delayed = mockk<EnterpriseSynchronizationService>()
        coEvery { delayed.synchronize(any()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            h.sessions.state.value as EnterpriseState.Available
        }
        val doc = h.open(delayed, closeHost = { hostClosed.complete(Unit) })
        val original = doc.selection
        var replies = 0
        doc.receive(h.raw(doc, "refresh"), PortalProtocol.LOCAL_ORIGIN, true) { replies++ }
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val conversations = mockk<ConversationApplicationService>()
        coEvery { conversations.stopEnterpriseWork(any()) } returns Unit
        val exit = EnterpriseExitService(h.sessions, h.sync, conversations,
            ApplicationRecoveryGate().apply { ready() }, appScope, h.registry)
        try {
            started.await()
            val request = requireNotNull(exit.captureRequest())
            val completion = async { exit.exit(request) }
            h.sessions.state.first { it is EnterpriseState.Available && it.manifest.phase == EnterpriseSessionPhase.CLOSING }
            hostClosed.await()
            assertTrue(doc.isClosed)
            assertFalse(completion.isCompleted)
            try {
                PortalDocument.open(original, h.sessions, h.sync, this, h.registry, { now }) {}
                fail("A closing Session must reject a new document")
            } catch (_: EnterpriseConfigurationException) { }
            release.complete(Unit)
            completion.await()
            doc.awaitClosed()
            assertEquals(0, replies)
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (h.sessions.state.value as EnterpriseState.Available).manifest.phase)
            coVerify(exactly = 1) { conversations.stopEnterpriseWork(any()) }
        } finally {
            release.complete(Unit)
            doc.close()
            doc.awaitClosed()
            appScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `registry retains failed host cleanup and waits for its original requests before retry`() = runBlocking<Unit>(Dispatchers.Main) {
        val h = harness(this)
        val expected = java.io.IOException("Host teardown failed")
        var failHost = true
        var notifications = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = mockk<EnterpriseSynchronizationService>()
        coEvery { delayed.synchronize(any()) } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            h.sessions.state.value as EnterpriseState.Available
        }
        val doc = h.open(delayed, closeHost = { if (failHost) throw expected }) {
            notifications++
            throw IllegalStateException("Navigation no longer present")
        }
        doc.receive(h.raw(doc, "refresh"), PortalProtocol.LOCAL_ORIGIN, true) { fail("Closed document replied") }
        try {
            entered.await()
            val token = h.sessions.beginExit(requireNotNull(h.sessions.captureExitRequest()))
            val pending = async {
                try { h.registry.closeAndAwait(token.access, PortalCloseReason.AUTHORIZATION_REVOKED); null }
                catch (error: java.io.IOException) { error }
            }
            yield()
            assertTrue(doc.isClosed)
            assertFalse("A host failure must not skip the original request cleanup", pending.isCompleted)
            assertEquals(0, notifications)
            release.complete(Unit)
            assertSame(expected, generateSequence<Throwable>(pending.await()) { it.cause }.last())
            failHost = false
            h.registry.closeAndAwait(token.access, PortalCloseReason.AUTHORIZATION_REVOKED)
            assertEquals(1, notifications)
            h.registry.closeAndAwait(token.access, PortalCloseReason.AUTHORIZATION_REVOKED)
            assertEquals(1, notifications)
            h.sessions.finishExit(token)
        } finally {
            failHost = false
            release.complete(Unit)
            doc.close()
            doc.awaitClosed()
        }
    }

    @Test
    fun `host wait timeout and cancellation retain the original cleanup and prevent premature reopening`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = harness(backgroundScope)
        val selection = requireNotNull(h.sessions.observeSelectedRealmSelection().first())
        val deletion = CompletableDeferred<Unit>()
        var deletions = 0
        val doc = PortalDocument.open(selection, h.sessions, h.sync, backgroundScope, h.registry, { now },
            closeHost = { deletions++; deletion }) {}
        try {
            doc.close()
            val timeout = async {
                try { doc.awaitHostClosed(); null } catch (error: PortalFailure) { error }
            }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertNotNull(timeout.await())
            assertTrue(deletion.isActive)
            doc.close()
            assertEquals(1, deletions)
            val cancelledOpen = launch { h.open() }
            runCurrent()
            assertFalse(cancelledOpen.isCompleted)
            cancelledOpen.cancelAndJoin()
            assertTrue(deletion.isActive)
            val next = async { h.open() }
            runCurrent()
            assertFalse(next.isCompleted)
            deletion.complete(Unit)
            runCurrent()
            val replacement = next.await()
            assertNotEquals(doc.id, replacement.id)
            assertEquals(1, deletions)
            doc.awaitClosed()
            replacement.close()
            replacement.awaitClosed()
        } finally { deletion.complete(Unit); doc.close(); doc.awaitClosed() }
    }

    @Test
    fun `closing an old Session registry lease cannot close the newly enrolled document`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val old = h.open()
        val oldAccess = old.selection.access as RealmAccess.Enterprise
        val token = h.sessions.beginExit(requireNotNull(h.sessions.captureExitRequest()))
        h.registry.closeAndAwait(oldAccess, PortalCloseReason.AUTHORIZATION_REVOKED)
        h.sessions.finishExit(token)
        h.source.enrollExample()
        val current = h.open()
        try {
            h.registry.closeAndAwait(oldAccess, PortalCloseReason.AUTHORIZATION_REVOKED)
            assertFalse(current.isClosed)
            assertNotEquals(oldAccess, current.selection.access)
            assertEquals(current.id, h.call(current, "getStatus").getValue("documentId").jsonPrimitive.content)
        } finally { current.close(); current.awaitClosed() }
    }

    @Test
    fun `dispatcher return cancellation is preserved when host cleanup fails and can be retried`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val independent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val returning = CompletableDeferred<Runnable>()
        var intercept = true
        val returnDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (intercept) check(returning.complete(block)) else Dispatchers.Main.dispatch(context, block)
            }
        }
        var closed = false
        var delivered = false
        var cleanupAttempts = 0
        var observedFailure: Exception? = null
        val cleanupFailure = java.io.IOException("Host cleanup failed")
        val selection = requireNotNull(h.sessions.observeSelectedRealmSelection().first())
        val opener = launch(returnDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                PortalDocument.open(selection, h.sessions, h.sync, independent, h.registry, { now }, closeHost = {
                    cleanupAttempts++
                    if (cleanupAttempts == 1) throw cleanupFailure
                    CompletableDeferred(Unit)
                }) { closed = true }
                delivered = true
            } catch (error: Exception) { observedFailure = error; throw error }
        }
        try {
            val resume = returning.await()
            assertFalse(closed)
            intercept = false
            opener.cancel()
            resume.run()
            opener.join()
            assertFalse(closed)
            assertFalse(delivered)
            assertTrue(observedFailure is CancellationException)
            val suppressed = requireNotNull(observedFailure).suppressed.single()
            assertSame(cleanupFailure, generateSequence(suppressed) { it.cause }.last())
            h.sessions.selectPersonalFixture()
            h.registry.closeAndAwait(selection.access as RealmAccess.Enterprise, PortalCloseReason.AUTHORIZATION_REVOKED)
            assertTrue(closed)
            assertEquals(2, cleanupAttempts)
        } finally { intercept = false; independent.cancel(); opener.cancelAndJoin() }
    }

    @Test
    fun `native timeout includes admission waiting and does not wait again to report failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = harness(backgroundScope)
        val doc = h.open()
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            h.sessions.withSelectedRealmSelection(doc.selection) { locked.complete(Unit); release.await() }
        }
        try {
            locked.await()
            var replies = 0
            val waiting = doc.receive(h.raw(doc, "getStatus"), PortalProtocol.LOCAL_ORIGIN, true) { replies++ }!!
            runCurrent()
            assertFalse(waiting.isCompleted)
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(waiting.isCompleted)
            assertEquals(0, replies)
            assertFalse(holder.isCompleted)
            release.complete(Unit)
            holder.join()
            assertTrue(h.call(doc, "getStatus").getValue("result").jsonObject.getValue("managedReady").jsonPrimitive.boolean)
        } finally { release.complete(Unit); holder.cancelAndJoin(); doc.close() }
    }

    private suspend fun harness(scope: CoroutineScope): Harness {
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { now }
        val sourceRoot = temporary.newFolder()
        val source = LocalEnterpriseSource(
            { requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.EXAMPLE_ASSET}")) }, sessions,
            LocalEnrollmentAuthority(sourceRoot, { now }),
            { requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.IDENTITY_ASSET}")) },
            LocalEnterpriseConfigurationStore(sourceRoot), { now },
        )
        source.enrollExample()
        return Harness(sessions, source, EnterpriseSynchronizationService(sessions, source, scope), scope)
    }

    private inner class Harness(val sessions: EnterpriseSessionController, val source: LocalEnterpriseSource,
        val sync: EnterpriseSynchronizationService, val scope: CoroutineScope) {
        val registry = PortalDocumentRegistry()
        private var nextRequest = 0
        suspend fun open(synchronization: EnterpriseSynchronizationService = sync,
            closeHost: () -> Unit = {}, onClosed: (PortalClosure) -> Unit = {}) =
            PortalDocument.open(requireNotNull(sessions.observeSelectedRealmSelection().first()), sessions, synchronization,
                scope, registry, { now }, { closeHost(); CompletableDeferred(Unit) }, onClosed)
        fun raw(doc: PortalDocument, method: String, params: JsonObject = buildJsonObject {}, requestId: String = "r${nextRequest++}") =
            buildJsonObject { put("bridgeVersion", 3); put("documentId", doc.id); put("requestId", requestId); put("method", method); put("params", params) }.toString()
        suspend fun call(doc: PortalDocument, method: String, params: JsonObject = buildJsonObject {}): JsonObject {
            var response: String? = null
            requireNotNull(doc.receive(raw(doc, method, params), PortalProtocol.LOCAL_ORIGIN, true) { response = it }).join()
            return Json.parseToJsonElement(requireNotNull(response) { "No response for $method" }).jsonObject
        }
    }
}
