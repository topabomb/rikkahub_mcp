package net.weero.measix.pilot.service.portal

import android.content.Context
import android.content.Intent
import androidx.annotation.MainThread
import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.service.EnterpriseExitService
import java.net.URI
import kotlin.uuid.Uuid

internal sealed interface PortalNativePrompt {
    val id: Uuid
    data class Logout(override val id: Uuid = Uuid.random(), val enterpriseName: String) : PortalNativePrompt
    data class External(override val id: Uuid = Uuid.random(), val url: URI) : PortalNativePrompt
}

/** One document owns its native prompts; UI decisions never acquire authority from a replacement page. */
@MainThread
internal class PortalNativeActions(
    private val context: Context,
    private val document: PortalDocumentContext,
    private val sessions: EnterpriseSessionController,
    private val exits: EnterpriseExitService,
    private val media: PortalMediaSession,
    private val captures: PortalCaptureFactory,
    private val scope: CoroutineScope,
) {
    private data class Pending(val prompt: PortalNativePrompt, val decision: CompletableDeferred<Boolean>)
    private class Capture(val reservation: PortalMediaCapture, val operation: PortalCaptureOperation) {
        var published: PortalMediaHandle? = null
        var cleanup: Deferred<Unit>? = null
    }
    private class CaptureRequest {
        lateinit var task: Deferred<PortalMediaHandle>
        var published: PortalMediaHandle? = null
        var userCancelled = false
    }
    private var activeCapture: CaptureRequest? = null
    private var pending: Pending? = null
    private var interacting = false
    private val ownedCaptures = mutableSetOf<Capture>()
    private var closing: Deferred<Unit>? = null
    private var closed = false
    private val _prompt = MutableStateFlow<PortalNativePrompt?>(null)
    val prompt = _prompt.asStateFlow()
    private val _capture = MutableStateFlow<PortalCaptureOperation?>(null)
    val capture = _capture.asStateFlow()
    val capabilities = listOf("logout", "openExternal", "capturePhoto", "recordAudio", "readMedia", "releaseMedia")
    private val selection get() = document.selection

    suspend fun logout() {
        requireOpen()
        val original = exits.captureRequest() ?: throw PortalFailure("session_expired")
        if (original.selection != selection || original.access != selection.access) throw PortalFailure("session_expired")
        val name = requireNotNull(sessions.portalState(selection).manifest.session).identity.enterpriseName
        confirm(PortalNativePrompt.Logout(enterpriseName = name))
        // Exit owns its application task. Revoking this document cancels only this caller's wait.
        exits.exit(original)
    }

    suspend fun openExternal(url: URI) {
        confirm(PortalNativePrompt.External(url = url))
        sessions.withSelectedRealmSelection(selection) {
            requireOpen()
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toASCIIString().toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun decide(original: PortalNativePrompt, accepted: Boolean) {
        val current = pending ?: return
        if (closed || current.prompt != original) return
        current.decision.complete(accepted)
    }

    suspend fun capturePhoto(): PortalMediaHandle = capture(null)
    suspend fun recordAudio(maxDurationSeconds: Int): PortalMediaHandle = capture(maxDurationSeconds)

    suspend fun readMedia(command: PortalCommand.ReadMedia): PortalMediaChunk {
        requireOpen()
        return media.read(command.mediaId, command.offset, command.maxBytes)
    }

    suspend fun releaseMedia(mediaId: String) { media.release(mediaId) }
    suspend fun sweepMedia() { media.sweep() }

    fun cancelCapture(original: PortalCaptureOperation) {
        if (_capture.value !== original) return
        val request = activeCapture ?: return
        request.userCancelled = true
        request.task.cancel()
        original.cancel()
    }

    private suspend fun capture(maxDurationSeconds: Int?): PortalMediaHandle {
        requireOpen()
        if (interacting || ownedCaptures.isNotEmpty()) throw PortalFailure("resource_limit")
        interacting = true
        val request = CaptureRequest()
        activeCapture = request
        try {
            val handle = supervisorScope {
                request.task = async(start = CoroutineStart.LAZY) { performCapture(maxDurationSeconds, request) }
                request.task.start()
                request.task.await()
            }
            if (request.userCancelled) throw PortalFailure("user_cancelled")
            return handle
        } catch (error: Exception) {
            // A published handle is still native-owned until returned to the bridge dispatcher.
            request.published?.let { handle ->
                try { withContext(NonCancellable) { media.release(handle.mediaId) } }
                catch (cleanup: Exception) { if (cleanup !== error) error.addSuppressed(cleanup) }
            }
            currentCoroutineContext().ensureActive()
            if (error is CancellationException && request.userCancelled) throw PortalFailure("user_cancelled")
            throw error
        } finally {
            activeCapture = null
            _capture.value = null
            interacting = false
        }
    }

    private suspend fun performCapture(maxDurationSeconds: Int?, request: CaptureRequest): PortalMediaHandle {
        var reservation: PortalMediaCapture? = null
        var owned: Capture? = null
        var handle: PortalMediaHandle? = null
        var failure: Exception? = null
        try {
            reservation = media.reserve(if (maxDurationSeconds == null) PortalMediaStore.JPEG else PortalMediaStore.AUDIO_MP4)
            requireOpen()
            val operation = captures.create(reservation, maxDurationSeconds) { effect ->
                sessions.withSelectedRealmSelection(selection) {
                    currentCoroutineContext().ensureActive()
                    requireOpen()
                    effect()
                }
            }
            owned = Capture(reservation, operation).also { ownedCaptures += it }
            _capture.value = operation
            operation.result.await()
            awaitCleanup(operation.close())
            requireOpen()
            handle = media.publish(reservation)
            owned.published = handle
            request.published = handle
        } catch (error: Exception) { failure = error }
        try {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (owned != null) awaitCleanup(cleanCapture(owned))
                else reservation?.let { media.discard(it) }
            }
        } catch (cleanup: Exception) {
            if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
        failure?.let { throw it }
        currentCoroutineContext().ensureActive()
        requireOpen()
        return requireNotNull(handle)
    }

    private fun cleanCapture(capture: Capture): Deferred<Unit> {
        capture.cleanup?.let { if (it.isActive || (it.isCompleted && !it.isCancelled)) return it }
        val hardware = capture.operation.close()
        return scope.async(NonCancellable + Dispatchers.Main.immediate) {
            hardware.await()
            if (capture.published == null) media.discard(capture.reservation)
            ownedCaptures.remove(capture)
            Unit
        }.also { capture.cleanup = it }
    }

    /** This receipt owns only hardware/files; it must never enter Session admission or join bridge jobs. */
    fun close(): Deferred<Unit> {
        closed = true
        pending?.decision?.cancel()
        _prompt.value = null
        _capture.value = null
        closing?.let { if (it.isActive || (it.isCompleted && !it.isCancelled)) return it }
        val receipts = ownedCaptures.toList().map { capture ->
            try { cleanCapture(capture) }
            catch (error: Exception) { CompletableDeferred<Unit>().apply { completeExceptionally(error) } }
        }
        return scope.async(NonCancellable + Dispatchers.Main.immediate) {
            var failure: Exception? = null
            receipts.forEach { receipt ->
                try { receipt.await() }
                catch (error: Exception) {
                    if (failure == null) failure = error else if (error !== failure) failure?.addSuppressed(error)
                }
            }
            failure?.let { throw it }
            media.close()
        }.also { closing = it }
    }

    private suspend fun confirm(prompt: PortalNativePrompt) {
        requireOpen()
        if (interacting || ownedCaptures.isNotEmpty()) throw PortalFailure("resource_limit")
        val request = Pending(prompt, CompletableDeferred())
        interacting = true
        pending = request
        _prompt.value = prompt
        try {
            if (!request.decision.await()) throw PortalFailure("user_cancelled")
            currentCoroutineContext().ensureActive()
            requireOpen()
        } finally {
            if (pending === request) { pending = null; _prompt.value = null; interacting = false }
        }
    }

    private fun requireOpen() {
        if (closed) throw PortalFailure("session_expired")
        document.requireUnexpired()
    }

    private suspend fun awaitCleanup(receipt: Deferred<Unit>) {
        if (withTimeoutOrNull(10_000) { receipt.await(); true } != true) throw PortalFailure("timeout")
    }
}
