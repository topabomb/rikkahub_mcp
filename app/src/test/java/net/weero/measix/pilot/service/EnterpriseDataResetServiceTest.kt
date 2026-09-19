package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.ai.mcp.McpCatalogStore
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.repository.MemoryRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/**
 * Reset formats every enterprise realm the device holds: both branches run in every settled state and the
 * only runtime gate is the stop barrier for a live Session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseDataResetServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `corrupt durable intent fails closed and remains available for diagnosis`() {
        val root = temporary.newFolder()
        val file = File(root, "intent.json").apply { writeText("{") }

        try {
            EnterpriseDataResetStore(root).read()
            fail("Corrupt reset intent must not be treated as completed")
        } catch (error: EnterpriseStorageException) {
            assertEquals("invalid_enterprise_reset_intent", error.reason)
        }
        assertTrue(file.isFile)
    }

    @Test fun `keep history reset drops access state and managed caches for every frozen realm`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            val residual = f.residualScope()
            f.frozenScopes = setOf(scope, residual)
            val installation = net.weero.measix.pilot.data.enterprise.enterpriseTestStore(f.root).installationId()

            f.reset(reset, EnterpriseDataResetMode.KEEP_HISTORY)

            val manifest = (sessions.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertNull(manifest.session)
            assertEquals(scope, manifest.lastIdentity?.scope)
            coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(scope) }
            coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(residual) }
            coVerify(exactly = 0) { f.conversations.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.files.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.memories.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.settings.clearEnterprisePreferences(any()) }
            assertNotEquals(installation, net.weero.measix.pilot.data.enterprise.enterpriseTestStore(f.root).installationId())
            assertEquals(0, f.logoutCalls)
            f.assertStoreClean()
        }
    }

    @Test fun `clear all removes history of every frozen realm including residual principals`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            val residual = f.residualScope()
            f.frozenScopes = setOf(scope, residual)

            f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL)

            assertNull((sessions.state.value as EnterpriseState.Available).manifest.lastIdentity)
            for (target in listOf(scope, residual)) {
                coVerify(exactly = 1) { f.conversations.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.files.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.memories.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.settings.clearEnterprisePreferences(target) }
                coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(target) }
            }
            assertEquals(0, f.logoutCalls)
            f.assertStoreClean()
        }
    }

    @Test fun `scope committed before closing barrier is included by the post barrier freeze`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val active = f.enroll(sessions)
            val raced = f.residualScope()
            f.frozenScopes = setOf(active)
            coEvery { f.conversations.stopEnterpriseWork(any()) } answers {
                f.frozenScopes = f.frozenScopes + raced
            }

            f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL)

            coVerify(exactly = 1) { f.conversations.clearEnterpriseScope(raced) }
            coVerify(exactly = 1) { f.files.clearEnterpriseScope(raced) }
            coVerify(exactly = 1) { f.memories.clearEnterpriseScope(raced) }
            coVerify(exactly = 1) { f.settings.clearEnterprisePreferences(raced) }
            coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(raced) }
        }
    }

    @Test fun `corrupt storage resets access facts and managed caches without touching history`() = runTest {
        fixture { f ->
            val enrolled = f.new()
            val scope = f.enroll(enrolled.first)
            coEvery { f.conversations.enterpriseScopes() } returns setOf(scope)
            File(f.root, "manifest.json").writeText("corrupted")
            val (sessions, _, reset) = f.new()
            assertTrue(sessions.recover() is EnterpriseState.Failed)

            f.reset(reset, EnterpriseDataResetMode.KEEP_HISTORY)

            val manifest = (sessions.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertNull(manifest.session)
            assertNull(manifest.lastIdentity)
            coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(scope) }
            coVerify(exactly = 0) { f.conversations.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.files.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.memories.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.settings.clearEnterprisePreferences(any()) }
            assertEquals(0, f.logoutCalls)
            f.assertStoreClean()
        }
    }

    @Test fun `corrupt storage can also clear every frozen realm`() = runTest {
        fixture { f ->
            val enrolled = f.new()
            val scope = f.enroll(enrolled.first)
            val residual = f.residualScope()
            coEvery { f.conversations.enterpriseScopes() } returns setOf(scope, residual)
            File(f.root, "manifest.json").writeText("corrupted")
            val (sessions, _, reset) = f.new()
            assertTrue(sessions.recover() is EnterpriseState.Failed)

            f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL)

            for (target in listOf(scope, residual)) {
                coVerify(exactly = 1) { f.conversations.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.files.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.memories.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.settings.clearEnterprisePreferences(target) }
            }
            assertEquals(0, f.logoutCalls)
            f.assertStoreClean()
        }
    }

    @Test fun `interrupted reset resumes from the retained intent after a restart`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            // Two frozen realms keep the retained intent multi-scope, so the resume path must read it back.
            val residual = f.residualScope()
            f.frozenScopes = setOf(scope, residual)
            coEvery { f.catalogs.clearEnterpriseScope(any()) } throws IOException("catalog pending")
            try { f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL); fail("Expected catalog failure") }
            catch (_: IOException) { }

            val retained = requireNotNull(f.store.read())
            assertEquals(EnterpriseDataResetStage.DOMAINS_CLOSED, retained.stage)
            assertEquals(setOf(scope, residual), retained.scopes.toSet())
            assertEquals(2, retained.scopes.size)
            assertEquals(EnterpriseSessionPhase.CLOSING, (sessions.state.value as EnterpriseState.Available).manifest.phase)

            coEvery { f.catalogs.clearEnterpriseScope(any()) } returns Unit
            val (restarted, _, resume) = f.new()
            resume.resumePending()

            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (restarted.state.value as EnterpriseState.Available).manifest.phase)
            assertNull(f.store.read())
            f.assertStoreClean()
        }
    }

    @Test fun `owner failure retains the intent and retry converges`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            f.enroll(sessions)
            coEvery { f.memories.clearEnterpriseScope(any()) } throws IOException("memory store unavailable")
            try { f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL); fail("Expected memory failure") }
            catch (_: IOException) { }
            assertNotNull(f.store.read())

            coEvery { f.memories.clearEnterpriseScope(any()) } returns Unit
            reset.retryReset()

            assertNull(f.store.read())
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (sessions.state.value as EnterpriseState.Available).manifest.phase)
            f.assertStoreClean()
        }
    }

    @Test fun `keep history resume keeps the previous enterprise identity after a restart`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            coEvery { f.catalogs.clearEnterpriseScope(any()) } throws IOException("catalog pending")
            try { f.reset(reset, EnterpriseDataResetMode.KEEP_HISTORY); fail("Expected catalog failure") }
            catch (_: IOException) { }

            coEvery { f.catalogs.clearEnterpriseScope(any()) } returns Unit
            val (restarted, resume) = f.restart()
            resume.resumePending()

            // The CLOSING continuation preserves the identity a signed-out rewrite alone would lose.
            val manifest = (restarted.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertNull(manifest.session)
            assertEquals(scope, manifest.lastIdentity?.scope)
            assertNull(f.store.read())
            f.assertStoreClean()
        }
    }

    @Test fun `frozen resume freezes a live session durably without the runtime domain stop`() = runTest {
        fixture { f ->
            // A live READY Session on disk: the confirmed reset died before its barrier ran. The bare
            // enroller carries no exit watcher, so nothing races the resumed writes over the same store.
            val enroller = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(f.root)) { f.now }
            val scope = f.enroll(enroller)
            f.store.write(f.frozenIntent(scope))

            val (restarted, resume) = f.restart()
            resume.resumePending()

            val manifest = (restarted.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertNull(manifest.session)
            assertEquals(scope, manifest.lastIdentity?.scope)
            // Cold start holds no in-process enterprise work, so the runtime stop path is never entered;
            // pre-restart turns are converged by turn recovery, not by the reset barrier.
            coVerify(exactly = 0) { f.conversations.stopEnterpriseWork(any()) }
            assertNull(f.store.read())
            f.assertStoreClean()
        }
    }

    @Test fun `startup resume defers to a pending foreign exit and converges once it completes`() = runTest {
        fixture { f ->
            val enroller = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(f.root)) { f.now }
            val scope = f.enroll(enroller)
            // A reset confirmed while a foreign exit was already closing is rejected but keeps its intent.
            enroller.beginExit(requireNotNull(enroller.captureExitRequest()))
            f.store.write(f.frozenIntent(scope))

            val (restarted, resume) = f.restart()
            // Must not throw: the pending exit's own recovery completion runs later in this startup.
            resume.resumePending()

            assertEquals("enterprise_exit_in_progress", resume.progress.value?.failure)
            assertNotNull(f.store.read())
            assertEquals(EnterpriseSessionPhase.CLOSING,
                (restarted.state.value as EnterpriseState.Available).manifest.phase)

            // The pending-exit completion finishes the foreign CLOSING, then the retained intent converges.
            restarted.finishExit(requireNotNull(restarted.pendingExit()))
            resume.retryReset()

            assertNull(f.store.read())
            val manifest = (restarted.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertEquals(scope, manifest.lastIdentity?.scope)
            f.assertStoreClean()
        }
    }

    @Test fun `reset waits for a foreign exit and completes on retry`() = runTest {
        fixture { f ->
            val (sessions, exits, reset) = f.new()
            f.enroll(sessions)
            val request = requireNotNull(exits.captureRequest())
            sessions.beginExit(request)

            var error: EnterpriseConfigurationException? = null
            try { f.reset(reset, EnterpriseDataResetMode.KEEP_HISTORY); fail("Expected reset rejection") }
            catch (rejected: EnterpriseConfigurationException) { error = rejected }
            assertEquals("enterprise_exit_in_progress", error?.reason)
            // The durable intent stays so the user's confirmed choice can resume once the exit finishes.
            assertNotNull(f.store.read())

            // The foreign exit finishes through its own owner, then the retained intent converges on retry.
            sessions.finishExit(requireNotNull(sessions.pendingExit()))
            reset.retryReset()

            assertNull(f.store.read())
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (sessions.state.value as EnterpriseState.Available).manifest.phase)
            f.assertStoreClean()
        }
    }

    @Test fun `revocation and pending exit completion never adopt a local data reset closing token`() = runTest {
        fixture { f ->
            val (sessions, exits, _) = f.new()
            val scope = f.enroll(sessions)
            val access = requireNotNull((sessions.state.value as EnterpriseState.Available).manifest.session)
                .let { RealmAccess.Enterprise(scope, it.id) }
            sessions.beginLocalDataReset(requireNotNull(exits.captureRequest()))

            var invalidation: EnterpriseConfigurationException? = null
            try { sessions.beginInvalidation(access, EnterpriseExitReason.AUTHORIZATION_REVOKED); fail("Expected invalidation rejection") }
            catch (rejected: EnterpriseConfigurationException) { invalidation = rejected }
            assertEquals("enterprise_reset_in_progress", invalidation?.reason)
            var recovery: EnterpriseConfigurationException? = null
            try { exits.completeDuringRecovery(); fail("Expected recovery completion rejection") }
            catch (rejected: EnterpriseConfigurationException) { recovery = rejected }
            assertEquals("enterprise_reset_in_progress", recovery?.reason)
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.scope.coroutineContext[Job]?.cancel() }
    }

    private inner class Fixture {
        val root = temporary.newFolder()
        // The reset tests call the close barrier directly; keep the unrelated expiry observer on an
        // independent scheduler so runTest cannot auto-advance a multi-year platform Session to expiry.
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        var now = 1000L
        var logoutCalls = 0
        var frozenScopes: Set<ConfigurationScope.Enterprise> = emptySet()
        val store = EnterpriseDataResetStore(temporary.newFolder())
        val conversations = mockk<ConversationApplicationService>()
        val settings = mockk<SettingsStore>()
        val memories = mockk<MemoryRepository>()
        val catalogs = mockk<McpCatalogStore>()
        val files = mockk<FileManagementApplicationService>()

        init {
            coEvery { conversations.stopEnterpriseWork(any()) } returns Unit
            coEvery { conversations.requireEnterpriseStopped(any()) } returns Unit
            coEvery { conversations.clearEnterpriseScope(any()) } returns Unit
            coEvery { settings.clearEnterprisePreferences(any()) } returns Unit
            coEvery { memories.clearEnterpriseScope(any()) } returns Unit
            coEvery { catalogs.clearEnterpriseScope(any()) } returns Unit
            coEvery { files.clearEnterpriseScope(any()) } returns Unit
            // Scope freeze reads every owner's realms; the fixture keeps it explicit per test.
            coEvery { conversations.enterpriseScopes() } answers { frozenScopes }
            coEvery { files.enterpriseScopes() } returns emptySet()
            coEvery { memories.enterpriseScopes() } returns emptySet()
            coEvery { settings.enterpriseScopes() } answers { frozenScopes }
            coEvery { catalogs.enterpriseScopes() } returns emptySet()
        }

        /** A realm that only exists as leftover history, to prove the reset is not narrowed to one principal. */
        fun residualScope(): ConfigurationScope.Enterprise = ConfigurationScope.Enterprise(
            packetAuthority(), "residual-user",
        )

        /** The intent a confirmed-but-interrupted reset left on disk before its barrier completed. */
        fun frozenIntent(scope: ConfigurationScope.Enterprise) = EnterpriseDataResetIntent(
            schemaVersion = EnterpriseDataResetIntent.SCHEMA_VERSION,
            operationId = Uuid.random().toString(),
            mode = EnterpriseDataResetMode.KEEP_HISTORY,
            createdAtMillis = now,
            scopes = listOf(scope),
            stage = EnterpriseDataResetStage.FROZEN,
            completedOwners = emptyList(),
        )

        /** A fresh controller, exit service and reset service over the same durable directories. */
        fun new(): Triple<EnterpriseSessionController, EnterpriseExitService, EnterpriseDataResetService> {
            val sessions = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root)) { now }
            val gate = ApplicationRecoveryGate().apply { ready() }
            val exits = EnterpriseExitService(sessions,
                mockk { coEvery { cancelAndAwait(any()) } returns Unit }, conversations, gate, scope,
                net.weero.measix.pilot.service.portal.PortalDocumentRegistry(),
                mockk(relaxed = true), terminals = mockk { io.mockk.coEvery { closeRealm(any()) } returns Unit },
                mcp = mockk { io.mockk.coEvery { closeRealm(any()) } returns Unit }, speech = mockk(relaxed = true),
                platformLogout = { logoutCalls++ })
            val reset = EnterpriseDataResetService(store, sessions, exits, conversations, settings, memories,
                catalogs, files, gate) { now }
            return Triple(sessions, exits, reset)
        }

        /**
         * A fresh controller and reset service without an exit watcher: a startup resume never uses the
         * runtime domain stop, and the strict exit mock fails the test the moment it is called.
         */
        fun restart(): Pair<EnterpriseSessionController, EnterpriseDataResetService> {
            val sessions = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root)) { now }
            val reset = EnterpriseDataResetService(store, sessions, mockk(), conversations, settings,
                memories, catalogs, files, ApplicationRecoveryGate().apply { ready() }) { now }
            return sessions to reset
        }

        suspend fun enroll(sessions: EnterpriseSessionController): ConfigurationScope.Enterprise {
            sessions.enrollFixture(exampleEnterprisePackage())
            val scope = requireNotNull((sessions.state.value as EnterpriseState.Available).manifest.session).identity.scope
            frozenScopes = setOf(scope)
            return scope
        }

        suspend fun reset(service: EnterpriseDataResetService, mode: EnterpriseDataResetMode) {
            service.reset(EnterpriseDataResetRequest(Uuid.random(), mode))
        }

        fun assertStoreClean() {
            assertTrue("revisions", (File(root, "revisions").listFiles() ?: emptyArray()).isEmpty())
            assertTrue("credentials", (File(root, "credentials").listFiles() ?: emptyArray()).isEmpty())
        }

        private fun packetAuthority() = exampleEnterprisePackage().identity.authority
    }
}
