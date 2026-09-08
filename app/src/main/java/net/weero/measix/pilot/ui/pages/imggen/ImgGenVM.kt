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
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.android.appTempFolder
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaConsumerPlan
import net.weero.measix.pilot.data.imggen.GeneratedMediaKind
import net.weero.measix.pilot.data.imggen.ImageGenerationCoordinator
import net.weero.measix.pilot.data.imggen.ImageGenerationOutcome
import net.weero.measix.pilot.data.imggen.ImageGenerationRequest
import net.weero.measix.pilot.data.imggen.ImageGenerationSelection
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
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
    val providerManager: ProviderManager,
    private val selectionResolver: ImageGenerationSelectionResolver,
    private val coordinator: ImageGenerationCoordinator,
    private val fileManagementQueryService: FileManagementQueryService,
    private val fileManagementApplicationService: FileManagementApplicationService,
    private val configurationQueryService: net.weero.measix.pilot.service.ConfigurationQueryService,
) : AndroidViewModel(context) {
    private val modelCatalog = configurationQueryService.observeModelCatalog()
        .stateIn(viewModelScope, SharingStarted.Eagerly, net.weero.measix.pilot.service.ModelCatalogReadState.Loading)
    val settings: StateFlow<Settings> = settingsStore.effectiveSettings
        .map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun selectImageGenerationModel(modelId: ConfigurationReference) {
        viewModelScope.launch {
            try {
                settingsStore.updateLocal { it.copy(imageGenerationModelId = modelId) }
            } catch (error: SettingsLockedException) {
                _error.value = "managed_configuration_locked:${error.reason}"
            }
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

    fun generateImage() {
        if(prompt.value.isBlank()) return
        val realmSelection = (modelCatalog.value as? net.weero.measix.pilot.service.ModelCatalogReadState.Available)
            ?.catalog?.selection ?: return
        val previous = cancelJob
        previous?.cancel()
        cancelJob = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) { previous?.join() }
            currentCoroutineContext().ensureActive()
            val requestJob = requireNotNull(currentCoroutineContext()[Job])
            var previewFile: File? = null
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                configurationQueryService.requireSelection(realmSelection)

                val settings = settingsStore.effectiveSettings.first().settings
                val selection = selectionResolver.resolve(settings)
                if (selection !is ImageGenerationSelection.Available) {
                    _error.value = "image_model_unavailable"
                    return@launch
                }
                val requestPrompt = _prompt.value
                val request = ImageGenerationRequest(
                    realmAccess = realmSelection.access,
                    source = ImageGenerationSource.Page,
                    selection = selection,
                    prompt = requestPrompt,
                    numOfImages = _numberOfImages.value,
                    size = _size.value,
                    partialImages = 2,
                    consumerPlan = GeneratedMediaConsumerPlan.NONE,
                    onPartial = { item ->
                        previewFile?.delete()
                        val preview = saveImagePreview(item, realmSelection, requestJob)
                        previewFile = preview.file
                        _currentGeneratedImages.value = listOf(
                            GeneratedImage(
                                selection = realmSelection,
                                id = 0,
                                prompt = requestPrompt,
                                filePath = preview.file.absolutePath,
                                image = preview.image,
                                timestamp = System.currentTimeMillis(),
                                model = selection.model.displayName,
                            )
                        )
                    },
                )
                when (val outcome = coordinator.enqueue(request)) {
                    is ImageGenerationOutcome.Failure -> {
                        previewFile?.delete()
                        previewFile = null
                        _error.value = outcome.reason
                    }
                    is ImageGenerationOutcome.Success -> {
                        previewFile?.delete()
                        previewFile = null
                        _currentGeneratedImages.value = outcome.media.map { media ->
                            GeneratedImage(
                                selection = realmSelection,
                                id = media.mediaId.toInt(),
                                prompt = requestPrompt,
                                filePath = media.canonicalFile.absolutePath,
                                image = fileManagementApplicationService.imageSource(ManagedFileKey.Generated(media.mediaId.toInt(), realmSelection), media.canonicalFile.name),
                                timestamp = System.currentTimeMillis(),
                                model = selection.model.displayName,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Failed to generate image", error)
                _error.value = "unknown"
            } finally {
                previewFile?.delete()
                _isGenerating.value = false
            }
        }
    }

    fun editImage() {
        if (prompt.value.isBlank() || referenceImages.value.isEmpty()) return
        val realmSelection = (modelCatalog.value as? net.weero.measix.pilot.service.ModelCatalogReadState.Available)
            ?.catalog?.selection ?: return
        val previous = cancelJob
        previous?.cancel()
        cancelJob = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) { previous?.join() }
            currentCoroutineContext().ensureActive()
            val requestJob = requireNotNull(currentCoroutineContext()[Job])
            var previewFile: File? = null
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                configurationQueryService.requireSelection(realmSelection)

                val settings = settingsStore.effectiveSettings.first().settings
                val selection = selectionResolver.resolve(settings)
                if (selection !is ImageGenerationSelection.Available) {
                    _error.value = "image_model_unavailable"
                    return@launch
                }

                val requestPrompt = _prompt.value
                val sourceImages = _referenceImages.value
                val request = ImageGenerationRequest(
                    realmAccess = realmSelection.access,
                    source = ImageGenerationSource.Page,
                    selection = selection,
                    prompt = requestPrompt,
                    numOfImages = _numberOfImages.value,
                    size = _size.value,
                    partialImages = 2,
                    mediaKind = GeneratedMediaKind.EDIT,
                    sourcePaths = sourceImages.joinToString("\n"),
                    editImages = sourceImages,
                    onPartial = { item ->
                        previewFile?.delete()
                        val preview = saveImagePreview(item, realmSelection, requestJob)
                        previewFile = preview.file
                        _currentGeneratedImages.value = listOf(
                            GeneratedImage(
                                selection = realmSelection,
                                id = 0,
                                prompt = requestPrompt,
                                filePath = preview.file.absolutePath,
                                image = preview.image,
                                timestamp = System.currentTimeMillis(),
                                model = selection.model.displayName,
                            )
                        )
                    },
                )
                when (val outcome = coordinator.enqueue(request)) {
                    is ImageGenerationOutcome.Failure -> {
                        previewFile?.delete()
                        previewFile = null
                        _error.value = outcome.reason
                    }
                    is ImageGenerationOutcome.Success -> {
                        previewFile?.delete()
                        previewFile = null
                        _currentGeneratedImages.value = outcome.media.map { media ->
                            GeneratedImage(
                                selection = realmSelection,
                                id = media.mediaId.toInt(),
                                prompt = requestPrompt,
                                filePath = media.canonicalFile.absolutePath,
                                image = fileManagementApplicationService.imageSource(ManagedFileKey.Generated(media.mediaId.toInt(), realmSelection), media.canonicalFile.name),
                                timestamp = System.currentTimeMillis(),
                                model = selection.model.displayName,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Failed to edit image", error)
                _error.value = "unknown"
            } finally {
                previewFile?.delete()
                _isGenerating.value = false
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
        viewModelScope.launch(Dispatchers.IO) {
            deleteReferenceFilesNow(paths)
        }
    }

    override fun onCleared() {
        cancelJob?.cancel()
        deleteReferenceFilesNow(_referenceImages.value)
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
