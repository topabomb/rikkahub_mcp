package net.weero.measix.pilot.data.enterprise

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.encodeToJsonElement
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseSessionControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `obsolete enterprise manifest is rejected without rewriting stored facts`() = runTest {
        val root = temporary.newFolder()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root)) { 1000L }
        controller.enrollFixture(exampleEnterprisePackage())
        controller.beginExit(requireNotNull(controller.captureExitRequest()))
        val current = controller.available().manifest
        val fields = EnterprisePackageCodec.json.encodeToJsonElement(current).jsonObject.toMutableMap()
        fields["schemaVersion"] = JsonPrimitive(2)
        fields.remove("exitReason")
        val originalBytes = JsonObject(fields).toString().toByteArray()
        File(root, "manifest.json").writeBytes(originalBytes)
        val store = EnterpriseAppliedStore(root) { error("Obsolete input must never be committed") }
        assertEquals("unsupported_enterprise_manifest", expectFailure<EnterpriseStorageException> { store.readManifest() }.reason)
        assertArrayEquals(originalBytes, File(root, "manifest.json").readBytes())
        val reopened = EnterpriseSessionController(EnterpriseAppliedStore(root)) { 1000L }
        assertEquals(EnterpriseState.Failed("unsupported_enterprise_manifest"), reopened.recover())
        assertArrayEquals(originalBytes, File(root, "manifest.json").readBytes())
    }

    @Test fun `closing reason is immutable and missing reason in current storage is rejected`() = runTest {
        val root = temporary.newFolder()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root)) { 1000L }
        controller.enrollFixture(exampleEnterprisePackage())
        val access = controller.captureSelectedRealmAccess() as RealmAccess.Enterprise
        val token = controller.beginInvalidation(access, EnterpriseExitReason.AUTHORIZATION_REVOKED)
        assertEquals(token, controller.beginInvalidation(access, EnterpriseExitReason.AUTHORIZATION_EXPIRED))
        val invalid = controller.available().manifest.copy(exitReason = null)
        File(root, "manifest.json").writeText(EnterprisePackageCodec.json.encodeToJsonElement(invalid).toString())
        assertEquals("inconsistent_enterprise_exit_reason", expectFailure<EnterpriseStorageException> {
            EnterpriseAppliedStore(root).readManifest()
        }.reason)
    }

    @Test
    fun `identity alone stays pending and switching retains the complete applied identity`() = runTest {
        val packet = exampleEnterprisePackage()
        val root = temporary.newFolder()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root))
        val pending = controller.enrollLocal(packet.identity, { packet.identity }, { null })
        assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, controller.available().manifest.phase)
        expectReason("enterprise_session_not_ready") { controller.selectEnterpriseFixture() }
        controller.synchronize(RealmAccess.Enterprise(packet.identity.scope, pending.manifest.session!!.id), packet)
        controller.selectEnterpriseFixture()
        val ready = controller.available()
        controller.selectPersonalFixture()
        assertEquals(ConfigurationScope.Personal, controller.available().manifest.selectedScope)
        assertEquals(ready.manifest.session, controller.available().manifest.session)
        controller.selectEnterpriseFixture()
        assertEquals(ready, controller.available())
        val recovered = EnterpriseSessionController(EnterpriseAppliedStore(root))
        assertEquals(ready, recovered.recover())
    }

    @Test
    fun `staging and manifest failures preserve previous durable state and recovery removes orphans`() = runTest {
        for (point in listOf(EnterpriseStorageCheckpoint.CONFIGURATION_STAGED, EnterpriseStorageCheckpoint.BINDINGS_STAGED, EnterpriseStorageCheckpoint.BEFORE_MANIFEST_COMMIT, EnterpriseStorageCheckpoint.MANIFEST_WRITTEN)) {
            val root = temporary.newFolder()
            var fault: EnterpriseStorageCheckpoint? = null
            val store = EnterpriseAppliedStore(root) { if (it == fault) throw IOException("injected") }
            val controller = EnterpriseSessionController(store)
            val packet = exampleEnterprisePackage()
            controller.enrollFixture(packet)
            val original = controller.available()
            fault = point
            expectFailure<IOException> {
                controller.synchronize(RealmAccess.Enterprise(packet.identity.scope, original.manifest.session!!.id),
                    packet.copy(configuration = packet.configuration.copy(generation = 2)))
            }
            assertEquals(original, controller.available())
            fault = null
            val recovered = EnterpriseSessionController(EnterpriseAppliedStore(root))
            assertEquals(original, recovered.recover())
            assertEquals(1, File(root, "revisions").listFiles()!!.size)
        }
    }

    @Test
    fun `private binding replacement preserves in-flight inputs until lease release`() = runTest {
        val root = temporary.newFolder()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root))
        val original = withCredential(exampleEnterprisePackage(), "first-private-key")
        controller.enrollFixture(original)
        val selectionRevision = controller.selectionRevision.value
        val old = controller.captureBindings(controller.captureRealmAccess(original.identity.scope) as RealmAccess.Enterprise)
        val peer = controller.captureBindings(controller.captureRealmAccess(original.identity.scope) as RealmAccess.Enterprise)
        val changed = withCredential(original, "second-private-key")
        controller.synchronize(RealmAccess.Enterprise(original.identity.scope, old.sessionId), changed)
        assertEquals(selectionRevision, controller.selectionRevision.value)
        val newer = controller.captureBindings(controller.captureRealmAccess(original.identity.scope) as RealmAccess.Enterprise)
        assertEquals("first-private-key", old.binding("mdl_chat").credential)
        assertEquals("second-private-key", newer.binding("mdl_chat").credential)
        assertEquals(2, File(root, "revisions").listFiles()!!.size)
        val publicFile = File(root, "revisions/${newer.version.revision}/configuration.json")
        assertFalse(publicFile.readText().contains("private-key"))
        assertFalse(File(root, "manifest.json").readText().contains("private-key"))
        old.release()
        old.release()
        assertEquals(2, File(root, "revisions").listFiles()!!.size)
        assertEquals("first-private-key", peer.binding("mdl_chat").credential)
        peer.release()
        assertEquals(1, File(root, "revisions").listFiles()!!.size)
        newer.release()
    }

    @Test
    fun `exit revokes leases and closing restart preserves its token despite damaged old files`() = runTest {
        val root = temporary.newFolder()
        val packet = exampleEnterprisePackage()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root))
        controller.enrollFixture(packet)
        val lease = controller.captureBindings(controller.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise)
        val token = controller.beginExit(requireNotNull(controller.captureExitRequest()))
        expectReason("enterprise_binding_lease_unavailable") { lease.binding("mdl_chat") }
        expectReason("enterprise_executions_pending") { controller.finishExit(token) }
        lease.release()
        controller.finishExit(token)
        controller.pruneUnusedRevisions()
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, controller.available().manifest.phase)
        assertEquals(0, File(root, "revisions").listFiles()!!.size)

        controller.enrollFixture(packet)
        val version = controller.available().manifest.applied!!
        val pending = controller.beginExit(requireNotNull(controller.captureExitRequest()))
        File(root, "revisions/${version.revision}/bindings.json").writeText("damaged")
        val recovered = EnterpriseSessionController(EnterpriseAppliedStore(root))
        assertEquals(EnterpriseSessionPhase.CLOSING, (recovered.recover() as EnterpriseState.Available).manifest.phase)
        assertEquals(pending, recovered.pendingExit())
        recovered.finishExit(pending)
        recovered.pruneUnusedRevisions()
        assertEquals(0, File(root, "revisions").listFiles()!!.size)
    }

    @Test
    fun `reenrollment cannot authorize an original session binding request`() = runTest {
        val packet = exampleEnterprisePackage()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { 1000L }
        val first = controller.enrollFixture(packet)
        val original = RealmAccess.Enterprise(packet.identity.scope, first.manifest.session!!.id)
        val old = controller.captureBindings(original)
        val second = controller.enrollFixture(withCredential(packet, "replacement-key"))
        val replacement = RealmAccess.Enterprise(packet.identity.scope, second.manifest.session!!.id)
        assertNotEquals(original, replacement)
        expectReason("enterprise_data_access_unavailable") { controller.captureBindings(original) }
        expectReason("enterprise_binding_lease_unavailable") { old.binding("mdl_chat") }
        val current = controller.captureBindings(replacement)
        assertEquals("replacement-key", current.binding("mdl_chat").credential)
        old.release()
        current.release()
        controller.finishExit(controller.beginExit(requireNotNull(controller.captureExitRequest())))
    }

    @Test
    fun `cancellation or expiry during a binding read cannot leave an execution lease`() = runTest {
        for (cancel in listOf(true, false)) {
            var now = 1000L
            var pauseRead = false
            val entered = CompletableDeferred<Unit>()
            val resume = CountDownLatch(1)
            val store = EnterpriseAppliedStore(temporary.newFolder()) {
                if (pauseRead && it == EnterpriseStorageCheckpoint.BINDINGS_READ) {
                    entered.complete(Unit)
                    check(resume.await(10, TimeUnit.SECONDS))
                }
            }
            val controller = EnterpriseSessionController(store) { now }
            val packet = exampleEnterprisePackage()
            val ready = controller.enrollFixture(packet)
            val access = RealmAccess.Enterprise(packet.identity.scope, ready.manifest.session!!.id)
            pauseRead = true
            val request = launch {
                if (cancel) {
                    controller.captureBindings(access)
                    fail("Cancelled capture returned a lease")
                } else {
                    expectReason("enterprise_session_expired") { controller.captureBindings(access) }
                }
            }
            entered.await()
            if (cancel) request.cancel() else now = ready.manifest.session.expiresAtMillis
            resume.countDown()
            request.join()
            val token = controller.pendingExit()
                ?: controller.beginExit(requireNotNull(controller.captureExitRequest()))
            controller.finishExit(token)
            assertNull(controller.available().manifest.session)
        }
    }

    @Test
    fun `failed lease cleanup stays owned and is retried before exit can finish`() = runTest {
        val root = temporary.newFolder()
        var failPrune = false
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root) {
            if (failPrune && it == EnterpriseStorageCheckpoint.BEFORE_REVISION_PRUNE) throw IOException("injected")
        }) { 1000L }
        val packet = exampleEnterprisePackage()
        val ready = controller.enrollFixture(packet)
        val lease = controller.captureBindings(RealmAccess.Enterprise(packet.identity.scope, ready.manifest.session!!.id))
        val token = controller.beginExit(requireNotNull(controller.captureExitRequest()))
        failPrune = true
        expectFailure<IOException> { lease.release() }
        expectReason("enterprise_binding_lease_unavailable") { lease.binding("mdl_chat") }
        expectReason("enterprise_executions_pending") { controller.finishExit(token) }
        failPrune = false
        lease.release()
        lease.release()
        controller.finishExit(token)
        assertTrue(File(root, "revisions").listFiles()!!.isEmpty())
    }

    @Test
    fun `all release callers await the same actual cleanup even when cancelled`() = runTest {
        val packet = exampleEnterprisePackage()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { 1000L }
        val ready = controller.enrollFixture(packet)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var releases = 0
        val lease = EnterpriseBindingLease("test-lease", ready.manifest.session!!.id, packet.identity.scope,
            ready.manifest.applied!!, packet.runtimeBindings.associateBy { it.resourceId }) {
            releases++
            entered.complete(Unit)
            resume.await()
        }
        val first = launch { lease.release() }
        entered.await()
        val second = launch { lease.release() }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        expectReason("enterprise_binding_lease_unavailable") { lease.binding("mdl_chat") }
        first.cancel()
        second.cancel()
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        resume.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, releases)
    }

    @Test
    fun `expiry and offline never silently become personal resource executions`() = runTest {
        val root = temporary.newFolder()
        val packet = exampleEnterprisePackage()
        var now = 1000L
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root)) { now }
        controller.enrollFixture(packet)
        val expiry = controller.available().manifest.session!!.expiresAtMillis
        controller.setOffline(true)
        expectReason("enterprise_session_not_ready") { controller.captureBindings(controller.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise) }
        controller.selectPersonalFixture()
        controller.selectEnterpriseFixture()
        assertEquals(EnterpriseSessionPhase.OFFLINE, controller.available().manifest.phase)
        now = expiry
        expectReason("enterprise_session_expired") { controller.selectEnterpriseFixture() }
        assertEquals(EnterpriseSessionPhase.CLOSING, controller.available().manifest.phase)
        assertEquals(EnterpriseExitReason.AUTHORIZATION_EXPIRED, controller.pendingExit()?.reason)
        assertEquals(ConfigurationScope.Personal, controller.available().manifest.selectedScope)
        assertNull(controller.available().manifest.applied)
    }

    @Test
    fun `bad committed binding fails closed but expired identity retains a recoverable closing intent`() = runTest {
        val root = temporary.newFolder()
        val packet = exampleEnterprisePackage()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root)) { 1000L }
        controller.enrollFixture(packet)
        val manifest = controller.available().manifest
        File(root, "revisions/${manifest.applied!!.revision}/bindings.json").writeText("corrupt")
        val recovered = EnterpriseSessionController(EnterpriseAppliedStore(root)) { 1001L }
        assertEquals(EnterpriseState.Failed("enterprise_revision_hash_mismatch"), recovered.recover())
        val expired = EnterpriseSessionController(EnterpriseAppliedStore(root)) { manifest.session!!.expiresAtMillis }
        assertEquals(EnterpriseSessionPhase.CLOSING, (expired.recover() as EnterpriseState.Available).manifest.phase)
        val token = requireNotNull(expired.pendingExit())
        assertEquals(EnterpriseExitReason.AUTHORIZATION_EXPIRED, token.reason)
        expired.finishExit(token)
        assertEquals(EnterpriseSessionPhase.REAUTH_REQUIRED, (expired.state.value as EnterpriseState.Available).manifest.phase)
    }

    @Test
    fun `unexpired damaged configuration permits explicit exit and subsequent normal enrollment`() = runTest {
        val root = temporary.newFolder()
        val packet = exampleEnterprisePackage()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root))
        controller.enrollFixture(packet)
        val version = controller.available().manifest.applied!!
        File(root, "revisions/${version.revision}/configuration.json").writeText("corrupt")
        val recovered = EnterpriseSessionController(EnterpriseAppliedStore(root))
        assertTrue(recovered.recover() is EnterpriseState.Failed)
        recovered.finishExit(recovered.beginExit(requireNotNull(recovered.captureExitRequest())))
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, recovered.available().manifest.phase)
        recovered.enrollFixture(packet)
        assertEquals(EnterpriseSessionPhase.READY, recovered.available().manifest.phase)
    }

    @Test
    fun `AtomicFile rename failure cannot publish a revision absent from its manifest`() = runTest {
        val root = temporary.newFolder()
        var breakRename = false
        val store = EnterpriseAppliedStore(root) {
            if (breakRename && it == EnterpriseStorageCheckpoint.MANIFEST_WRITTEN) {
                assertTrue(File(root, "manifest.json.new").renameTo(File(root, "uncommitted-manifest")))
            }
        }
        val controller = EnterpriseSessionController(store)
        val packet = exampleEnterprisePackage()
        controller.enrollFixture(packet)
        val previous = controller.available()
        breakRename = true
        expectFailure<EnterpriseStorageException> {
            controller.synchronize(RealmAccess.Enterprise(packet.identity.scope, previous.manifest.session!!.id),
                packet.copy(configuration = packet.configuration.copy(generation = 2)))
        }
        assertEquals(previous, controller.available())
        assertEquals(previous.manifest, store.readManifest())
    }

    @Test
    fun `cancellation before commit retains old state while cancellation during owned commit publishes durable state`() = runTest {
        for (point in listOf(EnterpriseStorageCheckpoint.BINDINGS_STAGED, EnterpriseStorageCheckpoint.MANIFEST_WRITTEN)) {
            val root = temporary.newFolder()
            var pause = false
            val entered = CompletableDeferred<Unit>()
            val resume = CountDownLatch(1)
            val store = EnterpriseAppliedStore(root) {
                if (pause && it == point) {
                    entered.complete(Unit)
                    check(resume.await(10, TimeUnit.SECONDS))
                }
            }
            val controller = EnterpriseSessionController(store)
            val packet = exampleEnterprisePackage()
            controller.enrollFixture(packet)
            val previous = controller.available()
            pause = true
            val operation = launch {
                controller.synchronize(RealmAccess.Enterprise(packet.identity.scope, previous.manifest.session!!.id),
                    packet.copy(configuration = packet.configuration.copy(generation = 2)))
            }
            entered.await()
            operation.cancel()
            resume.countDown()
            operation.join()
            assertTrue(operation.isCancelled)
            assertEquals(controller.available().manifest, store.readManifest())
            if (point == EnterpriseStorageCheckpoint.BINDINGS_STAGED) {
                assertEquals(previous, controller.available())
            } else {
                assertEquals(2L, controller.available().manifest.applied!!.generation)
            }
        }
    }

    @Test
    fun `synchronization applies a source generation without renewing the session or clearing offline state`() = runTest {
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        val packet = exampleEnterprisePackage()
        val first = controller.enrollFixture(packet)
        val original = controller.available().manifest.applied!!
        controller.setOffline(true)
        val access = RealmAccess.Enterprise(packet.identity.scope, first.manifest.session!!.id)
        controller.synchronize(access, packet.copy(configuration = packet.configuration.copy(generation = 2, policy = packet.configuration.policy.copy(allowLocalMcp = false))))
        assertEquals(original.generation + 1, controller.available().manifest.applied!!.generation)
        assertFalse(controller.available().configuration!!.policy.allowLocalMcp)
        assertEquals(first.manifest.session, controller.available().manifest.session)
        assertEquals(EnterpriseSessionPhase.OFFLINE, controller.available().manifest.phase)
        expectReason("enterprise_session_not_ready") { controller.captureBindings(access) }
        expectReason("enterprise_generation_regression") {
            controller.synchronize(access, packet)
        }
    }

    @Test
    fun `local service changes can add and remove a resource with its binding atomically`() = runTest {
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        val packet = exampleEnterprisePackage()
        val first = controller.enrollFixture(packet)
        val access = RealmAccess.Enterprise(packet.identity.scope, first.manifest.session!!.id)
        controller.synchronize(access, packet.copy(
            configuration = packet.configuration.copy(generation = 2, models = packet.configuration.models + EnterpriseModel("mdl_spare", "Spare", "spare")),
            runtimeBindings = packet.runtimeBindings + EnterpriseRuntimeBinding("mdl_spare", EnterpriseRuntimeProtocol.EXAMPLE),
        ))
        val lease = controller.captureBindings(controller.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise)
        assertEquals(EnterpriseRuntimeProtocol.EXAMPLE, lease.binding("mdl_spare").protocol)
        lease.release()
        controller.synchronize(access, packet.copy(configuration = packet.configuration.copy(generation = 3)))
        assertFalse(controller.available().configuration!!.models.any { it.id == "mdl_spare" })
    }

    @Test
    fun `conflicting generation and principal updates are rejected without losing current state`() = runTest {
        val packet = exampleEnterprisePackage()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        controller.enrollFixture(packet.copy(configuration = packet.configuration.copy(generation = 2)))
        val original = controller.available()
        val access = RealmAccess.Enterprise(packet.identity.scope, original.manifest.session!!.id)
        expectReason("enterprise_generation_regression") { controller.synchronize(access, packet) }
        val conflicting = packet.copy(configuration = packet.configuration.copy(generation = 2, policy = packet.configuration.policy.copy(allowLocalProviders = false)))
        expectReason("enterprise_generation_conflict") { controller.synchronize(access, conflicting) }
        expectReason("exit_current_enterprise_first") {
            controller.enrollFixture(packet.copy(identity = packet.identity.copy(userId = "someone_else")))
        }
        assertEquals(original, controller.available())
    }

    private fun EnterpriseSessionController.available() = state.value as EnterpriseState.Available

    private fun withCredential(packet: EnterprisePackage, credential: String) = packet.copy(
        runtimeBindings = packet.runtimeBindings.map {
            if (it.resourceId == "mdl_chat") it.copy(protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT, endpoint = "https://example.invalid/v1", credential = credential) else it
        },
    )

    private suspend fun expectReason(reason: String, block: suspend () -> Unit) {
        assertEquals(reason, expectFailure<EnterpriseConfigurationException>(block).reason)
    }

    private suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
