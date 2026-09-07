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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import kotlin.uuid.Uuid

internal sealed interface EnterpriseState {
    data object Loading : EnterpriseState
    data class Available(val manifest: EnterpriseManifest, val configuration: EnterpriseConfiguration?) : EnterpriseState
    data class Failed(val reason: String) : EnterpriseState
}

internal data class EnterpriseExitToken(val sessionId: String, val scope: ConfigurationScope.Enterprise)

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

    fun binding(resourceId: String): EnterpriseRuntimeBinding {
        if (closed.get() || revoked.get()) throw EnterpriseConfigurationException("enterprise_binding_lease_unavailable")
        return bindings[resourceId] ?: throw EnterpriseConfigurationException("enterprise_binding_missing")
    }

    internal fun revoke() { revoked.set(true) }

    suspend fun release() {
        if (closed.compareAndSet(false, true)) withContext(NonCancellable) { releaseOwner(this@EnterpriseBindingLease) }
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

    suspend fun recover(): EnterpriseState = mutex.withLock {
        try {
            ensureLoaded()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loaded = null
            _state.value = EnterpriseState.Failed(safeReason(error))
        }
        state.value
    }

    /** Enrollment identity can exist before a valid configuration; it never implies READY. */
    suspend fun registerIdentity(identity: EnterpriseIdentity) = mutex.withLock {
        EnterprisePackageCodec.validateIdentity(identity)
        val current = ensureLoaded()
        requireSamePrincipal(current.manifest, identity)
        val session = EnterpriseSession(Uuid.random().toString(), identity, nowMillis() + SESSION_LIFETIME_MILLIS)
        publish(EnterpriseManifest(2, EnterpriseSessionPhase.CONFIGURATION_PENDING, session, null, ConfigurationScope.Personal, identity, current.manifest.feeds), null)
    }

    suspend fun applyPackage(bytes: ByteArray, enter: Boolean = true): EnterpriseState.Available {
        if (bytes.size > EnterprisePackageCodec.MAX_BYTES) fail("enterprise_package_too_large")
        val candidate = EnterprisePackageCodec.decode(bytes.copyOf())
        return mutex.withLock { applyValidated(candidate, enter) }
    }

    /** Admission conflicts precede code consumption. Only this owner publishes the resulting client session. */
    suspend fun enrollLocal(
        installedIdentity: EnterpriseIdentity,
        redeem: suspend () -> EnterpriseIdentity,
        configuration: suspend () -> EnterprisePackage?,
    ): EnterpriseState.Available = mutex.withLock {
        EnterprisePackageCodec.validateIdentity(installedIdentity)
        val current = ensureLoaded()
        if (current.manifest.phase == EnterpriseSessionPhase.CLOSING) fail("enterprise_exit_in_progress")
        requireSamePrincipal(current.manifest, installedIdentity)
        if (redeem() != installedIdentity) fail("enterprise_enrollment_identity_mismatch")
        currentCoroutineContext().ensureActive()
        val candidate = configuration()
        if (candidate != null) {
            if (candidate.identity != installedIdentity) fail("enterprise_enrollment_identity_mismatch")
            EnterprisePackageCodec.validate(candidate)
            val retained = current.manifest.applied
            if (retained != null && current.configuration != null && candidate.configuration.generation < retained.generation) {
                // Enrollment renews this principal; an older installed example cannot roll back an applied local update.
                val session = EnterpriseSession(Uuid.random().toString(), installedIdentity, nowMillis() + SESSION_LIFETIME_MILLIS)
                publish(current.manifest.copy(phase = EnterpriseSessionPhase.READY, session = session, selectedScope = installedIdentity.scope), current.configuration)
            } else {
                applyValidated(candidate, enter = true, replaceSession = true)
            }
        } else {
            val session = EnterpriseSession(Uuid.random().toString(), installedIdentity, nowMillis() + SESSION_LIFETIME_MILLIS)
            publish(EnterpriseManifest(2, EnterpriseSessionPhase.CONFIGURATION_PENDING, session, null, ConfigurationScope.Personal, installedIdentity, current.manifest.feeds), null)
        }
    }

    /** Local service changes use the same whole-package transaction and reject stale editors. */
    suspend fun updateLocalPackage(
        expectedRevision: String,
        transform: (EnterprisePackage) -> EnterprisePackage,
    ): EnterpriseState.Available = mutex.withLock {
        val current = requireSession(allowOffline = true)
        val manifest = current.manifest
        if (manifest.applied?.revision != expectedRevision) fail("enterprise_configuration_changed")
        val configuration = requireNotNull(current.configuration)
        if (configuration.generation == Long.MAX_VALUE) fail("enterprise_generation_exhausted")
        val previous = EnterprisePackage(
            EnterprisePackageCodec.FORMAT_VERSION, requireNotNull(manifest.session).identity, configuration,
            withContext(Dispatchers.IO) { store.bindings(manifest) },
        )
        val changed = transform(previous)
        val candidate = changed.copy(configuration = changed.configuration.copy(generation = configuration.generation + 1))
        EnterprisePackageCodec.validate(candidate)
        applyValidated(candidate, manifest.selectedScope is ConfigurationScope.Enterprise)
    }

    private suspend fun applyValidated(candidate: EnterprisePackage, enter: Boolean, replaceSession: Boolean = false): EnterpriseState.Available {
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
        val version = withContext(Dispatchers.IO) { store.prepare(candidate) }
        val feeds = if (candidate.feedSeed != null && manifest.feeds.none { it.scope == candidate.identity.scope }) {
            manifest.feeds + withContext(Dispatchers.IO) {
                store.prepareFeed(candidate.identity.scope, EnterpriseFeed.initialize(candidate.feedSeed))
            }
        } else manifest.feeds
        val session = manifest.session?.takeIf { !replaceSession && it.expiresAtMillis > nowMillis() }
            ?.copy(identity = candidate.identity)
            ?: EnterpriseSession(Uuid.random().toString(), candidate.identity, nowMillis() + SESSION_LIFETIME_MILLIS)
        val next = EnterpriseManifest(
            2, if (manifest.phase == EnterpriseSessionPhase.OFFLINE && session.id == manifest.session?.id) {
                EnterpriseSessionPhase.OFFLINE
            } else EnterpriseSessionPhase.READY, session, version,
            if (enter) candidate.identity.scope else ConfigurationScope.Personal, candidate.identity, feeds,
        )
        return publish(next, candidate.configuration)
    }

    suspend fun switchToPersonal() = mutex.withLock {
        val current = ensureLoaded()
        publish(current.manifest.copy(selectedScope = ConfigurationScope.Personal), current.configuration)
    }

    suspend fun changeFeed(access: RealmAccess.Enterprise, expectedRevision: String, command: EnterpriseFeedCommand): EnterpriseFeedVersion = mutex.withLock {
        val current = requireFeedSession(access)
        val previous = current.manifest.feeds.find { it.scope == access.scope } ?: fail("enterprise_feed_not_ready")
        if (previous.revision != expectedRevision) fail("enterprise_feed_changed")
        prune(current.manifest)
        val next = withContext(Dispatchers.IO) {
            store.prepareFeed(access.scope, EnterpriseFeed.change(store.readFeed(previous), command, Instant.ofEpochMilli(nowMillis())))
        }
        publish(current.manifest.copy(feeds = current.manifest.feeds.map { if (it.scope == access.scope) next else it }), current.configuration)
        next
    }

    suspend fun listFeed(access: RealmAccess.Enterprise, query: EnterpriseFeedQuery): EnterpriseFeedResult = mutex.withLock {
        val current = requireFeedSession(access)
        val version = current.manifest.feeds.find { it.scope == access.scope } ?: fail("enterprise_feed_not_ready")
        withContext(Dispatchers.IO) {
            EnterpriseFeed.list(store.readFeed(version), access.scope, query, Instant.ofEpochMilli(nowMillis()))
        }
    }

    suspend fun feedDetail(access: RealmAccess.Enterprise, id: String): EnterpriseUpdateItem = mutex.withLock {
        val current = requireFeedSession(access)
        val version = current.manifest.feeds.find { it.scope == access.scope } ?: fail("enterprise_feed_not_ready")
        withContext(Dispatchers.IO) { EnterpriseFeed.detail(store.readFeed(version), id) }
    }

    private suspend fun requireFeedSession(access: RealmAccess.Enterprise): LoadedEnterpriseState {
        val current = requireSession(allowOffline = true)
        if (!allowsDataAccess(current.manifest, access) || current.manifest.selectedScope != access.scope) {
            fail("enterprise_data_access_unavailable")
        }
        return current
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

    /** Session changes wait for the complete operation, including its durable commit or rollback. */
    suspend fun <T> withRealmAccess(access: RealmAccess, operation: suspend () -> T): T = when (access) {
        RealmAccess.Personal -> operation()
        is RealmAccess.Enterprise -> mutex.withLock {
            if (!allowsDataAccess(ensureLoaded().manifest, access)) fail("enterprise_data_access_unavailable")
            operation()
        }
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
        scope: ConfigurationScope.Enterprise,
        operation: suspend (EnterpriseState.Available) -> T,
    ): T = mutex.withLock {
        val current = requireSession(allowOffline = true)
        if (current.manifest.session?.identity?.scope != scope) fail("enterprise_principal_mismatch")
        operation(EnterpriseState.Available(current.manifest, current.configuration))
    }

    suspend fun switchToEnterprise() = mutex.withLock {
        val current = requireSession(allowOffline = true)
        val scope = requireNotNull(current.manifest.session).identity.scope
        publish(current.manifest.copy(selectedScope = scope), current.configuration)
    }

    suspend fun setOffline(offline: Boolean) = mutex.withLock {
        val current = requireSession(allowOffline = true)
        publish(current.manifest.copy(phase = if (offline) EnterpriseSessionPhase.OFFLINE else EnterpriseSessionPhase.READY), current.configuration)
    }

    suspend fun requireReauthentication() = mutex.withLock {
        val manifest = manifestForExit()
        val identity = manifest.session?.identity ?: manifest.lastIdentity
        publish(EnterpriseManifest.signedOut(identity, manifest.feeds).copy(phase = EnterpriseSessionPhase.REAUTH_REQUIRED), null)
        prune(requireNotNull(loaded).manifest)
    }

    /** First revoke admission, then the application owner cancels domain work and closes leases. */
    suspend fun beginExit(): EnterpriseExitToken? = mutex.withLock {
        val manifest = manifestForExit()
        val session = manifest.session ?: return@withLock null
        publish(manifest.copy(phase = EnterpriseSessionPhase.CLOSING, applied = null, selectedScope = ConfigurationScope.Personal), null)
        EnterpriseExitToken(session.id, session.identity.scope)
    }

    /** Revocation needs the validated identity, never readable capability files. */
    private suspend fun manifestForExit(): EnterpriseManifest =
        loaded?.manifest ?: withContext(Dispatchers.IO) { store.readManifest() }

    suspend fun finishExit(token: EnterpriseExitToken) = mutex.withLock {
        val current = ensureLoaded()
        val session = current.manifest.session
        if (current.manifest.phase != EnterpriseSessionPhase.CLOSING || session == null || session.id != token.sessionId ||
            session.identity.scope != token.scope) fail("stale_enterprise_exit")
        if (leases.values.any { it.sessionId == token.sessionId }) fail("enterprise_executions_pending")
        publish(EnterpriseManifest.signedOut(session.identity, current.manifest.feeds), null)
        prune(requireNotNull(loaded).manifest)
    }

    suspend fun captureBindings(scope: ConfigurationScope.Enterprise): EnterpriseBindingLease = mutex.withLock {
        val current = requireSession(allowOffline = false)
        val session = requireNotNull(current.manifest.session)
        if (session.identity.scope != scope) fail("enterprise_principal_mismatch")
        val bindings = withContext(Dispatchers.IO) { store.bindings(current.manifest) }
        EnterpriseBindingLease(
            id = Uuid.random().toString(), sessionId = session.id, scope = scope,
            version = requireNotNull(current.manifest.applied), bindings = bindings.associateBy { it.resourceId },
            releaseOwner = ::releaseLease,
        ).also { leases[it.id] = it }
    }

    private suspend fun releaseLease(lease: EnterpriseBindingLease) = mutex.withLock {
        if (leases[lease.id] !== lease) fail("unknown_enterprise_binding_lease")
        leases.remove(lease.id)
        prune(requireNotNull(loaded).manifest)
    }

    private suspend fun requireSession(allowOffline: Boolean): LoadedEnterpriseState {
        val current = ensureLoaded()
        val session = current.manifest.session ?: fail("enterprise_session_required")
        if (session.expiresAtMillis <= nowMillis()) {
            publish(EnterpriseManifest.signedOut(session.identity, current.manifest.feeds).copy(phase = EnterpriseSessionPhase.REAUTH_REQUIRED), null)
            prune(requireNotNull(loaded).manifest)
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
        val terminal = when {
            manifest.phase == EnterpriseSessionPhase.CLOSING -> EnterpriseManifest.signedOut(session?.identity, manifest.feeds)
            session != null && session.expiresAtMillis <= nowMillis() ->
                EnterpriseManifest.signedOut(session.identity, manifest.feeds).copy(phase = EnterpriseSessionPhase.REAUTH_REQUIRED)
            else -> null
        }
        if (terminal != null) {
            publish(terminal, null)
            prune(terminal)
        } else {
            val recovered = withContext(Dispatchers.IO) { store.load() }
            prune(recovered.manifest)
            loaded = recovered
            _state.value = EnterpriseState.Available(recovered.manifest, recovered.configuration)
        }
        return requireNotNull(loaded)
    }

    /** Once staging succeeds, commit and publication are one owned, cancellation-safe boundary. */
    private suspend fun publish(manifest: EnterpriseManifest, configuration: EnterpriseConfiguration?): EnterpriseState.Available {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable + Dispatchers.IO) {
            store.commit(manifest)
            loaded = LoadedEnterpriseState(manifest, configuration)
            if (manifest.phase !in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE)) {
                leases.values.forEach(EnterpriseBindingLease::revoke)
            } else {
                leases.values.filter { it.sessionId != manifest.session?.id }.forEach(EnterpriseBindingLease::revoke)
            }
            EnterpriseState.Available(manifest, configuration).also { _state.value = it }
        }
    }

    private suspend fun prune(manifest: EnterpriseManifest) = withContext(Dispatchers.IO) {
        store.prune(
            leases.values.mapTo(mutableSetOf()) { it.version.revision }.apply { manifest.applied?.let { add(it.revision) } },
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
