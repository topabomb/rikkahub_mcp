package net.weero.measix.pilot.data.enterprise

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import kotlin.uuid.Uuid

@Serializable
internal enum class EnterpriseSessionPhase { SIGNED_OUT, CONFIGURATION_PENDING, READY, OFFLINE, CLOSING, REAUTH_REQUIRED }

@Serializable
internal data class EnterpriseSession(
    val id: String,
    val identity: EnterpriseIdentity,
    val expiresAtMillis: Long,
)

@Serializable
internal data class EnterpriseAppliedVersion(
    val revision: String,
    val generation: Long,
    val configurationHash: String,
    val bindingsHash: String,
)

@Serializable
internal data class EnterpriseFeedVersion(
    val scope: ConfigurationScope.Enterprise,
    val revision: String,
    val publicRevision: Long,
    val hash: String,
)

@Serializable
private data class StoredEnterpriseFeed(val scope: ConfigurationScope.Enterprise, val document: EnterpriseFeedDocument)

/** The manifest is the only durable publication point for identity, definitions, and bindings. */
@Serializable
internal data class EnterpriseManifest(
    val schemaVersion: Int,
    val phase: EnterpriseSessionPhase,
    val session: EnterpriseSession?,
    val applied: EnterpriseAppliedVersion?,
    val selectedScope: ConfigurationScope,
    val lastIdentity: EnterpriseIdentity?,
    val feeds: List<EnterpriseFeedVersion> = emptyList(),
) {
    companion object {
        fun signedOut(identity: EnterpriseIdentity? = null, feeds: List<EnterpriseFeedVersion> = emptyList()) = EnterpriseManifest(
            2, EnterpriseSessionPhase.SIGNED_OUT, null, null, ConfigurationScope.Personal, identity, feeds,
        )
    }
}

@Serializable
private data class StoredEnterpriseConfiguration(
    val revision: String,
    val identity: EnterpriseIdentity,
    val configuration: EnterpriseConfiguration,
)

@Serializable
private data class StoredEnterpriseBindings(val revision: String, val bindings: List<EnterpriseRuntimeBinding>)

internal data class LoadedEnterpriseState(val manifest: EnterpriseManifest, val configuration: EnterpriseConfiguration?)

internal enum class EnterpriseStorageCheckpoint { CONFIGURATION_STAGED, BINDINGS_STAGED, FEED_STAGED, BEFORE_MANIFEST_COMMIT, MANIFEST_WRITTEN }

internal class EnterpriseStorageException(val reason: String) : IOException(reason)

/** Files live under noBackupFilesDir; only the session owner calls this single-writer store. */
internal class EnterpriseAppliedStore(
    private val root: File,
    private val checkpoint: (EnterpriseStorageCheckpoint) -> Unit = {},
) {
    private val json get() = EnterprisePackageCodec.json
    private val manifestFile get() = AtomicFile(File(root, "manifest.json"))
    private val revisions get() = File(root, "revisions")
    private val feedRevisions get() = File(root, "feed-revisions")

    fun load(): LoadedEnterpriseState {
        val manifest = readManifest()
        val packageValue = manifest.applied?.let { readPackage(manifest, it) }
        return LoadedEnterpriseState(manifest, packageValue?.configuration)
    }

    fun readManifest(): EnterpriseManifest {
        val manifest = if (manifestFile.baseFile.exists() || File(root, "manifest.json.bak").exists()) {
            decode<EnterpriseManifest>(manifestFile.readFully())
        } else {
            EnterpriseManifest.signedOut()
        }
        validateManifest(manifest)
        return manifest
    }

    fun prepare(value: EnterprisePackage): EnterpriseAppliedVersion {
        EnterprisePackageCodec.validate(value)
        val revision = Uuid.random().toString()
        val directory = revisionDirectory(revision)
        if (!directory.mkdirs()) throw EnterpriseStorageException("enterprise_staging_directory_failed")
        val configuration = json.encodeToString(StoredEnterpriseConfiguration(revision, value.identity, value.configuration)).toByteArray()
        val bindings = json.encodeToString(StoredEnterpriseBindings(revision, value.runtimeBindings)).toByteArray()
        writeSynced(File(directory, "configuration.json"), configuration)
        checkpoint(EnterpriseStorageCheckpoint.CONFIGURATION_STAGED)
        writeSynced(File(directory, "bindings.json"), bindings)
        checkpoint(EnterpriseStorageCheckpoint.BINDINGS_STAGED)
        return EnterpriseAppliedVersion(revision, value.configuration.generation, hash(configuration), hash(bindings))
    }

    fun commit(manifest: EnterpriseManifest) {
        validateManifest(manifest)
        manifest.applied?.let { readPackage(manifest, it) }
        val previousFeeds = readManifest().feeds.associateBy { it.scope }
        manifest.feeds.filter { previousFeeds[it.scope] != it }.forEach(::readFeed)
        if (!root.isDirectory && !root.mkdirs()) throw EnterpriseStorageException("enterprise_store_directory_failed")
        val bytes = json.encodeToString(manifest).toByteArray()
        checkpoint(EnterpriseStorageCheckpoint.BEFORE_MANIFEST_COMMIT)
        val atomic = manifestFile
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            checkpoint(EnterpriseStorageCheckpoint.MANIFEST_WRITTEN)
            // AtomicFile logs sync/rename errors instead of throwing; verify its commit explicitly.
            stream.fd.sync()
            atomic.finishWrite(stream)
            if (!readBounded(atomic.baseFile).contentEquals(bytes)) {
                throw EnterpriseStorageException("enterprise_manifest_commit_failed")
            }
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun bindings(manifest: EnterpriseManifest): List<EnterpriseRuntimeBinding> {
        val version = manifest.applied ?: throw EnterpriseStorageException("enterprise_configuration_not_ready")
        return readPackage(manifest, version).runtimeBindings
    }

    fun prepareFeed(scope: ConfigurationScope.Enterprise, document: EnterpriseFeedDocument): EnterpriseFeedVersion {
        EnterpriseFeed.validate(document)
        val revision = Uuid.random().toString()
        val directory = File(feedRevisions, revision)
        if (!directory.mkdirs()) throw EnterpriseStorageException("enterprise_feed_staging_failed")
        val bytes = json.encodeToString(StoredEnterpriseFeed(scope, document)).toByteArray(Charsets.UTF_8)
        writeSynced(File(directory, "feed.json"), bytes)
        checkpoint(EnterpriseStorageCheckpoint.FEED_STAGED)
        return EnterpriseFeedVersion(scope, revision, document.publicRevision, hash(bytes))
    }

    fun readFeed(version: EnterpriseFeedVersion): EnterpriseFeedDocument {
        validateFeedVersion(version)
        val bytes = readBounded(File(File(feedRevisions, version.revision), "feed.json"))
        if (hash(bytes) != version.hash) throw EnterpriseStorageException("enterprise_feed_hash_mismatch")
        val stored = decode<StoredEnterpriseFeed>(bytes)
        if (stored.scope != version.scope || stored.document.publicRevision != version.publicRevision) {
            throw EnterpriseStorageException("enterprise_feed_identity_mismatch")
        }
        return stored.document.also(EnterpriseFeed::validate)
    }

    /** Keep the active revision and every in-flight lease; uncommitted staging has no authority. */
    fun prune(retainedRevisions: Set<String>, retainedFeedRevisions: Set<String>) {
        pruneDirectory(revisions, retainedRevisions)
        pruneDirectory(feedRevisions, retainedFeedRevisions)
    }

    private fun pruneDirectory(parent: File, retainedRevisions: Set<String>) {
        if (!parent.exists()) return
        val children = parent.listFiles() ?: throw EnterpriseStorageException("enterprise_revision_listing_failed")
        children.forEach { child ->
            if (child.name in retainedRevisions || !isRevision(child.name)) return@forEach
            if (child.canonicalFile.parentFile != parent.canonicalFile || Files.isSymbolicLink(child.toPath())) {
                throw EnterpriseStorageException("enterprise_revision_path_invalid")
            }
            Files.walk(child.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }

    private fun readPackage(manifest: EnterpriseManifest, version: EnterpriseAppliedVersion): EnterprisePackage {
        val directory = revisionDirectory(version.revision)
        val configurationBytes = readBounded(File(directory, "configuration.json"))
        val bindingsBytes = readBounded(File(directory, "bindings.json"))
        if (hash(configurationBytes) != version.configurationHash || hash(bindingsBytes) != version.bindingsHash) {
            throw EnterpriseStorageException("enterprise_revision_hash_mismatch")
        }
        val public = decode<StoredEnterpriseConfiguration>(configurationBytes)
        val private = decode<StoredEnterpriseBindings>(bindingsBytes)
        if (public.revision != version.revision || private.revision != version.revision ||
            public.identity != manifest.session?.identity || public.configuration.generation != version.generation) {
            throw EnterpriseStorageException("enterprise_revision_identity_mismatch")
        }
        return EnterprisePackage(EnterprisePackageCodec.FORMAT_VERSION, public.identity, public.configuration, private.bindings)
            .also(EnterprisePackageCodec::validate)
    }

    private fun validateManifest(manifest: EnterpriseManifest) {
        if (manifest.schemaVersion != 2) throw EnterpriseStorageException("unsupported_enterprise_manifest")
        if (manifest.feeds.map { it.scope }.distinct().size != manifest.feeds.size) throw EnterpriseStorageException("duplicate_enterprise_feed")
        manifest.feeds.forEach(::validateFeedVersion)
        manifest.session?.let { session ->
            EnterprisePackageCodec.validateIdentity(session.identity)
            if (!isRevision(session.id) || session.expiresAtMillis <= 0 || !session.identity.authority.isLocal) {
                throw EnterpriseStorageException("invalid_enterprise_session")
            }
        }
        manifest.lastIdentity?.let(EnterprisePackageCodec::validateIdentity)
        manifest.applied?.let { version ->
            if (!isRevision(version.revision) || version.generation <= 0 ||
                !version.configurationHash.matches(Regex("[0-9a-f]{64}")) || !version.bindingsHash.matches(Regex("[0-9a-f]{64}"))) {
                throw EnterpriseStorageException("invalid_enterprise_revision")
            }
        }
        val valid = when (manifest.phase) {
            EnterpriseSessionPhase.SIGNED_OUT, EnterpriseSessionPhase.REAUTH_REQUIRED ->
                manifest.session == null && manifest.applied == null && manifest.selectedScope == ConfigurationScope.Personal
            EnterpriseSessionPhase.CONFIGURATION_PENDING ->
                manifest.session != null && manifest.applied == null && manifest.selectedScope == ConfigurationScope.Personal
            EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE ->
                manifest.session != null && manifest.applied != null &&
                    (manifest.selectedScope == ConfigurationScope.Personal || manifest.selectedScope == manifest.session.identity.scope)
            EnterpriseSessionPhase.CLOSING -> manifest.session != null && manifest.selectedScope == ConfigurationScope.Personal
        }
        if (!valid) throw EnterpriseStorageException("inconsistent_enterprise_manifest")
    }

    private fun revisionDirectory(revision: String): File {
        if (!isRevision(revision)) throw EnterpriseStorageException("invalid_enterprise_revision")
        return File(revisions, revision)
    }

    private fun validateFeedVersion(version: EnterpriseFeedVersion) {
        if (!version.scope.authority.isLocal || !isRevision(version.revision) || version.publicRevision < 0 ||
            !version.hash.matches(Regex("[0-9a-f]{64}"))) throw EnterpriseStorageException("invalid_enterprise_feed_version")
    }

    private fun isRevision(value: String): Boolean = runCatching { Uuid.parse(value).toString() == value }.getOrDefault(false)

    private fun readBounded(file: File): ByteArray {
        if (!file.isFile || file.length() > EnterprisePackageCodec.MAX_BYTES) throw EnterpriseStorageException("enterprise_revision_unreadable")
        return file.inputStream().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > EnterprisePackageCodec.MAX_BYTES) throw EnterpriseStorageException("enterprise_revision_too_large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        if (bytes.size > EnterprisePackageCodec.MAX_BYTES) throw EnterpriseStorageException("enterprise_revision_too_large")
        FileOutputStream(file).use { stream -> stream.write(bytes); stream.fd.sync() }
    }

    private inline fun <reified T> decode(bytes: ByteArray): T = try {
        json.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
    } catch (_: IllegalArgumentException) {
        throw EnterpriseStorageException("invalid_enterprise_storage")
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
