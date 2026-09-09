package net.weero.measix.pilot.service.portal

import java.time.Instant
import android.content.Context
import io.mockk.verify
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
import kotlinx.coroutines.flow.MutableStateFlow
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
            ApplicationRecoveryGate().apply { ready() }, appScope, h.registry, mockk(relaxed = true), mockk { io.mockk.coEvery { closeRealm(any()) } returns Unit })
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

    @Test
    fun `approved Portal logout uses native confirmation and finishes despite cancelling its own request`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val conversations = mockk<ConversationApplicationService>()
        coEvery { conversations.stopEnterpriseWork(any()) } returns Unit
        val exit = EnterpriseExitService(h.sessions, h.sync, conversations,
            ApplicationRecoveryGate().apply { ready() }, appScope, h.registry, mockk(relaxed = true), mockk { io.mockk.coEvery { closeRealm(any()) } returns Unit })
        lateinit var native: PortalNativeActions
        val doc = h.open(createNative = { h.native(mockk(), it, exit).also { native = it } })
        try {
            val status = h.call(doc, "getStatus").getValue("result").jsonObject
            assertTrue(status.getValue("capabilities").jsonArray.contains(JsonPrimitive("logout")))
            var replies = 0
            val request = requireNotNull(doc.receive(h.raw(doc, "logout"), PortalProtocol.LOCAL_ORIGIN, true) { replies++ })
            val prompt = native.prompt.first { it != null } as PortalNativePrompt.Logout
            assertEquals((h.sessions.state.value as EnterpriseState.Available).manifest.session!!.identity.enterpriseName, prompt.enterpriseName)
            assertEquals(EnterpriseSessionPhase.READY, (h.sessions.state.value as EnterpriseState.Available).manifest.phase)
            native.decide(prompt, true)
            withTimeout(5_000) {
                h.sessions.state.first { it is EnterpriseState.Available && it.manifest.phase == EnterpriseSessionPhase.SIGNED_OUT }
                request.join()
                doc.awaitClosed()
            }
            assertTrue(doc.isClosed)
            assertNull(native.prompt.value)
            assertEquals(0, replies)
            coVerify(exactly = 1) { conversations.stopEnterpriseWork(any()) }
        } finally { doc.close(); appScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test
    fun `declined timed out and stale native confirmations never perform external or exit actions`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = harness(backgroundScope)
        val context = mockk<Context>(relaxed = true)
        val exit = mockk<EnterpriseExitService>()
        lateinit var native: PortalNativeActions
        val doc = h.open(createNative = { h.native(context, it, exit).also { native = it } })
        val params = buildJsonObject { put("url", "https://example.com/approved-path") }
        try {
            val declined = async { h.call(doc, "openExternal", params) }
            val first = requireNotNull(native.prompt.first { it != null })
            assertEquals("resource_limit", h.call(doc, "openExternal", params).getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            native.decide(first, false)
            assertEquals("user_cancelled", declined.await().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            val timedOut = async { h.call(doc, "openExternal", params) }
            val second = requireNotNull(native.prompt.first { it != null })
            native.decide(first, true)
            runCurrent()
            assertFalse(timedOut.isCompleted)
            advanceTimeBy(10_000); runCurrent()
            assertEquals("timeout", timedOut.await().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            native.decide(second, true)
            assertNull(native.prompt.value)
            verify(exactly = 0) { context.startActivity(any()) }
            val accepted = async { h.call(doc, "openExternal", params) }
            val third = requireNotNull(native.prompt.first { it != null })
            native.decide(third, true)
            assertTrue(accepted.await().containsKey("result"))
            verify(exactly = 1) { context.startActivity(match { it.data.toString() == "https://example.com/approved-path" }) }
            var lateReplies = 0
            val cancelled = requireNotNull(doc.receive(h.raw(doc, "openExternal", params), PortalProtocol.LOCAL_ORIGIN, true) { lateReplies++ })
            val fourth = requireNotNull(native.prompt.first { it != null })
            doc.close()
            native.decide(fourth, true)
            cancelled.join()
            assertEquals(0, lateReplies)
            verify(exactly = 1) { context.startActivity(any()) }
            coVerify(exactly = 0) { exit.exit(any()) }
        } finally { doc.close() }
    }

    @Test
    fun `native confirmation rechecks document expiry even when the scheduled close has not run`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val exit = mockk<EnterpriseExitService>()
        coEvery { exit.captureRequest() } coAnswers { h.sessions.captureExitRequest() }
        val context = mockk<Context>(relaxed = true)
        for (method in listOf("openExternal", "logout")) {
            lateinit var native: PortalNativeActions
            val doc = h.open(createNative = { h.native(context, it, exit).also { native = it } })
            try {
                val result = async { h.call(doc, method,
                    if (method == "openExternal") buildJsonObject { put("url", "https://example.com") } else buildJsonObject {}) }
                val prompt = requireNotNull(native.prompt.first { it != null })
                now += 600_000
                assertFalse(doc.isClosed)
                native.decide(prompt, true)
                assertEquals("session_expired", result.await().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(EnterpriseSessionPhase.READY, (h.sessions.state.value as EnterpriseState.Available).manifest.phase)
                verify(exactly = 0) { context.startActivity(any()) }
                coVerify(exactly = 0) { exit.exit(any()) }
            } finally { doc.close(); doc.awaitClosed() }
        }
    }

    @Test
    fun `realm switch waits for late native media writes without holding cleanup behind Session admission`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        lateinit var operation: ControlledCapture
        val doc = h.open(createNative = { context -> h.native(mockk(), context, mockk(), PortalCaptureFactory { capture, _, _ ->
            ControlledCapture(capture).also { operation = it }
        }) })
        var replies = 0
        val request = requireNotNull(doc.receive(h.raw(doc, "capturePhoto"), PortalProtocol.LOCAL_ORIGIN, true) { replies++ })
        try {
            requireNotNull(doc.native).capture.first { it != null }
            val original = doc.selection
            val switch = async {
                h.sessions.switchRealm(RealmSwitchRequest(original, RealmAccess.Personal)) { access ->
                    val close = h.registry.capture(access as RealmAccess.Enterprise)
                    close.revoke(PortalCloseReason.AUTHORIZATION_REVOKED)
                    close.awaitHostsClosed()
                }
            }
            operation.closeStarted.await()
            assertFalse(switch.isCompleted)
            assertEquals(original.access.scope, (h.sessions.state.value as EnterpriseState.Available).manifest.selectedScope)
            assertTrue(operation.capture.file.exists())
            operation.capture.file.writeBytes(portalTestJpeg())
            operation.hardwareStopped.complete(Unit)
            withTimeout(5_000) { switch.await(); request.join(); doc.awaitClosed() }
            assertFalse(operation.capture.file.exists())
            assertEquals(0, replies)
            assertEquals(RealmAccess.Personal, h.sessions.readPresentation().selection?.access)
        } finally { operation.hardwareStopped.complete(Unit); doc.close(); request.cancelAndJoin() }
    }

    @Test
    fun `application cancellation still starts native cleanup and waits for the original writer`() = runBlocking(Dispatchers.Main) {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val h = harness(app)
        lateinit var operation: ControlledCapture
        val doc = h.open(createNative = { context -> h.native(mockk(), context, mockk(), PortalCaptureFactory { capture, _, _ ->
            ControlledCapture(capture).also { operation = it }
        }) })
        doc.receive(h.raw(doc, "recordAudio", buildJsonObject { put("maxDurationSeconds", 5) }), PortalProtocol.LOCAL_ORIGIN, true) { fail("Cancelled media replied") }
        try {
            requireNotNull(doc.native).capture.first { it != null }
            app.cancel()
            operation.closeStarted.await()
            assertTrue(doc.isClosed)
            assertTrue(operation.capture.file.exists())
            assertFalse(doc.isHostClosed)
            operation.capture.file.writeBytes(portalTestAudio())
            operation.hardwareStopped.complete(Unit)
            withTimeout(5_000) { doc.awaitClosed(); requireNotNull(app.coroutineContext[Job]).join() }
            assertFalse(operation.capture.file.exists())
            assertTrue(doc.isHostClosed)
        } finally { operation.hardwareStopped.complete(Unit); doc.close(); app.cancel() }
    }

    @Test
    fun `captured media is read through the original handle and released idempotently`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val doc = h.open(createNative = { context -> h.native(mockk(), context, mockk(), PortalCaptureFactory { capture, _, _ ->
            ControlledCapture(capture).apply {
                capture.file.writeBytes(portalTestJpeg())
                hardwareStopped.complete(Unit)
                result.complete(Unit)
            }
        }) })
        try {
            val handle = h.call(doc, "capturePhoto").getValue("result").jsonObject
            val id = handle.getValue("mediaId").jsonPrimitive.content
            assertEquals("image/jpeg", handle.getValue("mimeType").jsonPrimitive.content)
            val chunk = h.call(doc, "readMedia", buildJsonObject { put("mediaId", id); put("offset", 0); put("maxBytes", 65536) })
                .getValue("result").jsonObject
            assertArrayEquals(portalTestJpeg(), java.util.Base64.getDecoder().decode(chunk.getValue("dataBase64").jsonPrimitive.content))
            assertEquals(handle.getValue("byteLength"), chunk.getValue("nextOffset"))
            assertTrue(chunk.getValue("eof").jsonPrimitive.boolean)
            val params = buildJsonObject { put("mediaId", id) }
            assertTrue(h.call(doc, "releaseMedia", params).containsKey("result"))
            assertTrue(h.call(doc, "releaseMedia", params).containsKey("result"))
            assertEquals("media_unavailable", h.call(doc, "readMedia", buildJsonObject { put("mediaId", id); put("offset", 0); put("maxBytes", 1) })
                .getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        } finally { doc.close(); doc.awaitClosed() }
    }

    @Test
    fun `native cancel after hardware completion cancels publication and waits for file cleanup`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val store = PortalMediaStore(temporary.newFolder(), { now }).also { it.recover() }
        lateinit var operation: ControlledCapture
        val doc = h.open(createNative = { context -> h.native(mockk(), context, mockk(), PortalCaptureFactory { capture, _, _ ->
            ControlledCapture(capture).also { operation = it }
        }, store) })
        val release = CompletableDeferred<Unit>()
        var holder: Job? = null
        try {
            val response = async { h.call(doc, "capturePhoto") }
            val native = requireNotNull(doc.native)
            native.capture.first { it != null }
            val locked = CompletableDeferred<Unit>()
            holder = launch { store.locked { locked.complete(Unit); release.await() } }
            locked.await()
            operation.capture.file.writeBytes(portalTestJpeg())
            operation.hardwareStopped.complete(Unit)
            operation.result.complete(Unit)
            operation.closeStarted.await()
            yield()
            native.cancelCapture(operation)
            yield()
            assertFalse(response.isCompleted)
            assertTrue(operation.capture.file.exists())
            release.complete(Unit)
            assertEquals("user_cancelled", response.await().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse(operation.capture.file.exists())
            assertNull(native.capture.value)
        } finally { release.complete(Unit); holder?.join(); operation.hardwareStopped.complete(Unit); doc.close(); doc.awaitClosed() }
    }

    @Test
    fun `failed reply releases a published capture while cancelled reads preserve delivered handles`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        val root = temporary.newFolder()
        val store = PortalMediaStore(root, { now }).also { it.recover() }
        val doc = h.open(createNative = { context -> h.native(mockk(), context, mockk(), PortalCaptureFactory { capture, _, _ ->
            ControlledCapture(capture).apply {
                capture.file.writeBytes(portalTestJpeg()); hardwareStopped.complete(Unit); result.complete(Unit)
            }
        }, store) })
        val release = CompletableDeferred<Unit>()
        var holder: Job? = null
        try {
            val id = h.call(doc, "capturePhoto").getValue("result").jsonObject.getValue("mediaId").jsonPrimitive.content
            val params = buildJsonObject { put("mediaId", id); put("offset", 0); put("maxBytes", 1) }
            val locked = CompletableDeferred<Unit>()
            holder = launch { store.locked { locked.complete(Unit); release.await() } }
            locked.await()
            val read = requireNotNull(doc.receive(h.raw(doc, "readMedia", params), PortalProtocol.LOCAL_ORIGIN, true) { fail("Cancelled read replied") })
            yield(); read.cancel(); release.complete(Unit); read.join()
            assertTrue(h.call(doc, "readMedia", params).containsKey("result"))
            assertTrue(h.call(doc, "releaseMedia", buildJsonObject { put("mediaId", id) }).containsKey("result"))
            var published = false
            requireNotNull(doc.receive(h.raw(doc, "capturePhoto"), PortalProtocol.LOCAL_ORIGIN, true) { response ->
                published = Json.parseToJsonElement(response).jsonObject.containsKey("result")
                throw IllegalStateException("Original reply proxy gone")
            }).join()
            doc.awaitClosed()
            assertTrue(published)
            assertTrue(root.walkTopDown().none { it.isFile })
        } finally { release.complete(Unit); holder?.join(); doc.close(); doc.awaitClosed() }
    }

    @Test
    fun `native cleanup failure keeps the host barrier closed until the original owner retries`() = runBlocking(Dispatchers.Main) {
        val h = harness(this)
        var rejectDeletion = false
        val store = PortalMediaStore(temporary.newFolder(), { now }, { file -> !rejectDeletion && file.delete() }).also { it.recover() }
        rejectDeletion = true
        lateinit var operation: ControlledCapture
        var browserClosed = false
        val doc = h.open(closeHost = { browserClosed = true }, createNative = { context ->
            h.native(mockk(), context, mockk(), PortalCaptureFactory { capture, _, _ ->
                ControlledCapture(capture).also { operation = it }
            }, store)
        })
        val request = requireNotNull(doc.receive(h.raw(doc, "capturePhoto"), PortalProtocol.LOCAL_ORIGIN, true) { fail("Closed capture replied") })
        try {
            requireNotNull(doc.native).capture.first { it != null }
            operation.hardwareStopped.complete(Unit)
            doc.close()
            request.join()
            try { doc.awaitHostClosed(); fail("Deletion failure incorrectly opened the host barrier") }
            catch (_: java.io.IOException) { }
            assertTrue(browserClosed)
            assertFalse(doc.isHostClosed)
            assertTrue(operation.capture.file.exists())
            rejectDeletion = false
            doc.close()
            doc.awaitClosed()
            assertTrue(doc.isHostClosed)
            assertFalse(operation.capture.file.exists())
        } finally { rejectDeletion = false; operation.hardwareStopped.complete(Unit); doc.close(); doc.awaitClosed() }
    }

    private class ControlledCapture(val capture: PortalMediaCapture) : PortalCaptureOperation {
        override val permission = if (capture.mimeType == PortalMediaStore.JPEG) android.Manifest.permission.CAMERA else android.Manifest.permission.RECORD_AUDIO
        override val phase = MutableStateFlow(PortalCapturePhase.PERMISSION)
        override val preview = MutableStateFlow<android.view.View?>(null)
        override val result = CompletableDeferred<Unit>()
        val hardwareStopped = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        override fun permissionResult(granted: Boolean) = Unit
        override fun start() = Unit
        override fun stop() = Unit
        override fun cancel() { result.completeExceptionally(PortalFailure("user_cancelled")); close() }
        override fun close(): Deferred<Unit> { closeStarted.complete(Unit); result.cancel(); return hardwareStopped }
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
            closeHost: () -> Unit = {}, createNative: (suspend (PortalDocumentContext) -> PortalNativeActions)? = null,
            onClosed: (PortalClosure) -> Unit = {}) =
            PortalDocument.open(requireNotNull(sessions.observeSelectedRealmSelection().first()), sessions, synchronization,
                scope, registry, { now }, { closeHost(); CompletableDeferred(Unit) }, createNative, onClosed)
        suspend fun native(context: Context, document: PortalDocumentContext, exit: EnterpriseExitService,
            captures: PortalCaptureFactory = PortalCaptureFactory { _, _, _ -> error("Unexpected capture") },
            store: PortalMediaStore? = null): PortalNativeActions {
            val media = store ?: PortalMediaStore(temporary.newFolder(), { now }).also { it.recover() }
            return PortalNativeActions(context, document, sessions, exit, media.open(document.id), captures, scope)
        }
        fun raw(doc: PortalDocument, method: String, params: JsonObject = buildJsonObject {}, requestId: String = "r${nextRequest++}") =
            buildJsonObject { put("bridgeVersion", 3); put("documentId", doc.id); put("requestId", requestId); put("method", method); put("params", params) }.toString()
        suspend fun call(doc: PortalDocument, method: String, params: JsonObject = buildJsonObject {}): JsonObject {
            var response: String? = null
            requireNotNull(doc.receive(raw(doc, method, params), PortalProtocol.LOCAL_ORIGIN, true) { response = it }).join()
            return Json.parseToJsonElement(requireNotNull(response) { "No response for $method" }).jsonObject
        }
    }
}
