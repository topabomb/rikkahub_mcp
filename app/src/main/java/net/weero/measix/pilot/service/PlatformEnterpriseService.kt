package net.weero.measix.pilot.service

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.utils.userVisibleDiagnostic

/** Network orchestration owns no durable credentials; Session publication remains with its existing owner. */
internal class PlatformEnterpriseService(
    private val sessions: EnterpriseSessionController,
    private val client: PlatformControlClient,
    private val now: () -> Instant = Instant::now,
    private val onSessionRevoked: (RealmAccess.Enterprise) -> Unit = {},
) {
    private val enrollment = Mutex()
    private val refresh = Mutex()
    private val _pendingLogoutFailure = MutableStateFlow<String?>(null)
    val pendingLogoutFailure = _pendingLogoutFailure.asStateFlow()

    /** Called only after the native origin confirmation. A saved exchange resumes without consuming another code. */
    suspend fun enroll(material: EnrollmentMaterial.Platform, deviceName: String, appVersion: String): RealmAccess.Enterprise = enrollment.withLock {
        _pendingLogoutFailure.value = null
        val pending = sessions.pendingPlatformEnrollment()
        if (pending != null) {
            if (pending.platform.connection.origin != material.platformOrigin) {
                if (!now().isBefore(material.expiresAt)) throw EnterpriseConfigurationException("enterprise_enrollment_expired")
                try { logoutPending(pending) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { _pendingLogoutFailure.value = error.userVisibleDiagnostic() }
                sessions.abandonPendingPlatformEnrollment(pending.sessionId)
            } else try {
                val response = read(pending.sessionId) { connection, token -> client.bootstrap(connection, token) }
                return@withLock sessions.completePlatformBootstrap(pending.sessionId, response)
            } catch (error: Exception) {
                if (!error.isTerminalPendingEnrollmentFailure()) throw error
                if (!now().isBefore(material.expiresAt)) throw EnterpriseConfigurationException("enterprise_enrollment_expired")
                sessions.abandonPendingPlatformEnrollment(pending.sessionId)
            }
        }
        if (!now().isBefore(material.expiresAt)) throw EnterpriseConfigurationException("enterprise_enrollment_expired")
        val attempt = sessions.beginPlatformEnrollment()
        val sessionId = try {
            val connection = client.discover(material.platformOrigin)
            val response = client.enroll(connection, PlatformEnrollmentExchangeRequest(
                material.code, attempt.installationId, deviceName, appVersion, PlatformEnrollmentExchangeRequestPlatform.ANDROID,
            ))
            sessions.acceptPlatformEnrollment(attempt, connection, response)
        } finally {
            withContext(NonCancellable) { sessions.finishEnrollmentAttempt(attempt) }
        }
        bootstrap(sessionId)
    }

    /** A confirmed new origin may replace pending enrollment even when the old Core is offline. */
    private suspend fun logoutPending(pending: PendingPlatformEnrollment) = refresh.withLock {
        var request = sessions.capturePendingPlatformLogout(pending)
        if (request.credential.pendingIdempotencyKey != null) {
            val attempt = sessions.beginPlatformRefresh(pending.sessionId)
            val response = client.refresh(request.connection, attempt.credential.refreshToken,
                requireNotNull(attempt.credential.pendingIdempotencyKey))
            sessions.acceptPlatformRefresh(attempt, response)
            request = sessions.capturePendingPlatformLogout(requireNotNull(sessions.pendingPlatformEnrollment()))
        }
        client.logout(request.connection, request.credential.refreshToken)
    }

    private fun Exception.isTerminalPendingEnrollmentFailure(): Boolean = when (this) {
        is EnterpriseConfigurationException -> reason in setOf("enterprise_refresh_expired", "enterprise_session_expired")
        is PlatformHttpException -> (status == 401 && problem?.code in setOf("session_expired", "invalid_credential")) ||
            (status == 403 && problem?.code == "session_revoked")
        else -> false
    }

    suspend fun recoverPlatformAccess(): RealmAccess.Enterprise? = enrollment.withLock {
        sessions.pendingPlatformEnrollment()?.let { bootstrap(it.sessionId) }
            ?: sessions.recoverablePlatformAccess()
    }

    private suspend fun bootstrap(sessionId: String): RealmAccess.Enterprise {
        val bootstrap = read(sessionId) { connection, token -> client.bootstrap(connection, token) }
        return sessions.completePlatformBootstrap(sessionId, bootstrap)
    }

    /** Shared synchronization owns deduplication; this source performs network I/O outside Session admission. */
    suspend fun synchronize(access: RealmAccess.Enterprise): EnterpriseState.Available {
        val input = sessions.platformConfiguration(access)
        val connection = requireNotNull(input.session.platform).connection
        val cached = input.candidate
        val state = read(access.sessionId) { current, token ->
            client.state(current, token, cached?.configuration?.generation)
        }
        val generation = state.activeManagedGeneration
        require(state.targetManagedGeneration == null || state.targetManagedGeneration == generation) {
            "platform_snapshot_target_mismatch"
        }
        if (generation == 0L) return sessions.recordPlatformPending(access)
        val matching = cached?.takeIf { it.configuration.generation == generation }
        val cachedExecution = matching?.execution as? EnterpriseExecution.Platform
        val response = read(access.sessionId) { current, token ->
            client.snapshot(current, token, generation, cachedExecution?.snapshotHash)
        }
        val candidate = when (response) {
            is PlatformSnapshotResponse.Downloaded -> PlatformSnapshotMapper.map(connection, input.session.identity, response.snapshot)
            PlatformSnapshotResponse.NotModified -> requireNotNull(matching) { "snapshot_304_without_cache" }
        }
        val applied = sessions.synchronize(access, candidate)
        val execution = candidate.execution as EnterpriseExecution.Platform
        val token = accessToken(access.sessionId)
        sessions.platformContext(access.sessionId)
        client.reportApplied(connection, token.value, PlatformManagedAppliedReport(candidate.configuration.generation, execution.snapshotHash))
        return applied
    }

    /** Every new interaction checks authority; a captured version is consumed by the Session lease CAS. */
    suspend fun prepareExecution(access: RealmAccess.Enterprise, synchronize: suspend () -> Unit): EnterpriseAppliedVersion {
        suspend fun check(): Pair<PlatformManagedState, PlatformConfigurationInput> {
            val input = sessions.platformConfiguration(access)
            val state = read(access.sessionId) { connection, token ->
                client.state(connection, token, input.candidate?.configuration?.generation)
            }
            return state to input
        }
        var (state, input) = check()
        if (state.activeManagedGeneration > 0 &&
            (state.syncRequired || input.candidate?.configuration?.generation != state.activeManagedGeneration)) {
            synchronize()
            val checked = check()
            state = checked.first
            input = checked.second
        }
        if (state.activeManagedGeneration == 0L) throw EnterpriseConfigurationException("enterprise_configuration_not_ready")
        if (state.runtimeStatus != PlatformManagedStateRuntimeStatus.READY) {
            throw EnterpriseConfigurationException("platform_runtime_${state.runtimeStatus.name.lowercase()}")
        }
        if (state.runtimeBlocked || state.syncRequired || input.candidate?.configuration?.generation != state.activeManagedGeneration) {
            throw EnterpriseConfigurationException("platform_runtime_synchronization_required")
        }
        return sessions.confirmPlatformExecution(access, requireNotNull(input.candidate))
    }

    private suspend fun currentAccessToken(sessionId: String): PlatformAccessToken = refresh.withLock {
        sessions.platformAccessToken(sessionId) ?: refreshLocked(sessionId)
    }

    suspend fun accessToken(sessionId: String): PlatformAccessToken = try {
        currentAccessToken(sessionId)
    } catch (error: PlatformHttpException) {
        notifyRevoked(sessionId, error)
        throw error
    }

    /** A 401 is emitted before Core mints a ticket, so the existing one-refresh control retry remains non-replaying. */
    suspend fun createPortalGrant(access: RealmAccess.Enterprise): PlatformPortalGrant =
        read(access.sessionId) { connection, token -> client.createPortalGrant(connection, token) }

    private suspend fun notifyRevoked(sessionId: String, error: PlatformHttpException) {
        if (error.status == 403 && error.problem?.code == "session_revoked") {
            sessions.platformSessionAccess(sessionId)?.let(onSessionRevoked)
        }
    }

    private suspend fun refreshLocked(sessionId: String): PlatformAccessToken {
        val attempt = sessions.beginPlatformRefresh(sessionId)
        val response = client.refresh(attempt.context.platform.connection, attempt.credential.refreshToken,
            requireNotNull(attempt.credential.pendingIdempotencyKey))
        sessions.acceptPlatformRefresh(attempt, response)
        return sessions.platformAccessToken(sessionId)
            ?: throw EnterpriseConfigurationException("platform_access_token_expired")
    }

    /** A pending refresh is replayed with its original key before logout so a rotated token is never guessed. */
    suspend fun logout(token: EnterpriseExitToken) = refresh.withLock {
        val request = sessions.capturePlatformLogout(token) ?: return@withLock
        val credential = request.credential
        val currentToken = credential.pendingIdempotencyKey?.let { key ->
            client.refresh(request.connection, credential.refreshToken, key).refreshToken
        } ?: credential.refreshToken
        client.logout(request.connection, currentToken)
    }

    /** Only side-effect-free Client reads may use this retry; Runtime and code exchange never do. */
    suspend fun <T> read(sessionId: String, request: suspend (PlatformConnection, String) -> T): T {
        return try {
            val token = currentAccessToken(sessionId)
            val connection = sessions.platformContext(sessionId).platform.connection
            try {
                request(connection, token.value)
            } catch (error: PlatformHttpException) {
                if (error.status != 401 || error.problem?.code != "invalid_credential") throw error
                val replacement = refresh.withLock {
                    val latest = sessions.platformAccessToken(sessionId)
                    if (latest != null && latest !== token) latest else refreshLocked(sessionId)
                }
                sessions.platformContext(sessionId)
                request(connection, replacement.value)
            }
        } catch (error: PlatformHttpException) {
            notifyRevoked(sessionId, error)
            throw error
        }
    }
}
