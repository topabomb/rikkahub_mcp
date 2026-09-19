package net.weero.measix.pilot.data.enterprise

import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import kotlin.uuid.Uuid

internal sealed interface EnterpriseState {
    data object Loading : EnterpriseState
    data class Available(
        val manifest: EnterpriseManifest,
        val configuration: EnterpriseConfiguration?,
        val modelCapabilities: Map<String, me.rerere.ai.provider.ChatTransportCapabilities> = emptyMap(),
    ) : EnterpriseState
    data class Failed(val reason: String) : EnterpriseState
}

internal data class EnterpriseExitRequest(val access: RealmAccess.Enterprise, val selection: RealmSelection)
internal data class RealmSwitchRequest(val selection: RealmSelection, val target: RealmAccess)
internal data class EnterprisePresentation(val state: EnterpriseState, val selection: RealmSelection?)
internal data class EnterpriseExitToken(val access: RealmAccess.Enterprise, val reason: EnterpriseExitReason)
internal sealed interface EnterpriseExitSignal {
    data class Closing(val token: EnterpriseExitToken) : EnterpriseExitSignal
    data class Expired(val access: RealmAccess.Enterprise) : EnterpriseExitSignal
}

/** A lease pins one immutable execution source; authoritative preflight and local policy grant admission. */
internal class EnterpriseExecutionLease internal constructor(
    internal val id: String,
    val sessionId: String,
    val scope: ConfigurationScope.Enterprise,
    val version: EnterpriseAppliedVersion,
    private val candidate: EnterpriseCandidate,
    private val releaseOwner: suspend (EnterpriseExecutionLease) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)
    private val releaseMutex = Mutex()
    private var released = false

    val execution: EnterpriseExecution get() { requireAvailable(); return candidate.execution }
    val configuration: EnterpriseConfiguration get() { requireAvailable(); return candidate.configuration }

    private fun requireAvailable() {
        if (closed.get() || revoked.get()) throw EnterpriseConfigurationException("enterprise_execution_lease_unavailable")
    }

    internal fun revoke() { revoked.set(true) }

    /** Await outside Session admission; failure retains ownership so another call can retry cleanup. */
    suspend fun release() {
        closed.set(true)
        withContext(NonCancellable) {
            releaseMutex.withLock {
                if (!released) {
                    releaseOwner(this@EnterpriseExecutionLease)
                    released = true
                }
            }
        }
    }
}

/** Serializes session changes with the only durable enterprise publication protocol. */
internal class EnterpriseSessionController(
    private val store: EnterpriseAppliedStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var loaded: LoadedEnterpriseState? = null
    private val leases = mutableMapOf<String, EnterpriseExecutionLease>()
    private val _state = MutableStateFlow<EnterpriseState>(EnterpriseState.Loading)
    val state: StateFlow<EnterpriseState> = _state.asStateFlow()
    private val _selectionRevision = MutableStateFlow(0L)
    val selectionRevision: StateFlow<Long> = _selectionRevision.asStateFlow()
    private var enrollmentAttempt: PlatformEnrollmentAttempt? = null
    private val platformAccessTokens = mutableMapOf<String, PlatformAccessToken>()

    suspend fun beginPlatformEnrollment(): PlatformEnrollmentAttempt = mutex.withLock {
        val manifest = ensureLoaded().manifest
        if (manifest.session != null || manifest.pendingEnrollment != null) fail("exit_current_enterprise_first")
        if (enrollmentAttempt != null) fail("enterprise_enrollment_in_progress")
        PlatformEnrollmentAttempt(Uuid.random().toString(), withContext(Dispatchers.IO) { store.installationId() })
            .also { enrollmentAttempt = it }
    }

    suspend fun finishEnrollmentAttempt(attempt: PlatformEnrollmentAttempt) = mutex.withLock {
        if (enrollmentAttempt == attempt) enrollmentAttempt = null
    }

    /** An exchanged one-use code now belongs to this operation; persist its refresh credential before Bootstrap. */
    suspend fun acceptPlatformEnrollment(attempt: PlatformEnrollmentAttempt, connection: PlatformConnection,
        response: PlatformEnrollmentExchangeResponse): String = withContext(NonCancellable) { mutex.withLock {
        val current = ensureLoaded().manifest
        if (enrollmentAttempt != attempt || current.session != null || current.pendingEnrollment != null) fail("enterprise_enrollment_replaced")
        if (response.deploymentId != connection.discovery.deploymentId) fail("enterprise_enrollment_identity_mismatch")
        val idle = Instant.parse(response.sessionIdleExpiresAt).toEpochMilli()
        val refresh = PlatformRefreshCredential(response.sessionId, response.refreshToken, Instant.parse(response.refreshExpiresAt).toEpochMilli())
        val credential = withContext(Dispatchers.IO) { store.prepareCredential(refresh) }
        val pending = PendingPlatformEnrollment(response.sessionId, response.userId, idle,
            PlatformSessionDetails(connection, response.deviceId, credential))
        publish(current.copy(phase = EnterpriseSessionPhase.SIGNED_OUT, pendingEnrollment = pending))
        platformAccessTokens[response.sessionId] = PlatformAccessToken(response.accessToken, Instant.parse(response.accessTokenExpiresAt).toEpochMilli())
        enrollmentAttempt = null
        response.sessionId
    } }

    suspend fun pendingPlatformEnrollment(): PendingPlatformEnrollment? = mutex.withLock { ensureLoaded().manifest.pendingEnrollment }

    /** Only a confirmed replacement may discard an exchanged but uncompleted enrollment. */
    suspend fun abandonPendingPlatformEnrollment(expectedSessionId: String) = mutex.withLock {
        val manifest = ensureLoaded().manifest
        if (manifest.session != null || manifest.pendingEnrollment?.sessionId != expectedSessionId) fail("enterprise_enrollment_replaced")
        publish(manifest.copy(pendingEnrollment = null))
        prune(requireNotNull(loaded).manifest)
    }

    suspend fun capturePendingPlatformLogout(expected: PendingPlatformEnrollment): PlatformLogoutRequest = mutex.withLock {
        if (ensureLoaded().manifest.pendingEnrollment != expected) fail("enterprise_enrollment_replaced")
        PlatformLogoutRequest(expected.platform.connection, withContext(Dispatchers.IO) {
            store.credential(expected.platform.credential, expected.sessionId)
        })
    }

    suspend fun platformSessionAccess(sessionId: String): RealmAccess.Enterprise? = mutex.withLock {
        ensureLoaded().manifest.session?.takeIf { it.id == sessionId && it.platform != null }?.let {
            RealmAccess.Enterprise(it.identity.scope, it.id)
        }
    }

    suspend fun recoverablePlatformAccess(): RealmAccess.Enterprise? = mutex.withLock {
        val manifest = ensureLoaded().manifest
        manifest.session?.takeIf { session ->
            session.platform != null && session.expiresAtMillis > nowMillis() &&
                manifest.phase in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE,
                    EnterpriseSessionPhase.CONFIGURATION_PENDING)
        }?.let { RealmAccess.Enterprise(it.identity.scope, it.id) }
    }

    suspend fun platformContext(sessionId: String): PlatformSessionContext = mutex.withLock { platformContextLocked(sessionId) }

    private suspend fun platformContextLocked(sessionId: String): PlatformSessionContext {
        val manifest = ensureLoaded().manifest
        if (manifest.phase == EnterpriseSessionPhase.CLOSING) fail("enterprise_exit_in_progress")
        val context = manifest.pendingEnrollment?.takeIf { it.sessionId == sessionId }?.let {
            PlatformSessionContext(it.sessionId, it.userId, it.expiresAtMillis, it.platform)
        } ?: manifest.session?.takeIf { it.id == sessionId }?.let {
            PlatformSessionContext(it.id, it.identity.userId, it.expiresAtMillis, it.platform ?: fail("platform_session_required"))
        } ?: fail("enterprise_data_access_unavailable")
        if (context.expiresAtMillis <= nowMillis()) fail("enterprise_session_expired")
        return context
    }

    suspend fun platformAccessToken(sessionId: String): PlatformAccessToken? = mutex.withLock {
        platformContextLocked(sessionId)
        platformAccessTokens[sessionId]?.takeIf { it.expiresAtMillis > nowMillis() + 30_000 }
    }

    suspend fun beginPlatformRefresh(sessionId: String): PlatformRefreshAttempt = mutex.withLock {
        val context = platformContextLocked(sessionId)
        val stored = withContext(Dispatchers.IO) { store.credential(context.platform.credential, sessionId) }
        if (stored.refreshExpiresAtMillis <= nowMillis()) fail("enterprise_refresh_expired")
        if (stored.pendingIdempotencyKey != null) return@withLock PlatformRefreshAttempt(context, stored)
        val pending = stored.copy(pendingIdempotencyKey = "idem_${Uuid.random()}")
        val version = withContext(Dispatchers.IO) { store.prepareCredential(pending) }
        val updated = context.copy(platform = context.platform.copy(credential = version))
        publishPlatformCredential(updated)
        platformAccessTokens.remove(sessionId)
        PlatformRefreshAttempt(updated, pending)
    }

    suspend fun acceptPlatformRefresh(attempt: PlatformRefreshAttempt, response: PlatformRefreshResponse) = mutex.withLock {
        val current = platformContextLocked(attempt.context.sessionId)
        if (current.platform.credential != attempt.context.platform.credential) fail("enterprise_refresh_replaced")
        val next = PlatformRefreshCredential(current.sessionId, response.refreshToken, Instant.parse(response.refreshExpiresAt).toEpochMilli())
        val version = withContext(Dispatchers.IO) { store.prepareCredential(next) }
        publishPlatformCredential(current.copy(expiresAtMillis = Instant.parse(response.sessionIdleExpiresAt).toEpochMilli(),
            platform = current.platform.copy(credential = version)))
        platformAccessTokens[current.sessionId] = PlatformAccessToken(response.accessToken, Instant.parse(response.accessTokenExpiresAt).toEpochMilli())
    }

    private suspend fun publishPlatformCredential(context: PlatformSessionContext) {
        val manifest = ensureLoaded().manifest
        val pending = manifest.pendingEnrollment
        if (pending?.sessionId == context.sessionId) {
            publish(manifest.copy(pendingEnrollment = pending.copy(platform = context.platform, expiresAtMillis = context.expiresAtMillis)))
        } else {
            val session = manifest.session ?: fail("enterprise_session_required")
            if (session.id != context.sessionId) fail("enterprise_data_access_unavailable")
            publish(manifest.copy(session = session.copy(platform = context.platform, expiresAtMillis = context.expiresAtMillis)))
        }
    }

    suspend fun completePlatformBootstrap(sessionId: String, bootstrap: PlatformBootstrap): RealmAccess.Enterprise = mutex.withLock {
        val current = ensureLoaded().manifest
        val pending = current.pendingEnrollment ?: fail("platform_bootstrap_not_pending")
        if (pending.sessionId != sessionId || bootstrap.session.sessionId != sessionId || bootstrap.user.userId != pending.userId ||
            bootstrap.device.deviceId != pending.platform.deviceId || bootstrap.deployment.deploymentId != pending.platform.connection.discovery.deploymentId) {
            fail("platform_bootstrap_identity_mismatch")
        }
        if (bootstrap.device.status != PlatformBootstrapDeviceStatus.ACTIVE || 4L !in bootstrap.supportedSnapshotSchemaVersions) fail("platform_bootstrap_unavailable")
        val identity = EnterpriseIdentity(pending.platform.connection.authority, bootstrap.deployment.name, pending.userId, bootstrap.user.displayName)
        EnterpriseConfigurationCodec.validateIdentity(identity)
        val expires = minOf(Instant.parse(bootstrap.session.expiresAt).toEpochMilli(), Instant.parse(bootstrap.session.sessionIdleExpiresAt).toEpochMilli())
        val session = EnterpriseSession(sessionId, identity, expires, pending.platform)
        publish(current.copy(phase = EnterpriseSessionPhase.CONFIGURATION_PENDING, session = session, lastIdentity = identity, pendingEnrollment = null))
        RealmAccess.Enterprise(identity.scope, session.id)
    }

    suspend fun recover(): EnterpriseState = mutex.withLock {
        try {
            ensureLoaded()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loaded = null
            publishState(EnterpriseState.Failed(safeReason(error)))
        }
        state.value
    }

    suspend fun platformConfiguration(access: RealmAccess.Enterprise): PlatformConfigurationInput = mutex.withLock {
        val current = ensureLoaded()
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        val session = requireNotNull(current.manifest.session)
        if (session.platform == null) fail("platform_session_required")
        PlatformConfigurationInput(session, withContext(Dispatchers.IO) { store.appliedCandidate(current.manifest) })
    }

    suspend fun recordPlatformPending(access: RealmAccess.Enterprise): EnterpriseState.Available = mutex.withLock {
        val current = ensureLoaded()
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        if (current.manifest.session?.platform == null) fail("platform_session_required")
        if (current.manifest.applied != null) fail("enterprise_generation_regression")
        publish(current.manifest.copy(phase = EnterpriseSessionPhase.CONFIGURATION_PENDING, lastConfigurationSyncMillis = nowMillis()))
    }

    suspend fun confirmPlatformExecution(access: RealmAccess.Enterprise, checked: EnterpriseCandidate): EnterpriseAppliedVersion = mutex.withLock {
        val current = requireSession(allowOffline = false)
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        val latest = withContext(Dispatchers.IO) { store.appliedCandidate(current.manifest) }
        if (latest != checked) fail("enterprise_configuration_changed_during_preflight")
        requireNotNull(current.manifest.applied)
    }

    /** A fetched candidate cannot renew, recreate or switch the session that requested it. */
    suspend fun synchronize(access: RealmAccess.Enterprise, candidate: EnterpriseCandidate): EnterpriseState.Available = mutex.withLock {
        val current = ensureLoaded()
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        if (candidate.identity.scope != access.scope) fail("enterprise_principal_mismatch")
        candidate.validate()
        applyValidated(candidate, current.manifest.selectedScope is ConfigurationScope.Enterprise, expectedAccess = access)
    }

    private suspend fun applyValidated(candidate: EnterpriseCandidate, enter: Boolean,
        expectedAccess: RealmAccess.Enterprise? = null): EnterpriseState.Available {
        val current = ensureLoaded()
        val manifest = current.manifest
        if (manifest.phase == EnterpriseSessionPhase.CLOSING) fail("enterprise_exit_in_progress")
        requireSamePrincipal(manifest, candidate.identity)
        manifest.applied?.let { version ->
            if (candidate.configuration.generation < version.generation) fail("enterprise_generation_regression")
            if (candidate.configuration.generation == version.generation && current.configuration != candidate.configuration) {
                fail("enterprise_generation_conflict")
            }
            if (candidate.configuration.generation == version.generation && candidate.execution is EnterpriseExecution.Platform) {
                val previous = withContext(Dispatchers.IO) { store.execution(manifest) } as? EnterpriseExecution.Platform
                    ?: fail("enterprise_source_changed")
                if (previous.releaseId != candidate.execution.releaseId || previous.snapshotHash != candidate.execution.snapshotHash ||
                    previous.runtimePaths != candidate.execution.runtimePaths) fail("enterprise_generation_conflict")
            }
        }
        prune(manifest)
        val version = withContext(Dispatchers.IO) {
            manifest.applied?.takeIf { current.configuration == candidate.configuration &&
                manifest.session?.identity == candidate.identity && store.execution(manifest) == candidate.execution }
                ?: store.prepare(candidate)
        }
        if (expectedAccess != null && !allowsDataAccess(manifest, expectedAccess)) fail("enterprise_data_access_unavailable")
        val session = requireNotNull(manifest.session).copy(identity = candidate.identity)
        val next = EnterpriseManifest(
            schemaVersion = ENTERPRISE_MANIFEST_SCHEMA_VERSION,
            phase = if (manifest.phase == EnterpriseSessionPhase.OFFLINE) EnterpriseSessionPhase.OFFLINE else EnterpriseSessionPhase.READY,
            session = session,
            applied = version,
            selectedScope = if (enter) candidate.identity.scope else ConfigurationScope.Personal,
            lastIdentity = candidate.identity,
            lastConfigurationSyncMillis = nowMillis(),
        )
        return publish(next)
    }

    suspend fun readPresentation(): EnterprisePresentation = mutex.withLock {
        val published = state.value
        EnterprisePresentation(published, when (published) {
            EnterpriseState.Loading -> null
            is EnterpriseState.Failed -> RealmSelection(RealmAccess.Personal, selectionRevision.value)
            is EnterpriseState.Available -> exitSelection(published.manifest)
        })
    }

    /** Host shutdown may await OS callbacks here, but original request jobs must be joined outside this lock. */
    suspend fun switchRealm(request: RealmSwitchRequest, closePreviousHost: suspend (RealmAccess) -> Unit): RealmSelection = mutex.withLock {
        val current = ensureLoaded()
        val selected = exitSelection(current.manifest)
        if (request.selection != selected) fail("enterprise_selection_revoked")
        if (request.target is RealmAccess.Enterprise) {
            val session = current.manifest.session ?: fail("enterprise_session_required")
            if (session.id != request.target.sessionId || session.identity.scope != request.target.scope) fail("enterprise_data_access_unavailable")
            if (session.expiresAtMillis <= nowMillis()) {
                if (current.manifest.phase != EnterpriseSessionPhase.CLOSING) beginClosing(current.manifest, EnterpriseExitReason.AUTHORIZATION_EXPIRED)
                fail("enterprise_session_expired")
            }
            if (current.manifest.phase !in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE)) fail("enterprise_session_not_ready")
        }
        if (request.target == selected.access) return@withLock selected
        try {
            closePreviousHost(selected.access)
            if (request.target is RealmAccess.Enterprise &&
                current.manifest.session!!.expiresAtMillis <= nowMillis()) {
                beginClosing(current.manifest, EnterpriseExitReason.AUTHORIZATION_EXPIRED)
                fail("enterprise_session_expired")
            }
            val next = publish(current.manifest.copy(selectedScope = request.target.scope))
            exitSelection(next.manifest)
        } catch (error: Throwable) {
            // A retired host cannot reuse its rendered authority even when the domain switch fails.
            if (selectionRevision.value == selected.revision) _selectionRevision.value++
            throw error
        }
    }

    suspend fun portalState(selection: RealmSelection): EnterpriseState.Available = mutex.withLock {
        val current = requirePortalSelection(selection)
        current.toAvailable()
    }

    private suspend fun requirePortalSelection(selection: RealmSelection): LoadedEnterpriseState {
        val current = ensureLoaded()
        val access = selection.access as? RealmAccess.Enterprise ?: fail("enterprise_session_required")
        if (selection.revision != selectionRevision.value || current.manifest.selectedScope != access.scope ||
            !allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        return current
    }

    /** A timed-out UI request may reply only if its original selection can be verified without waiting. */
    fun tryWithSelectedRealmSelection(selection: RealmSelection, operation: () -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        try {
            val manifest = loaded?.manifest ?: return false
            if (selection.revision != selectionRevision.value || manifest.selectedScope != selection.access.scope ||
                (selection.access is RealmAccess.Enterprise && !allowsDataAccess(manifest, selection.access))) return false
            operation()
            return true
        } finally { mutex.unlock() }
    }

    suspend fun captureRealmAccess(scope: ConfigurationScope): RealmAccess = when (scope) {
        ConfigurationScope.Personal -> RealmAccess.Personal
        is ConfigurationScope.Enterprise -> mutex.withLock {
            val current = ensureLoaded()
            val session = current.manifest.session ?: fail("enterprise_session_required")
            val access = RealmAccess.Enterprise(scope, session.id)
            if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
            access
        }
    }

    suspend fun captureSelectedRealmAccess(): RealmAccess = mutex.withLock {
        if (state.value is EnterpriseState.Failed) return@withLock RealmAccess.Personal
        val manifest = ensureLoaded().manifest
        if (manifest.selectedScope == ConfigurationScope.Personal) return@withLock RealmAccess.Personal
        val session = manifest.session ?: fail("enterprise_session_required")
        val access = RealmAccess.Enterprise(session.identity.scope, session.id)
        if (!allowsDataAccess(manifest, access)) fail("enterprise_data_access_unavailable")
        access
    }

    /** Capture the pair under the owner lock; independently emitted flows are only wake-up signals. */
    fun observeSelectedRealmSelection(): Flow<RealmSelection?> =
        combine(observeSelectedRealmAccess(), selectionRevision) { _, _ -> Unit }.map {
            mutex.withLock {
                val published = state.value
                val manifest = (published as? EnterpriseState.Available)?.manifest
                val access = when {
                    published is EnterpriseState.Failed -> RealmAccess.Personal
                    manifest == null -> null
                    manifest.selectedScope == ConfigurationScope.Personal -> RealmAccess.Personal
                    else -> manifest.session?.let { session ->
                        RealmAccess.Enterprise(session.identity.scope, session.id)
                            .takeIf { allowsDataAccess(manifest, it) }
                    }
                }
                access?.let { RealmSelection(it, selectionRevision.value) }
            }
        }.distinctUntilChanged()

    suspend fun <T> withSelectedRealmSelection(selection: RealmSelection, operation: suspend () -> T): T =
        withSelectedRealmAccess(selection.access) {
            if (selection.revision != selectionRevision.value) fail("enterprise_selection_revoked")
            operation()
        }

    /** Recheck after awaited work inside the selected-session boundary; wall-clock expiry needs no publication. */
    internal fun requirePublishedSelection(selection: RealmSelection) {
        if (selection.revision != selectionRevision.value) fail("enterprise_selection_revoked")
        val published = state.value
        if (published is EnterpriseState.Failed && selection.access == RealmAccess.Personal) return
        val manifest = (published as? EnterpriseState.Available)?.manifest ?: fail("enterprise_data_access_unavailable")
        val access = selection.access
        if (manifest.selectedScope != access.scope ||
            access is RealmAccess.Enterprise && !allowsDataAccess(manifest, access)) fail("enterprise_data_access_unavailable")
    }

    /** Recheck the original execution authority immediately before a durable write under this owner. */
    internal fun requirePublishedRealmAccess(access: RealmAccess) {
        if (access == RealmAccess.Personal) return
        val manifest = (state.value as? EnterpriseState.Available)?.manifest ?: fail("enterprise_data_access_unavailable")
        if (!allowsDataAccess(manifest, access as RealmAccess.Enterprise)) fail("enterprise_data_access_unavailable")
    }

    /** UI directory subscriptions follow the selected realm; expiry revokes the original session. */
    fun observeSelectedRealmAccess(): Flow<RealmAccess?> = state.flatMapLatest { published ->
        val manifest = (published as? EnterpriseState.Available)?.manifest
        when {
            published is EnterpriseState.Failed -> flowOf(RealmAccess.Personal)
            manifest == null -> flowOf(null)
            manifest.selectedScope == ConfigurationScope.Personal -> flowOf(RealmAccess.Personal)
            manifest.session == null -> flowOf(null)
            else -> {
                val access = RealmAccess.Enterprise(manifest.session.identity.scope, manifest.session.id)
                observeRealmAccess(access).map { allowed -> access.takeIf { allowed } }
            }
        }
    }.distinctUntilChanged()

    /** Session changes wait for the complete operation, including its durable commit or rollback. */
    suspend fun <T> withRealmAccess(access: RealmAccess, operation: suspend () -> T): T = when (access) {
        RealmAccess.Personal -> operation()
        is RealmAccess.Enterprise -> mutex.withLock {
            if (!allowsDataAccess(ensureLoaded().manifest, access)) fail("enterprise_data_access_unavailable")
            operation()
        }
    }

    /** Current-space UI reads cannot continue using an old selection after a realm switch. */
    suspend fun <T> withSelectedRealmAccess(access: RealmAccess, operation: suspend () -> T): T = mutex.withLock {
        if (state.value is EnterpriseState.Failed && access == RealmAccess.Personal) return@withLock operation()
        val manifest = ensureLoaded().manifest
        if (manifest.selectedScope != access.scope ||
            (access is RealmAccess.Enterprise && !allowsDataAccess(manifest, access))) fail("enterprise_data_access_unavailable")
        operation()
    }

    /** A captured subscription expires even when no command changes the manifest. New sessions cannot revive it. */
    fun observeRealmAccess(access: RealmAccess): Flow<Boolean> = state.flatMapLatest { published ->
        flow {
            if (access == RealmAccess.Personal) {
                emit(true)
            } else {
                val manifest = (published as? EnterpriseState.Available)?.manifest
                val allowed = manifest != null && allowsDataAccess(manifest, access as RealmAccess.Enterprise)
                emit(allowed)
                if (allowed) {
                    delay((requireNotNull(manifest.session).expiresAtMillis - nowMillis()).coerceAtLeast(1))
                    emit(false)
                }
            }
        }
    }.distinctUntilChanged()

    private fun allowsDataAccess(manifest: EnterpriseManifest, access: RealmAccess.Enterprise): Boolean =
        manifest.session?.let { session ->
            session.id == access.sessionId && session.identity.scope == access.scope && session.expiresAtMillis > nowMillis() &&
                manifest.phase in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE, EnterpriseSessionPhase.CONFIGURATION_PENDING)
        } == true

    /** Lock order is enterprise session, then user configuration; policy cannot change during a scoped write. */
    suspend fun <T> withAppliedConfiguration(
        access: RealmAccess.Enterprise,
        operation: suspend (EnterpriseState.Available) -> T,
    ): T = mutex.withLock {
        val current = requireSession(allowOffline = true)
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        operation(current.toAvailable())
    }

    suspend fun captureExitRequest(): EnterpriseExitRequest? = mutex.withLock {
        val manifest = manifestForExit()
        val session = manifest.session ?: return@withLock null
        val access = RealmAccess.Enterprise(session.identity.scope, session.id)
        EnterpriseExitRequest(access, exitSelection(manifest))
    }

    /** First revoke admission, then the application owner cancels domain work and closes leases. */
    suspend fun beginExit(request: EnterpriseExitRequest): EnterpriseExitToken = mutex.withLock {
        val manifest = manifestForExit()
        requireExitIdentity(manifest, request.access)
        if (manifest.phase == EnterpriseSessionPhase.CLOSING) fail("enterprise_exit_in_progress")
        if (exitSelection(manifest) != request.selection) fail("enterprise_selection_revoked")
        beginClosing(manifest, EnterpriseExitReason.USER_REQUEST)
    }

    /** Revocation targets the original Session even from personal space or after its expiry. */
    suspend fun beginInvalidation(access: RealmAccess.Enterprise, reason: EnterpriseExitReason): EnterpriseExitToken = mutex.withLock {
        require(reason in setOf(EnterpriseExitReason.AUTHORIZATION_EXPIRED, EnterpriseExitReason.AUTHORIZATION_REVOKED))
        val manifest = manifestForExit()
        requireExitIdentity(manifest, access)
        when {
            manifest.exitReason == EnterpriseExitReason.LOCAL_DATA_RESET -> fail("enterprise_reset_in_progress")
            manifest.phase == EnterpriseSessionPhase.CLOSING -> closingToken(manifest)
            else -> beginClosing(manifest, reason)
        }
    }

    suspend fun pendingExit(): EnterpriseExitToken? = mutex.withLock {
        manifestForExit().takeIf { it.phase == EnterpriseSessionPhase.CLOSING }?.let(::closingToken)
    }

    suspend fun withClosingSession(token: EnterpriseExitToken, operation: suspend () -> Unit) = mutex.withLock {
        val manifest = manifestForExit()
        if (manifest.phase != EnterpriseSessionPhase.CLOSING || closingToken(manifest) != token) fail("stale_enterprise_exit")
        operation()
    }

    /** The closing token is the sole authority to read a refresh credential after admission is revoked. */
    suspend fun capturePlatformLogout(token: EnterpriseExitToken): PlatformLogoutRequest? = mutex.withLock {
        val manifest = manifestForExit()
        if (manifest.phase != EnterpriseSessionPhase.CLOSING || closingToken(manifest) != token) fail("stale_enterprise_exit")
        val platform = manifest.session?.platform ?: return@withLock null
        PlatformLogoutRequest(platform.connection, withContext(Dispatchers.IO) {
            store.credential(platform.credential, token.access.sessionId)
        })
    }

    /** One clock and durable owner drive expiry independently of the selected UI space. */
    fun observeExitSignals(): Flow<EnterpriseExitSignal> = state.flatMapLatest { published -> flow {
        val manifest = (published as? EnterpriseState.Available)?.manifest ?: return@flow
        val session = manifest.session ?: return@flow
        if (manifest.phase == EnterpriseSessionPhase.CLOSING) {
            if (manifest.exitReason != EnterpriseExitReason.LOCAL_DATA_RESET) {
                emit(EnterpriseExitSignal.Closing(closingToken(manifest)))
            }
        }
        else {
            delay((session.expiresAtMillis - nowMillis()).coerceAtLeast(0))
            emit(EnterpriseExitSignal.Expired(RealmAccess.Enterprise(session.identity.scope, session.id)))
        }
    } }

    private fun exitSelection(manifest: EnterpriseManifest): RealmSelection {
        val access = if (state.value is EnterpriseState.Failed || manifest.selectedScope == ConfigurationScope.Personal) RealmAccess.Personal
            else RealmAccess.Enterprise(requireNotNull(manifest.session).identity.scope, manifest.session.id)
        return RealmSelection(access, selectionRevision.value)
    }

    private fun requireExitIdentity(manifest: EnterpriseManifest, access: RealmAccess.Enterprise) {
        val session = manifest.session
        if (session?.id != access.sessionId || session.identity.scope != access.scope) fail("stale_enterprise_exit")
    }

    private fun closingToken(manifest: EnterpriseManifest): EnterpriseExitToken {
        check(manifest.phase == EnterpriseSessionPhase.CLOSING)
        val session = requireNotNull(manifest.session)
        return EnterpriseExitToken(RealmAccess.Enterprise(session.identity.scope, session.id), requireNotNull(manifest.exitReason))
    }

    private suspend fun beginClosing(manifest: EnterpriseManifest, reason: EnterpriseExitReason): EnterpriseExitToken {
        val closing = manifest.copy(phase = EnterpriseSessionPhase.CLOSING, applied = null,
            selectedScope = ConfigurationScope.Personal, lastConfigurationSyncMillis = null, exitReason = reason)
        publish(closing)
        return closingToken(closing)
    }

    /** Revocation needs the validated identity, never readable capability files. */
    private suspend fun manifestForExit(): EnterpriseManifest =
        loaded?.manifest ?: withContext(Dispatchers.IO) { store.readManifest() }

    suspend fun finishExit(token: EnterpriseExitToken) = mutex.withLock {
        val manifest = manifestForExit()
        if (manifest.phase != EnterpriseSessionPhase.CLOSING || closingToken(manifest) != token) fail("stale_enterprise_exit")
        if (leases.values.any { it.sessionId == token.access.sessionId }) fail("enterprise_executions_pending")
        val phase = when (token.reason) {
            EnterpriseExitReason.USER_REQUEST -> EnterpriseSessionPhase.SIGNED_OUT
            EnterpriseExitReason.AUTHORIZATION_EXPIRED, EnterpriseExitReason.AUTHORIZATION_REVOKED -> EnterpriseSessionPhase.REAUTH_REQUIRED
            EnterpriseExitReason.LOCAL_DATA_RESET -> fail("stale_enterprise_exit")
        }
        publish(EnterpriseManifest.signedOut(requireNotNull(manifest.session).identity).copy(phase = phase))
    }

    /** Revokes live enterprise admission before the reset coordinator closes domain work and deletes data. */
    suspend fun beginLocalDataReset(request: EnterpriseExitRequest): EnterpriseExitToken = mutex.withLock {
        val manifest = manifestForExit()
        requireExitIdentity(manifest, request.access)
        if (manifest.phase == EnterpriseSessionPhase.CLOSING) fail("enterprise_exit_in_progress")
        if (exitSelection(manifest) != request.selection) fail("enterprise_selection_revoked")
        beginClosing(manifest, EnterpriseExitReason.LOCAL_DATA_RESET)
    }

    suspend fun completeLocalDataReset(token: EnterpriseExitToken, retainIdentity: Boolean) = mutex.withLock {
        if (token.reason != EnterpriseExitReason.LOCAL_DATA_RESET) fail("stale_enterprise_exit")
        val manifest = manifestForExit()
        if (manifest.phase != EnterpriseSessionPhase.CLOSING || closingToken(manifest) != token) fail("stale_enterprise_exit")
        if (leases.values.any { it.sessionId == token.access.sessionId }) fail("enterprise_executions_pending")
        val identity = if (retainIdentity) requireNotNull(manifest.session).identity else null
        val committed = withContext(NonCancellable + Dispatchers.IO) { store.resetLocalState(identity) }
        loaded = committed
        publishState(committed.toAvailable())
    }

    /** Rewrites damaged or already signed-out access state; an active Session must use beginLocalDataReset first. */
    suspend fun resetLocalDataState(retainIdentity: Boolean): EnterpriseState = mutex.withLock {
        val current = loaded?.manifest
        if (current?.phase == EnterpriseSessionPhase.CLOSING) fail("enterprise_reset_in_progress")
        if (current?.session != null) fail("enterprise_reset_requires_freeze")
        val identity = if (retainIdentity) current?.lastIdentity else null
        val committed = withContext(NonCancellable + Dispatchers.IO) { store.resetLocalState(identity) }
        loaded = committed
        publishState(committed.toAvailable())
        state.value
    }

    suspend fun pruneUnusedRevisions() = mutex.withLock { prune(manifestForExit()) }

    /** A synchronous projection needs no execution lease or revision retention beyond this read. */
    suspend fun <T> readExecution(
        access: RealmAccess.Enterprise,
        project: (EnterpriseAppliedVersion, EnterpriseExecution) -> T,
    ): T = mutex.withLock {
        val current = requireSession(allowOffline = true)
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        val execution = withContext(Dispatchers.IO) { store.execution(current.manifest) }
        currentCoroutineContext().ensureActive()
        requireSession(allowOffline = true)
        project(requireNotNull(current.manifest.applied), execution)
    }

    suspend fun captureExecution(access: RealmAccess.Enterprise, expectedVersion: EnterpriseAppliedVersion? = null): EnterpriseExecutionLease = mutex.withLock {
        val current = requireSession(allowOffline = false)
        val session = requireNotNull(current.manifest.session)
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        if (expectedVersion == null || current.manifest.applied != expectedVersion) {
            fail("enterprise_configuration_changed_during_preflight")
        }
        val candidate = withContext(Dispatchers.IO) { requireNotNull(store.appliedCandidate(current.manifest)) }
        // Disk reads can outlive the session even while publication is serialized.
        currentCoroutineContext().ensureActive()
        requireSession(allowOffline = false)
        EnterpriseExecutionLease(
            id = Uuid.random().toString(), sessionId = session.id, scope = access.scope,
            version = requireNotNull(current.manifest.applied), candidate = candidate,
            releaseOwner = ::releaseLease,
        ).also { leases[it.id] = it }
    }

    private suspend fun releaseLease(lease: EnterpriseExecutionLease) = mutex.withLock {
        if (leases[lease.id] !== lease) fail("unknown_enterprise_binding_lease")
        prune(requireNotNull(loaded).manifest, excluding = lease)
        leases.remove(lease.id)
    }

    private suspend fun requireSession(allowOffline: Boolean): LoadedEnterpriseState {
        val current = ensureLoaded()
        val session = current.manifest.session ?: fail("enterprise_session_required")
        if (session.expiresAtMillis <= nowMillis()) {
            if (current.manifest.phase != EnterpriseSessionPhase.CLOSING) beginClosing(current.manifest, EnterpriseExitReason.AUTHORIZATION_EXPIRED)
            fail("enterprise_session_expired")
        }
        if (current.manifest.phase != EnterpriseSessionPhase.READY &&
            !(allowOffline && current.manifest.phase == EnterpriseSessionPhase.OFFLINE)) fail("enterprise_session_not_ready")
        return current
    }

    private suspend fun ensureLoaded(): LoadedEnterpriseState {
        loaded?.let { return it }
        val manifest = withContext(Dispatchers.IO) { store.readManifest() }
        val session = manifest.session
        if (manifest.phase == EnterpriseSessionPhase.CLOSING) {
            loaded = LoadedEnterpriseState(manifest, null)
            publishState(requireNotNull(loaded).toAvailable())
        } else if (session != null && session.expiresAtMillis <= nowMillis()) {
            beginClosing(manifest, EnterpriseExitReason.AUTHORIZATION_EXPIRED)
        } else {
            val recovered = withContext(Dispatchers.IO) { store.load() }
            prune(recovered.manifest)
            loaded = recovered
            publishState(recovered.toAvailable())
        }
        return requireNotNull(loaded)
    }

    /** Once staging succeeds, commit and publication are one owned, cancellation-safe boundary. */
    private suspend fun publish(manifest: EnterpriseManifest): EnterpriseState.Available {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable + Dispatchers.IO) {
            val committed = store.commit(manifest)
            loaded = committed
            if (manifest.phase !in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE)) {
                leases.values.forEach(EnterpriseExecutionLease::revoke)
            } else {
                leases.values.filter { it.sessionId != manifest.session?.id }.forEach(EnterpriseExecutionLease::revoke)
            }
            committed.toAvailable().also(::publishState)
        }
    }

    /** A page must notice leaving its selection even when observers conflate a quick round trip. */
    private fun publishState(next: EnterpriseState) {
        val manifest = (next as? EnterpriseState.Available)?.manifest
        val activeSession = manifest?.takeUnless { it.phase == EnterpriseSessionPhase.CLOSING }?.let {
            it.session?.id ?: it.pendingEnrollment?.sessionId
        }
        platformAccessTokens.keys.retainAll(listOfNotNull(activeSession).toSet())
        fun identity(state: EnterpriseState): Pair<ConfigurationScope, String?> {
            val manifest = (state as? EnterpriseState.Available)?.manifest
            val scope = manifest?.selectedScope ?: ConfigurationScope.Personal
            return scope to manifest?.session?.id?.takeIf { scope is ConfigurationScope.Enterprise }
        }
        if (identity(_state.value) != identity(next)) _selectionRevision.value++
        _state.value = next
    }

    private suspend fun prune(manifest: EnterpriseManifest, excluding: EnterpriseExecutionLease? = null) = withContext(Dispatchers.IO) {
        store.prune(leases.values.filter { it !== excluding }.mapTo(mutableSetOf()) { it.version.revision }
            .apply { manifest.applied?.let { add(it.revision) } })
    }

    private fun requireSamePrincipal(manifest: EnterpriseManifest, identity: EnterpriseIdentity) {
        if (manifest.pendingEnrollment != null || enrollmentAttempt != null) fail("enterprise_enrollment_in_progress")
        if (manifest.phase == EnterpriseSessionPhase.CLOSING) fail("enterprise_exit_in_progress")
        manifest.session?.let { if (it.identity.scope != identity.scope) fail("exit_current_enterprise_first") }
    }

    private fun safeReason(error: Exception): String = when (error) {
        is EnterpriseConfigurationException -> error.reason
        is EnterpriseStorageException -> error.reason
        else -> "enterprise_storage_unavailable"
    }

    private fun fail(reason: String): Nothing = throw EnterpriseConfigurationException(reason)

    companion object {
    }
}
