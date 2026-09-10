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

/** A binding lease owns immutable connection inputs, not permission to execute a capability. */
internal class EnterpriseBindingLease internal constructor(
    internal val id: String,
    val sessionId: String,
    val scope: ConfigurationScope.Enterprise,
    val version: EnterpriseAppliedVersion,
    private val bindings: Map<String, EnterpriseRuntimeBinding>,
    private val releaseOwner: suspend (EnterpriseBindingLease) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)
    private val releaseMutex = Mutex()
    private var released = false

    fun binding(resourceId: String): EnterpriseRuntimeBinding {
        if (closed.get() || revoked.get()) throw EnterpriseConfigurationException("enterprise_binding_lease_unavailable")
        return bindings[resourceId] ?: throw EnterpriseConfigurationException("enterprise_binding_missing")
    }

    internal fun revoke() { revoked.set(true) }

    /** Await outside Session admission; failure retains ownership so another call can retry cleanup. */
    suspend fun release() {
        closed.set(true)
        withContext(NonCancellable) {
            releaseMutex.withLock {
                if (!released) {
                    releaseOwner(this@EnterpriseBindingLease)
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
    private val leases = mutableMapOf<String, EnterpriseBindingLease>()
    private val _state = MutableStateFlow<EnterpriseState>(EnterpriseState.Loading)
    val state: StateFlow<EnterpriseState> = _state.asStateFlow()
    private val _selectionRevision = MutableStateFlow(0L)
    val selectionRevision: StateFlow<Long> = _selectionRevision.asStateFlow()

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

    /** Admission conflicts precede code consumption. Only this owner publishes the resulting client session. */
    suspend fun enrollLocal(
        installedIdentity: EnterpriseIdentity,
        redeem: suspend () -> EnterpriseIdentity,
        configuration: suspend () -> EnterprisePackage?,
        requireSignedOut: Boolean = false,
    ): EnterpriseState.Available = mutex.withLock {
        EnterprisePackageCodec.validateIdentity(installedIdentity)
        val current = ensureLoaded()
        if (current.manifest.phase == EnterpriseSessionPhase.CLOSING) fail("enterprise_exit_in_progress")
        if (requireSignedOut && current.manifest.session != null) fail("exit_current_enterprise_first")
        requireSamePrincipal(current.manifest, installedIdentity)
        if (redeem() != installedIdentity) fail("enterprise_enrollment_identity_mismatch")
        currentCoroutineContext().ensureActive()
        val candidate = configuration()
        if (candidate != null) {
            if (candidate.identity != installedIdentity) fail("enterprise_enrollment_identity_mismatch")
            EnterprisePackageCodec.validate(candidate)
            applyValidated(candidate, enter = true, replaceSession = true)
        } else {
            val session = EnterpriseSession(Uuid.random().toString(), installedIdentity, nowMillis() + SESSION_LIFETIME_MILLIS)
            publish(EnterpriseManifest(ENTERPRISE_MANIFEST_SCHEMA_VERSION, EnterpriseSessionPhase.CONFIGURATION_PENDING, session, null, ConfigurationScope.Personal, installedIdentity, current.manifest.feeds))
        }
    }

    /** A fetched candidate cannot renew, recreate or switch the session that requested it. */
    suspend fun synchronize(access: RealmAccess.Enterprise, candidate: EnterprisePackage): EnterpriseState.Available = mutex.withLock {
        val current = ensureLoaded()
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        if (candidate.identity.scope != access.scope) fail("enterprise_principal_mismatch")
        EnterprisePackageCodec.validate(candidate)
        applyValidated(candidate, current.manifest.selectedScope is ConfigurationScope.Enterprise, expectedAccess = access)
    }

    /** Explicit native installation publishes source facts before applying them; application failure is observable. */
    suspend fun importLocal(selection: RealmSelection, identity: EnterpriseIdentity, publishSource: suspend () -> LocalEnterpriseCandidate): LocalEnterpriseImportResult = mutex.withLock {
        EnterprisePackageCodec.validateIdentity(identity)
        val current = ensureLoaded()
        requirePublishedSelection(selection)
        requireSamePrincipal(current.manifest, identity)
        val candidate = publishSource()
        check(candidate.packet.identity == identity)
        try {
            requirePublishedSelection(selection)
            val applied = applyValidated(candidate.packet,
                current.manifest.session == null || current.manifest.selectedScope is ConfigurationScope.Enterprise,
                expectedAccess = current.manifest.session?.let { RealmAccess.Enterprise(it.identity.scope, it.id) })
            LocalEnterpriseImportResult(candidate.revision, applied, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalEnterpriseImportResult(candidate.revision, null, safeReason(error))
        }
    }

    private suspend fun applyValidated(candidate: EnterprisePackage, enter: Boolean, replaceSession: Boolean = false,
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
        }
        prune(manifest)
        val version = withContext(Dispatchers.IO) {
            manifest.applied?.takeIf { current.configuration == candidate.configuration &&
                manifest.session?.identity == candidate.identity && store.bindings(manifest) == candidate.runtimeBindings }
                ?: store.prepare(candidate)
        }
        val feeds = if (candidate.feedSeed != null && manifest.feeds.none { it.scope == candidate.identity.scope }) {
            manifest.feeds + withContext(Dispatchers.IO) {
                store.prepareFeed(candidate.identity.scope, EnterpriseFeed.initialize(candidate.feedSeed))
            }
        } else manifest.feeds
        if (expectedAccess != null && !allowsDataAccess(manifest, expectedAccess)) fail("enterprise_data_access_unavailable")
        val session = if (expectedAccess != null) requireNotNull(manifest.session).copy(identity = candidate.identity)
        else manifest.session?.takeIf { !replaceSession && it.expiresAtMillis > nowMillis() }
            ?.copy(identity = candidate.identity)
            ?: EnterpriseSession(Uuid.random().toString(), candidate.identity, nowMillis() + SESSION_LIFETIME_MILLIS)
        val next = EnterpriseManifest(
            ENTERPRISE_MANIFEST_SCHEMA_VERSION, if (manifest.phase == EnterpriseSessionPhase.OFFLINE && session.id == manifest.session?.id) {
                EnterpriseSessionPhase.OFFLINE
            } else EnterpriseSessionPhase.READY, session, version,
            if (enter) candidate.identity.scope else ConfigurationScope.Personal, candidate.identity, feeds, nowMillis(),
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
            val next = publish(current.manifest.copy(selectedScope = request.target.scope))
            exitSelection(next.manifest)
        } catch (error: Throwable) {
            // A retired host cannot reuse its rendered authority even when the domain switch fails.
            if (selectionRevision.value == selected.revision) _selectionRevision.value++
            throw error
        }
    }

    suspend fun readLocalFeed(selection: RealmSelection): LocalEnterpriseFeedSnapshot = mutex.withLock {
        val current = requireLocalFeedSelection(selection)
        val version = current.manifest.feeds.find { it.scope == selection.access.scope } ?: fail("enterprise_feed_not_ready")
        val document = withContext(Dispatchers.IO) { store.readFeed(version) }
        requirePublishedSelection(selection)
        LocalEnterpriseFeedSnapshot(selection, version.revision, document)
    }

    suspend fun changeFeed(selection: RealmSelection, expectedRevision: String, command: EnterpriseFeedCommand): LocalEnterpriseFeedSnapshot = mutex.withLock {
        val current = requireLocalFeedSelection(selection)
        val access = selection.access as RealmAccess.Enterprise
        val previous = current.manifest.feeds.find { it.scope == access.scope } ?: fail("enterprise_feed_not_ready")
        if (previous.revision != expectedRevision) fail("enterprise_feed_changed")
        prune(current.manifest)
        val document = withContext(Dispatchers.IO) {
            EnterpriseFeed.change(store.readFeed(previous), command, Instant.ofEpochMilli(nowMillis()))
        }
        val next = withContext(Dispatchers.IO) { store.prepareFeed(access.scope, document) }
        requirePublishedSelection(selection)
        publish(current.manifest.copy(feeds = current.manifest.feeds.map { if (it.scope == access.scope) next else it }))
        LocalEnterpriseFeedSnapshot(selection, next.revision, document)
    }

    suspend fun listFeed(selection: RealmSelection, query: EnterpriseFeedQuery): EnterpriseFeedResult = mutex.withLock {
        val current = requirePortalSelection(selection)
        val access = selection.access as RealmAccess.Enterprise
        val version = current.manifest.feeds.find { it.scope == access.scope } ?: fail("enterprise_feed_not_ready")
        withContext(Dispatchers.IO) {
            EnterpriseFeed.list(store.readFeed(version), access.scope, query, Instant.ofEpochMilli(nowMillis()))
        }
    }

    suspend fun listFeed(access: RealmAccess.Enterprise, query: EnterpriseFeedQuery): EnterpriseFeedResult = mutex.withLock {
        val current = requireSession(allowOffline = true)
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        val version = current.manifest.feeds.find { it.scope == access.scope } ?: fail("enterprise_feed_not_ready")
        withContext(Dispatchers.IO) {
            EnterpriseFeed.list(store.readFeed(version), access.scope, query, Instant.ofEpochMilli(nowMillis()))
        }
    }

    suspend fun feedDetail(selection: RealmSelection, id: String): EnterpriseUpdateItem = mutex.withLock {
        val current = requirePortalSelection(selection)
        val access = selection.access as RealmAccess.Enterprise
        val version = current.manifest.feeds.find { it.scope == access.scope } ?: fail("enterprise_feed_not_ready")
        withContext(Dispatchers.IO) { EnterpriseFeed.detail(store.readFeed(version), id) }
    }

    private suspend fun requireLocalFeedSelection(selection: RealmSelection): LoadedEnterpriseState {
        val current = requirePortalSelection(selection)
        if (!selection.access.scope.let { it is ConfigurationScope.Enterprise && it.authority.isLocal }) fail("local_enterprise_required")
        if (current.manifest.phase !in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE)) fail("enterprise_session_not_ready")
        return current
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
                    delay((requireNotNull(manifest?.session).expiresAtMillis - nowMillis()).coerceAtLeast(1))
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

    suspend fun setLocalOffline(selection: RealmSelection, access: RealmAccess.Enterprise, offline: Boolean) = mutex.withLock {
        val current = requireLocalSessionTarget(selection, access)
        if (current.manifest.phase !in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE)) fail("enterprise_session_not_ready")
        publish(current.manifest.copy(phase = if (offline) EnterpriseSessionPhase.OFFLINE else EnterpriseSessionPhase.READY))
    }

    /** Shortening the existing durable deadline exercises the normal expiry and recovery owners. */
    suspend fun shortenLocalSession(selection: RealmSelection, access: RealmAccess.Enterprise) = mutex.withLock {
        val current = requireLocalSessionTarget(selection, access)
        val session = requireNotNull(current.manifest.session)
        publish(current.manifest.copy(session = session.copy(expiresAtMillis = minOf(session.expiresAtMillis, nowMillis() + 60_000))))
    }

    suspend fun validateLocalSessionTarget(selection: RealmSelection, access: RealmAccess.Enterprise) = mutex.withLock {
        requireLocalSessionTarget(selection, access)
        Unit
    }

    private suspend fun requireLocalSessionTarget(selection: RealmSelection, access: RealmAccess.Enterprise): LoadedEnterpriseState {
        val current = ensureLoaded()
        requirePublishedSelection(selection)
        if (!access.scope.authority.isLocal) fail("local_enterprise_required")
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        return current
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
        require(reason != EnterpriseExitReason.USER_REQUEST)
        val manifest = manifestForExit()
        requireExitIdentity(manifest, access)
        if (manifest.phase == EnterpriseSessionPhase.CLOSING) closingToken(manifest)
        else beginClosing(manifest, reason)
    }

    suspend fun pendingExit(): EnterpriseExitToken? = mutex.withLock {
        manifestForExit().takeIf { it.phase == EnterpriseSessionPhase.CLOSING }?.let(::closingToken)
    }

    suspend fun withClosingSession(token: EnterpriseExitToken, operation: suspend () -> Unit) = mutex.withLock {
        val manifest = manifestForExit()
        if (manifest.phase != EnterpriseSessionPhase.CLOSING || closingToken(manifest) != token) fail("stale_enterprise_exit")
        operation()
    }

    /** One clock and durable owner drive expiry independently of the selected UI space. */
    fun observeExitSignals(): Flow<EnterpriseExitSignal> = state.flatMapLatest { published -> flow {
        val manifest = (published as? EnterpriseState.Available)?.manifest ?: return@flow
        val session = manifest.session ?: return@flow
        if (manifest.phase == EnterpriseSessionPhase.CLOSING) emit(EnterpriseExitSignal.Closing(closingToken(manifest)))
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
        val phase = if (token.reason == EnterpriseExitReason.USER_REQUEST) EnterpriseSessionPhase.SIGNED_OUT else EnterpriseSessionPhase.REAUTH_REQUIRED
        publish(EnterpriseManifest.signedOut(requireNotNull(manifest.session).identity, manifest.feeds).copy(phase = phase))
    }

    suspend fun pruneUnusedRevisions() = mutex.withLock { prune(manifestForExit()) }

    /** A synchronous projection needs no execution lease or revision retention beyond this read. */
    suspend fun <T> readBindings(
        access: RealmAccess.Enterprise,
        project: (EnterpriseAppliedVersion, List<EnterpriseRuntimeBinding>) -> T,
    ): T = mutex.withLock {
        val current = requireSession(allowOffline = true)
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        val bindings = withContext(Dispatchers.IO) { store.bindings(current.manifest) }
        currentCoroutineContext().ensureActive()
        requireSession(allowOffline = true)
        project(requireNotNull(current.manifest.applied), bindings)
    }

    suspend fun captureBindings(access: RealmAccess.Enterprise): EnterpriseBindingLease = mutex.withLock {
        val current = requireSession(allowOffline = false)
        val session = requireNotNull(current.manifest.session)
        if (!allowsDataAccess(current.manifest, access)) fail("enterprise_data_access_unavailable")
        val bindings = withContext(Dispatchers.IO) { store.bindings(current.manifest) }
        // Disk reads can outlive the session even while publication is serialized.
        currentCoroutineContext().ensureActive()
        requireSession(allowOffline = false)
        EnterpriseBindingLease(
            id = Uuid.random().toString(), sessionId = session.id, scope = access.scope,
            version = requireNotNull(current.manifest.applied), bindings = bindings.associateBy { it.resourceId },
            releaseOwner = ::releaseLease,
        ).also { leases[it.id] = it }
    }

    private suspend fun releaseLease(lease: EnterpriseBindingLease) = mutex.withLock {
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
                leases.values.forEach(EnterpriseBindingLease::revoke)
            } else {
                leases.values.filter { it.sessionId != manifest.session?.id }.forEach(EnterpriseBindingLease::revoke)
            }
            committed.toAvailable().also(::publishState)
        }
    }

    /** A page must notice leaving its selection even when observers conflate a quick round trip. */
    private fun publishState(next: EnterpriseState) {
        fun identity(state: EnterpriseState): Pair<ConfigurationScope, String?> {
            val manifest = (state as? EnterpriseState.Available)?.manifest
            val scope = manifest?.selectedScope ?: ConfigurationScope.Personal
            return scope to manifest?.session?.id?.takeIf { scope is ConfigurationScope.Enterprise }
        }
        if (identity(_state.value) != identity(next)) _selectionRevision.value++
        _state.value = next
    }

    private suspend fun prune(manifest: EnterpriseManifest, excluding: EnterpriseBindingLease? = null) = withContext(Dispatchers.IO) {
        store.prune(
            leases.values.filter { it !== excluding }.mapTo(mutableSetOf()) { it.version.revision }
                .apply { manifest.applied?.let { add(it.revision) } },
            manifest.feeds.mapTo(mutableSetOf()) { it.revision },
        )
    }

    private fun requireSamePrincipal(manifest: EnterpriseManifest, identity: EnterpriseIdentity) {
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
        private const val SESSION_LIFETIME_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
