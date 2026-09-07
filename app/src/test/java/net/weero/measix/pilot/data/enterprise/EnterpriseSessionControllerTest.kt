package net.weero.measix.pilot.data.enterprise

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `identity alone stays pending and switching retains the complete applied identity`() = runTest {
        val packet = exampleEnterprisePackage()
        val root = temporary.newFolder()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root))
        val pending = controller.enrollLocal(packet.identity, { packet.identity }, { null })
        assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, controller.available().manifest.phase)
        expectReason("enterprise_session_not_ready") { controller.switchToEnterprise() }
        controller.synchronize(RealmAccess.Enterprise(packet.identity.scope, pending.manifest.session!!.id), packet)
        controller.switchToEnterprise()
        val ready = controller.available()
        controller.switchToPersonal()
        assertEquals(ConfigurationScope.Personal, controller.available().manifest.selectedScope)
        assertEquals(ready.manifest.session, controller.available().manifest.session)
        controller.switchToEnterprise()
        assertEquals(ready, controller.available())
        val recovered = EnterpriseSessionController(EnterpriseAppliedStore(root))
        assertEquals(ready, recovered.recover())
    }

    @Test
    fun `staging and manifest failures preserve previous durable state and recovery removes orphans`() = runTest {
        for (point in EnterpriseStorageCheckpoint.entries.filter { it != EnterpriseStorageCheckpoint.FEED_STAGED }) {
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
        val old = controller.captureBindings(original.identity.scope)
        val changed = withCredential(original, "second-private-key")
        controller.synchronize(RealmAccess.Enterprise(original.identity.scope, old.sessionId), changed)
        assertEquals(selectionRevision, controller.selectionRevision.value)
        val newer = controller.captureBindings(original.identity.scope)
        assertEquals("first-private-key", old.binding("mdl_chat").credential)
        assertEquals("second-private-key", newer.binding("mdl_chat").credential)
        assertEquals(2, File(root, "revisions").listFiles()!!.size)
        val publicFile = File(root, "revisions/${newer.version.revision}/configuration.json")
        assertFalse(publicFile.readText().contains("private-key"))
        assertFalse(File(root, "manifest.json").readText().contains("private-key"))
        old.release()
        old.release()
        assertEquals(1, File(root, "revisions").listFiles()!!.size)
        newer.release()
    }

    @Test
    fun `exit revokes leases before cleanup and closing restart finishes even with damaged old files`() = runTest {
        val root = temporary.newFolder()
        val packet = exampleEnterprisePackage()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root))
        controller.enrollFixture(packet)
        val lease = controller.captureBindings(packet.identity.scope)
        val token = requireNotNull(controller.beginExit())
        expectReason("enterprise_binding_lease_unavailable") { lease.binding("mdl_chat") }
        expectReason("enterprise_executions_pending") { controller.finishExit(token) }
        lease.release()
        controller.finishExit(token)
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, controller.available().manifest.phase)
        assertEquals(0, File(root, "revisions").listFiles()!!.size)

        controller.enrollFixture(packet)
        val version = controller.available().manifest.applied!!
        controller.beginExit()
        File(root, "revisions/${version.revision}/bindings.json").writeText("damaged")
        val recovered = EnterpriseSessionController(EnterpriseAppliedStore(root))
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (recovered.recover() as EnterpriseState.Available).manifest.phase)
        assertEquals(0, File(root, "revisions").listFiles()!!.size)
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
        expectReason("enterprise_session_not_ready") { controller.captureBindings(packet.identity.scope) }
        controller.switchToPersonal()
        controller.switchToEnterprise()
        assertEquals(EnterpriseSessionPhase.OFFLINE, controller.available().manifest.phase)
        now = expiry
        expectReason("enterprise_session_expired") { controller.switchToEnterprise() }
        assertEquals(EnterpriseSessionPhase.REAUTH_REQUIRED, controller.available().manifest.phase)
        assertEquals(ConfigurationScope.Personal, controller.available().manifest.selectedScope)
        assertNull(controller.available().manifest.applied)
    }

    @Test
    fun `bad committed binding fails closed but expired identity can still be recovered as signed out`() = runTest {
        val root = temporary.newFolder()
        val packet = exampleEnterprisePackage()
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(root)) { 1000L }
        controller.enrollFixture(packet)
        val manifest = controller.available().manifest
        File(root, "revisions/${manifest.applied!!.revision}/bindings.json").writeText("corrupt")
        val recovered = EnterpriseSessionController(EnterpriseAppliedStore(root)) { 1001L }
        assertEquals(EnterpriseState.Failed("enterprise_revision_hash_mismatch"), recovered.recover())
        val expired = EnterpriseSessionController(EnterpriseAppliedStore(root)) { manifest.session!!.expiresAtMillis }
        assertEquals(EnterpriseSessionPhase.REAUTH_REQUIRED, (expired.recover() as EnterpriseState.Available).manifest.phase)
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
        recovered.finishExit(requireNotNull(recovered.beginExit()))
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
        expectReason("enterprise_session_not_ready") { controller.captureBindings(exampleEnterprisePackage().identity.scope) }
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
        val lease = controller.captureBindings(packet.identity.scope)
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
