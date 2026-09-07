package net.weero.measix.pilot.data.enterprise

import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.weero.measix.pilot.data.configuration.EnterprisePolicy

/** Public example enrollment material simulates validation; it is never a production credential. */
@Serializable
internal data class LocalEnterpriseEnrollment(
    val formatVersion: Int,
    val sourceNamespace: String,
    val deploymentId: String,
    val userId: String,
    val enrollmentCode: String,
    val expiresAtMillis: Long,
)

/** Supplies external enterprise inputs. Applied state and session authority stay in their owner. */
internal class LocalEnterpriseSource(
    private val openExample: () -> InputStream,
    private val sessions: EnterpriseSessionController,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun exampleEnrollmentText(): String {
        val example = readExample()
        return EnterprisePackageCodec.json.encodeToString(
            LocalEnterpriseEnrollment(
                1, example.identity.authority.sourceNamespace, example.identity.authority.deploymentId,
                example.identity.userId, EXAMPLE_CODE, nowMillis() + ENROLLMENT_LIFETIME_MILLIS,
            ),
        )
    }

    /** One-click, pasted text and a decoded QR all enter through this validator. */
    suspend fun enroll(text: String): EnterpriseState.Available {
        if (text.length > MAX_ENROLLMENT_CHARS) fail("enterprise_enrollment_too_large")
        val material = try {
            EnterprisePackageCodec.json.decodeFromString<LocalEnterpriseEnrollment>(text)
        } catch (_: IllegalArgumentException) {
            fail("invalid_enterprise_enrollment")
        }
        if (material.formatVersion != 1) fail("unsupported_enterprise_enrollment_version")
        val now = nowMillis()
        if (material.expiresAtMillis <= now || material.expiresAtMillis - now > ENROLLMENT_LIFETIME_MILLIS) {
            fail("enterprise_enrollment_expired")
        }
        val example = readExample()
        val identity = example.identity
        if (material.sourceNamespace != identity.authority.sourceNamespace || material.deploymentId != identity.authority.deploymentId ||
            material.userId != identity.userId || material.enrollmentCode != EXAMPLE_CODE) fail("enterprise_enrollment_rejected")
        return sessions.applyPackage(EnterprisePackageCodec.encode(example))
    }

    suspend fun enrollExample(): EnterpriseState.Available = enroll(exampleEnrollmentText())

    suspend fun setPolicy(expectedRevision: String, policy: EnterprisePolicy): EnterpriseState.Available =
        sessions.updateLocalPackage(expectedRevision) { it.copy(configuration = it.configuration.copy(policy = policy)) }

    /** System file picker owns and closes the stream; private input never becomes an asset. */
    suspend fun importPackage(input: InputStream): EnterpriseState.Available {
        val packet = withContext(Dispatchers.IO) { EnterprisePackageCodec.decode(input) }
        return sessions.applyPackage(EnterprisePackageCodec.encode(packet))
    }

    private suspend fun readExample(): EnterprisePackage = withContext(Dispatchers.IO) {
        openExample().use(EnterprisePackageCodec::decode)
    }

    private fun fail(reason: String): Nothing = throw EnterpriseConfigurationException(reason)

    companion object {
        const val EXAMPLE_ASSET = "enterprise.local.example.json"
        private const val EXAMPLE_CODE = "measix-public-example"
        private const val MAX_ENROLLMENT_CHARS = 4096
        private const val ENROLLMENT_LIFETIME_MILLIS = 10L * 60 * 1000
    }
}
