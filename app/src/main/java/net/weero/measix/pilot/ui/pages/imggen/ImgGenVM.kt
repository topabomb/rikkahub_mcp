package net.weero.measix.pilot.ui.pages.imggen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.android.appTempFolder
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.imggen.GeneratedMediaConsumerPlan
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.imggen.ImageGenerationCoordinator
import net.weero.measix.pilot.data.imggen.ImageGenerationOutcome
import net.weero.measix.pilot.data.imggen.ImageGenerationRequest
import net.weero.measix.pilot.data.imggen.ImageGenerationSelection
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
import net.weero.measix.pilot.data.imggen.ImageGenerationSource
import net.weero.measix.pilot.data.repository.GenMediaRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String
)

private fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    val imagesDir = filesManager.getImagesDir()
    val fullPath = File(imagesDir, this.path.removePrefix("images/")).absolutePath

    return GeneratedImage(
        id = this.id,
        prompt = this.prompt,
        filePath = fullPath,
        timestamp = this.createAt,
        model = this.modelId
    )
}

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
    private val selectionResolver: ImageGenerationSelectionResolver,
    private val coordinator: ImageGenerationCoordinator,
    private val generatedMediaStore: GeneratedMediaStore,
) : AndroidViewModel(context) {
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

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getAllMedia() }
    )
    val generatedImages: Flow<PagingData<GeneratedImage>> = pager.flow
        .map { pagingData ->
            pagingData.map { entity -> entity.toGeneratedImage(filesManager) }
        }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            runCatching { coordinator.reconcileMedia() }
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

    fun addReferenceImages(paths: List<String>) {
        _referenceImages.value = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
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

                val settings = settingsStore.settingsFlow.first()
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
                        val preview = saveImagePreview(
                            item = item,
                            modelName = selection.model.displayName,
                            index = item.partialImageIndex ?: 0,
                        )
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
            } catch (e: Exception) {
                if(e is CancellationException) return@launch
                Log.e(TAG, "Failed to generate image", e)
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

                val settings = settingsStore.settingsFlow.first()
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
                    mediaType = GenMediaEntity.TYPE_IMAGE_EDIT,
                    sourcePaths = sourceImages.joinToString("\n"),
                    editImages = sourceImages,
                    onPartial = { item ->
                        previewFile?.delete()
                        val preview = saveImagePreview(
                            item = item,
                            modelName = selection.model.displayName,
                            index = item.partialImageIndex ?: 0,
                        )
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
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Failed to edit image", e)
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

    private fun saveImagePreview(
        item: ImageGenerationItem,
        modelName: String,
        index: Int,
    ): File {
        val timestamp = System.currentTimeMillis()
        val imageFile = File(getApplication<Application>().appTempFolder, "imggen_${timestamp}_${modelName}_$index.png")
        return filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
    }

    fun deleteImage(image: GeneratedImage) {
        viewModelScope.launch {
            try {
                if (!generatedMediaStore.delete(image.id)) {
                    _error.value = "delete_failed"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete image", e)
                _error.value = "delete_failed"
            }
        }
    }

    private fun deleteReferenceFiles(paths: List<String>) {
        viewModelScope.launch {
            paths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "ImgGenVM"
        private const val MAX_REFERENCE_IMAGES = 16
    }
}
