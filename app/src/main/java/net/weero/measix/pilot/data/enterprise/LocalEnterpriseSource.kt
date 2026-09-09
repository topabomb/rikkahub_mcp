package net.weero.measix.pilot.data.enterprise

import java.io.InputStream
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.EnterprisePolicy

internal data class LocalEnterpriseImportResult(val sourceRevision: String, val applied: EnterpriseState.Available?, val failureReason: String?)

/** Owns installed local sources. Short enrollment materials can only reference existing installations. */
internal class LocalEnterpriseSource(
    private val openExample: () -> InputStream,
    private val sessions: EnterpriseSessionController,
    private val enrollmentAuthority: LocalEnrollmentAuthority,
    private val openIdentity: () -> InputStream,
    private val configurations: LocalEnterpriseConfigurationStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val parser = EnrollmentMaterialParser()
    private val mutex = Mutex()

    suspend fun exampleEnrollmentText(): String = enrollmentText(bundledIdentity().scope)

    suspend fun enrollmentText(scope: ConfigurationScope.Enterprise): String =
        EnrollmentMaterialParser.encodeLocal(enrollmentAuthority.issue(installed(scope).identity))

    suspend fun installations(): List<LocalEnterpriseInstallation> = mutex.withLock { ensureInstalled() }

    /** One-click, pasted text and a decoded QR all enter through this parser and source validator. */
    suspend fun enroll(text: String): EnterpriseState.Available {
        val material = parser.parse(text)
        requireEnrollmentNotExpired(material, Instant.ofEpochMilli(nowMillis()))
        if (material is EnrollmentMaterial.Platform) fail("platform_enrollment_not_supported")
        material as EnrollmentMaterial.LocalExample
        val directory = installations()
        requireInstalledEnrollmentSource(material, directory.map { it.identity.authority.sourceNamespace }.toSet())
        if (directory.none { it.identity.authority.deploymentId == material.deploymentId &&
                it.identity.authority.sourceNamespace == material.sourceNamespace }) fail("unknown_local_enterprise_source")
        val identity = enrollmentAuthority.resolveIdentity(material)
        if (directory.none { it.identity == identity }) fail("unknown_local_enterprise_source")
        return sessions.enrollLocal(
            installedIdentity = identity,
            redeem = { enrollmentAuthority.redeem(material, identity) },
            configuration = {
                try { candidate(identity.scope)?.packet }
                catch (_: EnterpriseConfigurationException) { null }
                catch (_: IOException) { null }
            },
        )
    }

    suspend fun enrollExample(): EnterpriseState.Available = enroll(exampleEnrollmentText())

    suspend fun setPolicy(scope: ConfigurationScope.Enterprise, expectedRevision: String, policy: EnterprisePolicy): LocalEnterpriseCandidate =
        changeConfiguration(scope, expectedRevision) { it.copy(configuration = it.configuration.copy(policy = policy)) }

    suspend fun candidate(scope: ConfigurationScope.Enterprise): LocalEnterpriseCandidate? = mutex.withLock {
        val installation = ensureInstalled().find { it.identity.scope == scope } ?: fail("unknown_local_enterprise_source")
        withContext(Dispatchers.IO) { configurations.read(installation) }
    }

    suspend fun changeConfiguration(scope: ConfigurationScope.Enterprise, expectedRevision: String,
        transform: (EnterprisePackage) -> EnterprisePackage): LocalEnterpriseCandidate = mutex.withLock {
        val installation = ensureInstalled().find { it.identity.scope == scope } ?: fail("unknown_local_enterprise_source")
        val previous = withContext(Dispatchers.IO) { configurations.read(installation) } ?: fail("enterprise_configuration_not_ready")
        if (previous.revision != expectedRevision) fail("local_enterprise_configuration_changed")
        val generation = previous.packet.configuration.generation
        if (generation == Long.MAX_VALUE) fail("enterprise_generation_exhausted")
        val changed = transform(previous.packet)
        if (changed.identity != previous.packet.identity) fail("enterprise_principal_mismatch")
        publish(changed.copy(configuration = changed.configuration.copy(generation = generation + 1)), expectedRevision)
    }

    /** System file picker owns and closes the stream; full private packages never enter the enrollment parser. */
    suspend fun importPackage(selection: RealmSelection, input: InputStream): LocalEnterpriseImportResult {
        val packet = withContext(Dispatchers.IO) { EnterprisePackageCodec.decode(input) }
        val revision = installations().find { it.identity.scope == packet.identity.scope }?.revision
        return sessions.importLocal(selection, packet.identity) {
            mutex.withLock { publish(packet, revision, imported = true) }
        }
    }

    private suspend fun installed(scope: ConfigurationScope.Enterprise): LocalEnterpriseInstallation =
        installations().find { it.identity.scope == scope } ?: fail("unknown_local_enterprise_source")

    private suspend fun publish(packet: EnterprisePackage, expectedRevision: String?, imported: Boolean = false): LocalEnterpriseCandidate {
        EnterprisePackageCodec.validate(packet)
        LocalEnterpriseMcpSurface.validate(packet)
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO + NonCancellable) { configurations.publish(packet, expectedRevision, imported) }
    }

    private suspend fun ensureInstalled(): List<LocalEnterpriseInstallation> {
        withContext(Dispatchers.IO) { configurations.installations() }?.let { return it }
        val identity = bundledIdentity()
        val packet = try {
            withContext(Dispatchers.IO) { openExample().use(EnterprisePackageCodec::decode).also(LocalEnterpriseMcpSurface::validate).takeIf { it.identity == identity } }
        } catch (_: EnterpriseConfigurationException) { null }
        catch (_: IOException) { null }
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO + NonCancellable) {
            configurations.initialize(identity, packet)
            requireNotNull(configurations.installations())
        }
    }

    /** The bundle initializes identity independently of the capability document. Later reads use the directory. */
    private suspend fun bundledIdentity(): EnterpriseIdentity = withContext(Dispatchers.IO) {
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
