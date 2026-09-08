package net.weero.measix.pilot.service.portal

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import androidx.annotation.MainThread
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.EnterpriseSynchronizationService
import kotlin.coroutines.EmptyCoroutineContext

internal enum class PortalCloseReason { USER_REQUEST, HOST_DISPOSED, DOCUMENT_REPLACED, DOCUMENT_EXPIRED, AUTHORIZATION_REVOKED, HOST_FAILURE }
internal data class PortalClosure(val documentId: String, val reason: PortalCloseReason)

/** Immutable identity and deadline shared by bridge dispatch and this document's native actions. */
internal class PortalDocumentContext(
    val id: String,
    val selection: RealmSelection,
    val expiresAtMillis: Long,
    val nowMillis: () -> Long,
) {
    fun requireUnexpired() {
        if (nowMillis() >= expiresAtMillis) throw PortalFailure("session_expired")
    }
}

/** One approved top-level document owns its requests; all calls and callbacks run on the UI dispatcher. */
internal class PortalDocument private constructor(
    private val context: PortalDocumentContext,
    private val sessions: EnterpriseSessionController,
    private val synchronization: EnterpriseSynchronizationService,
    private val parentScope: CoroutineScope,
    private val closeHost: () -> Deferred<Unit>,
    val native: PortalNativeActions?,
    private val onClosed: (PortalClosure) -> Unit,
) : AutoCloseable {
    val id get() = context.id
    val selection get() = context.selection
    private val expiresAtMillis get() = context.expiresAtMillis
    private val nowMillis get() = context.nowMillis
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Main.immediate)
    private var closed = false
    private var hostClosing: Deferred<Unit>? = null
    private var closure: PortalClosure? = null
    @Volatile private var hostClosed = false
    private var notified = false
    private val completion = CompletableDeferred<Unit>()
    private val seen = mutableSetOf<String>()
    private val requests = mutableMapOf<String, Job>()
    private var lastFeedRefresh: Long? = null
    val bootstrap: String get() = PortalProtocol.bootstrap(id)
    val isClosed: Boolean get() = closed
    internal val isHostClosed: Boolean get() = hostClosed

    init {
        lifetime.invokeOnCompletion { completeClosure() }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!closed) close(PortalCloseReason.HOST_DISPOSED) }
        }
        scope.launch {
            sessions.observeSelectedRealmSelection().collect { current ->
                if (current != selection) close(PortalCloseReason.AUTHORIZATION_REVOKED)
            }
        }
        scope.launch {
            delay((expiresAtMillis - nowMillis()).coerceAtLeast(1))
            close(PortalCloseReason.DOCUMENT_EXPIRED)
        }
        if (native != null) scope.launch {
            while (isActive) {
                delay(1_000)
                try { native.sweepMedia() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { close(PortalCloseReason.HOST_FAILURE) }
            }
        }
    }

    /** reply is the original JavaScriptReplyProxy, never a lookup of the current WebView or page. */
    @MainThread
    fun receive(raw: String, sourceOrigin: String, isMainFrame: Boolean, reply: (String) -> Unit): Job? {
        if (closed || sourceOrigin != PortalProtocol.LOCAL_ORIGIN || !isMainFrame) return null
        val fields = try { PortalProtocol.decode(raw) } catch (_: PortalFailure) { return null }
        val documentId = try { PortalProtocol.identifier(fields, "documentId") } catch (_: PortalFailure) { return null }
        if (documentId != id) return null
        val requestId = try { PortalProtocol.identifier(fields, "requestId") } catch (_: PortalFailure) { return null }
        // Replayed IDs cannot take ownership of either an in-flight or completed request's reply channel.
        if (!seen.add(requestId)) return null
        val request = try { Result.success(PortalProtocol.request(fields)) }
        catch (failure: PortalFailure) { Result.failure(failure) }
        val durationNanos = when (request.getOrNull()?.command) {
            PortalCommand.CapturePhoto, is PortalCommand.RecordAudio -> 120_000_000_000L
            else -> 10_000_000_000L
        }
        val deadlineNanos = System.nanoTime() + durationNanos
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0) {
                    replyTimeout(requestId, reply)
                    return@launch
                }
                withTimeout(remainingMillis) {
                    dispatch(fields, request, requestId, reply)
                }
            } catch (_: TimeoutCancellationException) {
                replyTimeout(requestId, reply)
            } finally {
                requests.remove(requestId)
            }
        }
        requests[requestId] = job
        job.start()
        return job
    }

    private suspend fun dispatch(fields: JsonObject, request: Result<PortalRequest>, requestId: String, reply: (String) -> Unit) {
        var capture: PortalMediaHandle? = null
        var delivered = false
        var primary: Exception? = null
        try {
            authorize()
            val command = request.getOrThrow().command
            val result = when (command) {
                PortalCommand.CapturePhoto -> nativeActions().capturePhoto().also { capture = it }.json()
                is PortalCommand.RecordAudio -> nativeActions().recordAudio(command.maxDurationSeconds).also { capture = it }.json()
                else -> execute(command)
            }
            delivered = respond(requestId, reply, result = result)
        } catch (cancelled: CancellationException) {
            primary = cancelled
            throw cancelled
        } catch (failure: PortalFailure) {
            primary = failure
            respond(requestId, reply, failure = failure)
        } catch (failure: EnterpriseFeedException) {
            primary = failure
            respond(requestId, reply, failure = PortalFailure(when (failure.reason) {
                "enterprise_update_not_found" -> "enterprise_update_not_found"
                "invalid_request" -> "invalid_request"
                else -> "source_unavailable"
            }))
        } catch (failure: EnterpriseConfigurationException) {
            primary = failure
            val code = if ((fields["method"] as? JsonPrimitive)?.content == "refresh" &&
                failure.reason !in setOf("enterprise_configuration_not_ready", "enterprise_feed_not_ready",
                    "enterprise_data_access_unavailable", "enterprise_session_required", "enterprise_selection_revoked")) {
                "configuration_invalid"
            } else when (failure.reason) {
                "enterprise_configuration_not_ready", "enterprise_feed_not_ready" -> "configuration_not_ready"
                else -> "source_unavailable"
            }
            respond(requestId, reply, failure = PortalFailure(code))
        } catch (failure: Exception) {
            primary = failure
            respond(requestId, reply, failure = PortalFailure("source_unavailable"))
        } finally {
            if (capture != null && !delivered) {
                try { withContext(NonCancellable) { nativeActions().releaseMedia(capture.mediaId) } }
                catch (cleanup: Exception) {
                    if (primary != null && primary !== cleanup) primary.addSuppressed(cleanup)
                    close(PortalCloseReason.HOST_FAILURE)
                }
            }
        }
    }

    private fun PortalMediaHandle.json() = buildJsonObject {
        put("mediaId", mediaId); put("mimeType", mimeType); put("byteLength", byteLength)
    }

    private suspend fun execute(command: PortalCommand): JsonObject = when (command) {
        PortalCommand.GetStatus -> status()
        PortalCommand.GetLocalContext -> {
            val session = requireNotNull(sessions.portalState(selection).manifest.session)
            buildJsonObject {
                put("formatVersion", 1); put("kind", "LOCAL_EXAMPLE")
                put("sourceNamespace", session.identity.authority.sourceNamespace)
                put("deploymentId", session.identity.authority.deploymentId)
                put("userId", session.identity.userId); put("sessionId", session.id); put("documentId", id)
                put("enterpriseName", session.identity.enterpriseName); put("userDisplayName", session.identity.userName)
                put("expiresAt", Instant.ofEpochMilli(expiresAtMillis).toString())
                put("sessionIdleExpiresAt", Instant.ofEpochMilli(session.expiresAtMillis).toString())
            }
        }
        is PortalCommand.ListLocalUpdates -> {
            val result = sessions.listFeed(selection, command.query)
            lastFeedRefresh = nowMillis()
            buildJsonObject {
                put("kind", if (result.etag == command.ifNoneMatch) "notModified" else "modified")
                put("etag", result.etag)
                if (result.etag != command.ifNoneMatch) put("feed", EnterprisePackageCodec.json.encodeToJsonElement(result.body))
            }
        }
        is PortalCommand.GetLocalUpdate ->
            EnterprisePackageCodec.json.encodeToJsonElement(sessions.feedDetail(selection, command.id)).jsonObject
        PortalCommand.Refresh -> {
            synchronization.synchronize(selection.access as RealmAccess.Enterprise)
            status()
        }
        PortalCommand.Close -> { close(PortalCloseReason.USER_REQUEST); buildJsonObject {} }
        PortalCommand.Logout -> { nativeActions().logout(); buildJsonObject {} }
        is PortalCommand.OpenExternal -> { nativeActions().openExternal(command.url); buildJsonObject {} }
        is PortalCommand.ReadMedia -> nativeActions().readMedia(command).let { chunk -> buildJsonObject {
            put("dataBase64", chunk.dataBase64); put("nextOffset", chunk.nextOffset); put("eof", chunk.eof)
        } }
        is PortalCommand.ReleaseMedia -> { nativeActions().releaseMedia(command.mediaId); buildJsonObject {} }
        is PortalCommand.Cancel -> { requests[command.targetRequestId]?.cancel(); buildJsonObject {} }
        else -> throw PortalFailure("unsupported_method")
    }

    private fun nativeActions(): PortalNativeActions = native ?: throw PortalFailure("unsupported_method")

    private suspend fun status(): JsonObject {
        val state = sessions.portalState(selection)
        if ((state.manifest.applied?.generation ?: 0) > 9007199254740991) throw PortalFailure("configuration_invalid")
        return buildJsonObject {
            put("managedReady", state.configuration != null && state.manifest.applied != null)
            put("appliedManagedGeneration", state.manifest.applied?.generation?.let(::JsonPrimitive) ?: JsonNull)
            put("lastConfigurationSync", state.manifest.lastConfigurationSyncMillis?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
            put("lastEnterpriseUpdateRefresh", lastFeedRefresh?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
            putJsonArray("capabilities") { (CAPABILITIES + native?.capabilities.orEmpty()).forEach { add(it) } }
        }
    }

    private suspend fun authorize() {
        if (closed) throw CancellationException("Portal document closed")
        context.requireUnexpired()
        sessions.withSelectedRealmSelection(selection) { Unit }
    }

    private fun replyTimeout(requestId: String, reply: (String) -> Unit) {
        if (closed) return
        // A timed-out request cannot wait again for the Session lock merely to send its error.
        sessions.tryWithSelectedRealmSelection(selection) {
            if (!closed) deliver(reply, PortalProtocol.error(id, requestId,
                PortalFailure(if (nowMillis() >= expiresAtMillis) "session_expired" else "timeout")))
        }
    }

    private suspend fun respond(requestId: String, reply: (String) -> Unit, result: JsonObject? = null, failure: PortalFailure? = null): Boolean {
        if (closed) return false
        var delivered = false
        try {
            // The Session barrier covers the actual native reply, not merely creation of response JSON.
            sessions.withSelectedRealmSelection(selection) {
                if (!closed) {
                    val error = if (nowMillis() >= expiresAtMillis) PortalFailure("session_expired") else failure
                    delivered = deliver(reply, if (error != null) PortalProtocol.error(id, requestId, error)
                        else PortalProtocol.success(id, requestId, requireNotNull(result))) && error == null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: EnterpriseConfigurationException) {
            close(PortalCloseReason.AUTHORIZATION_REVOKED)
        }
        return delivered
    }

    private fun deliver(reply: (String) -> Unit, response: String): Boolean {
        try { reply(response); return true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { close(PortalCloseReason.HOST_FAILURE); return false }
    }

    @MainThread
    override fun close() = close(PortalCloseReason.HOST_DISPOSED)

    @MainThread
    fun close(reason: PortalCloseReason) {
        if (!closed) {
            closed = true
            closure = PortalClosure(id, reason)
            requests.clear()
            seen.clear()
            lastFeedRefresh = null
            lifetime.cancel()
        }
        if (hostClosed || hostClosing?.isActive == true) return
        val receipts = listOfNotNull<() -> Deferred<Unit>>({ closeHost() }, native?.let { { it.close() } }).map { start ->
            try { start() }
            catch (failure: Exception) { CompletableDeferred<Unit>().apply { completeExceptionally(failure) } }
        }
        val attempt = parentScope.async(NonCancellable + Dispatchers.Main.immediate) {
            var failure: Exception? = null
            receipts.forEach { receipt ->
                try { receipt.await() }
                catch (error: Exception) {
                    if (failure == null) failure = error else if (error !== failure) failure?.addSuppressed(error)
                }
            }
            failure?.let { throw it }
            Unit
        }
        hostClosing = attempt
        attempt.invokeOnCompletion { failure ->
            val completed = Runnable {
                if (hostClosing !== attempt || failure != null) return@Runnable
                hostClosed = true
                completeClosure()
                if (!notified) {
                    notified = true
                    try { onClosed(requireNotNull(closure)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { android.util.Log.e("PortalDocument", "Portal close notification failed", error) }
                }
            }
            val main = Dispatchers.Main.immediate
            if (main.isDispatchNeeded(EmptyCoroutineContext)) main.dispatch(EmptyCoroutineContext, completed)
            else completed.run()
        }
    }

    private fun completeClosure() {
        if (hostClosed && lifetime.isCompleted) completion.complete(Unit)
    }

    /** Host completion has no dependency on Session admission, so it may precede a selection commit. */
    suspend fun awaitHostClosed() {
        val pending = withContext(Dispatchers.Main.immediate) { requireNotNull(hostClosing) }
        // System browsing-data deletion cannot be cancelled. A timeout only ends this wait.
        if (withTimeoutOrNull(10_000) { pending.await(); true } != true) throw PortalFailure("timeout")
    }

    /** Called by an external lifecycle owner, never by a request belonging to this document. */
    suspend fun awaitClosed() {
        lifetime.join()
        awaitHostClosed()
        completion.await()
    }

    internal fun invokeOnCompletion(handler: () -> Unit) { completion.invokeOnCompletion { handler() } }

    companion object {
        private val CAPABILITIES = listOf("getStatus", "refresh", "close", "cancel", "getLocalContext", "listLocalUpdates", "getLocalUpdate")
        suspend fun open(
            selection: RealmSelection,
            sessions: EnterpriseSessionController,
            synchronization: EnterpriseSynchronizationService,
            scope: CoroutineScope,
            registry: PortalDocumentRegistry,
            nowMillis: () -> Long = System::currentTimeMillis,
            closeHost: () -> Deferred<Unit> = { CompletableDeferred(Unit) },
            createNative: (suspend (PortalDocumentContext) -> PortalNativeActions)? = null,
            onClosed: (PortalClosure) -> Unit,
        ): PortalDocument {
            var created: PortalDocument? = null
            var native: PortalNativeActions? = null
            try {
                return withContext(Dispatchers.Main.immediate) {
                    registry.awaitHostAvailable()
                    val state = sessions.portalState(selection)
                    val session = requireNotNull(state.manifest.session)
                    if (!session.identity.authority.isLocal) throw PortalFailure("source_forbidden")
                    val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                    sessions.withSelectedRealmSelection(selection) {
                        registry.requireHostAvailable()
                        val context = PortalDocumentContext(id, selection, minOf(session.expiresAtMillis, nowMillis() + 600_000), nowMillis)
                        native = createNative?.invoke(context)
                        PortalDocument(context, sessions, synchronization, scope, closeHost, native, onClosed).also {
                            created = it
                            if (it.isClosed || !it.lifetime.isActive) throw CancellationException("Portal document unavailable")
                            registry.register(it)
                        }
                    }
                }
            } catch (failure: Exception) {
                // A dispatcher return can reject ownership after the independent document has been created.
                try {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        created?.close()
                        created?.awaitClosed()
                        if (created == null && native != null) {
                            val receipt = native!!.close()
                            if (withTimeoutOrNull(10_000) { receipt.await(); true } != true) throw PortalFailure("timeout")
                        }
                    }
                } catch (cleanup: Exception) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
