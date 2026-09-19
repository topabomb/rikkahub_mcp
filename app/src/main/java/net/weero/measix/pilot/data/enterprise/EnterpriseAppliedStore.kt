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
import me.rerere.ai.provider.ChatTransportCapabilities

@Serializable
internal enum class EnterpriseSessionPhase { SIGNED_OUT, CONFIGURATION_PENDING, READY, OFFLINE, CLOSING, REAUTH_REQUIRED }

@Serializable
internal enum class EnterpriseExitReason { USER_REQUEST, AUTHORIZATION_EXPIRED, AUTHORIZATION_REVOKED, CLEAR_EXAMPLE_DATA }

internal const val ENTERPRISE_MANIFEST_SCHEMA_VERSION = 4

@Serializable
internal data class EnterpriseSession(
    val id: String,
    val identity: EnterpriseIdentity,
    val expiresAtMillis: Long,
    val platform: PlatformSessionDetails? = null,
)

@Serializable
internal data class EnterpriseAppliedVersion(
    val revision: String,
    val generation: Long,
    val configurationHash: String,
    val executionHash: String,
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

/** The manifest is the only durable publication point for identity, definitions, and execution inputs. */
@Serializable
internal data class EnterpriseManifest(
    val schemaVersion: Int,
    val phase: EnterpriseSessionPhase,
    val session: EnterpriseSession?,
    val applied: EnterpriseAppliedVersion?,
    val selectedScope: ConfigurationScope,
    val lastIdentity: EnterpriseIdentity?,
    val feeds: List<EnterpriseFeedVersion> = emptyList(),
    val lastConfigurationSyncMillis: Long? = null,
    val exitReason: EnterpriseExitReason? = null,
    val pendingEnrollment: PendingPlatformEnrollment? = null,
) {
    companion object {
        fun signedOut(identity: EnterpriseIdentity? = null, feeds: List<EnterpriseFeedVersion> = emptyList()) = EnterpriseManifest(
            ENTERPRISE_MANIFEST_SCHEMA_VERSION, EnterpriseSessionPhase.SIGNED_OUT, null, null, ConfigurationScope.Personal, identity, feeds,
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
private data class StoredEnterpriseExecution(val revision: String, val execution: EnterpriseExecution)

internal data class LoadedEnterpriseState(
    val manifest: EnterpriseManifest,
    val configuration: EnterpriseConfiguration?,
    val modelCapabilities: Map<String, ChatTransportCapabilities> = emptyMap(),
) {
    fun toAvailable() = EnterpriseState.Available(manifest, configuration, modelCapabilities)
}

private fun EnterpriseCandidate?.loaded(manifest: EnterpriseManifest) = LoadedEnterpriseState(
    manifest, this?.configuration, this?.modelCapabilities().orEmpty(),
)

internal enum class EnterpriseStorageCheckpoint {
    CONFIGURATION_STAGED, EXECUTION_STAGED, FEED_STAGED, BEFORE_MANIFEST_COMMIT, MANIFEST_WRITTEN,
    EXECUTION_READ, BEFORE_REVISION_PRUNE,
}

internal class EnterpriseStorageException(val reason: String) : IOException(reason)

/** Files live under noBackupFilesDir; only the session owner calls this single-writer store. */
internal class EnterpriseAppliedStore(
    private val root: File,
    private val credentialCipher: EnterpriseCredentialCipher = EnterpriseCredentialCipher(),
    private val checkpoint: (EnterpriseStorageCheckpoint) -> Unit = {},
) {
    private val json get() = EnterprisePackageCodec.json
    private val manifestFile get() = AtomicFile(File(root, "manifest.json"))
    private val revisions get() = File(root, "revisions")
    private val feedRevisions get() = File(root, "feed-revisions")
    private val credentialRevisions get() = File(root, "credentials")

    fun installationId(): String {
        val file = AtomicFile(File(root, "installation-id"))
        if (file.baseFile.exists() || File(root, "installation-id.bak").exists()) {
            return file.readFully().toString(Charsets.UTF_8).also {
                require(it.matches(Regex("ins_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))) { "invalid_installation_identity" }
            }
        }
        if (!root.isDirectory && !root.mkdirs()) throw EnterpriseStorageException("enterprise_store_directory_failed")
        val id = "ins_${Uuid.random()}"
        val bytes = id.toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
            if (!file.readFully().contentEquals(bytes)) throw EnterpriseStorageException("installation_identity_commit_failed")
        } catch (error: Exception) { file.failWrite(stream); throw error }
        return id
    }

    fun prepareCredential(value: PlatformRefreshCredential): EnterpriseCredentialVersion {
        val revision = Uuid.random().toString()
        val folder = File(credentialRevisions, revision)
        if (!folder.mkdirs()) throw EnterpriseStorageException("enterprise_credential_staging_failed")
        val plain = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        val encrypted = try { credentialCipher.encrypt(plain, revision) } finally { plain.fill(0) }
        writeSynced(File(folder, "credential.bin"), encrypted)
        return EnterpriseCredentialVersion(revision, hash(encrypted))
    }

    fun credential(version: EnterpriseCredentialVersion, sessionId: String): PlatformRefreshCredential {
        if (!isRevision(version.revision)) throw EnterpriseStorageException("invalid_credential_revision")
        val bytes = readBounded(File(File(credentialRevisions, version.revision), "credential.bin"))
        if (hash(bytes) != version.hash) throw EnterpriseStorageException("enterprise_credential_hash_mismatch")
        val plain = credentialCipher.decrypt(bytes, version.revision)
        return try { decode<PlatformRefreshCredential>(plain) } finally { plain.fill(0) }.also {
            require(it.sessionId == sessionId && it.refreshToken.isNotBlank() && it.refreshExpiresAtMillis > 0) { "invalid_platform_credential" }
        }
    }

    fun load(): LoadedEnterpriseState {
        val manifest = readManifest()
        manifest.session?.platform?.let { credential(it.credential, requireNotNull(manifest.session).id) }
        manifest.pendingEnrollment?.let { credential(it.platform.credential, it.sessionId) }
        val candidate = manifest.applied?.let { readCandidate(manifest, it) }
        return candidate.loaded(manifest)
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

    fun prepare(value: EnterpriseCandidate): EnterpriseAppliedVersion {
        value.validate()
        val revision = Uuid.random().toString()
        val directory = revisionDirectory(revision)
        if (!directory.mkdirs()) throw EnterpriseStorageException("enterprise_staging_directory_failed")
        val configuration = json.encodeToString(StoredEnterpriseConfiguration(revision, value.identity, value.configuration)).toByteArray()
        val executionBytes = json.encodeToString(StoredEnterpriseExecution(revision, value.execution)).toByteArray()
        writeSynced(File(directory, "configuration.json"), configuration)
        checkpoint(EnterpriseStorageCheckpoint.CONFIGURATION_STAGED)
        writeSynced(File(directory, "execution.json"), executionBytes)
        checkpoint(EnterpriseStorageCheckpoint.EXECUTION_STAGED)
        return EnterpriseAppliedVersion(revision, value.configuration.generation, hash(configuration), hash(executionBytes))
    }

    fun commit(manifest: EnterpriseManifest): LoadedEnterpriseState {
        validateManifest(manifest)
        manifest.session?.platform?.let { credential(it.credential, requireNotNull(manifest.session).id) }
        manifest.pendingEnrollment?.let { credential(it.platform.credential, it.sessionId) }
        val candidate = manifest.applied?.let { readCandidate(manifest, it) }
        val previousFeeds = readManifest().feeds.associateBy { it.scope }
        manifest.feeds.filter { previousFeeds[it.scope] != it }.forEach(::readFeed)
        writeManifest(manifest)
        return candidate.loaded(manifest)
    }

    private fun writeManifest(manifest: EnterpriseManifest) {
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

    fun execution(manifest: EnterpriseManifest): EnterpriseExecution =
        (appliedCandidate(manifest) ?: throw EnterpriseStorageException("enterprise_configuration_not_ready")).execution

    fun appliedCandidate(manifest: EnterpriseManifest): EnterpriseCandidate? =
        manifest.applied?.let { readCandidate(manifest, it).also { checkpoint(EnterpriseStorageCheckpoint.EXECUTION_READ) } }

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
        checkpoint(EnterpriseStorageCheckpoint.BEFORE_REVISION_PRUNE)
        pruneDirectory(revisions, retainedRevisions)
        pruneDirectory(feedRevisions, retainedFeedRevisions)
        val manifest = readManifest()
        val credentials = listOfNotNull(manifest.session?.platform?.credential?.revision, manifest.pendingEnrollment?.platform?.credential?.revision).toSet()
        pruneDirectory(credentialRevisions, credentials)
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

    private fun readCandidate(manifest: EnterpriseManifest, version: EnterpriseAppliedVersion): EnterpriseCandidate {
        val directory = revisionDirectory(version.revision)
        val configurationBytes = readBounded(File(directory, "configuration.json"))
        val executionBytes = readBounded(File(directory, "execution.json"))
        if (hash(configurationBytes) != version.configurationHash || hash(executionBytes) != version.executionHash) {
            throw EnterpriseStorageException("enterprise_revision_hash_mismatch")
        }
        val public = decode<StoredEnterpriseConfiguration>(configurationBytes)
        val private = decode<StoredEnterpriseExecution>(executionBytes)
        if (public.revision != version.revision || private.revision != version.revision ||
            public.identity != manifest.session?.identity || public.configuration.generation != version.generation) {
            throw EnterpriseStorageException("enterprise_revision_identity_mismatch")
        }
        return EnterpriseCandidate(public.identity, public.configuration, private.execution).also(EnterpriseCandidate::validate)
    }

    private fun validateManifest(manifest: EnterpriseManifest) {
        if (manifest.schemaVersion != ENTERPRISE_MANIFEST_SCHEMA_VERSION) throw EnterpriseStorageException("unsupported_enterprise_manifest")
        manifest.pendingEnrollment?.let {
            require(manifest.session == null && manifest.phase == EnterpriseSessionPhase.SIGNED_OUT && it.expiresAtMillis > 0) { "inconsistent_pending_platform_enrollment" }
        }
        manifest.session?.platform?.let {
            require(it.connection.authority == manifest.session.identity.authority) { "platform_session_authority_mismatch" }
        }
        if ((manifest.phase == EnterpriseSessionPhase.CLOSING) != (manifest.exitReason != null)) {
            throw EnterpriseStorageException("inconsistent_enterprise_exit_reason")
        }
        if (manifest.lastConfigurationSyncMillis?.let { it < 0 || (manifest.applied == null && manifest.session?.platform == null) } == true) {
            throw EnterpriseStorageException("invalid_enterprise_sync_time")
        }
        if (manifest.feeds.map { it.scope }.distinct().size != manifest.feeds.size) throw EnterpriseStorageException("duplicate_enterprise_feed")
        manifest.feeds.forEach(::validateFeedVersion)
        manifest.session?.let { session ->
            EnterprisePackageCodec.validateIdentity(session.identity)
            val validSessionId = if (session.identity.authority.isLocal) isRevision(session.id)
                else session.id.matches(Regex("ses_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            if (!validSessionId || session.expiresAtMillis <= 0) {
                throw EnterpriseStorageException("invalid_enterprise_session")
            }
        }
        manifest.lastIdentity?.let(EnterprisePackageCodec::validateIdentity)
        manifest.applied?.let { version ->
            if (!isRevision(version.revision) || version.generation <= 0 ||
                !version.configurationHash.matches(Regex("[0-9a-f]{64}")) || !version.executionHash.matches(Regex("[0-9a-f]{64}"))) {
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
            EnterpriseSessionPhase.CLOSING -> manifest.session != null && manifest.applied == null &&
                manifest.selectedScope == ConfigurationScope.Personal
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
