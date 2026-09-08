package net.weero.measix.pilot.service.portal

import java.time.Instant
import java.util.concurrent.Executors
import io.mockk.coEvery
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
            h.sessions.switchToPersonal()
            h.sessions.switchToEnterprise()
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
            h.sessions.switchToPersonal()
            h.sessions.switchToEnterprise()
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
    fun `cancellation at dispatcher return releases the newly created document`() = runBlocking(Dispatchers.Main) {
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
        val selection = requireNotNull(h.sessions.observeSelectedRealmSelection().first())
        val opener = launch(returnDispatcher, start = CoroutineStart.UNDISPATCHED) {
            PortalDocument.open(selection, h.sessions, h.sync, independent, { now }) { closed = true }
            delivered = true
        }
        try {
            val resume = returning.await()
            assertFalse(closed)
            intercept = false
            opener.cancel()
            resume.run()
            opener.join()
            assertTrue(closed)
            assertFalse(delivered)
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
        private var nextRequest = 0
        suspend fun open(synchronization: EnterpriseSynchronizationService = sync) =
            PortalDocument.open(requireNotNull(sessions.observeSelectedRealmSelection().first()), sessions, synchronization, scope, { now }) {}
        fun raw(doc: PortalDocument, method: String, params: JsonObject = buildJsonObject {}, requestId: String = "r${nextRequest++}") =
            buildJsonObject { put("bridgeVersion", 3); put("documentId", doc.id); put("requestId", requestId); put("method", method); put("params", params) }.toString()
        suspend fun call(doc: PortalDocument, method: String, params: JsonObject = buildJsonObject {}): JsonObject {
            var response: String? = null
            requireNotNull(doc.receive(raw(doc, method, params), PortalProtocol.LOCAL_ORIGIN, true) { response = it }).join()
            return Json.parseToJsonElement(requireNotNull(response) { "No response for $method" }).jsonObject
        }
    }
}
