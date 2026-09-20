package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.ai.mcp.McpCatalogStore
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterpriseDataResetIntent
import net.weero.measix.pilot.data.enterprise.EnterpriseDataResetMode
import net.weero.measix.pilot.data.enterprise.EnterpriseDataResetProgress
import net.weero.measix.pilot.data.enterprise.EnterpriseDataResetRequest
import net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStage
import net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore
import net.weero.measix.pilot.data.enterprise.EnterpriseExitReason
import net.weero.measix.pilot.data.enterprise.EnterpriseExitToken
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import kotlin.uuid.Uuid

/**
 * Owns local enterprise data reset requests and their recoverable progress only; conversations, files,
 * settings, memories and catalogs remain owned by their existing owners.
 *
 * Reset formats the enterprise domain over every enterprise scope this device holds — it never narrows
 * to the current Session or a named principal. The only runtime constraint is the stop barrier: a live
 * Session is frozen through CLOSING before anything is deleted. Startup resume loads the session state
 * first (so identity survives a restart), never re-runs the runtime domain stop — this process holds no
 * enterprise work at startup — and defers to a pending foreign exit instead of failing the recovery gate.
 */
internal class EnterpriseDataResetService(
    private val store: EnterpriseDataResetStore,
    private val sessions: EnterpriseSessionController,
    private val exits: EnterpriseExitService,
    private val conversations: ConversationApplicationService,
    private val settings: SettingsStore,
    private val memories: MemoryRepository,
    private val catalogs: McpCatalogStore,
    private val files: FileManagementApplicationService,
    private val recoveryGate: ApplicationRecoveryGate,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    /** Startup sees these reasons only while a foreign pending exit owns the durable CLOSING. */
    private val pendingExitReasons = setOf("enterprise_exit_in_progress", "enterprise_reset_in_progress")
    private val _progress = MutableStateFlow<EnterpriseDataResetProgress?>(null)
    val progress: StateFlow<EnterpriseDataResetProgress?> = _progress.asStateFlow()

    /** UI command path: the one-time confirmed request starts a durable, restart-safe reset. */
    suspend fun reset(request: EnterpriseDataResetRequest) {
        recoveryGate.awaitReady()
        mutex.withLock {
            if (store.read() != null) throw EnterpriseConfigurationException("enterprise_reset_in_progress")
            val intent = intent(request, freezeScopes())
            store.write(intent)
            _progress.value = progressOf(intent)
            execute(intent, stopDomainWork = true)
        }
    }

    /** UI retry path: a retained intent with a stable failure resumes from its recorded stage. */
    suspend fun retryReset() {
        recoveryGate.awaitReady()
        mutex.withLock {
            val intent = store.read() ?: run { _progress.value = null; return@withLock }
            _progress.value = progressOf(intent)
            execute(intent, stopDomainWork = true)
        }
    }

    /**
     * Startup continuation before enterprise session recovery; it must never wait on the recovery gate.
     * The durable session state is loaded first: a resumed reset keeps lastIdentity through CLOSING
     * completion and a live Session enters its durable freeze instead of being formatted blind. This
     * process holds no enterprise work at startup, so the runtime domain stop is not re-run here.
     */
    suspend fun resumePending() {
        val intent = store.read() ?: return
        sessions.recover()
        mutex.withLock {
            _progress.value = progressOf(intent)
            try {
                execute(intent, stopDomainWork = false)
            } catch (error: EnterpriseConfigurationException) {
                // A foreign pending exit owns the durable CLOSING and completes later in this startup;
                // keep the intent and its retryable failure instead of failing the whole recovery gate.
                if (error.reason !in pendingExitReasons) throw error
            }
        }
    }

    /** Freezes every enterprise scope this device still holds; the personal scope never enters the plan. */
    private suspend fun freezeScopes(): List<ConfigurationScope.Enterprise> =
        (conversations.enterpriseScopes() + files.enterpriseScopes() + memories.enterpriseScopes() +
            settings.enterpriseScopes() + catalogs.enterpriseScopes())
            .sortedWith(compareBy({ it.authority.sourceNamespace }, { it.authority.deploymentId }, { it.userId }))

    private suspend fun execute(intent: EnterpriseDataResetIntent, stopDomainWork: Boolean) {
        try {
            run(intent, stopDomainWork)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The durable intent and its stage are retained; completion is never faked. The latest
            // published stage is reused so the failure projection never lags the persisted fact.
            _progress.value = (_progress.value ?: progressOf(intent)).copy(failure = reason(error))
            throw error
        }
    }

    private suspend fun run(original: EnterpriseDataResetIntent, stopDomainWork: Boolean) {
        var current = strengthenIdentityDeletion(original)

        // Stage 1: stop barrier for the live Session. Wiping it without CLOSING is refused by the owner.
        if (current.stage == EnterpriseDataResetStage.FROZEN) {
            freezeActiveSession()?.let { token ->
                // A startup resume freezes admission durably but never re-runs the runtime domain stop:
                // nothing in this process works for the domain yet, and stopEnterpriseWork's pending-turn
                // check would deadlock the recovery gate before turn recovery could converge those turns.
                if (stopDomainWork) exits.closeEnterpriseDomain(token)
            }
            // The first enumeration only makes the request durable. An enterprise write may have committed
            // before CLOSING revoked admission, so freeze the union again after the stop barrier and before
            // any owner is cleared. This closes the only window where a residual realm could be missed.
            current = current.copy(
                scopes = normalizeScopes(current.scopes + freezeScopes()),
                stage = EnterpriseDataResetStage.DOMAINS_CLOSED,
            )
            store.write(current)
            _progress.value = progressOf(current)
        }

        // Stage 2: the chosen branch over every frozen scope; completed owners are recorded for resumption.
        // Identity deletion may arrive after any checkpoint. Its stronger branch rolls DATA_CLEARED back
        // once and resumes the missing history owners instead of accepting the earlier KEEP_HISTORY result.
        while (current.stage != EnterpriseDataResetStage.DATA_CLEARED) {
            current = strengthenIdentityDeletion(current)
            val scopes = current.scopes
            val done = current.completedOwners.toMutableSet()
            suspend fun step(owner: String, clear: suspend () -> Unit) {
                if (!done.add(owner)) return
                clear()
                current = current.copy(completedOwners = done.toList())
                store.write(current)
            }
            // Reset access always drops delivered managed catalogs at the access trust boundary.
            step(EnterpriseDataResetIntent.OWNER_CATALOGS) { scopes.forEach { catalogs.clearEnterpriseScope(it) } }
            current = strengthenIdentityDeletion(current)
            if (current.mode == EnterpriseDataResetMode.CLEAR_ALL) {
                step(EnterpriseDataResetIntent.OWNER_CONVERSATIONS) { scopes.forEach { conversations.clearEnterpriseScope(it) } }
                step(EnterpriseDataResetIntent.OWNER_FILES) { scopes.forEach { files.clearEnterpriseScope(it) } }
                step(EnterpriseDataResetIntent.OWNER_MEMORIES) { scopes.forEach { memories.clearEnterpriseScope(it) } }
                step(EnterpriseDataResetIntent.OWNER_SETTINGS) { scopes.forEach { settings.clearEnterprisePreferences(it) } }
            }
            current = current.copy(stage = EnterpriseDataResetStage.DATA_CLEARED)
            store.write(current)
            _progress.value = progressOf(current)
        }

        current = strengthenIdentityDeletion(current)
        if (current.stage != EnterpriseDataResetStage.DATA_CLEARED) {
            run(current, stopDomainWork)
            return
        }

        // Stage 3: rewrite the applied store to signed-out (idempotent when an earlier attempt reached here).
        val retainIdentity = current.mode == EnterpriseDataResetMode.KEEP_HISTORY
        val pending = pendingResetToken()
        if (pending != null) sessions.completeLocalDataReset(pending, retainIdentity)
        else sessions.resetLocalDataState(retainIdentity)

        store.clear()
        _progress.value = null
    }

    /** Identity deletion is terminal and stronger than either user-selected reset branch. */
    private suspend fun strengthenIdentityDeletion(intent: EnterpriseDataResetIntent): EnterpriseDataResetIntent {
        val manifest = (sessions.state.value as? EnterpriseState.Available)?.manifest
        if (manifest?.exitReason != EnterpriseExitReason.IDENTITY_DELETED ||
            intent.mode == EnterpriseDataResetMode.CLEAR_ALL) {
            return intent
        }
        val strengthened = intent.copy(
            mode = EnterpriseDataResetMode.CLEAR_ALL,
            scopes = normalizeScopes(intent.scopes + freezeScopes()),
            stage = if (intent.stage == EnterpriseDataResetStage.DATA_CLEARED) {
                EnterpriseDataResetStage.DOMAINS_CLOSED
            } else intent.stage,
        )
        store.write(strengthened)
        _progress.value = progressOf(strengthened)
        return strengthened
    }

    /**
     * Freezes the live Session through the CLOSING protocol; returns null when there is nothing to stop.
     * Only the in-memory state is consulted, so unreadable storage is never parsed here. A foreign exit
     * already closing is refused so its own protocol can finish first.
     */
    private suspend fun freezeActiveSession(): EnterpriseExitToken? {
        val state = sessions.state.value
        if (state is EnterpriseState.Failed) return null
        if (state !is EnterpriseState.Available) return null
        if (state.manifest.phase == EnterpriseSessionPhase.CLOSING) {
            val pending = requireNotNull(sessions.pendingExit())
            if (pending.reason != EnterpriseExitReason.LOCAL_DATA_RESET) {
                throw EnterpriseConfigurationException("enterprise_exit_in_progress")
            }
            return pending
        }
        // No Session (for example after a completed exit) leaves nothing to freeze.
        val request = sessions.captureExitRequest() ?: return null
        return sessions.beginLocalDataReset(request)
    }

    /** The reset CLOSING token is adopted from memory only; every other state falls back to a direct rewrite. */
    private suspend fun pendingResetToken(): EnterpriseExitToken? {
        val state = sessions.state.value as? EnterpriseState.Available ?: return null
        if (state.manifest.phase != EnterpriseSessionPhase.CLOSING) return null
        return sessions.pendingExit()?.takeIf { it.reason == EnterpriseExitReason.LOCAL_DATA_RESET }
    }

    private fun intent(request: EnterpriseDataResetRequest, scopes: List<ConfigurationScope.Enterprise>) =
        EnterpriseDataResetIntent(
            schemaVersion = EnterpriseDataResetIntent.SCHEMA_VERSION,
            operationId = request.operationId.toString(),
            mode = request.mode,
            createdAtMillis = nowMillis(),
            scopes = scopes,
            stage = EnterpriseDataResetStage.FROZEN,
            completedOwners = emptyList(),
        )

    private fun normalizeScopes(scopes: Collection<ConfigurationScope.Enterprise>) = scopes.distinct()
        .sortedWith(compareBy({ it.authority.sourceNamespace }, { it.authority.deploymentId }, { it.userId }))

    private fun progressOf(intent: EnterpriseDataResetIntent) = EnterpriseDataResetProgress(
        operationId = Uuid.parse(intent.operationId),
        mode = intent.mode,
        stage = intent.stage,
        failure = null,
    )

    private fun reason(error: Exception): String = when (error) {
        is EnterpriseConfigurationException -> error.reason
        is net.weero.measix.pilot.data.enterprise.EnterpriseStorageException -> error.reason
        else -> error.userVisibleDiagnostic()
    }
}
