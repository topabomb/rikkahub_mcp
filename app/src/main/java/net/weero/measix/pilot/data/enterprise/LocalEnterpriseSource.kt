package net.weero.measix.pilot.data.enterprise

import java.io.InputStream
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.configuration.EnterprisePolicy

/** Supplies installed local service inputs. No input text can register a source or start network enrollment. */
internal class LocalEnterpriseSource(
    private val openExample: () -> InputStream,
    private val sessions: EnterpriseSessionController,
    private val enrollmentAuthority: LocalEnrollmentAuthority,
    private val openIdentity: () -> InputStream,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val parser = EnrollmentMaterialParser()

    suspend fun exampleEnrollmentText(): String =
        EnrollmentMaterialParser.encodeLocal(enrollmentAuthority.issue(installedIdentity()))

    /** One-click, pasted text and a decoded QR all enter through this parser and source validator. */
    suspend fun enroll(text: String): EnterpriseState.Available {
        val material = parser.parse(text)
        requireEnrollmentNotExpired(material, Instant.ofEpochMilli(nowMillis()))
        if (material is EnrollmentMaterial.Platform) fail("platform_enrollment_not_supported")
        material as EnrollmentMaterial.LocalExample
        val identity = installedIdentity()
        requireInstalledEnrollmentSource(material, setOf(identity.authority.sourceNamespace))
        if (material.deploymentId != identity.authority.deploymentId) fail("unknown_local_enterprise_source")
        return sessions.enrollLocal(
            installedIdentity = identity,
            redeem = { enrollmentAuthority.redeem(material, identity) },
            configuration = {
                try { withContext(Dispatchers.IO) { openExample().use(EnterprisePackageCodec::decode).takeIf { it.identity == identity } } }
                catch (_: EnterpriseConfigurationException) { null }
                catch (_: IOException) { null }
            },
        )
    }

    suspend fun enrollExample(): EnterpriseState.Available = enroll(exampleEnrollmentText())

    suspend fun setPolicy(expectedRevision: String, policy: EnterprisePolicy): EnterpriseState.Available =
        sessions.updateLocalPackage(expectedRevision) { it.copy(configuration = it.configuration.copy(policy = policy)) }

    /** System file picker owns and closes the stream; full private packages never enter the enrollment parser. */
    suspend fun importPackage(input: InputStream): EnterpriseState.Available {
        val packet = withContext(Dispatchers.IO) { EnterprisePackageCodec.decode(input) }
        return sessions.applyPackage(EnterprisePackageCodec.encode(packet))
    }

    /** Identity belongs to the installed source, so an unavailable capability document does not erase an authenticated principal. */
    private suspend fun installedIdentity(): EnterpriseIdentity = withContext(Dispatchers.IO) {
        val bytes = openIdentity().use(EnterprisePackageCodec::readBytes)
        try {
            val text = bytes.toString(Charsets.UTF_8)
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) fail("invalid_installed_enterprise_source")
            EnterprisePackageCodec.json.decodeFromString<EnterpriseIdentity>(text)
                .also(EnterprisePackageCodec::validateIdentity)
        } catch (_: IllegalArgumentException) {
            fail("invalid_installed_enterprise_source")
        }
    }

    private fun fail(reason: String): Nothing = throw EnterpriseConfigurationException(reason)

    companion object {
        const val EXAMPLE_ASSET = "enterprise.local.example.json"
        const val IDENTITY_ASSET = "enterprise.local.identity.json"
    }
}

internal fun requireEnrollmentNotExpired(material: EnrollmentMaterial, now: Instant) {
    if (material.expiresAt <= now) throw EnterpriseConfigurationException("enterprise_enrollment_expired")
}

internal fun requireInstalledEnrollmentSource(material: EnrollmentMaterial.LocalExample, installedSources: Set<String>) {
    if (material.sourceNamespace !in installedSources) throw EnterpriseConfigurationException("unknown_local_enterprise_source")
}
