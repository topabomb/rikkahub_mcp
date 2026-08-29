package net.weero.measix.pilot.data.imggen

import android.util.Log
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.classifyProviderFailure
import net.weero.measix.pilot.data.db.entity.GenMediaEntity

sealed class ImageGenerationSource {
    data class Page(val sessionId: String) : ImageGenerationSource()
    data class Tool(
        val ownerAssistantId: kotlin.uuid.Uuid,
        val revalidate: suspend (ImageGenerationSelection.Available) -> ImageGenerationFailure?,
    ) : ImageGenerationSource()
}

enum class ImageGenerationPhase {
    QUEUED,
    GENERATING,
    PERSISTING,
}

data class ImageGenerationRequest(
    val id: String = UUID.randomUUID().toString(),
    val source: ImageGenerationSource,
    val selection: ImageGenerationSelection.Available,
    val prompt: String,
    val numOfImages: Int = 1,
    val size: String,
    val partialImages: Int = 0,
    val mediaType: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
    val sourcePaths: String? = null,
    val consumerPlan: GeneratedMediaConsumerPlan = GeneratedMediaConsumerPlan.NONE,
    val editImages: List<String> = emptyList(),
    val onPartial: (suspend (ImageGenerationItem) -> Unit)? = null,
    val onPhase: (suspend (ImageGenerationPhase) -> Unit)? = null,
)

sealed class ImageGenerationOutcome {
    data class Success(val media: List<CommittedGeneratedMedia>) : ImageGenerationOutcome()
    data class Failure(val reason: String, val detail: String? = null) : ImageGenerationOutcome()
}

data class ImageGenerationFailure(val reason: String)

class ImageGenerationCoordinator(
    private val scope: CoroutineScope,
    private val mediaStore: GeneratedMediaStore,
) {
    private val mutex = Mutex()
    private val queue = ArrayDeque<QueuedRequest>()
    private var workerJob: Job? = null
    private var running: QueuedRequest? = null

    suspend fun enqueue(request: ImageGenerationRequest): ImageGenerationOutcome {
        val queued = QueuedRequest(request, CompletableDeferred())
        try {
            request.onPhase?.invoke(ImageGenerationPhase.QUEUED)
            mutex.withLock {
                if (!queued.isActive) return@withLock
                queue.addLast(queued)
                ensureWorkerLocked()
            }
            return queued.result.await()
        } catch (error: Exception) {
            withContext(NonCancellable) {
                cancel(request.id)
            }
            throw error
        }
    }

    suspend fun cancel(requestId: String) {
        val target = mutex.withLock {
            val waiting = queue.firstOrNull { it.request.id == requestId }
            if (waiting != null) {
                queue.remove(waiting)
                waiting
            } else {
                running?.takeIf { it.request.id == requestId }
            }
        } ?: return
        abort(target)
    }

    suspend fun cancelPageSession(sessionId: String) {
        val waiting = mutex.withLock {
            val matches = queue.filter {
                val source = it.request.source
                source is ImageGenerationSource.Page && source.sessionId == sessionId
            }
            queue.removeAll(matches.toSet())
            val current = running?.takeIf {
                val source = it.request.source
                source is ImageGenerationSource.Page && source.sessionId == sessionId
            }
            matches + listOfNotNull(current)
        }
        waiting.forEach(::abort)
    }

    private fun ensureWorkerLocked() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            while (true) {
                val next = mutex.withLock {
                    val item = queue.removeFirstOrNull()
                    if (item == null) {
                        workerJob = null
                        running = null
                    } else {
                        running = item
                    }
                    item
                } ?: break
                process(next)
                mutex.withLock {
                    if (running === next) running = null
                }
            }
        }
    }

    private suspend fun process(queued: QueuedRequest) {
        if (!queued.isActive) return
        try {
            coroutineScope {
                launch {
                    val child = coroutineContext[Job]
                    val handle = queued.control.invokeOnCompletion { cause ->
                        if (cause != null) child?.cancel()
                    }
                    try {
                        executeRequest(queued)
                    } finally {
                        handle.dispose()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (!queued.result.isCompleted) queued.result.cancel(cancelled)
        } catch (error: Exception) {
            Log.e(TAG, "image generation failed", error)
            val classified = classifyProviderFailure(error)
            queued.result.complete(
                ImageGenerationOutcome.Failure(
                    reason = classified.kind.reason,
                    detail = classified.detail,
                )
            )
        } finally {
            queued.control.complete()
        }
    }

    private suspend fun executeRequest(queued: QueuedRequest) {
        if (!queued.isActive) return
        val request = queued.request
        if (request.source is ImageGenerationSource.Tool) {
            val revoked = request.source.revalidate(request.selection)
            if (revoked != null) {
                queued.result.complete(ImageGenerationOutcome.Failure(revoked.reason))
                return
            }
        }
        if (!queued.isActive) return
        request.onPhase?.invoke(ImageGenerationPhase.GENERATING)
        val finals = collectFinals(request)
        if (finals.isEmpty()) {
            queued.result.complete(ImageGenerationOutcome.Failure("invalid_result"))
            return
        }
        request.onPhase?.invoke(ImageGenerationPhase.PERSISTING)
        val modelLabel = request.selection.model.displayName.ifBlank {
            request.selection.model.modelId
        }
        val committed = try {
            finals.map { item ->
                mediaStore.commit(
                    item = item,
                    prompt = request.prompt,
                    modelLabel = modelLabel,
                    type = request.mediaType,
                    sourcePaths = request.sourcePaths,
                    consumerPlan = request.consumerPlan,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "image persistence failed", error)
            queued.result.complete(ImageGenerationOutcome.Failure("persistence_error"))
            return
        }
        queued.result.complete(ImageGenerationOutcome.Success(committed))
    }

    private suspend fun collectFinals(request: ImageGenerationRequest): List<ImageGenerationItem> {
        val needed = request.numOfImages.coerceAtLeast(1)
        val finals = mutableListOf<ImageGenerationItem>()
        val flow = if (request.editImages.isEmpty()) {
            request.selection.provider.generateImage(
                request.selection.effectiveProvider,
                ImageGenerationParams(
                    model = request.selection.model,
                    prompt = request.prompt,
                    numOfImages = request.numOfImages,
                    size = request.size,
                    partialImages = request.partialImages,
                    customHeaders = request.selection.model.customHeaders,
                    customBody = request.selection.model.customBodies,
                ),
            )
        } else {
            request.selection.provider.editImage(
                request.selection.effectiveProvider,
                ImageEditParams(
                    model = request.selection.model,
                    prompt = request.prompt,
                    images = request.editImages,
                    numOfImages = request.numOfImages,
                    size = request.size,
                    partialImages = request.partialImages,
                    customHeaders = request.selection.model.customHeaders,
                    customBody = request.selection.model.customBodies,
                ),
            )
        }
        val parent = coroutineContext[Job]
        val collectorJob = SupervisorJob(parent)
        try {
            withContext(collectorJob) {
                flow.collect { item ->
                    if (item.partial) {
                        request.onPartial?.invoke(item)
                    } else if (finals.size < needed) {
                        finals.add(item)
                        if (finals.size >= needed) {
                            collectorJob.cancel()
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (finals.size < needed) throw cancelled
        } finally {
            collectorJob.complete()
        }
        return finals
    }

    private fun abort(target: QueuedRequest) {
        target.control.cancel()
        if (!target.result.isCompleted) {
            target.result.cancel()
        }
    }

    private class QueuedRequest(
        val request: ImageGenerationRequest,
        val result: CompletableDeferred<ImageGenerationOutcome>,
        val control: CompletableJob = Job(),
    ) {
        val isActive: Boolean
            get() = control.isActive && !result.isCompleted
    }

    companion object {
        private const val TAG = "ImageGenerationCoordinator"
    }
}
