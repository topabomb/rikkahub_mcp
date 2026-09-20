package net.weero.measix.pilot.data.enterprise

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.LegacyEnterprisePrincipalEncoding
import kotlin.uuid.Uuid
import me.rerere.ai.provider.ChatTransportCapabilities

@Serializable
internal enum class EnterpriseSessionPhase { SIGNED_OUT, CONFIGURATION_PENDING, READY, OFFLINE, CLOSING, REAUTH_REQUIRED }

@Serializable
internal enum class EnterpriseExitReason {
    USER_REQUEST, AUTHORIZATION_EXPIRED, AUTHORIZATION_REVOKED, IDENTITY_DELETED, LOCAL_DATA_RESET,
}

internal const val ENTERPRISE_MANIFEST_SCHEMA_VERSION = 6

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

/** The manifest is the only durable publication point for identity, definitions, and execution inputs. */
@Serializable
internal data class EnterpriseManifest(
    val schemaVersion: Int,
    val phase: EnterpriseSessionPhase,
    val session: EnterpriseSession?,
    val applied: EnterpriseAppliedVersion?,
    val selectedScope: ConfigurationScope,
    val lastIdentity: EnterpriseIdentity?,
    val lastConfigurationSyncMillis: Long? = null,
    val exitReason: EnterpriseExitReason? = null,
    val pendingEnrollment: PendingPlatformEnrollment? = null,
) {
    companion object {
        fun signedOut(identity: EnterpriseIdentity? = null) = EnterpriseManifest(
            ENTERPRISE_MANIFEST_SCHEMA_VERSION, EnterpriseSessionPhase.SIGNED_OUT, null, null, ConfigurationScope.Personal, identity,
        )
    }
}

@Serializable
private data class StoredEnterpriseConfiguration(
    val revision: String,
    val configuration: EnterpriseConfiguration,
)

@Serializable
private data class StoredEnterpriseExecution(
    val revision: String,
    val releaseId: String,
    val snapshotHash: String,
    val runtimePaths: Map<String, String>,
)

@Serializable
private data class LegacyStoredEnterpriseConfiguration(
    val revision: String,
    val identity: EnterpriseIdentity,
    val configuration: EnterpriseConfiguration,
)

@Serializable
private data class LegacyStoredEnterpriseExecution(
    val revision: String,
    val execution: EnterpriseExecution,
)

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
    CONFIGURATION_STAGED, EXECUTION_STAGED, BEFORE_MANIFEST_COMMIT, MANIFEST_WRITTEN,
    EXECUTION_READ, BEFORE_REVISION_PRUNE,
}

internal class EnterpriseStorageException(val reason: String) : IOException(reason)

/** Files live under noBackupFilesDir; only the session owner calls this single-writer store. */
internal class EnterpriseAppliedStore(
    private val root: File,
    private val credentialCipher: EnterpriseCredentialCipher = EnterpriseCredentialCipher(),
    private val checkpoint: (EnterpriseStorageCheckpoint) -> Unit = {},
) {
    private val json get() = EnterpriseConfigurationCodec.json
    private val manifestFile get() = AtomicFile(File(root, "manifest.json"))
    private val revisions get() = File(root, "revisions")
    private val credentialRevisions get() = File(root, "credentials")
    private val installationIdentity get() = AtomicFile(File(root, "installation-id"))

    fun installationId(): String {
        val file = installationIdentity
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
            decodeOrMigrateManifest(manifestFile.readFully())
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
        val configuration = json.encodeToString(StoredEnterpriseConfiguration(revision, value.configuration)).toByteArray()
        val execution = value.execution as EnterpriseExecution.Platform
        val executionBytes = json.encodeToString(StoredEnterpriseExecution(
            revision,
            execution.releaseId,
            execution.snapshotHash,
            execution.runtimePaths,
        )).toByteArray()
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

    /** Keep the active revision and every in-flight lease; uncommitted staging has no authority. */
    fun prune(retainedRevisions: Set<String>) {
        checkpoint(EnterpriseStorageCheckpoint.BEFORE_REVISION_PRUNE)
        pruneDirectory(revisions, retainedRevisions)
        val manifest = readManifest()
        val credentials = listOfNotNull(manifest.session?.platform?.credential?.revision, manifest.pendingEnrollment?.platform?.credential?.revision).toSet()
        pruneDirectory(credentialRevisions, credentials)
    }

    /**
     * Replaces unreadable or stale enterprise access state without reading it back. The signed-out manifest
     * becomes authoritative first; a later recovery prunes any orphaned revision left by interruption.
     */
    fun resetLocalState(
        retainedIdentity: EnterpriseIdentity?,
        terminalReason: EnterpriseExitReason? = null,
    ): LoadedEnterpriseState {
        require(terminalReason == null || terminalReason == EnterpriseExitReason.IDENTITY_DELETED)
        val manifest = EnterpriseManifest.signedOut(retainedIdentity).copy(exitReason = terminalReason)
        writeManifest(manifest)
        installationIdentity.delete()
        if (listOf("installation-id", "installation-id.bak", "installation-id.new").any { File(root, it).exists() }) {
            throw EnterpriseStorageException("installation_identity_clear_failed")
        }
        pruneDirectory(revisions, emptySet())
        pruneDirectory(credentialRevisions, emptySet())
        return LoadedEnterpriseState(manifest, null)
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
            public.configuration.generation != version.generation) {
            throw EnterpriseStorageException("enterprise_revision_identity_mismatch")
        }
        val identity = manifest.session?.identity
            ?: throw EnterpriseStorageException("enterprise_session_required")
        val connection = manifest.session.platform?.connection
            ?: throw EnterpriseStorageException("platform_session_required")
        val execution = EnterpriseExecution.Platform(
            connection,
            private.releaseId,
            private.snapshotHash,
            private.runtimePaths,
        )
        return EnterpriseCandidate(identity, public.configuration, execution).also(EnterpriseCandidate::validate)
    }

    private fun validateManifest(manifest: EnterpriseManifest) {
        if (manifest.schemaVersion != ENTERPRISE_MANIFEST_SCHEMA_VERSION) throw EnterpriseStorageException("unsupported_enterprise_manifest")
        manifest.pendingEnrollment?.let {
            require(manifest.session == null && manifest.phase == EnterpriseSessionPhase.SIGNED_OUT && it.expiresAtMillis > 0) { "inconsistent_pending_platform_enrollment" }
        }
        manifest.session?.platform?.let {
            require(it.connection.authority == manifest.session.identity.authority) { "platform_session_authority_mismatch" }
        }
        val terminalDeletion = manifest.phase == EnterpriseSessionPhase.SIGNED_OUT &&
            manifest.exitReason == EnterpriseExitReason.IDENTITY_DELETED
        if (!terminalDeletion && (manifest.phase == EnterpriseSessionPhase.CLOSING) != (manifest.exitReason != null)) {
            throw EnterpriseStorageException("inconsistent_enterprise_exit_reason")
        }
        if (manifest.lastConfigurationSyncMillis?.let { it < 0 || (manifest.applied == null && manifest.session?.platform == null) } == true) {
            throw EnterpriseStorageException("invalid_enterprise_sync_time")
        }
        manifest.session?.let { session ->
            EnterpriseConfigurationCodec.validateIdentity(session.identity)
            val validSessionId = session.id.matches(Regex("ses_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            if (!validSessionId || session.expiresAtMillis <= 0) {
                throw EnterpriseStorageException("invalid_enterprise_session")
            }
        }
        manifest.lastIdentity?.let(EnterpriseConfigurationCodec::validateIdentity)
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

    private fun isRevision(value: String): Boolean = runCatching { Uuid.parse(value).toString() == value }.getOrDefault(false)

    private fun readBounded(file: File): ByteArray {
        if (!file.isFile || file.length() > EnterpriseConfigurationCodec.MAX_BYTES) throw EnterpriseStorageException("enterprise_revision_unreadable")
        return file.inputStream().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > EnterpriseConfigurationCodec.MAX_BYTES) throw EnterpriseStorageException("enterprise_revision_too_large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        if (bytes.size > EnterpriseConfigurationCodec.MAX_BYTES) throw EnterpriseStorageException("enterprise_revision_too_large")
        FileOutputStream(file).use { stream -> stream.write(bytes); stream.fd.sync() }
    }

    private inline fun <reified T> decode(bytes: ByteArray): T = try {
        json.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
    } catch (_: IllegalArgumentException) {
        throw EnterpriseStorageException("invalid_enterprise_storage")
    }

    private fun decodeOrMigrateManifest(bytes: ByteArray): EnterpriseManifest {
        val encoded = bytes.toString(Charsets.UTF_8)
        val schemaVersion = try {
            json.parseToJsonElement(encoded).jsonObject.getValue("schemaVersion").jsonPrimitive.int
        } catch (_: IllegalArgumentException) {
            throw EnterpriseStorageException("invalid_enterprise_storage")
        }
        return when (schemaVersion) {
            ENTERPRISE_MANIFEST_SCHEMA_VERSION -> decode(bytes)
            5 -> migrateManifestV5(encoded)
            else -> throw EnterpriseStorageException("unsupported_enterprise_manifest")
        }
    }

    /** One durable conversion removes URL-qualified identity from the last development manifest. */
    private fun migrateManifestV5(encoded: String): EnterpriseManifest {
        val migratedJson = LegacyEnterprisePrincipalEncoding.migrateStorageJson(encoded) ?: encoded
        val fields = try {
            json.parseToJsonElement(migratedJson).jsonObject.toMutableMap()
        } catch (_: IllegalArgumentException) {
            throw EnterpriseStorageException("invalid_enterprise_storage")
        }
        fields["schemaVersion"] = JsonPrimitive(ENTERPRISE_MANIFEST_SCHEMA_VERSION)
        val decoded = decode<EnterpriseManifest>(JsonObject(fields).toString().toByteArray())
        val migrated = decoded.applied?.let { version ->
            decoded.copy(applied = prepare(readLegacyCandidate(decoded, version)))
        } ?: decoded
        return commit(migrated).manifest
    }

    private fun readLegacyCandidate(
        manifest: EnterpriseManifest,
        version: EnterpriseAppliedVersion,
    ): EnterpriseCandidate {
        val directory = revisionDirectory(version.revision)
        val configurationBytes = readBounded(File(directory, "configuration.json"))
        val executionBytes = readBounded(File(directory, "execution.json"))
        if (hash(configurationBytes) != version.configurationHash || hash(executionBytes) != version.executionHash) {
            throw EnterpriseStorageException("enterprise_revision_hash_mismatch")
        }
        fun migrate(bytes: ByteArray): ByteArray {
            val value = bytes.toString(Charsets.UTF_8)
            return (LegacyEnterprisePrincipalEncoding.migrateStorageJson(value) ?: value).toByteArray()
        }
        val configuration = decode<LegacyStoredEnterpriseConfiguration>(migrate(configurationBytes))
        val execution = decode<LegacyStoredEnterpriseExecution>(migrate(executionBytes))
        if (configuration.revision != version.revision || execution.revision != version.revision ||
            configuration.identity != manifest.session?.identity || configuration.configuration.generation != version.generation) {
            throw EnterpriseStorageException("enterprise_revision_identity_mismatch")
        }
        return EnterpriseCandidate(configuration.identity, configuration.configuration, execution.execution)
            .also(EnterpriseCandidate::validate)
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
