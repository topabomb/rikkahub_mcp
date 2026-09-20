package net.weero.measix.pilot.data.enterprise

import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformAuthenticationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cipher = EnterpriseCredentialCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/cases.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        .first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject.getValue("value").toString()
    private fun controller(root: File) = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root, credentialCipher = cipher)) { 1000L }
    private val connection get() = PlatformConnection("http://192.168.1.20:8080", PlatformWireCodec.decode(fixture("discovery")))
    private val response get() = PlatformWireCodec.decode<PlatformEnrollmentExchangeResponse>(fixture("enrollment-response"))

    @Test fun `exchanged code survives restart before Bootstrap without plaintext or durable access token`() = runBlocking {
        val root = temporary.newFolder()
        val owner = controller(root)
        val attempt = owner.beginPlatformEnrollment()
        val id = owner.acceptPlatformEnrollment(attempt, connection, response)
        assertNotNull(owner.platformAccessToken(id))
        val restored = controller(root)
        restored.recover()
        assertEquals(id, restored.pendingPlatformEnrollment()?.sessionId)
        assertNull(restored.platformAccessToken(id))
        val text = root.walkTopDown().filter { it.isFile }.joinToString { it.readBytes().toString(Charsets.UTF_8) }
        assertFalse(text.contains(response.refreshToken))
        assertFalse(text.contains(response.accessToken))
        assertEquals(attempt.installationId, net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root, credentialCipher = cipher).installationId())
    }

    @Test fun `local data reset removes the prior installation binding identity`() {
        val root = temporary.newFolder()
        val store = net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root, credentialCipher = cipher)
        val previous = store.installationId()

        store.resetLocalState(null)

        assertFalse(File(root, "installation-id").exists())
        assertNotEquals(previous, store.installationId())
    }

    @Test fun `refresh recovery retains the same old credential and key across owner recreation`() = runBlocking {
        val root = temporary.newFolder()
        val owner = controller(root)
        val id = owner.acceptPlatformEnrollment(owner.beginPlatformEnrollment(), connection, response)
        val first = owner.beginPlatformRefresh(id)
        val restored = controller(root)
        restored.recover()
        val recovery = restored.beginPlatformRefresh(id)
        assertEquals(first, recovery)
        assertNotNull(recovery.credential.pendingIdempotencyKey)
        val next = PlatformWireCodec.decode<PlatformRefreshResponse>(fixture("refresh-response"))
        restored.acceptPlatformRefresh(recovery, next)
        val another = restored.beginPlatformRefresh(id)
        assertEquals(next.refreshToken, another.credential.refreshToken)
        assertNotEquals(recovery.credential.pendingIdempotencyKey, another.credential.pendingIdempotencyKey)
        try { restored.acceptPlatformRefresh(recovery, next); fail("stale refresh committed") }
        catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_refresh_replaced", error.reason) }
    }

    @Test fun `Bootstrap validates principal before publishing identity and keeps credential ownership`() = runBlocking {
        val root = temporary.newFolder()
        val owner = controller(root)
        val id = owner.acceptPlatformEnrollment(owner.beginPlatformEnrollment(), connection, response)
        val bootstrap = PlatformWireCodec.decode<PlatformBootstrap>(fixture("bootstrap"))
        val wrong = bootstrap.copy(user = bootstrap.user.copy(userId = "usr_12345678-1234-4234-8234-123456789012"))
        try { owner.completePlatformBootstrap(id, wrong); fail("wrong principal published") }
        catch (error: EnterpriseConfigurationException) { assertEquals("platform_bootstrap_identity_mismatch", error.reason) }
        assertNotNull(owner.pendingPlatformEnrollment())
        val access = owner.completePlatformBootstrap(id, bootstrap)
        assertEquals(response.userId, access.scope.userId)
        assertNull(owner.pendingPlatformEnrollment())
        val state = controller(root).recover() as EnterpriseState.Available
        assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, state.manifest.phase)
        assertNotNull(state.manifest.session?.platform)
        assertEquals(bootstrap.user.displayName, state.manifest.session?.identity?.userName)
    }

    @Test fun `accepted enrollment after identity deletion resumes Bootstrap across restart`() = runBlocking {
        val root = temporary.newFolder()
        val original = controller(root)
        val packet = exampleEnterprisePackage()
        original.enrollFixture(packet)
        val retired = original.captureRealmAccess(packet.identity.scope) as RealmAccess.Enterprise
        original.finishExit(original.beginInvalidation(retired, EnterpriseExitReason.IDENTITY_DELETED))

        val freshUserId = "usr_00000000-0000-4000-8000-000000000099"
        val exchanged = response.copy(userId = freshUserId)
        val sessionId = original.acceptPlatformEnrollment(
            original.beginPlatformEnrollment(),
            connection,
            exchanged,
        )
        val pendingManifest = (original.state.value as EnterpriseState.Available).manifest
        assertNotNull(pendingManifest.pendingEnrollment)
        assertEquals(EnterpriseExitReason.IDENTITY_DELETED, pendingManifest.exitReason)

        val restored = controller(root)
        restored.recover()
        val baseBootstrap = PlatformWireCodec.decode<PlatformBootstrap>(fixture("bootstrap"))
        val bootstrap = baseBootstrap.copy(
            user = baseBootstrap.user.copy(userId = freshUserId, displayName = "Recreated member"),
            session = baseBootstrap.session.copy(sessionId = sessionId),
        )
        val access = restored.completePlatformBootstrap(sessionId, bootstrap)

        val manifest = (restored.state.value as EnterpriseState.Available).manifest
        assertEquals(freshUserId, access.scope.userId)
        assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, manifest.phase)
        assertNull(manifest.exitReason)
        assertEquals(freshUserId, manifest.session?.identity?.userId)
        assertNull(manifest.pendingEnrollment)
    }

    @Test fun `ciphertext is bound to its immutable revision and tampering is rejected`() {
        val encrypted = cipher.encrypt("secret".toByteArray(), "first")
        assertEquals("secret", cipher.decrypt(encrypted, "first").toString(Charsets.UTF_8))
        assertThrows(java.security.GeneralSecurityException::class.java) { cipher.decrypt(encrypted, "other") }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(java.security.GeneralSecurityException::class.java) { cipher.decrypt(encrypted, "first") }
    }
}
