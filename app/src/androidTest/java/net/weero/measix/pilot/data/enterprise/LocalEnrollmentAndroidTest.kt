package net.weero.measix.pilot.data.enterprise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import kotlinx.coroutines.runBlocking
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
            controller.finishExit(requireNotNull(controller.beginExit()))
            val reopened = EnterpriseSessionController(EnterpriseAppliedStore(clientRoot)) { now }
            reopened.recover()
            try { source(reopened, authorityRoot).enroll(raw); fail("consumed code cannot be reused") }
            catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_enrollment_consumed", error.reason) }
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (reopened.state.value as EnterpriseState.Available).manifest.phase)
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
    )
}
