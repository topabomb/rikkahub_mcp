package net.weero.measix.pilot.service.portal

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import androidx.annotation.MainThread
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.EnterpriseSynchronizationService

/** One approved top-level document owns its requests; all calls and callbacks run on the UI dispatcher. */
internal class PortalDocument private constructor(
    val id: String,
    val selection: RealmSelection,
    private val expiresAtMillis: Long,
    private val sessions: EnterpriseSessionController,
    private val synchronization: EnterpriseSynchronizationService,
    parentScope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val onClosed: () -> Unit,
) : AutoCloseable {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Main.immediate)
    private var closed = false
    private val seen = mutableSetOf<String>()
    private val requests = mutableMapOf<String, Job>()
    private var lastFeedRefresh: Long? = null
    val bootstrap: String get() = PortalProtocol.bootstrap(id)
    val isClosed: Boolean get() = closed

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        scope.launch {
            sessions.observeSelectedRealmSelection().collect { current -> if (current != selection) close() }
        }
        scope.launch {
            delay((expiresAtMillis - nowMillis()).coerceAtLeast(1))
            close()
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
        val deadlineNanos = System.nanoTime() + 10_000_000_000L
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0) {
                    replyTimeout(requestId, reply)
                    return@launch
                }
                withTimeout(remainingMillis) {
                    dispatch(fields, requestId, reply)
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

    private suspend fun dispatch(fields: JsonObject, requestId: String, reply: (String) -> Unit) {
        try {
            authorize()
            val request = PortalProtocol.request(fields)
            respond(requestId, reply, result = execute(request.command))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PortalFailure) {
            respond(requestId, reply, failure = failure)
        } catch (failure: EnterpriseFeedException) {
            respond(requestId, reply, failure = PortalFailure(when (failure.reason) {
                "enterprise_update_not_found" -> "enterprise_update_not_found"
                "invalid_request" -> "invalid_request"
                else -> "source_unavailable"
            }))
        } catch (failure: EnterpriseConfigurationException) {
            val code = if ((fields["method"] as? JsonPrimitive)?.content == "refresh" &&
                failure.reason !in setOf("enterprise_configuration_not_ready", "enterprise_feed_not_ready",
                    "enterprise_data_access_unavailable", "enterprise_session_required", "enterprise_selection_revoked")) {
                "configuration_invalid"
            } else when (failure.reason) {
                "enterprise_configuration_not_ready", "enterprise_feed_not_ready" -> "configuration_not_ready"
                else -> "source_unavailable"
            }
            respond(requestId, reply, failure = PortalFailure(code))
        } catch (_: Exception) {
            respond(requestId, reply, failure = PortalFailure("source_unavailable"))
        }
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
        PortalCommand.Close -> { close(); buildJsonObject {} }
        is PortalCommand.Cancel -> { requests[command.targetRequestId]?.cancel(); buildJsonObject {} }
        else -> throw PortalFailure("unsupported_method")
    }

    private suspend fun status(): JsonObject {
        val state = sessions.portalState(selection)
        if ((state.manifest.applied?.generation ?: 0) > 9007199254740991) throw PortalFailure("configuration_invalid")
        return buildJsonObject {
            put("managedReady", state.configuration != null && state.manifest.applied != null)
            put("appliedManagedGeneration", state.manifest.applied?.generation?.let(::JsonPrimitive) ?: JsonNull)
            put("lastConfigurationSync", state.manifest.lastConfigurationSyncMillis?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
            put("lastEnterpriseUpdateRefresh", lastFeedRefresh?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
            putJsonArray("capabilities") { CAPABILITIES.forEach { add(it) } }
        }
    }

    private suspend fun authorize() {
        if (closed) throw CancellationException("Portal document closed")
        if (nowMillis() >= expiresAtMillis) throw PortalFailure("session_expired")
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

    private suspend fun respond(requestId: String, reply: (String) -> Unit, result: JsonObject? = null, failure: PortalFailure? = null) {
        if (closed) return
        try {
            // The Session barrier covers the actual native reply, not merely creation of response JSON.
            sessions.withSelectedRealmSelection(selection) {
                if (!closed) {
                    val error = if (nowMillis() >= expiresAtMillis) PortalFailure("session_expired") else failure
                    deliver(reply, if (error != null) PortalProtocol.error(id, requestId, error)
                        else PortalProtocol.success(id, requestId, requireNotNull(result)))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: EnterpriseConfigurationException) {
            close()
        }
    }

    private fun deliver(reply: (String) -> Unit, response: String) {
        try { reply(response) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { close() }
    }

    @MainThread
    override fun close() {
        if (closed) return
        closed = true
        lifetime.cancel()
        requests.clear()
        seen.clear()
        lastFeedRefresh = null
        onClosed()
    }

    companion object {
        private val CAPABILITIES = listOf("getStatus", "refresh", "close", "cancel", "getLocalContext", "listLocalUpdates", "getLocalUpdate")
        suspend fun open(
            selection: RealmSelection,
            sessions: EnterpriseSessionController,
            synchronization: EnterpriseSynchronizationService,
            scope: CoroutineScope,
            nowMillis: () -> Long = System::currentTimeMillis,
            onClosed: () -> Unit,
        ): PortalDocument {
            var created: PortalDocument? = null
            try {
                return withContext(Dispatchers.Main.immediate) {
                    val state = sessions.portalState(selection)
                    val session = requireNotNull(state.manifest.session)
                    if (!session.identity.authority.isLocal) throw PortalFailure("source_forbidden")
                    val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                    PortalDocument(id, selection, minOf(session.expiresAtMillis, nowMillis() + 600_000),
                        sessions, synchronization, scope, nowMillis, onClosed).also {
                        created = it
                        if (it.isClosed || !it.lifetime.isActive) throw CancellationException("Portal document unavailable")
                    }
                }
            } catch (failure: Exception) {
                // A dispatcher return can reject ownership after the independent document has been created.
                withContext(NonCancellable + Dispatchers.Main.immediate) { created?.close() }
                throw failure
            }
        }
    }
}
