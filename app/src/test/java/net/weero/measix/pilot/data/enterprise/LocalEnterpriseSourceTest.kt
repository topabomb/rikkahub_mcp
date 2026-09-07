package net.weero.measix.pilot.data.enterprise

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalEnterpriseSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `one click and parsed enrollment material apply identical complete packages`() = runTest {
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        val source = source(controller)
        val text = source.exampleEnrollmentText()
        assertTrue(text.length < 4096)
        val pasted = source.enroll(text)
        controller.finishExit(requireNotNull(controller.beginExit()))
        val oneClick = source.enrollExample()
        assertEquals(pasted.configuration, oneClick.configuration)
        assertEquals(pasted.manifest.session!!.identity, oneClick.manifest.session!!.identity)
        assertEquals(EnterpriseSessionPhase.READY, oneClick.manifest.phase)
    }

    @Test
    fun `wrong or expired material never leaves a partial binding`() = runTest {
        val controller = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder()))
        controller.recover()
        var now = 1000L
        val source = source(controller) { now }
        val good = EnterprisePackageCodec.json.decodeFromString<LocalEnterpriseEnrollment>(source.exampleEnrollmentText())
        val invalid = listOf(
            good.copy(sourceNamespace = "platform:example"),
            good.copy(enrollmentCode = "wrong"),
            good.copy(userId = "someone_else"),
            good.copy(formatVersion = 2),
        )
        for (material in invalid) {
            try {
                source.enroll(EnterprisePackageCodec.json.encodeToString(material))
                fail("Enrollment should have failed")
            } catch (_: EnterpriseConfigurationException) { }
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (controller.state.value as EnterpriseState.Available).manifest.phase)
        }
        now = good.expiresAtMillis
        try {
            source.enroll(EnterprisePackageCodec.json.encodeToString(good))
            fail("Expired enrollment should have failed")
        } catch (error: EnterpriseConfigurationException) {
            assertEquals("enterprise_enrollment_expired", error.reason)
        }
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (controller.state.value as EnterpriseState.Available).manifest.phase)
    }

    private fun source(controller: EnterpriseSessionController, now: () -> Long = { 1000L }) = LocalEnterpriseSource(
        openExample = { requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.EXAMPLE_ASSET}")) },
        sessions = controller,
        nowMillis = now,
    )
}
