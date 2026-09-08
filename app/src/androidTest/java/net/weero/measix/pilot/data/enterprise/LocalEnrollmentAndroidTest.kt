package net.weero.measix.pilot.data.enterprise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.service.EnterpriseSynchronizationService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class LocalEnrollmentAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2029-01-01T00:00:00Z").toEpochMilli()

    @Test
    fun installedCatalogAndConsumedCodeSurviveActualAtomicFileReopen() = runBlocking {
        val root = File(context.noBackupFilesDir, "local-enrollment-test-${Uuid.random()}").apply { check(mkdirs()) }
        try {
            val clientRoot = File(root, "client")
            val authorityRoot = File(root, "authority")
            val controller = EnterpriseSessionController(EnterpriseAppliedStore(clientRoot)) { now }
            val source = source(controller, authorityRoot)
            val raw = source.exampleEnrollmentText()
            val material = EnrollmentMaterialParser().parse(raw) as EnrollmentMaterial.LocalExample
            val ready = source.enroll(raw)
            val fullPackage = context.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
            assertEquals(fullPackage.identity, ready.manifest.session!!.identity)
            assertEquals(fullPackage.configuration, ready.configuration)
            assertFalse(raw.contains("userId"))
            assertFalse(File(authorityRoot, "enrollments.json").readText().contains(material.code))
            val previousSource = source.candidate(fullPackage.identity.scope)!!
            val published = source.setPolicy(fullPackage.identity.scope, previousSource.revision,
                fullPackage.configuration.policy.copy(allowLocalMcp = false))
            assertEquals(ready, controller.state.value)
            val reopenedSource = source(controller, authorityRoot)
            assertEquals(published, reopenedSource.candidate(fullPackage.identity.scope))
            val synchronized = EnterpriseSynchronizationService(controller, reopenedSource, this)
                .synchronize(RealmAccess.Enterprise(fullPackage.identity.scope, ready.manifest.session.id))
            assertEquals(ready.manifest.session, synchronized.manifest.session)
            assertFalse(synchronized.configuration!!.policy.allowLocalMcp)
            controller.finishExit(controller.beginExit(requireNotNull(controller.captureExitRequest())))
            val reopened = EnterpriseSessionController(EnterpriseAppliedStore(clientRoot)) { now }
            reopened.recover()
            try { source(reopened, authorityRoot).enroll(raw); fail("consumed code cannot be reused") }
            catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_enrollment_consumed", error.reason) }
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (reopened.state.value as EnterpriseState.Available).manifest.phase)
            val reentered = source(reopened, authorityRoot).enrollExample()
            assertEquals(published.packet.configuration, reentered.configuration)
            reopened.finishExit(reopened.beginExit(requireNotNull(reopened.captureExitRequest())))
            val bob = fullPackage.copy(identity = fullPackage.identity.copy(userId = "bob"))
            val imported = source(reopened, authorityRoot).importPackage(EnterprisePackageCodec.encode(bob).inputStream())
            assertEquals(bob.identity, imported.applied!!.manifest.session!!.identity)
            reopened.finishExit(reopened.beginExit(requireNotNull(reopened.captureExitRequest())))
            val finalSource = source(reopened, authorityRoot)
            assertEquals(2, finalSource.installations().size)
            assertEquals(bob.identity, finalSource.enroll(finalSource.enrollmentText(bob.identity.scope)).manifest.session!!.identity)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun unavailableCapabilityAssetPublishesOnlyVerifiedPendingIdentity() = runBlocking {
        val root = File(context.noBackupFilesDir, "local-enrollment-test-${Uuid.random()}").apply { check(mkdirs()) }
        try {
            val clientRoot = File(root, "client")
            val controller = EnterpriseSessionController(EnterpriseAppliedStore(clientRoot)) { now }
            val source = LocalEnterpriseSource(
                openExample = { throw FileNotFoundException("capability asset unavailable") },
                openIdentity = { context.assets.open(LocalEnterpriseSource.IDENTITY_ASSET) },
                sessions = controller, enrollmentAuthority = LocalEnrollmentAuthority(File(root, "authority"), { now }), nowMillis = { now },
                configurations = LocalEnterpriseConfigurationStore(File(root, "authority")),
            )
            val pending = source.enrollExample()
            assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, pending.manifest.phase)
            assertNotNull(pending.manifest.session)
            assertNull(pending.manifest.applied)
            assertNull(pending.configuration)
            assertEquals(pending, EnterpriseSessionController(EnterpriseAppliedStore(clientRoot)) { now }.recover())
        } finally { root.deleteRecursively() }
    }

    private fun source(controller: EnterpriseSessionController, authorityRoot: File) = LocalEnterpriseSource(
        openExample = { context.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET) },
        openIdentity = { context.assets.open(LocalEnterpriseSource.IDENTITY_ASSET) },
        sessions = controller, enrollmentAuthority = LocalEnrollmentAuthority(authorityRoot, { now }), nowMillis = { now },
        configurations = LocalEnterpriseConfigurationStore(authorityRoot),
    )
}
