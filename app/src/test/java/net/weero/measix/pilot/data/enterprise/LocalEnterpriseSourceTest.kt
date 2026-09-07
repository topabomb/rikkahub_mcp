package net.weero.measix.pilot.data.enterprise

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.common.configuration.EnterpriseAuthority
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
class LocalEnterpriseSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = Instant.parse("2029-01-01T00:00:00Z").toEpochMilli()

    @Test
    fun `one click paste and actual QR decoding enter the same complete local enterprise`() = runTest {
        val one = harness().source.enrollExample()
        val pastedHarness = harness()
        val pastedText = pastedHarness.source.exampleEnrollmentText()
        val pasted = pastedHarness.source.enroll(" \n$pastedText\n")
        val scannedHarness = harness()
        val scannedText = scannedHarness.source.exampleEnrollmentText()
        val matrix = QRCodeWriter().encode(scannedText, BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) -0x1000000 else -1 }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))).text
        assertEquals(scannedText, decoded)
        val scanned = scannedHarness.source.enroll(decoded)
        listOf(pasted, scanned).forEach {
            assertEquals(one.configuration, it.configuration)
            assertEquals(one.manifest.session!!.identity, it.manifest.session!!.identity)
            assertEquals(EnterpriseSessionPhase.READY, it.manifest.phase)
        }
    }

    @Test
    fun `platform fixture is explicitly unsupported without loading any local source or publishing identity`() = runTest {
        val h = harness()
        var opened = false
        val source = LocalEnterpriseSource({ opened = true; error("must not load a local source") }, h.sessions, h.authority,
            { opened = true; error("must not load local identity") }) { now }
        val platform = requireNotNull(javaClass.getResourceAsStream("/contracts/enrollment/platform-v1.json")).bufferedReader().use { it.readText() }
        rejected("platform_enrollment_not_supported") { source.enroll(platform) }
        assertFalse(opened)
        assertSignedOut(h)
        assertFalse(File(h.authorityRoot, "enrollments.json").exists())
        val localIdentity = packet().identity
        val platformScope = ConfigurationScope.Enterprise(EnterpriseAuthority("platform:example", localIdentity.authority.deploymentId), localIdentity.userId)
        assertNotEquals(localIdentity.scope, platformScope)
    }

    @Test
    fun `unknown source bad credential and old fields leave no binding and do not consume the valid code`() = runTest {
        val h = harness()
        val raw = h.source.exampleEnrollmentText()
        val good = EnrollmentMaterialParser().parse(raw) as EnrollmentMaterial.LocalExample
        rejected("unknown_local_enterprise_source") { h.source.enroll(EnrollmentMaterialParser.encodeLocal(good.copy(sourceNamespace = "platform:example"))) }
        rejected("unknown_local_enterprise_source") { h.source.enroll(EnrollmentMaterialParser.encodeLocal(good.copy(deploymentId = "other"))) }
        rejected("enterprise_enrollment_rejected") { h.source.enroll(EnrollmentMaterialParser.encodeLocal(good.copy(code = "wrong"))) }
        rejected("invalid_enterprise_enrollment_fields") { h.source.enroll(raw.dropLast(1) + ",\"userId\":\"other\"}") }
        assertSignedOut(h)
        assertEquals(EnterpriseSessionPhase.READY, h.source.enroll(raw).manifest.phase)
    }

    @Test
    fun `authority expiry cannot be extended by changing document time`() = runTest {
        val h = harness()
        val good = EnrollmentMaterialParser().parse(h.source.exampleEnrollmentText()) as EnrollmentMaterial.LocalExample
        now = good.expiresAt.toEpochMilli()
        rejected("enterprise_enrollment_expired") { h.source.enroll(EnrollmentMaterialParser.encodeLocal(good)) }
        val forged = good.copy(expiresAt = good.expiresAt.plusSeconds(3600))
        rejected("enterprise_enrollment_expired") { h.source.enroll(EnrollmentMaterialParser.encodeLocal(forged)) }
        assertSignedOut(h)
    }

    @Test
    fun `successful consumption survives logout and authority reopening and stores no plaintext code`() = runTest {
        val h = harness()
        val raw = h.source.exampleEnrollmentText()
        val code = EnrollmentMaterialParser().parse(raw).code
        h.source.enroll(raw)
        assertFalse(File(h.authorityRoot, "enrollments.json").readText().contains(code))
        h.sessions.finishExit(requireNotNull(h.sessions.beginExit()))
        val reopened = LocalEnterpriseSource({ exampleBytes().inputStream() }, EnterpriseSessionController(EnterpriseAppliedStore(h.clientRoot)) { now },
            LocalEnrollmentAuthority(h.authorityRoot, { now }), ::identityStream, { now })
        rejected("enterprise_enrollment_consumed") { reopened.enroll(raw) }
        assertEquals(EnterpriseSessionPhase.READY, reopened.enrollExample().manifest.phase)
    }

    @Test
    fun `concurrent exchange has one durable winner`() = runTest {
        val h = harness()
        val raw = h.source.exampleEnrollmentText()
        val otherSessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { now }
        val otherSource = LocalEnterpriseSource({ exampleBytes().inputStream() }, otherSessions, h.authority, ::identityStream) { now }
        val outcomes = listOf(h.source, otherSource).map { source -> async { runCatching { source.enroll(raw) } } }.awaitAll()
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals("enterprise_enrollment_consumed", (outcomes.single { it.isFailure }.exceptionOrNull() as EnterpriseConfigurationException).reason)
    }

    @Test
    fun `different principal and closing session are refused before consumption`() = runTest {
        val h = harness()
        h.sessions.registerIdentity(packet().identity.copy(userId = "bob"))
        val raw = h.source.exampleEnrollmentText()
        rejected("exit_current_enterprise_first") { h.source.enroll(raw) }
        val exit = requireNotNull(h.sessions.beginExit())
        rejected("enterprise_exit_in_progress") { h.source.enroll(raw) }
        h.sessions.finishExit(exit)
        assertEquals(EnterpriseSessionPhase.READY, h.source.enroll(raw).manifest.phase)
    }

    @Test
    fun `authority write failure does not consume code or publish client state`() = runTest {
        var failWrite = false
        val h = harness(authorityCheckpoint = { if (failWrite) error("injected authority disk failure") })
        val raw = h.source.exampleEnrollmentText()
        failWrite = true
        rejected("local_enrollment_store_write_failed") { h.source.enroll(raw) }
        assertSignedOut(h)
        failWrite = false
        assertEquals(EnterpriseSessionPhase.READY, h.source.enroll(raw).manifest.phase)
    }

    @Test
    fun `client commit failure preserves old binding without restoring an already consumed code`() = runTest {
        var failWrite = false
        val h = harness(clientCheckpoint = { if (failWrite && it == EnterpriseStorageCheckpoint.BEFORE_MANIFEST_COMMIT) error("injected client failure") })
        val previous = h.source.enrollExample()
        val raw = h.source.exampleEnrollmentText()
        failWrite = true
        try { h.source.enroll(raw); fail("commit should fail") } catch (_: IllegalStateException) { }
        assertEquals(previous, h.sessions.state.value)
        assertEquals(previous.manifest, EnterpriseAppliedStore(h.clientRoot).readManifest())
        failWrite = false
        rejected("enterprise_enrollment_consumed") { h.source.enroll(raw) }
        assertNotEquals(previous.manifest.session!!.id, h.source.enrollExample().manifest.session!!.id)
    }

    @Test
    fun `verified installed identity with missing or invalid capability configuration is pending not ready`() = runTest {
        val root = EnterprisePackageCodec.json.parseToJsonElement(exampleBytes().toString(Charsets.UTF_8)).jsonObject
        val badDocuments = listOf(
            JsonObject(root - "configuration"),
            JsonObject(root + ("configuration" to JsonObject(emptyMap()))),
        )
        for (bad in badDocuments) {
            val h = harness(bytes = bad.toString().toByteArray())
            val state = h.source.enrollExample()
            assertEquals(packet().identity, state.manifest.session!!.identity)
            assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, state.manifest.phase)
            assertNull(state.configuration)
            assertNull(state.manifest.applied)
            assertEquals(ConfigurationScope.Personal, state.manifest.selectedScope)
            assertEquals(state.manifest, EnterpriseAppliedStore(h.clientRoot).readManifest())
        }
    }

    @Test
    fun `corrupt authority ledger is not treated as an empty issuer`() = runTest {
        val h = harness()
        val raw = h.source.exampleEnrollmentText()
        File(h.authorityRoot, "enrollments.json").writeText("broken")
        rejected("local_enrollment_store_invalid") { h.source.enroll(raw) }
        rejected("local_enrollment_store_invalid") { h.source.exampleEnrollmentText() }
        assertSignedOut(h)
    }

    @Test
    fun `missing or truncated configuration still uses the independently installed identity`() = runTest {
        for (open in listOf<() -> java.io.InputStream>(
            { throw FileNotFoundException("configuration not installed") },
            { "{broken".byteInputStream() },
        )) {
            val h = harness()
            val source = LocalEnterpriseSource(open, h.sessions, h.authority, ::identityStream) { now }
            val result = source.enrollExample()
            assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, result.manifest.phase)
            assertEquals(packet().identity, result.manifest.session!!.identity)
        }
    }

    @Test
    fun `new enrollment retains this principals newer applied policy instead of rolling back to the bundled example`() = runTest {
        val h = harness()
        val first = h.source.enrollExample()
        val updated = h.source.setPolicy(first.manifest.applied!!.revision, first.configuration!!.policy.copy(allowLocalProviders = false))
        val renewed = h.source.enrollExample()
        assertEquals(updated.configuration, renewed.configuration)
        assertEquals(updated.manifest.applied, renewed.manifest.applied)
        assertNotEquals(updated.manifest.session!!.id, renewed.manifest.session!!.id)
        assertEquals(EnterpriseSessionPhase.READY, renewed.manifest.phase)
    }

    @Test
    fun `cancellation after authority consumption cannot revive code and client owned commit completes durably`() = runTest {
        for (pauseInClient in listOf(false, true)) {
            var pause = false
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            fun awaitRelease() { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) }
            val h = harness(
                authorityCheckpoint = { if (pause && !pauseInClient) awaitRelease() },
                clientCheckpoint = { if (pause && pauseInClient && it == EnterpriseStorageCheckpoint.MANIFEST_WRITTEN) awaitRelease() },
            )
            val previous = h.source.enrollExample()
            val raw = h.source.exampleEnrollmentText()
            pause = true
            val job = launch(Dispatchers.Default) { h.source.enroll(raw) }
            try {
                entered.await()
                job.cancel()
            } finally { release.countDown() }
            job.join()
            assertTrue(job.isCancelled)
            pause = false
            val durable = EnterpriseAppliedStore(h.clientRoot).readManifest()
            if (pauseInClient) assertNotEquals(previous.manifest.session!!.id, durable.session!!.id)
            else assertEquals(previous.manifest, durable)
            assertEquals(durable, (h.sessions.state.value as EnterpriseState.Available).manifest)
            val reopenedSource = LocalEnterpriseSource({ exampleBytes().inputStream() }, h.sessions,
                LocalEnrollmentAuthority(h.authorityRoot, { now }), ::identityStream) { now }
            rejected("enterprise_enrollment_consumed") { reopenedSource.enroll(raw) }
        }
    }

    @Test
    fun `full private package is rejected as enrollment but stays supported by the independent import`() = runTest {
        val h = harness()
        try { h.source.enroll(exampleBytes().toString(Charsets.UTF_8)); fail("full package cannot enroll") }
        catch (_: EnterpriseConfigurationException) { }
        assertSignedOut(h)
        val imported = h.source.importPackage(exampleBytes().inputStream())
        assertEquals(packet().configuration, imported.configuration)
        assertEquals(packet().identity, imported.manifest.session!!.identity)
    }

    private data class Harness(val clientRoot: File, val authorityRoot: File, val sessions: EnterpriseSessionController,
        val authority: LocalEnrollmentAuthority, val source: LocalEnterpriseSource)

    private fun harness(bytes: ByteArray = exampleBytes(), authorityCheckpoint: () -> Unit = {},
        clientCheckpoint: (EnterpriseStorageCheckpoint) -> Unit = {}): Harness {
        val clientRoot = temporary.newFolder()
        val authorityRoot = temporary.newFolder()
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(clientRoot, clientCheckpoint)) { now }
        val authority = LocalEnrollmentAuthority(authorityRoot, { now }, authorityCheckpoint)
        return Harness(clientRoot, authorityRoot, sessions, authority, LocalEnterpriseSource({ bytes.inputStream() }, sessions, authority, ::identityStream) { now })
    }

    private fun exampleBytes() = requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.EXAMPLE_ASSET}")).use { it.readBytes() }
    private fun identityStream() = requireNotNull(javaClass.getResourceAsStream("/${LocalEnterpriseSource.IDENTITY_ASSET}"))
    private fun packet() = EnterprisePackageCodec.decode(exampleBytes())
    private fun assertSignedOut(h: Harness) = assertEquals(EnterpriseSessionPhase.SIGNED_OUT, EnterpriseAppliedStore(h.clientRoot).readManifest().phase)
    private suspend fun rejected(reason: String, action: suspend () -> Any?) {
        try { action(); fail("expected $reason") } catch (error: EnterpriseConfigurationException) { assertEquals(reason, error.reason) }
    }
}
