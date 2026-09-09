package net.weero.measix.pilot.ui.pages.imggen

import me.rerere.common.configuration.ConfigurationReference
import android.app.Application
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
    val settings: StateFlow<Settings> = settingsStore.effectiveSettings
        .map { it.settings }
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

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

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

    fun addReferenceImages(paths: List<String>): Int {
        val retained = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
        val rejected = paths.filterNot(retained::contains)
        _referenceImages.value = retained
        deleteReferenceFiles(rejected)
        return rejected.size
    }

    fun removeReferenceImage(path: String) {
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        deleteReferenceFiles(listOf(path))
    }

    fun clearReferenceImages() {
        deleteReferenceFiles(_referenceImages.value)
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
        val sourceImages = if (edit) _referenceImages.value.toList() else emptyList()
        if (requestPrompt.isBlank() || (edit && sourceImages.isEmpty())) return
        val catalog = (modelCatalog.value as? net.weero.measix.pilot.service.ModelCatalogReadState.Available)?.catalog ?: return
        val selection = catalog.selection ?: return
        val chosen = catalog.roleSelections[net.weero.measix.pilot.data.configuration.ResourceSelectionSlot.IMAGE_MODEL]
        val model = chosen?.reference?.let(catalog::find)?.model
        if (chosen?.isAvailable != true || model == null) { _error.value = "image_model_unavailable"; return }
        val count = _numberOfImages.value
        val size = _size.value
        val previous = cancelJob
        previous?.cancel()
        val token = Any().also { generationToken = it }
        cancelJob = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
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
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    private suspend fun saveImagePreview(item: ImageGenerationItem, selection: net.weero.measix.pilot.data.enterprise.RealmSelection, owner: Job): net.weero.measix.pilot.service.GeneratedPreview {
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
        if (paths.isEmpty()) return
        val owner = cancelJob
        if (owner?.isCompleted == false) owner.invokeOnCompletion { deleteReferenceFilesNow(paths) }
        else deleteReferenceFilesNow(paths)
    }

    override fun onCleared() {
        cancelJob?.cancel()
        deleteReferenceFiles(_referenceImages.value)
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
