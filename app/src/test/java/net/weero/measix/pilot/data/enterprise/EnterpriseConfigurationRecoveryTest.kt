package net.weero.measix.pilot.data.enterprise

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseConfigurationRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `retired local identity cannot acquire a platform session and personal recovery remains available`() = runTest {
        val root = temporary.newFolder()
        val originalManifest = """{"schemaVersion":3,"phase":"READY","identity":{"sourceNamespace":"local:example"}}"""
        File(root, "manifest.json").writeText(originalManifest)
        val originalPayload = File(root, "old-local-payload").apply { writeText("kept local source") }
        val controller = EnterpriseSessionController(enterpriseTestStore(root))
        assertTrue(controller.recover() is EnterpriseState.Failed)
        assertTrue(controller.withRealmAccess(RealmAccess.Personal) { true })
        assertEquals(originalManifest, File(root, "manifest.json").readText())
        assertEquals("kept local source", originalPayload.readText())
        val packet = exampleEnterprisePackage()
        val retired = me.rerere.common.configuration.RetiredLocalEnterpriseIdentity.encode("local:example", packet.identity.authority.deploymentId)
        val identity = packet.identity.copy(authority = me.rerere.common.configuration.EnterpriseAuthority(retired))
        val error = assertThrows(EnterpriseConfigurationException::class.java) {
            EnterpriseConfigurationCodec.validateIdentity(identity)
        }
        assertEquals("retired_local_enterprise_identity", error.reason)
        val fresh = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()))
        try {
            fresh.enrollFixture(packet.copy(identity = identity))
            fail("Retired principal must not acquire a Session")
        } catch (error: IllegalArgumentException) {
            // Platform DTO validation rejects the reserved wire spelling before enrollment starts.
            // The direct domain check above independently protects non-wire publication boundaries.
            assertEquals("invalid_platform_Discovery_deploymentId", error.message)
        }
        assertNull((fresh.recover() as EnterpriseState.Available).manifest.session)
        assertTrue(fresh.withRealmAccess(RealmAccess.Personal) { true })
    }

    @Test
    fun `unreadable configuration preserves identity data access navigation and local exit`() = runTest {
        val root = temporary.newFolder()
        val original = EnterpriseSessionController(enterpriseTestStore(root))
            .enrollFixture(exampleEnterprisePackage())
        File(root, "revisions/${original.manifest.applied!!.revision}/configuration.json").writeText("broken")

        val controller = EnterpriseSessionController(enterpriseTestStore(root))
        val recovered = controller.recover()
        assertTrue("Configuration failure must not discard identity: $recovered", recovered is EnterpriseState.Available)
        recovered as EnterpriseState.Available
        assertEquals(original.manifest, recovered.manifest)
        assertNull(recovered.configuration)
        assertNotNull(recovered.configurationError)
        val access = controller.captureSelectedRealmAccess() as RealmAccess.Enterprise
        assertEquals("history", controller.withRealmAccess(access) { "history" })
        controller.selectPersonalFixture()
        assertTrue(controller.withRealmAccess(RealmAccess.Personal) { true })
        controller.switchRealm(RealmSwitchRequest(requireNotNull(controller.readPresentation().selection), access)) {}
        assertEquals(access, controller.captureSelectedRealmAccess())
        val refresh = controller.beginPlatformRefresh(access.sessionId)
        controller.acceptPlatformRefresh(refresh, PlatformRefreshResponse("new-access", "2099-01-01T00:00:00Z",
            "new-refresh", "2099-01-08T00:00:00Z", "2099-01-01T00:00:00Z"))
        assertEquals(original.manifest.applied, (controller.state.value as EnterpriseState.Available).manifest.applied)
        controller.finishExit(controller.beginExit(requireNotNull(controller.captureExitRequest())))
        assertEquals(RealmAccess.Personal, controller.captureSelectedRealmAccess())
        assertNull((controller.state.value as EnterpriseState.Available).manifest.session)
    }

    @Test
    fun `full synchronization repairs an unreadable configuration at the same generation`() = runTest {
        val root = temporary.newFolder()
        val packet = exampleEnterprisePackage()
        val original = EnterpriseSessionController(enterpriseTestStore(root)).enrollFixture(packet)
        File(root, "revisions/${original.manifest.applied!!.revision}/configuration.json").writeText("broken")
        val controller = EnterpriseSessionController(enterpriseTestStore(root))
        controller.recover()
        val access = controller.captureSelectedRealmAccess() as RealmAccess.Enterprise
        assertNull(controller.platformConfiguration(access).candidate)
        val repaired = controller.synchronize(access, packet.toCandidate())
        assertEquals(packet.configuration, repaired.configuration)
        assertNull(repaired.configurationError)
        assertEquals(original.manifest.session, repaired.manifest.session)
        assertEquals(original.manifest.applied.generation, repaired.manifest.applied!!.generation)
        assertNotEquals(original.manifest.applied.revision, repaired.manifest.applied.revision)
        assertEquals(packet.toCandidate(), controller.platformConfiguration(access).candidate)
    }

    @Test
    fun `synchronization detects corruption without restarting and retains verified release conflicts`() = runTest {
        val root = temporary.newFolder()
        val controller = EnterpriseSessionController(enterpriseTestStore(root))
        val packet = exampleEnterprisePackage()
        val original = controller.enrollFixture(packet)
        File(root, "revisions/${original.manifest.applied!!.revision}/configuration.json").writeText("broken")
        val access = controller.captureSelectedRealmAccess() as RealmAccess.Enterprise
        assertNull(controller.platformConfiguration(access).candidate)
        val candidate = packet.toCandidate()
        val execution = candidate.execution as EnterpriseExecution.Platform
        try {
            controller.synchronize(access, candidate.copy(execution = execution.copy(snapshotHash = "sha256:${"0".repeat(64)}")))
            fail("A verifiable release conflict must not be treated as configuration repair")
        } catch (error: EnterpriseConfigurationException) {
            assertEquals("enterprise_generation_conflict", error.reason)
        }
        assertEquals(original.manifest.applied, (controller.state.value as EnterpriseState.Available).manifest.applied)
        assertEquals(candidate.configuration, controller.synchronize(access, candidate).configuration)
    }

    @Test
    fun `readable canonical cache does not authorize an unsupported or unverified wire version`() = runTest {
        for (version in listOf(6L, 7L, null)) {
            val root = temporary.newFolder()
            val store = enterpriseTestStore(root)
            val packet = exampleEnterprisePackage()
            val original = EnterpriseSessionController(store).enrollFixture(packet)
            val candidate = packet.toCandidate().let {
                it.copy(execution = (it.execution as EnterpriseExecution.Platform).copy(snapshotSchemaVersion = version))
            }
            val manifest = original.manifest.copy(applied = store.prepare(candidate))
            store.commit(manifest)
            val controller = EnterpriseSessionController(store)
            assertTrue(controller.recover() is EnterpriseState.Available)
            val access = controller.captureSelectedRealmAccess() as RealmAccess.Enterprise
            assertTrue(controller.withRealmAccess(access) { true })
            val presentation = controller.readPresentation()
            assertEquals(version, presentation.publication?.schemaVersion)
            assertEquals((candidate.execution as EnterpriseExecution.Platform).releaseId, presentation.publication?.releaseId)
            assertEquals((candidate.execution as EnterpriseExecution.Platform).snapshotHash, presentation.publication?.snapshotHash)
            assertNull(presentation.publicationFailure)
            val attempts: List<suspend () -> Unit> = listOf(
                { controller.readExecution(access) { _, _ -> Unit } },
                { controller.confirmPlatformExecution(access, candidate) },
                { controller.captureExecution(access, manifest.applied).release() },
            )
            for (attempt in attempts) {
                try {
                    attempt()
                    fail("Cached version $version authorized execution")
                } catch (error: EnterpriseConfigurationException) {
                    if (version != null) assertTrue(error is EnterpriseSnapshotCompatibilityException)
                    else assertEquals("enterprise_snapshot_version_unverified", error.reason)
                }
            }
            assertNotNull(controller.synchronize(access, packet.toCandidate()).configuration)
            controller.readExecution(access) { _, execution ->
                assertEquals(5L, (execution as EnterpriseExecution.Platform).snapshotSchemaVersion)
            }
        }
    }

    @Test
    fun `new corrupted revision cannot be published through an identity update`() = runTest {
        val root = temporary.newFolder()
        val store = enterpriseTestStore(root)
        val candidate = exampleEnterprisePackage().toCandidate()
        val original = EnterpriseSessionController(store).enrollFixture(exampleEnterprisePackage()).manifest
        val staged = store.prepare(candidate)
        File(root, "revisions/${staged.revision}/configuration.json").writeText("broken")
        assertThrows(EnterpriseStorageException::class.java) {
            store.commit(original.copy(applied = staged), retainedApplied = original.applied)
        }
        assertEquals(original, store.readManifest())
        assertEquals(candidate.configuration, store.load().configuration)
    }
}
