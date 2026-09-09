package net.weero.measix.pilot.data.imggen

import net.weero.measix.pilot.data.enterprise.RealmAccess

import me.rerere.common.configuration.ConfigurationReference
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
import kotlinx.coroutines.ensureActive
import net.weero.measix.pilot.service.runtime.generateImage
import net.weero.measix.pilot.service.runtime.editImage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.classifyProviderFailure

internal sealed interface ImageGenerationSource {
    val access: RealmAccess
    data class Page(
        val selection: net.weero.measix.pilot.data.enterprise.RealmSelection,
        val modelId: ConfigurationReference,
    ) : ImageGenerationSource { override val access get() = selection.access }
    data class Tool(
        override val access: RealmAccess,
        val model: net.weero.measix.pilot.service.ModelExecutionSnapshot,
        val receiveChatArtifact: ((net.weero.measix.pilot.data.files.OwnedArtifact) -> Unit)? = null,
    ) : ImageGenerationSource
}

enum class ImageGenerationPhase {
    QUEUED,
    GENERATING,
    PERSISTING,
}

internal data class ImageGenerationRequest(
    val id: String = UUID.randomUUID().toString(),
    val source: ImageGenerationSource,
    val prompt: String,
    val numOfImages: Int = 1,
    val size: String,
    val partialImages: Int = 0,
    val mediaKind: GeneratedMediaKind = GeneratedMediaKind.GENERATION,
    val sourcePaths: String? = null,
    val editImages: List<String> = emptyList(),
    val onPartial: (suspend (ImageGenerationItem) -> Unit)? = null,
    val onPhase: (suspend (ImageGenerationPhase) -> Unit)? = null,
)

sealed class ImageGenerationOutcome {
    data class Success(val media: List<CommittedGeneratedMedia>, val cleanupPending: Boolean = false) : ImageGenerationOutcome()
    data class Failure(val reason: String, val detail: String? = null) : ImageGenerationOutcome()
}

internal class ImageGenerationCoordinator(
    private val scope: CoroutineScope,
    private val mediaStore: GeneratedMediaStore,
    private val models: net.weero.measix.pilot.service.ModelExecutionService,
    private val providers: me.rerere.ai.provider.ProviderManager,
    private val sessions: net.weero.measix.pilot.data.enterprise.EnterpriseSessionController,
) {
    private val mutex = Mutex()
    // Completed requests remain here until their original resource release succeeds.
    private val requests = linkedMapOf<String, QueuedRequest>()
    private var workerJob: Job? = null

    suspend fun enqueue(request: ImageGenerationRequest): ImageGenerationOutcome {
        val queued = QueuedRequest(request)
        var registered = false
        try {
            suspend fun register() = mutex.withLock {
                coroutineContext.ensureActive()
                check(request.id !in requests) { "image_request_already_registered" }
                requests[request.id] = queued
                registered = true
                ensureWorkerLocked()
            }
            when (val source = request.source) {
                is ImageGenerationSource.Page -> sessions.withSelectedRealmSelection(source.selection) { register() }
                is ImageGenerationSource.Tool -> sessions.withRealmAccess(source.access) { register() }
            }
            val outcome = queued.result.await()
            queued.finished.await()
            return if (outcome is ImageGenerationOutcome.Success) outcome.copy(cleanupPending = queued.cleanupFailure != null)
                else outcome
        } catch (error: Exception) {
            withContext(NonCancellable) {
                if (registered) try { cancelOwned(queued) }
                catch (cleanup: Exception) { if (cleanup !== error) error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    suspend fun cancel(requestId: String) {
        val target = mutex.withLock { requests[requestId]?.also { abortLocked(it) } } ?: return
        target.finished.await()
        release(target)
    }

    private suspend fun cancelOwned(target: QueuedRequest) {
        mutex.withLock { if (requests[target.request.id] === target) abortLocked(target) }
        target.finished.await()
        release(target)
    }

    suspend fun cancelAndAwait(access: RealmAccess.Enterprise) {
        val targets = mutex.withLock {
            requests.values.filter { it.request.source.access == access }.onEach(::abortLocked)
        }
        var failure: Exception? = null
        targets.forEach { target ->
            target.finished.await()
            try { release(target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun abortLocked(target: QueuedRequest) {
        target.control.cancel()
        target.result.cancel()
        if (!target.started) target.finished.complete(Unit)
    }

    private fun ensureWorkerLocked() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            while (true) {
                val next = mutex.withLock {
                    requests.values.firstOrNull { !it.started && !it.finished.isCompleted }?.also { it.started = true }
                        .also { if (it == null) workerJob = null }
                } ?: break
                process(next)
            }
        }
    }

    private suspend fun process(queued: QueuedRequest) {
        try {
            coroutineScope {
                launch {
                    val child = requireNotNull(coroutineContext[Job])
                    val handle = queued.control.invokeOnCompletion { cause -> if (cause != null) child.cancel() }
                    try {
                        child.ensureActive()
                        queued.request.onPhase?.invoke(ImageGenerationPhase.QUEUED)
                        val captured = when (val source = queued.request.source) {
                            is ImageGenerationSource.Tool -> source.model
                            is ImageGenerationSource.Page -> models.capturePageImage(source.selection, child, source.modelId) {
                                check(queued.lease == null) { "image_model_owner_already_bound" }
                                queued.lease = it
                            }
                        }
                        queued.result.complete(executeRequest(queued.request, captured))
                    } finally { handle.dispose() }
                }
            }
        } catch (cancelled: CancellationException) {
            queued.result.cancel(cancelled)
        } catch (error: Exception) {
            Log.e(TAG, "image generation failed", error)
            val classified = classifyProviderFailure(error)
            queued.result.complete(ImageGenerationOutcome.Failure(classified.kind.reason, classified.detail))
        } finally {
            withContext(NonCancellable) {
                try { release(queued) }
                catch (error: Exception) {
                    queued.cleanupFailure = error
                    Log.e(TAG, "image resource release pending", error)
                }
                queued.control.complete()
                queued.finished.complete(Unit)
            }
        }
    }

    private suspend fun release(queued: QueuedRequest) {
        queued.lease?.release()
        mutex.withLock { if (requests[queued.request.id] === queued) requests.remove(queued.request.id) }
        queued.cleanupFailure = null
    }

    private suspend fun executeRequest(
        request: ImageGenerationRequest,
        captured: net.weero.measix.pilot.service.ModelExecutionSnapshot,
    ): ImageGenerationOutcome {
        request.onPhase?.invoke(ImageGenerationPhase.GENERATING)
        val finals = captured.requests.execute { target -> collectFinals(request, captured.model, target) }
        if (finals.isEmpty()) return ImageGenerationOutcome.Failure("invalid_result")
        coroutineContext.ensureActive()
        request.onPhase?.invoke(ImageGenerationPhase.PERSISTING)
        val modelLabel = captured.model.displayName.ifBlank { captured.model.modelId }
        val committed = try {
            suspend fun persist() = finals.map { item ->
                    mediaStore.commit(
                        scope = request.source.access.scope, item = item, prompt = request.prompt,
                        modelLabel = modelLabel, kind = request.mediaKind, sourcePaths = request.sourcePaths,
                        receiveChatArtifact = (request.source as? ImageGenerationSource.Tool)?.receiveChatArtifact,
                    )
                }
            when (val source = request.source) {
                is ImageGenerationSource.Page -> sessions.withSelectedRealmSelection(source.selection) { persist() }
                is ImageGenerationSource.Tool -> sessions.withRealmAccess(source.access) { persist() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Log.e(TAG, "image persistence failed", error)
            return ImageGenerationOutcome.Failure("persistence_error")
        }
        return ImageGenerationOutcome.Success(committed)
    }

    private suspend fun collectFinals(
        request: ImageGenerationRequest,
        model: me.rerere.ai.provider.Model,
        target: net.weero.measix.pilot.service.runtime.ModelRequestTarget,
    ): List<ImageGenerationItem> {
        val needed = request.numOfImages.coerceAtLeast(1)
        val finals = mutableListOf<ImageGenerationItem>()
        val flow = if (request.editImages.isEmpty()) {
            target.generateImage(providers, ImageGenerationParams(
                model = model, prompt = request.prompt, numOfImages = request.numOfImages,
                size = request.size, partialImages = request.partialImages,
                customHeaders = model.customHeaders, customBody = model.customBodies,
            ))
        } else {
            target.editImage(providers, ImageEditParams(
                model = model, prompt = request.prompt, images = request.editImages,
                numOfImages = request.numOfImages, size = request.size, partialImages = request.partialImages,
                customHeaders = model.customHeaders, customBody = model.customBodies,
            ))
        }
        val parent = coroutineContext[Job]
        val collectorJob = SupervisorJob(parent)
        try {
            withContext(collectorJob) {
                flow.collect { item ->
                    if (item.partial) request.onPartial?.invoke(item)
                    else if (finals.size < needed) {
                        finals.add(item)
                        if (finals.size >= needed) collectorJob.cancel()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            parent?.ensureActive()
            if (finals.size < needed) throw cancelled
        } finally { collectorJob.complete() }
        return finals
    }

    private class QueuedRequest(val request: ImageGenerationRequest) {
        val result = CompletableDeferred<ImageGenerationOutcome>()
        val control: CompletableJob = Job()
        val finished = CompletableDeferred<Unit>()
        var started = false
        var lease: net.weero.measix.pilot.service.runtime.ModelExecutionLease? = null
        var cleanupFailure: Exception? = null
    }

    companion object { private const val TAG = "ImageGenerationCoordinator" }
}
