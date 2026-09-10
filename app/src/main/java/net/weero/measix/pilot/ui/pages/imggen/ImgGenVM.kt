package net.weero.measix.pilot.ui.pages.imggen

import me.rerere.common.configuration.ConfigurationReference
import android.app.Application
import android.net.Uri
import net.weero.measix.pilot.service.TemporaryImage
import net.weero.measix.pilot.data.enterprise.RealmSelection
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import net.weero.measix.pilot.data.datastore.Settings
import kotlinx.coroutines.launch
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.android.appTempFolder
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaKind
import net.weero.measix.pilot.data.imggen.ImageGenerationCoordinator
import net.weero.measix.pilot.data.imggen.ImageGenerationOutcome
import net.weero.measix.pilot.data.imggen.ImageGenerationRequest
import net.weero.measix.pilot.data.imggen.ImageGenerationSource
import net.weero.measix.pilot.service.FileManagementApplicationService
import net.weero.measix.pilot.service.FileManagementQueryService
import net.weero.measix.pilot.service.GeneratedMediaUiModel
import net.weero.measix.pilot.service.ManagedFileKey
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

data class GeneratedImage(
    val selection: net.weero.measix.pilot.data.enterprise.RealmSelection,
    val id: Int,
    val prompt: String,
    val filePath: String,
    val image: net.weero.measix.pilot.service.ImageSource,
    val timestamp: Long,
    val model: String
)

private fun GeneratedMediaUiModel.toGeneratedImage(files: net.weero.measix.pilot.service.FileManagementApplicationService): GeneratedImage {
    return GeneratedImage(
        selection = key.selection,
        id = key.mediaId,
        prompt = prompt,
        filePath = filePath,
        image = files.imageSource(key, File(filePath).name, createdAt),
        timestamp = createdAt,
        model = modelId,
    )
}

internal data class ImageReferenceImport(val selection: RealmSelection, val owner: Job)

class ImgGenVM internal constructor(
    context: Application,
    private val settingsStore: SettingsStore,
    private val coordinator: ImageGenerationCoordinator,
    private val fileManagementQueryService: FileManagementQueryService,
    private val fileManagementApplicationService: FileManagementApplicationService,
    private val configurationQueryService: net.weero.measix.pilot.service.ConfigurationQueryService,
    private val configurationApplicationService: net.weero.measix.pilot.service.ConfigurationApplicationService,
) : AndroidViewModel(context) {
    internal val modelCatalog = configurationQueryService.observeModelCatalog()
        .stateIn(viewModelScope, SharingStarted.Eagerly, net.weero.measix.pilot.service.ModelCatalogReadState.Loading)
    val settings: StateFlow<Settings> = settingsStore.userSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun selectImageGenerationModel(modelId: ConfigurationReference) {
        val selection = (modelCatalog.value as? net.weero.measix.pilot.service.ModelCatalogReadState.Available)?.catalog?.selection ?: return
        viewModelScope.launch {
            try {
                configurationApplicationService.selectResource(selection, net.weero.measix.pilot.data.configuration.ResourceSelectionSlot.IMAGE_MODEL, modelId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = error.message ?: "image_model_unavailable" }
        }
    }

    val realmSelection = fileManagementQueryService.observeSelection()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _size = MutableStateFlow(ImageGenSize.AUTO.value)
    val size: StateFlow<String> = _size

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null
    private var generationToken: Any? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentGeneratedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = combine(_currentGeneratedImages, realmSelection) { images, selected ->
        images.filter { it.selection == selected }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var referenceOwner = Job(viewModelScope.coroutineContext[Job])
    private var pendingReferenceImport: ImageReferenceImport? = null
    private val referenceReaders = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val _referenceImages = MutableStateFlow<List<TemporaryImage>>(emptyList())
    internal val referenceImages: StateFlow<List<TemporaryImage>> = combine(_referenceImages, realmSelection) { images, selected ->
        images.filter { it.selection == selected }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val generatedImages: Flow<PagingData<GeneratedImage>> = fileManagementQueryService
        .observeGeneratedPaging()
        .map { pagingData ->
            pagingData.map { it.toGeneratedImage(fileManagementApplicationService) }
        }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            realmSelection.collect { startNewSession() }
        }
    }

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 4)
    }

    fun updateSize(size: String) {
        _size.value = size
    }

    internal fun beginReferenceImport(): Boolean {
        if (pendingReferenceImport != null) return false
        val selected = realmSelection.value ?: return false
        pendingReferenceImport = ImageReferenceImport(selected, referenceOwner)
        return true
    }

    internal fun takeReferenceImport(): ImageReferenceImport? = pendingReferenceImport.also { pendingReferenceImport = null }

    internal suspend fun importReferenceImages(target: ImageReferenceImport, uris: List<Uri>): Int {
        val created = mutableListOf<TemporaryImage>()
        var failed = 0
        val capacity = (MAX_REFERENCE_IMAGES - _referenceImages.value.size).coerceAtLeast(0)
        try {
            target.owner.ensureActive()
            configurationQueryService.requireSelection(target.selection)
            for (uri in uris.take(capacity)) {
                try {
                    created += fileManagementApplicationService.importImageReference(getApplication<Application>(), uri,
                        getApplication<Application>().appTempFolder, target.selection, target.owner)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed++ }
            }
            configurationQueryService.requireSelection(target.selection)
            target.owner.ensureActive()
            check(target.owner === referenceOwner && realmSelection.value == target.selection) { "reference_import_revoked" }
            val available = (MAX_REFERENCE_IMAGES - _referenceImages.value.size).coerceAtLeast(0)
            val accepted = created.take(available)
            _referenceImages.value += accepted
            created.removeAll(accepted.toSet())
            return failed + (uris.size - capacity).coerceAtLeast(0) + created.size
        } finally {
            deleteReferenceFilesNow(created.map { it.file.path })
        }
    }

    internal fun removeReferenceImage(image: TemporaryImage) {
        _referenceImages.value = _referenceImages.value.filterNot { it == image }
        deleteReferenceFiles(listOf(image.file.path))
    }

    fun clearReferenceImages() {
        referenceOwner.cancel()
        referenceOwner = Job(viewModelScope.coroutineContext[Job])
        deleteReferenceFiles(_referenceImages.value.map { it.file.path })
        _referenceImages.value = emptyList()
    }

    fun clearError() {
        _error.value = null
    }

    fun startNewSession() {
        cancelGeneration()
        clearReferenceImages()
        _prompt.value = ""
        _currentGeneratedImages.value = emptyList()
        _error.value = null
    }

    fun generateImage() = submitImage(edit = false)

    fun editImage() = submitImage(edit = true)

    private fun submitImage(edit: Boolean) {
        val requestPrompt = _prompt.value
        val references = if (edit) _referenceImages.value.toList() else emptyList()
        if (requestPrompt.isBlank() || (edit && references.isEmpty())) return
        val catalog = (modelCatalog.value as? net.weero.measix.pilot.service.ModelCatalogReadState.Available)?.catalog ?: return
        val selection = catalog.selection ?: return
        if (references.any { it.selection != selection }) return
        val sourceImages = references.map { it.file.path }
        val chosen = catalog.roleSelections[net.weero.measix.pilot.data.configuration.ResourceSelectionSlot.IMAGE_MODEL]
        val model = chosen?.reference?.let(catalog::find)?.model
        if (chosen?.isAvailable != true || model == null) { _error.value = "image_model_unavailable"; return }
        val count = _numberOfImages.value
        val size = _size.value
        val previous = cancelJob
        previous?.cancel()
        val token = Any().also { generationToken = it }
        val next = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            val requestJob = requireNotNull(currentCoroutineContext()[Job])
            fun isCurrent() = generationToken === token && realmSelection.value == selection
            var previewFile: File? = null
            try {
                withContext(NonCancellable) { previous?.join() }
                currentCoroutineContext().ensureActive()
                configurationQueryService.requireSelection(selection)
                if (!isCurrent()) return@launch
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()
                val request = ImageGenerationRequest(
                    source = ImageGenerationSource.Page(selection, model.id),
                    prompt = requestPrompt, numOfImages = count, size = size, partialImages = 2,
                    mediaKind = if (edit) GeneratedMediaKind.EDIT else GeneratedMediaKind.GENERATION,
                    sourcePaths = sourceImages.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                    editImages = sourceImages,
                    onPartial = { item ->
                        requestJob.ensureActive()
                        if (isCurrent()) {
                            previewFile?.delete()
                            val preview = saveImagePreview(item, selection, requestJob)
                            previewFile = preview.file
                            if (isCurrent()) _currentGeneratedImages.value = listOf(GeneratedImage(
                                selection, 0, requestPrompt, preview.file.absolutePath, preview.image,
                                System.currentTimeMillis(), model.displayName,
                            ))
                        }
                    },
                )
                val outcome = coordinator.enqueue(request)
                if (isCurrent()) when (outcome) {
                    is ImageGenerationOutcome.Failure -> _error.value = outcome.reason
                    is ImageGenerationOutcome.Success -> {
                        _currentGeneratedImages.value = outcome.media.map { media -> GeneratedImage(
                            selection, media.mediaId.toInt(), requestPrompt, media.canonicalFile.absolutePath,
                            fileManagementApplicationService.imageSource(ManagedFileKey.Generated(media.mediaId.toInt(), selection), media.canonicalFile.name),
                            System.currentTimeMillis(), model.displayName,
                        ) }
                        if (outcome.cleanupPending) _error.value = "cleanup_pending"
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.e(TAG, "Failed to generate image", error)
                if (isCurrent()) _error.value = "unknown"
            } finally {
                previewFile?.delete()
                if (generationToken === token) _isGenerating.value = false
            }
        }
        cancelJob = next
        // Replacements join predecessors, so the latest borrower also covers older uses of the same file.
        sourceImages.forEach { referenceReaders[it] = next }
        next.invokeOnCompletion { sourceImages.forEach { referenceReaders.remove(it, next) } }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    private suspend fun saveImagePreview(item: ImageGenerationItem, selection: net.weero.measix.pilot.data.enterprise.RealmSelection, owner: Job): net.weero.measix.pilot.service.TemporaryImage {
        return fileManagementApplicationService.createGeneratedPreview(
            item = item,
            tempDirectory = getApplication<Application>().appTempFolder,
            selection = selection,
            owner = owner,
        )
    }

    suspend fun deleteImage(image: GeneratedImage): Boolean = try {
        fileManagementApplicationService.deleteGenerated(ManagedFileKey.Generated(image.id, image.selection))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e(TAG, "Failed to delete image", error)
        false
    }

    private fun deleteReferenceFiles(paths: List<String>) {
        paths.forEach { path ->
            val reader = referenceReaders[path]
            if (reader?.isCompleted == false) reader.invokeOnCompletion { deleteReferenceFilesNow(listOf(path)) }
            else deleteReferenceFilesNow(listOf(path))
        }
    }

    override fun onCleared() {
        pendingReferenceImport = null
        referenceOwner.cancel()
        cancelJob?.cancel()
        deleteReferenceFiles(_referenceImages.value.map { it.file.path })
        _referenceImages.value = emptyList()
        super.onCleared()
    }

    private fun deleteReferenceFilesNow(paths: List<String>) {
        paths.forEach { path ->
            val file = File(path)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete reference image: $file")
            }
        }
    }

    companion object {
        private const val TAG = "ImgGenVM"
        private const val MAX_REFERENCE_IMAGES = 16
    }
}
