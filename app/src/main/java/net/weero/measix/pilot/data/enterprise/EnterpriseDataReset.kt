package net.weero.measix.pilot.data.enterprise

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import kotlin.uuid.Uuid

/** KEEP_HISTORY removes only access facts and managed caches; CLEAR_ALL also removes every realm's history. */
@Serializable
internal enum class EnterpriseDataResetMode { KEEP_HISTORY, CLEAR_ALL }

/** Persisted stages; completion is the deleted intent itself, so no terminal stage is stored. */
@Serializable
internal enum class EnterpriseDataResetStage { FROZEN, DOMAINS_CLOSED, DATA_CLEARED }

/** One confirmed UI operation; page rotation or restart must never manufacture a replacement request. */
internal data class EnterpriseDataResetRequest(
    val operationId: Uuid,
    val mode: EnterpriseDataResetMode,
)

/** The durable reset intent; scopes is the frozen union of every enterprise realm this device holds. */
@Serializable
internal data class EnterpriseDataResetIntent(
    val schemaVersion: Int,
    val operationId: String,
    val mode: EnterpriseDataResetMode,
    val createdAtMillis: Long,
    val scopes: List<ConfigurationScope.Enterprise>,
    val stage: EnterpriseDataResetStage,
    val completedOwners: List<String>,
) {
    internal companion object {
        const val SCHEMA_VERSION = 2
        const val OWNER_CONVERSATIONS = "conversations"
        const val OWNER_FILES = "files"
        const val OWNER_MEMORIES = "memories"
        const val OWNER_SETTINGS = "settings"
        const val OWNER_CATALOGS = "catalogs"
        val OWNERS = listOf(OWNER_CONVERSATIONS, OWNER_FILES, OWNER_MEMORIES, OWNER_SETTINGS, OWNER_CATALOGS)
    }
}

internal data class EnterpriseDataResetProgress(
    val operationId: Uuid,
    val mode: EnterpriseDataResetMode,
    val stage: EnterpriseDataResetStage,
    val failure: String?,
)

/**
 * Single-writer intent storage under noBackupFilesDir/enterprise-reset, independent of the applied store it resets.
 * A corrupt intent fails closed. Its selected branch and owner checkpoints cannot be reconstructed safely,
 * so startup must expose the storage failure instead of treating a partially completed reset as absent.
 */
internal class EnterpriseDataResetStore(private val root: File) {
    private val json get() = EnterpriseConfigurationCodec.json
    private val intentFile get() = AtomicFile(File(root, "intent.json"))

    fun read(): EnterpriseDataResetIntent? {
        if (!intentFile.baseFile.exists() && !File(root, "intent.json.bak").exists()) return null
        val intent = try {
            intentFile.openRead().use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > MAX_BYTES) throw EnterpriseStorageException("invalid_enterprise_reset_intent")
                json.decodeFromString<EnterpriseDataResetIntent>(bytes.toString(Charsets.UTF_8))
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw EnterpriseStorageException("invalid_enterprise_reset_intent")
        }
        val idsValid = try {
            Uuid.parse(intent.operationId)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
        if (intent.schemaVersion != EnterpriseDataResetIntent.SCHEMA_VERSION || !idsValid ||
            intent.scopes.distinct().size != intent.scopes.size ||
            intent.scopes.any { it.userId.isBlank() || it.authority.sourceNamespace.isBlank() || it.authority.deploymentId.isBlank() } ||
            intent.completedOwners.any { it !in EnterpriseDataResetIntent.OWNERS } ||
            intent.completedOwners.distinct().size != intent.completedOwners.size ||
            intent.operationId.isBlank()) {
            throw EnterpriseStorageException("invalid_enterprise_reset_intent")
        }
        return intent
    }

    fun write(intent: EnterpriseDataResetIntent) {
        if (!root.isDirectory && !root.mkdirs()) throw EnterpriseStorageException("enterprise_reset_directory_failed")
        val bytes = json.encodeToString(intent).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw EnterpriseStorageException("invalid_enterprise_reset_intent")
        val atomic = intentFile
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
            if (!atomic.baseFile.inputStream().use { it.readBytes() }.contentEquals(bytes)) {
                throw EnterpriseStorageException("enterprise_reset_commit_failed")
            }
        } catch (error: Exception) {
            atomic.failWrite(stream)
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw EnterpriseStorageException("enterprise_reset_commit_failed")
        }
    }

    fun clear() {
        File(root, "intent.json.bak").delete()
        File(root, "intent.json.new").delete()
        intentFile.baseFile.delete()
    }

    private companion object { const val MAX_BYTES = 64 * 1024 }
}
