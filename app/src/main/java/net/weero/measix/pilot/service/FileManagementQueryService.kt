package net.weero.measix.pilot.service

import android.util.Log
import androidx.core.net.toUri
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import java.io.File
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore

/** A UI command retains both its typed identity and the selection that displayed it. */
sealed interface ManagedFileKey {
    val selection: RealmSelection
    data class Artifact(val artifactId: Long, override val selection: RealmSelection) : ManagedFileKey
    data class Generated(val mediaId: Int, override val selection: RealmSelection) : ManagedFileKey
}

data class ManagedFileUiModel(
    val key: ManagedFileKey,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val origin: ArtifactUiOrigin?,
    val createdAt: Long,
    val prompt: String?,
    val modelId: String?,
)

data class ManagedStorageUiModel(val count: Int, val sizeBytes: Long)

data class FileDirectoryUiModel(
    val selection: RealmSelection?,
    val uploads: List<ManagedFileUiModel> = emptyList(),
    val generated: List<ManagedFileUiModel> = emptyList(),
    val failed: Boolean = false,
)

data class GeneratedMediaUiModel(
    val key: ManagedFileKey.Generated,
    val prompt: String,
    val filePath: String,
    val createdAt: Long,
    val modelId: String,
)

/** Combines the existing file owners; authorization remains with the session owner. */
class FileManagementQueryService internal constructor(
    private val artifactStore: ArtifactStore,
    private val generatedMediaStore: GeneratedMediaStore,
    private val recoveryGate: ApplicationRecoveryGate,
    private val sessions: EnterpriseSessionController,
    private val clock: Clock = Clock.System,
) {
    fun observeSelection(): Flow<RealmSelection?> = flow {
        recoveryGate.awaitReady()
        emitAll(sessions.observeSelectedRealmSelection())
    }

    fun observeDirectory(): Flow<FileDirectoryUiModel> = observeSelection().flatMapLatest { selection ->
        val empty = FileDirectoryUiModel(null)
        if (selection == null) flowOf(empty) else combine(
            artifactStore.observe(selection.access.scope),
            generatedMediaStore.observe(selection.access.scope),
        ) { uploads, generated ->
            sessions.withSelectedRealmSelection(selection) {
                withContext(Dispatchers.IO) {
                    FileDirectoryUiModel(selection, uploads.map { it.toManaged(selection) }, generated.map { it.toManaged(selection) })
                }.also { sessions.requirePublishedSelection(selection) }
            }
        }.onStart { emit(empty) }.catch { error ->
            if (error is CancellationException) throw error
            Log.w("FileManagementQuery", "File directory unavailable", error)
            emit(if (error is EnterpriseConfigurationException) empty else empty.copy(failed = true))
        }
    }

    fun observeStorageStats(): Flow<ManagedStorageUiModel?> = observeDirectory().map { directory ->
        if (directory.selection == null) null else ManagedStorageUiModel(
            directory.uploads.size + directory.generated.size,
            directory.uploads.sumOf { it.sizeBytes } + directory.generated.sumOf { it.sizeBytes },
        )
    }

    fun observeGeneratedPaging(): Flow<PagingData<GeneratedMediaUiModel>> = observeSelection().flatMapLatest { selection ->
        if (selection == null) flowOf(PagingData.empty()) else selectedRealmPaging(
            sessions, selection, PagingConfig(pageSize = 20, enablePlaceholders = false),
            source = { generatedMediaStore.pagingSource(selection.access.scope) },
        ) { entity ->
            GeneratedMediaUiModel(
                ManagedFileKey.Generated(entity.id, selection), entity.prompt,
                generatedMediaStore.resolveCanonicalFile(entity).absolutePath, entity.createAt, entity.modelId,
            )
        }
    }

    /** Classification only; payload reads must use an authorized file owner. */
    fun isManagedGeneratedFile(file: File): Boolean = generatedMediaStore.isManagedFile(file)

    suspend fun candidateCount(selection: RealmSelection, category: FileCleanupCategory, range: FileCleanupRange): Int {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(selection) {
            val cutoff = cutoffFor(range, clock.now().toEpochMilliseconds())
            when (category) {
                FileCleanupCategory.UPLOAD -> artifactStore.countFolderCreatedBefore(selection.access.scope, FileFolders.UPLOAD, cutoff)
                FileCleanupCategory.GENERATED_IMAGES -> generatedMediaStore.candidateCount(selection.access.scope, cutoff)
            }.also { sessions.requirePublishedSelection(selection) }
        }
    }

    suspend fun inspectArtifact(key: ManagedFileKey.Artifact): ArtifactDeleteImpactUiModel? {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(key.selection) {
            val entity = artifactStore.get(key.artifactId)?.takeIf { it.scope == key.selection.access.scope }
                ?: return@withSelectedRealmSelection null
            artifactStore.inspect(entity).toUiModel().also { sessions.requirePublishedSelection(key.selection) }
        }
    }

    private fun ArtifactEntity.toManaged(selection: RealmSelection) = ManagedFileUiModel(
        key = ManagedFileKey.Artifact(id, selection),
        contentUri = artifactStore.file(this).toUri().toString(),
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        origin = when (ArtifactOrigin.valueOf(origin)) {
            ArtifactOrigin.USER -> ArtifactUiOrigin.USER
            ArtifactOrigin.GENERATED -> ArtifactUiOrigin.GENERATED
            ArtifactOrigin.SYSTEM -> ArtifactUiOrigin.SYSTEM
        },
        createdAt = createdAt,
        prompt = null,
        modelId = null,
    )

    private fun GenMediaEntity.toManaged(selection: RealmSelection): ManagedFileUiModel {
        val file = generatedMediaStore.resolveCanonicalFile(this)
        return ManagedFileUiModel(
            key = ManagedFileKey.Generated(id, selection),
            contentUri = file.toUri().toString(),
            displayName = file.name,
            mimeType = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/png"
            },
            sizeBytes = file.takeIf { it.isFile }?.length() ?: 0L,
            origin = null,
            createdAt = createAt,
            prompt = prompt,
            modelId = modelId,
        )
    }
}
