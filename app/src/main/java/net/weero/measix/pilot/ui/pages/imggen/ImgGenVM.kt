package net.weero.measix.pilot.ui.pages.imggen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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
import kotlin.uuid.Uuid

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String
)

private fun GeneratedMediaUiModel.toGeneratedImage(): GeneratedImage {
    return GeneratedImage(
        id = id,
        prompt = prompt,
        filePath = filePath,
        timestamp = createdAt,
        model = modelId,
    )
}

class ImgGenVM(
    context: Application,
    private val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
    private val selectionResolver: ImageGenerationSelectionResolver,
    private val coordinator: ImageGenerationCoordinator,
    private val fileManagementQueryService: FileManagementQueryService,
    private val fileManagementApplicationService: FileManagementApplicationService,
) : AndroidViewModel(context) {
    val settings: StateFlow<Settings> = settingsStore.effectiveSettings
        .map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun selectImageGenerationModel(modelId: Uuid) {
        viewModelScope.launch {
            try {
                settingsStore.updateLocal { it.copy(imageGenerationModelId = modelId) }
            } catch (error: SettingsLockedException) {
                _error.value = "managed_configuration_locked:${error.reason}"
            }
        }
    }

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _size = MutableStateFlow(ImageGenSize.AUTO.value)
    val size: StateFlow<String> = _size

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null
    private val pageSessionId = "imggen-page"
    private var activeRequestId: String? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentGeneratedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = _currentGeneratedImages

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    val generatedImages: Flow<PagingData<GeneratedImage>> = fileManagementQueryService
        .observeGeneratedPaging()
        .map { pagingData ->
            pagingData.map(GeneratedMediaUiModel::toGeneratedImage)
        }
        .cachedIn(viewModelScope)

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
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            var previewFile: File? = null
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()
                coordinator.cancelPageSession(pageSessionId)

                val settings = settingsStore.effectiveSettings.first().settings
                val selection = selectionResolver.resolve(settings)
                if (selection !is ImageGenerationSelection.Available) {
                    _error.value = "image_model_unavailable"
                    return@launch
                }
                val requestPrompt = _prompt.value
                val request = ImageGenerationRequest(
                    source = ImageGenerationSource.Page(pageSessionId),
                    selection = selection,
                    prompt = requestPrompt,
                    numOfImages = _numberOfImages.value,
                    size = _size.value,
                    partialImages = 2,
                    consumerPlan = GeneratedMediaConsumerPlan.NONE,
                    onPartial = { item ->
                        previewFile?.delete()
                        val preview = saveImagePreview(item)
                        previewFile = preview
                        _currentGeneratedImages.value = listOf(
                            GeneratedImage(
                                id = 0,
                                prompt = requestPrompt,
                                filePath = preview.absolutePath,
                                timestamp = System.currentTimeMillis(),
                                model = selection.model.displayName,
                            )
                        )
                    },
                )
                activeRequestId = request.id
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
                                id = media.mediaId.toInt(),
                                prompt = requestPrompt,
                                filePath = media.canonicalFile.absolutePath,
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
                activeRequestId = null
                _isGenerating.value = false
            }
        }
    }

    fun editImage() {
        if (prompt.value.isBlank() || referenceImages.value.isEmpty()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            var previewFile: File? = null
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()
                coordinator.cancelPageSession(pageSessionId)

                val settings = settingsStore.effectiveSettings.first().settings
                val selection = selectionResolver.resolve(settings)
                if (selection !is ImageGenerationSelection.Available) {
                    _error.value = "image_model_unavailable"
                    return@launch
                }

                val requestPrompt = _prompt.value
                val sourceImages = _referenceImages.value
                val request = ImageGenerationRequest(
                    source = ImageGenerationSource.Page(pageSessionId),
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
                        val preview = saveImagePreview(item)
                        previewFile = preview
                        _currentGeneratedImages.value = listOf(
                            GeneratedImage(
                                id = 0,
                                prompt = requestPrompt,
                                filePath = preview.absolutePath,
                                timestamp = System.currentTimeMillis(),
                                model = selection.model.displayName,
                            )
                        )
                    },
                )
                activeRequestId = request.id
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
                                id = media.mediaId.toInt(),
                                prompt = requestPrompt,
                                filePath = media.canonicalFile.absolutePath,
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
                activeRequestId = null
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
        val requestId = activeRequestId
        viewModelScope.launch {
            if (requestId != null) coordinator.cancel(requestId)
            coordinator.cancelPageSession(pageSessionId)
        }
    }

    private suspend fun saveImagePreview(item: ImageGenerationItem): File {
        return fileManagementApplicationService.createGeneratedPreview(
            item = item,
            tempDirectory = getApplication<Application>().appTempFolder,
        )
    }

    suspend fun deleteImage(image: GeneratedImage): Boolean = try {
        fileManagementApplicationService.deleteGenerated(ManagedFileKey.Generated(image.id))
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
