package net.weero.measix.pilot.data.enterprise

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal data class LocalEnterpriseCandidate(val revision: String, val packet: EnterprisePackage)

@Serializable
internal data class LocalEnterpriseInstallation(val identity: EnterpriseIdentity, val revision: String?, val generation: Long?)

@Serializable
private data class LocalEnterpriseDirectory(val schemaVersion: Int, val installations: List<LocalEnterpriseInstallation>)

/** Source identity and publication only. LocalEnterpriseSource serializes all directory access. */
internal class LocalEnterpriseConfigurationStore(
    private val root: File,
    private val beforeCommit: () -> Unit = {},
) {
    private val directory get() = AtomicFile(File(root, "directory.json"))
    private val revisions get() = File(root, "configurations")
    private val json get() = EnterprisePackageCodec.json

    fun installations(): List<LocalEnterpriseInstallation>? {
        val atomic = directory
        if (!atomic.baseFile.exists() && !File(root, "directory.json.bak").exists() &&
            !File(root, "directory.json.new").exists()) return null
        return try {
            val bytes = atomic.openRead().use(EnterprisePackageCodec::readBytes)
            val text = bytes.toString(Charsets.UTF_8)
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) fail("local_enterprise_directory_invalid")
            json.decodeFromString<LocalEnterpriseDirectory>(text).also { value ->
                if (value.schemaVersion != 1 || value.installations.map { it.identity.scope }.distinct().size != value.installations.size) {
                    fail("local_enterprise_directory_invalid")
                }
                value.installations.forEach { installation ->
                    EnterprisePackageCodec.validateIdentity(installation.identity)
                    if ((installation.revision == null) != (installation.generation == null) || installation.generation?.let { it <= 0 } == true) {
                        fail("local_enterprise_directory_invalid")
                    }
                    installation.revision?.let { requireRevision(it) }
                }
            }.installations
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            fail("local_enterprise_directory_invalid")
        }
    }

    fun read(installation: LocalEnterpriseInstallation): LocalEnterpriseCandidate? {
        val revision = installation.revision ?: return null
        requireRevision(revision)
        return try {
            val bytes = File(revisions, "$revision.json").inputStream().use(EnterprisePackageCodec::readBytes)
            if (hash(bytes) != revision) fail("local_enterprise_configuration_invalid")
            val packet = EnterprisePackageCodec.decode(bytes)
            if (packet.identity != installation.identity || packet.configuration.generation != installation.generation) fail("local_enterprise_configuration_invalid")
            LocalEnterpriseCandidate(revision, packet)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            fail("local_enterprise_configuration_invalid")
        }
    }

    fun initialize(identity: EnterpriseIdentity, packet: EnterprisePackage?) {
        check(installations() == null)
        EnterprisePackageCodec.validateIdentity(identity)
        if (packet != null && packet.identity != identity) fail("enterprise_enrollment_identity_mismatch")
        val revision = packet?.let(::stage)
        commit(listOf(LocalEnterpriseInstallation(identity, revision, packet?.configuration?.generation)))
    }

    fun publish(packet: EnterprisePackage, expectedRevision: String?, imported: Boolean = false): LocalEnterpriseCandidate {
        val all = installations() ?: fail("local_enterprise_directory_unavailable")
        val previous = all.find { it.identity.scope == packet.identity.scope }
        if (previous?.revision != expectedRevision) fail("local_enterprise_configuration_changed")
        previous?.generation?.let { if (packet.configuration.generation < it) fail("enterprise_generation_regression") }
        val previousPacket = try { previous?.let(::read)?.packet }
        catch (error: EnterpriseConfigurationException) {
            if (!imported || previous?.generation?.let { packet.configuration.generation <= it } != false) throw error
            null
        }
        previousPacket?.let { old ->
            if (packet.configuration.generation == old.configuration.generation && packet.configuration != old.configuration) {
                fail("enterprise_generation_conflict")
            }
        }
        prune(all)
        val revision = stage(packet)
        val next = LocalEnterpriseInstallation(packet.identity, revision, packet.configuration.generation)
        read(next)
        commit(all.filterNot { it.identity.scope == packet.identity.scope } + next)
        return LocalEnterpriseCandidate(revision, packet)
    }

    private fun stage(packet: EnterprisePackage): String {
        val bytes = EnterprisePackageCodec.encode(packet)
        val revision = hash(bytes)
        if (!revisions.isDirectory && !revisions.mkdirs()) fail("local_enterprise_configuration_write_failed")
        write(AtomicFile(File(revisions, "$revision.json")), bytes)
        return revision
    }

    private fun commit(installations: List<LocalEnterpriseInstallation>) {
        val bytes = json.encodeToString(LocalEnterpriseDirectory(1, installations)).toByteArray(Charsets.UTF_8)
        if (bytes.size > EnterprisePackageCodec.MAX_BYTES) fail("local_enterprise_directory_full")
        beforeCommit()
        write(directory, bytes)
    }

    private fun write(atomic: AtomicFile, bytes: ByteArray) {
        if (!root.isDirectory && !root.mkdirs()) fail("local_enterprise_configuration_write_failed")
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
            if (!atomic.baseFile.inputStream().use(EnterprisePackageCodec::readBytes).contentEquals(bytes)) {
                fail("local_enterprise_configuration_write_failed")
            }
        } catch (error: Exception) {
            atomic.failWrite(stream)
            if (error is CancellationException) throw error
            fail("local_enterprise_configuration_write_failed")
        }
    }

    private fun prune(all: List<LocalEnterpriseInstallation>) {
        val retained = all.mapNotNull { it.revision }.toSet()
        if (!revisions.exists()) return
        val files = revisions.listFiles() ?: fail("local_enterprise_configuration_read_failed")
        files.forEach { file ->
            val name = file.name.removeSuffix(".json")
            if (file.name != "$name.json" || !REVISION.matches(name) || name in retained) return@forEach
            if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != revisions.canonicalFile || !file.delete()) {
                fail("local_enterprise_configuration_cleanup_failed")
            }
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun requireRevision(value: String) { if (!REVISION.matches(value)) fail("local_enterprise_directory_invalid") }
    private fun fail(reason: String): Nothing = throw EnterpriseConfigurationException(reason)

    companion object { private val REVISION = Regex("[0-9a-f]{64}") }
}
