package net.weero.measix.pilot.service

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.utils.userVisibleDiagnostic

internal const val ENROLLMENT_EXPIRED = "enrollment_expired"

/** Network orchestration owns no durable credentials; Session publication remains with its existing owner. */
internal class PlatformEnterpriseService(
    private val sessions: EnterpriseSessionController,
    private val client: PlatformControlClient,
    private val now: () -> Instant = Instant::now,
    private val onSessionRevoked: (RealmAccess.Enterprise) -> Unit = {},
    private val onSessionInvalidated: suspend (RealmAccess.Enterprise, EnterpriseExitReason) -> Unit = { access, reason ->
        if (reason == EnterpriseExitReason.AUTHORIZATION_REVOKED) onSessionRevoked(access)
    },
) {
    private val enrollment = Mutex()
    private val refresh = Mutex()
    private val _pendingLogoutFailure = MutableStateFlow<String?>(null)
    val pendingLogoutFailure = _pendingLogoutFailure.asStateFlow()
    private val _runtimeUsageChanged = MutableSharedFlow<RealmAccess.Enterprise>(extraBufferCapacity = 32)
    val runtimeUsageChanged = _runtimeUsageChanged.asSharedFlow()

    /** Called only after the native origin confirmation. A saved exchange resumes without consuming another code. */
    suspend fun enroll(material: EnrollmentMaterial.Platform, deviceName: String, appVersion: String): RealmAccess.Enterprise = enrollment.withLock {
        _pendingLogoutFailure.value = null
        val pending = sessions.pendingPlatformEnrollment()
        if (pending != null) {
            if (pending.platform.connection.origin != material.platformOrigin) {
                if (!now().isBefore(material.expiresAt)) throw EnterpriseConfigurationException(ENROLLMENT_EXPIRED)
                try { logoutPending(pending) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { _pendingLogoutFailure.value = error.userVisibleDiagnostic() }
                sessions.abandonPendingPlatformEnrollment(pending.sessionId)
            } else try {
                val response = read(pending.sessionId) { connection, token -> client.bootstrap(connection, token) }
                return@withLock sessions.completePlatformBootstrap(pending.sessionId, response)
            } catch (error: Exception) {
                if (!error.isTerminalPendingEnrollmentFailure()) throw error
                if (!now().isBefore(material.expiresAt)) throw EnterpriseConfigurationException(ENROLLMENT_EXPIRED)
                sessions.abandonPendingPlatformEnrollment(pending.sessionId)
            }
        }
        if (!now().isBefore(material.expiresAt)) throw EnterpriseConfigurationException(ENROLLMENT_EXPIRED)
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
            (status == 403 && problem?.code in EnterpriseRuntimeProblemCodes.authorizationRevoked)
        else -> false
    }

    suspend fun recoverPlatformAccess(): RealmAccess.Enterprise? = enrollment.withLock {
        sessions.pendingPlatformEnrollment()?.let { bootstrap(it.sessionId) }
            ?: sessions.recoverablePlatformAccess()
    }

    /** Changes only the current Session's network route after the same Core principal proves itself. */
    suspend fun changeAddress(request: EnterpriseAddressChangeRequest, rawOrigin: String): RealmAccess.Enterprise {
        val operation = sessions.capturePlatformOperation(request.access.sessionId)
        try {
            if (operation.access != request.access) {
                throw EnterpriseConfigurationException("enterprise_data_access_unavailable")
            }
            val current = operation.context
            val normalized = EnrollmentMaterialParser.normalizeOrigin(rawOrigin)
            if (normalized == current.platform.connection.origin) return request.access
            val candidate = client.discover(normalized)
            if (candidate.discovery.deploymentId != request.access.scope.authority.deploymentId) {
                throw EnterpriseConfigurationException("enterprise_address_deployment_mismatch")
            }
            return refresh.withLock {
                val existing = sessions.platformAccessToken(request.access.sessionId)
                var token = existing ?: refreshLocked(request.access.sessionId, candidate)
                val bootstrap = try {
                    client.bootstrap(candidate, token.value)
                } catch (error: PlatformHttpException) {
                    if (error.status != 401 || error.problem?.code != "invalid_credential" || existing == null) throw error
                    token = refreshLocked(request.access.sessionId, candidate)
                    client.bootstrap(candidate, token.value)
                }
                sessions.acceptPlatformAddress(request, candidate, bootstrap)
            }
        } finally {
            operation.release()
        }
    }

    private suspend fun bootstrap(sessionId: String): RealmAccess.Enterprise {
        return try {
            val bootstrap = read(sessionId) { connection, token -> client.bootstrap(connection, token) }
            sessions.completePlatformBootstrap(sessionId, bootstrap)
        } catch (error: PlatformHttpException) {
            if (error.status == 401 && error.problem?.code == EnterpriseRuntimeProblemCodes.IDENTITY_DELETED) {
                withContext(NonCancellable) { sessions.finishDeletedPendingPlatformEnrollment(sessionId) }
            }
            throw error
        }
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
        read(access.sessionId) { current, token ->
            client.reportApplied(
                current,
                token,
                PlatformManagedAppliedReport(candidate.configuration.generation, execution.snapshotHash),
            )
        }
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

    private suspend fun currentAccessToken(
        sessionId: String,
        connection: PlatformConnection,
    ): PlatformAccessToken = refresh.withLock {
        sessions.platformAccessToken(sessionId) ?: refreshLocked(sessionId, connection)
    }

    suspend fun accessToken(
        sessionId: String,
        connection: PlatformConnection? = null,
    ): PlatformAccessToken {
        val operation = sessions.capturePlatformOperation(sessionId)
        return try {
            val frozen = connection ?: operation.context.platform.connection
            check(frozen.authority == operation.context.platform.connection.authority) {
                "enterprise_platform_authority_changed"
            }
            currentAccessToken(sessionId, frozen)
        } catch (error: PlatformHttpException) {
            operation.access?.let { notifyRevoked(it, error) }
            throw error
        } finally {
            operation.release()
        }
    }

    /** A 401 is emitted before Core mints a ticket, so the existing one-refresh control retry remains non-replaying. */
    suspend fun createPortalGrant(access: RealmAccess.Enterprise): PlatformPortalGrant =
        read(access.sessionId) { connection, token -> client.createPortalGrant(connection, token) }

    suspend fun budgets(access: RealmAccess.Enterprise): PlatformUserBudgetView {
        sessions.platformConfiguration(access)
        val result = read(access.sessionId) { connection, token -> client.budgets(connection, token) }
        sessions.platformConfiguration(access)
        return result
    }

    fun runtimeCompleted(access: RealmAccess.Enterprise) { _runtimeUsageChanged.tryEmit(access) }

    /** Only managed routes call this; ordinary provider failures must retain provider semantics. */
    suspend fun managedRuntimeFailure(access: RealmAccess.Enterprise, error: Throwable): Throwable {
        val problem = EnterpriseRuntimeProblemException.fromManagedFailure(error) ?: return error
        acceptRuntimeProblem(access, problem)
        return problem
    }

    fun managedRuntimeProblem(access: RealmAccess.Enterprise, status: Int, body: String): Throwable? =
        EnterpriseRuntimeProblemException.parse(status, body)

    suspend fun acceptManagedRuntimeProblem(
        access: RealmAccess.Enterprise,
        error: Throwable,
    ): EnterpriseRuntimeProblemException? {
        val problem = EnterpriseRuntimeProblemException.find(error) ?: return null
        acceptRuntimeProblem(access, problem)
        return problem
    }

    private suspend fun acceptRuntimeProblem(
        access: RealmAccess.Enterprise,
        problem: EnterpriseRuntimeProblemException,
    ) {
        _runtimeUsageChanged.tryEmit(access)
        if (problem.code == EnterpriseRuntimeProblemCodes.IDENTITY_DELETED) {
            withContext(NonCancellable) {
                onSessionInvalidated(access, EnterpriseExitReason.IDENTITY_DELETED)
            }
        } else if (problem.code in EnterpriseRuntimeProblemCodes.authorizationRevoked) {
            withContext(NonCancellable) {
                onSessionInvalidated(access, EnterpriseExitReason.AUTHORIZATION_REVOKED)
            }
        }
    }

    private suspend fun notifyRevoked(access: RealmAccess.Enterprise, error: PlatformHttpException) {
        val reason = when {
            error.status == 403 && error.problem?.code in EnterpriseRuntimeProblemCodes.authorizationRevoked ->
                EnterpriseExitReason.AUTHORIZATION_REVOKED
            error.status == 401 && error.problem?.code == EnterpriseRuntimeProblemCodes.IDENTITY_DELETED ->
                EnterpriseExitReason.IDENTITY_DELETED
            error.status == 401 && error.problem?.code in setOf("session_expired", "invalid_credential") ->
                EnterpriseExitReason.AUTHORIZATION_EXPIRED
            else -> return
        }
        onSessionInvalidated(access, reason)
    }

    private suspend fun refreshLocked(
        sessionId: String,
        connection: PlatformConnection? = null,
    ): PlatformAccessToken {
        val attempt = sessions.beginPlatformRefresh(sessionId)
        val response = client.refresh(connection ?: attempt.context.platform.connection, attempt.credential.refreshToken,
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
        val operation = sessions.capturePlatformOperation(sessionId)
        return try {
            val connection = operation.context.platform.connection
            val token = currentAccessToken(sessionId, connection)
            try {
                request(connection, token.value)
            } catch (error: PlatformHttpException) {
                if (error.status != 401 || error.problem?.code != "invalid_credential") throw error
                val replacement = refresh.withLock {
                    val latest = sessions.platformAccessToken(sessionId)
                    if (latest != null && latest !== token) latest else refreshLocked(sessionId, connection)
                }
                sessions.platformContext(sessionId)
                request(connection, replacement.value)
            }
        } catch (error: PlatformHttpException) {
            operation.access?.let { notifyRevoked(it, error) }
            throw error
        } finally {
            operation.release()
        }
    }
}
