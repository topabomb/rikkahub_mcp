package net.weero.measix.pilot.service

import android.content.Context
import android.net.Uri
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.EnterprisePolicy
import net.weero.measix.pilot.data.configuration.GatewayEnablementPolicy
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.portal.*

internal data class InstalledEnterpriseSource(
    val scope: ConfigurationScope.Enterprise,
    val enterpriseName: String,
    val userName: String,
)

/** Public source projection; private runtime bindings never enter the configuration editor. */
internal data class LocalEnterpriseConfigurationUiModel(
    val selection: RealmSelection,
    val revision: String,
    val generation: Long,
    val policy: EnterprisePolicy,
    val models: List<EnterpriseModel>,
    val gateways: List<EnterpriseGateway>,
)

internal sealed interface LocalEnterpriseConfigurationChange {
    data class Policy(val value: EnterprisePolicy) : LocalEnterpriseConfigurationChange
    data class Gateway(val id: String, val enablement: GatewayEnablementPolicy) : LocalEnterpriseConfigurationChange
    data class RenameModel(val id: String, val name: String) : LocalEnterpriseConfigurationChange
    data class ModelEnabled(val id: String, val enabled: Boolean) : LocalEnterpriseConfigurationChange
    data class AddExampleModel(val name: String) : LocalEnterpriseConfigurationChange
    data class DeleteModel(val id: String) : LocalEnterpriseConfigurationChange
}

internal data class LocalEnterpriseConfigurationEditResult(val configuration: LocalEnterpriseConfigurationUiModel, val applied: Boolean)

internal enum class LocalEnterpriseScenario { DISCONNECT, RECONNECT, EXPIRE_SOON, REVOKE }

internal data class EnterpriseOverview(
    val selection: RealmSelection?,
    val phase: EnterpriseSessionPhase?,
    val enterpriseName: String?,
    val userName: String?,
    val access: RealmAccess.Enterprise?,
    val isLocal: Boolean,
    val generation: Long?,
    val lastSyncMillis: Long?,
    val failure: String?,
    val exitFailure: EnterpriseExitFailure?,
    val switching: Boolean,
)

/** Native enterprise UI commands share the existing source, Session, synchronization and exit owners. */
internal class EnterpriseApplicationService(
    private val sessions: EnterpriseSessionController,
    private val source: LocalEnterpriseSource,
    private val synchronization: EnterpriseSynchronizationService,
    private val exit: EnterpriseExitService,
    private val portals: PortalDocumentRegistry,
    private val recovery: ApplicationRecoveryGate,
    private val scope: CoroutineScope,
    private val media: PortalMediaStore,
    private val terminals: net.weero.measix.pilot.service.workspace.WorkspaceTerminalRuntime,
    private val speech: SpeechApplicationService,
) {
    private data class Switching(val request: RealmSwitchRequest, val result: Deferred<RealmSelection>)
    private val mutex = Mutex()
    private val switching = MutableStateFlow<Switching?>(null)

    private fun observePresentation(): Flow<EnterprisePresentation> =
        combine(sessions.state, sessions.selectionRevision) { _, _ -> Unit }
            .map { sessions.readPresentation() }
            .distinctUntilChanged()

    // Task progress must remain observable while Session admission waits for host teardown.
    fun observe(): Flow<EnterpriseOverview> = combine(observePresentation(), exit.failure, switching) {
            presentation, exitFailure, activeSwitch ->
            val available = presentation.state as? EnterpriseState.Available
            val manifest = available?.manifest
            val identity = manifest?.session?.identity ?: manifest?.lastIdentity
            EnterpriseOverview(
                selection = presentation.selection,
                phase = manifest?.phase,
                enterpriseName = identity?.enterpriseName,
                userName = identity?.userName,
                access = manifest?.session?.let { RealmAccess.Enterprise(it.identity.scope, it.id) },
                isLocal = identity?.authority?.isLocal == true,
                generation = manifest?.applied?.generation,
                lastSyncMillis = manifest?.lastConfigurationSyncMillis,
                failure = (presentation.state as? EnterpriseState.Failed)?.reason,
                exitFailure = exitFailure,
                switching = activeSwitch != null,
            )
        }.distinctUntilChanged()

    suspend fun joinExample() { recovery.awaitReady(); source.enrollExample() }
    suspend fun joinPendingExample() { recovery.awaitReady(); source.enrollExample(configurationUnavailable = true) }
    suspend fun runLocalScenario(selection: RealmSelection, access: RealmAccess.Enterprise, scenario: LocalEnterpriseScenario) {
        recovery.awaitReady()
        when (scenario) {
            LocalEnterpriseScenario.DISCONNECT -> sessions.setLocalOffline(selection, access, true)
            LocalEnterpriseScenario.RECONNECT -> sessions.setLocalOffline(selection, access, false)
            LocalEnterpriseScenario.EXPIRE_SOON -> sessions.shortenLocalSession(selection, access)
            LocalEnterpriseScenario.REVOKE -> {
                sessions.validateLocalSessionTarget(selection, access)
                // Exit cleanup must acquire Session admission independently of scenario validation.
                exit.invalidate(access, EnterpriseExitReason.AUTHORIZATION_REVOKED)
            }
        }
    }
    suspend fun join(text: String) { recovery.awaitReady(); source.enroll(text) }
    suspend fun exampleEnrollmentText(): String { recovery.awaitReady(); return source.exampleEnrollmentText() }
    suspend fun installedSources(): List<InstalledEnterpriseSource> {
        recovery.awaitReady()
        return source.installations().map { InstalledEnterpriseSource(it.identity.scope,
            it.identity.enterpriseName, it.identity.userName) }
    }
    suspend fun joinInstalled(target: ConfigurationScope.Enterprise) {
        recovery.awaitReady()
        source.enroll(source.enrollmentText(target))
    }
    suspend fun importConfiguration(context: Context, selection: RealmSelection, uri: Uri): LocalEnterpriseImportResult {
        recovery.awaitReady()
        return withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openInputStream(uri)) { "enterprise_configuration_file_unavailable" }
                .use { source.importPackage(selection, it) }
        }
    }
    suspend fun synchronize(access: RealmAccess.Enterprise) { recovery.awaitReady(); synchronization.synchronize(access) }
    suspend fun localFeed(selection: RealmSelection): LocalEnterpriseFeedSnapshot {
        recovery.awaitReady()
        return sessions.readLocalFeed(selection)
    }
    suspend fun changeLocalFeed(original: LocalEnterpriseFeedSnapshot, command: EnterpriseFeedCommand): LocalEnterpriseFeedSnapshot {
        recovery.awaitReady()
        return sessions.changeFeed(original.selection, original.revision, command)
    }
    suspend fun localConfiguration(selection: RealmSelection): LocalEnterpriseConfigurationUiModel {
        recovery.awaitReady()
        val access = selection.access as? RealmAccess.Enterprise ?: throw EnterpriseConfigurationException("local_enterprise_required")
        if (!access.scope.authority.isLocal) throw EnterpriseConfigurationException("local_enterprise_required")
        return sessions.withSelectedRealmSelection(selection) {
            val candidate = source.candidate(access.scope) ?: throw EnterpriseConfigurationException("enterprise_configuration_not_ready")
            sessions.requirePublishedSelection(selection)
            candidate.presentation(selection)
        }
    }

    suspend fun changeLocalConfiguration(original: LocalEnterpriseConfigurationUiModel,
        change: LocalEnterpriseConfigurationChange): LocalEnterpriseConfigurationEditResult {
        recovery.awaitReady()
        val access = original.selection.access as? RealmAccess.Enterprise ?: throw EnterpriseConfigurationException("local_enterprise_required")
        if (!access.scope.authority.isLocal) throw EnterpriseConfigurationException("local_enterprise_required")
        val published = sessions.withSelectedRealmSelection(original.selection) {
            source.changeConfiguration(access.scope, original.revision) { packet ->
                sessions.requirePublishedSelection(original.selection)
                val config = packet.configuration
                fun requireModel(id: String) { if (config.models.none { it.id == id }) throw EnterpriseConfigurationException("enterprise_model_missing") }
                when (change) {
                    is LocalEnterpriseConfigurationChange.Policy -> packet.copy(configuration = config.copy(policy = change.value))
                    is LocalEnterpriseConfigurationChange.Gateway -> {
                        if (config.gateways.none { it.id == change.id }) throw EnterpriseConfigurationException("enterprise_gateway_missing")
                        packet.copy(configuration = config.copy(gateways = config.gateways.map {
                            if (it.id == change.id) it.copy(enablement = change.enablement) else it
                        }))
                    }
                    is LocalEnterpriseConfigurationChange.RenameModel -> {
                        requireModel(change.id)
                        packet.copy(configuration = config.copy(models = config.models.map {
                            if (it.id == change.id) it.copy(name = change.name.trim()) else it
                        }))
                    }
                    is LocalEnterpriseConfigurationChange.ModelEnabled -> {
                        requireModel(change.id)
                        packet.copy(configuration = config.copy(models = config.models.map {
                            if (it.id == change.id) it.copy(enabled = change.enabled) else it
                        }))
                    }
                    is LocalEnterpriseConfigurationChange.AddExampleModel -> {
                        val id = "mdl_${Uuid.random()}"
                        packet.copy(configuration = config.copy(models = config.models + EnterpriseModel(id, change.name.trim(), "local-example")),
                            runtimeBindings = packet.runtimeBindings + EnterpriseRuntimeBinding(id, EnterpriseRuntimeProtocol.EXAMPLE))
                    }
                    is LocalEnterpriseConfigurationChange.DeleteModel -> {
                        requireModel(change.id)
                        packet.copy(configuration = config.copy(models = config.models.filterNot { it.id == change.id }),
                            runtimeBindings = packet.runtimeBindings.filterNot { it.resourceId == change.id })
                    }
                }
            }
        }
        // Source publication is durable even if applying it fails. Synchronization owns the client commit.
        val applied = try {
            val receipt = synchronization.synchronize(access)
            (receipt.manifest.applied?.generation ?: 0) >= published.packet.configuration.generation
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        return LocalEnterpriseConfigurationEditResult(published.presentation(original.selection), applied)
    }

    private fun LocalEnterpriseCandidate.presentation(selection: RealmSelection) = LocalEnterpriseConfigurationUiModel(
        selection, revision, packet.configuration.generation, packet.configuration.policy,
        packet.configuration.models, packet.configuration.gateways,
    )

    suspend fun captureExitRequest(): EnterpriseExitRequest? = exit.captureRequest()
    suspend fun exit(request: EnterpriseExitRequest) { exit.exit(request) }
    suspend fun retryExit(failure: EnterpriseExitFailure) { exit.retry(failure) }

    suspend fun openPortal(context: Context, selection: RealmSelection, onClosed: (PortalClosure) -> Unit): PortalWebView {
        recovery.awaitReady()
        return PortalWebView.open(context, selection, sessions, synchronization, scope, portals,
            { document -> PortalNativeActions(context, document, sessions, exit, media.open(document.id),
                AndroidPortalCaptureFactory(context, scope), scope) }, onClosed)
    }

    suspend fun switchRealm(request: RealmSwitchRequest): RealmSelection {
        recovery.awaitReady()
        val pending = mutex.withLock {
            switching.value?.let { current ->
                if (current.request != request) throw EnterpriseConfigurationException("enterprise_switch_in_progress")
                return@withLock current.result
            }
            scope.coroutineContext.ensureActive()
            scope.async(start = CoroutineStart.LAZY) { performSwitch(request) }.also { pending ->
                switching.value = Switching(request, pending)
                pending.start()
            }
        }
        return pending.await()
    }

    private suspend fun performSwitch(request: RealmSwitchRequest): RealmSelection {
        var receipt: PortalCloseReceipt? = null
        var selected: RealmSelection? = null
        var failure: Exception? = null
        try {
            selected = sessions.switchRealm(request) { previous ->
                speech.revoke(previous)
                terminals.revokeViewports(previous)
                if (previous is RealmAccess.Enterprise) {
                    receipt = portals.capture(previous)
                    receipt!!.revoke(PortalCloseReason.AUTHORIZATION_REVOKED)
                    receipt!!.awaitHostsClosed()
                }
            }
        } catch (error: Exception) {
            failure = error
        }
        try {
            // The accepted switch owns this receipt even if its caller or application scope is cancelled.
            withContext(NonCancellable) {
                var portalFailure: Exception? = null
                try { receipt?.awaitClosed() } catch (error: Exception) { portalFailure = error }
                try { speech.closeRealm(request.selection.access) } catch (error: Exception) {
                    if (portalFailure == null) throw error else portalFailure.addSuppressed(error)
                }
                portalFailure?.let { throw it }
            }
        } catch (cleanup: Exception) {
            if (failure == null) failure = cleanup else if (cleanup !== failure) failure.addSuppressed(cleanup)
        } finally {
            withContext(NonCancellable) { mutex.withLock { switching.value = null } }
        }
        failure?.let { throw it }
        return requireNotNull(selected)
    }
}
