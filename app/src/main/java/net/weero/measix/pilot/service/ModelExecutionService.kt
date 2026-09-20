package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.supportsBuiltInSearch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.enterprise.EnterpriseExecution
import net.weero.measix.pilot.data.enterprise.PlatformProviderDefinitionClientProtocol
import net.weero.measix.pilot.data.enterprise.EnterpriseExecutionLease
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.withAssistantSearch
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpec
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpecResolution
import net.weero.measix.pilot.data.ai.subassistant.resolveSubAssistantRunSpec
import net.weero.measix.pilot.data.ai.subassistant.resolvePreWriteBlockReason
import net.weero.measix.pilot.data.ai.subassistant.resolveActiveRunStopReason
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ModelRequests
import net.weero.measix.pilot.service.runtime.ModelExecutionLease
import net.weero.measix.pilot.service.runtime.ModelRequestTarget
import net.weero.measix.pilot.service.runtime.captureProviderCredentialOwner
import net.weero.measix.pilot.service.runtime.freezeProviderWireShape
import net.weero.measix.pilot.service.runtime.mergeProviderTransportCredentials
import net.weero.measix.pilot.service.runtime.resolveProviderTransportOwner
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion
import net.weero.measix.pilot.data.configuration.ModelSelectionRole
import kotlin.uuid.Uuid

/** Frozen model request shape; credentials remain with the original task resource owner. */
internal data class ModelExecutionSnapshot(
    val model: Model,
    val requests: ModelRequests,
    val userRevision: String,
    val enterpriseVersion: EnterpriseAppliedVersion?,
    val mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
    val imageGeneration: net.weero.measix.pilot.data.configuration.ImageGenerationCapabilities?,
)

internal class CapturedModelConfiguration(
    val userSettings: Settings,
    val configuration: ResolvedConfiguration,
    val assistant: Assistant,
    val model: ModelExecutionSnapshot,
    val selectionRevision: Long,
    val interactionId: Uuid,
    val inspectionModel: ModelExecutionSnapshot? = null,
    val imageModel: ModelExecutionSnapshot? = null,
) {
    val mediaCapabilities: RequestMediaCapabilities get() = model.mediaCapabilities
}

internal data class ChildModelAdmission(val callerAssistantId: ConfigurationReference, val runSpec: SubAssistantRunSpec)

/** Captures model inputs once and revalidates original-resource admission at each external request. */
internal class ModelExecutionService(
    private val settings: SettingsStore,
    private val sessions: EnterpriseSessionController,
    private val recoveryGate: ApplicationRecoveryGate,
    private val providers: ProviderManager,
    private val appScope: net.weero.measix.pilot.AppScope,
    private val synchronization: EnterpriseSynchronizationService,
    private val platform: PlatformEnterpriseService,
) {
    suspend fun read(access: RealmAccess): ExecutionConfigurationSnapshot {
        recoveryGate.awaitReady()
        return withConfiguration(access) { it }
    }

    suspend fun captureTurn(
        access: RealmAccess,
        runtime: ConversationRuntime,
        turnId: Uuid,
        worker: Job,
        assistantId: ConfigurationReference,
        child: ChildModelAdmission? = null,
        stopInteraction: suspend () -> Unit,
    ): CapturedModelConfiguration = captureModel(access, runtime, worker, assistantId, ModelSelectionRole.CHAT, child, turnId,
        onBarrier = barrier(access, { runtime.requestCancel(turnId, "managed_snapshot_required") }, stopInteraction)) {
        runtime.bindModelExecution(turnId, worker, assistantId, it)
    }

    suspend fun captureAuxiliary(
        access: RealmAccess,
        runtime: ConversationRuntime,
        worker: Job,
        assistantId: ConfigurationReference,
        role: ModelSelectionRole,
    ): CapturedModelConfiguration {
        require(role in setOf(ModelSelectionRole.TITLE, ModelSelectionRole.SUGGESTION, ModelSelectionRole.COMPRESS))
        return captureModel(access, runtime, worker, assistantId, role, null,
            onBarrier = barrier(access, { worker.cancel(CancellationException("managed_snapshot_required")) }, {
                worker.join()
                runtime.releaseAuxiliaryModels(listOf(worker))
            })) {
            runtime.bindAuxiliaryModelExecution(access, worker, assistantId, it)
        }
    }

    suspend fun capturePageImage(
        selection: RealmSelection,
        worker: Job,
        modelId: ConfigurationReference,
        stopRequest: suspend () -> Unit,
        bindOwner: (ModelExecutionLease) -> Unit,
    ): ModelExecutionSnapshot {
        val interactionId = Uuid.random()
        return captureResources(selection.access, worker, bindOwner, selection,
            barrier(selection.access, { worker.cancel(CancellationException("managed_snapshot_required")) }, stopRequest)) { snapshot, bindings, _, primary ->
            captureSelection(selection.access, snapshot, bindings, ModelSelectionRole.IMAGE,
                snapshot.configuration.choice(net.weero.measix.pilot.data.configuration.ResourceSelectionSlot.IMAGE_MODEL, modelId),
                null, primary, interactionId, selection) { }
        }
    }

    private suspend fun captureModel(
        access: RealmAccess,
        runtime: ConversationRuntime,
        worker: Job,
        assistantId: ConfigurationReference,
        role: ModelSelectionRole,
        child: ChildModelAdmission?,
        turnId: Uuid? = null,
        onBarrier: () -> Unit,
        bindOwner: (ModelExecutionLease) -> Unit,
    ): CapturedModelConfiguration {
        val interactionId = turnId ?: Uuid.random()
        val selectionRevision = sessions.selectionRevision.value
        check(runtime.durable.header.scope == access.scope) { "conversation_scope_mismatch" }
        if (role == ModelSelectionRole.CHAT) {
            check((runtime.durable.header.parentConversationId != null) == (child != null)) { "model_execution_lineage_mismatch" }
        }
        return captureResources(access, worker, bindOwner, onBarrier = onBarrier) { snapshot, bindings, execution, primary ->
            val configuration = snapshot.configuration
            requireAvailable(configuration, ConfigurationCategory.ASSISTANT, assistantId)
            val configured = configuration.assistants[assistantId] ?: error("conversation_assistant_unavailable")
            val assistant = if (child == null) configured else {
                resolvePreWriteBlockReason(configuration, child.callerAssistantId, assistantId, child.runSpec)?.let(::error)
                val caller = configuration.assistants[child.callerAssistantId] ?: error("target_access_revoked")
                val selected = resolveSubAssistantRunSpec(configuration::availableChatModel, caller, configured)
                val spec = (selected as? SubAssistantRunSpecResolution.Ready)?.spec ?: error("target_model_unavailable")
                check(spec == child.runSpec) { "sub_assistant_configuration_changed_during_prepare" }
                spec.assistant
            }
            check(assistant.id == assistantId) { "model_execution_assistant_changed" }
            val validateAssistant: (ResolvedConfiguration) -> Unit = { latest ->
                child?.let {
                    resolveActiveRunStopReason(latest, it.callerAssistantId, assistantId, it.runSpec)?.let { reason ->
                        runtime.requestCancel(requireNotNull(turnId), reason)
                        throw CancellationException(reason)
                    }
                }
                requireAvailable(latest, ConfigurationCategory.ASSISTANT, assistantId)
            }
            val selected = if (role == ModelSelectionRole.CHAT) configuration.assistantModel(assistant)
                else configuration.auxiliaryModel(role, assistant)
            val model = captureSelection(access, snapshot, bindings, role, selected, assistant, primary, interactionId, validateAssistant = validateAssistant)
            fun captureTool(toolRole: ModelSelectionRole): ModelExecutionSnapshot? =
                configuration.modelSelection(toolRole).takeIf { it.isAvailable }?.let {
                    captureSelection(access, snapshot, bindings, toolRole, it, assistant, execution::borrow, interactionId, validateAssistant = validateAssistant)
                }
            val inspection = if (role == ModelSelectionRole.CHAT) captureTool(ModelSelectionRole.ATTACHMENT_INSPECTION) else null
            val image = if (role == ModelSelectionRole.CHAT &&
                net.weero.measix.pilot.data.ai.tools.local.LocalToolOption.TextToImage in assistant.localTools)
                captureTool(ModelSelectionRole.IMAGE) else null
            CapturedModelConfiguration(snapshot.userSettings, configuration, assistant, model, selectionRevision, interactionId, inspection, image)
        }
    }

    private suspend fun <T> captureResources(
        access: RealmAccess,
        worker: Job,
        bindOwner: (ModelExecutionLease) -> Unit,
        page: RealmSelection? = null,
        onBarrier: () -> Unit,
        prepare: (ExecutionConfigurationSnapshot, EnterpriseExecutionLease?, ModelExecutionLease,
            (suspend ((ModelRequestTarget) -> Unit) -> Unit) -> ModelRequests) -> T,
    ): T {
        recoveryGate.awaitReady()
        worker.ensureActive()
        var bindings: EnterpriseExecutionLease? = null
        var admission: (suspend ((ModelRequestTarget) -> Unit) -> Unit)? = null
        val enterpriseAccess = access as? RealmAccess.Enterprise
        val execution = ModelExecutionLease(
            releaseOwner = { bindings?.release() },
            onManagedSnapshotRequired = onBarrier,
            normalizeFailure = { target, error ->
                if (enterpriseAccess != null && target is ModelRequestTarget.Remote &&
                    target.credentials is RequestCredentials.Routed) {
                    platform.managedRuntimeFailure(enterpriseAccess, error)
                } else error
            },
            onRequestFinished = { target ->
                if (enterpriseAccess != null && target is ModelRequestTarget.Remote &&
                    target.credentials is RequestCredentials.Routed) platform.runtimeCompleted(enterpriseAccess)
            },
        ) { accept ->
            requireNotNull(admission) { "model_execution_not_prepared" }(accept)
        }
        bindOwner(execution)
        if (access is RealmAccess.Enterprise) {
            val checkedVersion = synchronization.prepareExecution(access)
            bindings = sessions.captureExecution(access, checkedVersion)
        }
        val originalBindings = bindings
        return withConfiguration(access, page) { snapshot ->
            if (originalBindings != null) {
                val current = sessions.state.value as EnterpriseState.Available
                check(current.manifest.applied == originalBindings.version) { "enterprise_configuration_changed_during_capture" }
            }
            prepare(snapshot, originalBindings, execution) {
                check(admission == null) { "model_execution_already_prepared" }
                admission = it
                execution
            }
        }
    }

    /** The original owner stops and releases outside request/admission locks; no failed request is replayed. */
    private fun barrier(access: RealmAccess, revoke: () -> Unit, stop: suspend () -> Unit): () -> Unit = {
        revoke()
        if (access is RealmAccess.Enterprise) appScope.launch {
            try {
                stop()
                synchronization.synchronize(access)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.e("ModelExecutionService", "Model barrier cleanup or synchronization failed", error) }
        }
    }

    private fun captureSelection(
        access: RealmAccess,
        snapshot: ExecutionConfigurationSnapshot,
        bindings: EnterpriseExecutionLease?,
        role: ModelSelectionRole,
        selection: net.weero.measix.pilot.data.configuration.ConfigurationSelection,
        assistant: Assistant?,
        requestView: (suspend ((ModelRequestTarget) -> Unit) -> Unit) -> ModelRequests,
        interactionId: Uuid,
        page: RealmSelection? = null,
        validateAssistant: (ResolvedConfiguration) -> Unit,
    ): ModelExecutionSnapshot {
        val configuration = snapshot.configuration
        check(selection.isAvailable) { "model_selection_unavailable:${role.name}:${selection.unavailableReason}" }
        val modelId = requireNotNull(selection.reference)
        requireAvailable(configuration, ConfigurationCategory.MODEL, modelId)
        val resolvedModel = configuration.models[modelId] ?: error("chat_model_unavailable")
        val selected = resolvedModel.model
        check(selected.type == role.type) { "model_capability_mismatch" }
        val model = selected.copy(
            customHeaders = selected.customHeaders.toList(), customBodies = selected.customBodies.toList(),
            inputModalities = selected.inputModalities.toList(), outputModalities = selected.outputModalities.toList(),
            abilities = selected.abilities.toList(), tools = selected.tools.toSet(), providerOverwrite = null,
        ).let { if (role == ModelSelectionRole.CHAT) it.withAssistantSearch(requireNotNull(assistant)) else it.copy(tools = emptySet()) }
        val enterpriseId = modelId as? ConfigurationReference.Enterprise
        val platformExecution = enterpriseId?.let {
            requireNotNull(bindings) { "enterprise_execution_owner_missing" }.execution as EnterpriseExecution.Platform
        }
        val platformProvider = platformExecution?.let {
            val definitions = requireNotNull(bindings).configuration
            if (role == ModelSelectionRole.IMAGE) {
                val definition = definitions.imageGenerators.single { it.id == modelId.id }
                check(definition.protocol == net.weero.measix.pilot.data.enterprise.PlatformImageGenerationDefinitionClientProtocol.OPENAI_IMAGES_GENERATIONS) {
                    "unsupported_platform_image_protocol"
                }
                ProviderSetting.OpenAI(
                    id = model.id,
                    name = model.displayName,
                    models = listOf(model),
                    baseUrl = it.connection.origin,
                    apiKey = "",
                )
            } else {
                val definition = definitions.models.single { it.id == modelId.id }
                val protocol = definitions.providers.single { it.id == definition.providerId }.protocol
                when (protocol) {
                    PlatformProviderDefinitionClientProtocol.OPENAI_CHAT_COMPLETIONS,
                    PlatformProviderDefinitionClientProtocol.OPENAI_RESPONSES -> ProviderSetting.OpenAI(
                        id = model.id, name = model.displayName, models = listOf(model), baseUrl = it.connection.origin,
                        apiKey = "", useResponseApi = protocol == PlatformProviderDefinitionClientProtocol.OPENAI_RESPONSES,
                    )
                    PlatformProviderDefinitionClientProtocol.GOOGLE_GENERATE_CONTENT -> ProviderSetting.Google(
                        id = model.id, name = model.displayName, models = listOf(model), baseUrl = it.connection.origin, apiKey = "",
                    )
                    PlatformProviderDefinitionClientProtocol.ANTHROPIC_MESSAGES -> ProviderSetting.Claude(
                        id = model.id, name = model.displayName, models = listOf(model), baseUrl = it.connection.origin, apiKey = "",
                    )
                }
            }
        }
        val initialTarget = if (platformProvider != null) ModelRequestTarget.Remote(platformProvider)
            else ModelRequestTarget.Remote(selected.findProvider(snapshot.userSettings.providers)
                ?: error("model_provider_unavailable"))
        check(BuiltInTools.Search !in model.tools ||
            supportsBuiltInSearch(initialTarget.provider)) {
            "model_builtin_search_not_supported"
        }
        val frozenShape = freezeProviderWireShape(initialTarget.provider, model)
        val credentialOwner = if (enterpriseId == null) {
            captureProviderCredentialOwner(snapshot.userSettings, selected, initialTarget.provider)
        } else null
        val media = if (role.type == ModelType.IMAGE) RequestMediaCapabilities.NONE
            else providers.getProviderByType(initialTarget.provider).requestMediaCapabilities(initialTarget.provider, model)
        val modelAdmission: suspend ((ModelRequestTarget) -> Unit) -> Unit = { accept ->
            // Refresh belongs to the Session owner and must happen outside its configuration lock.
            val platformToken = platformExecution?.let {
                platform.accessToken((access as RealmAccess.Enterprise).sessionId, it.connection)
            }
            withConfiguration(access, page) { latest ->
                currentCoroutineContext().ensureActive()
                validateAssistant(latest.configuration)
                if (role == ModelSelectionRole.IMAGE && assistant != null) {
                    check(net.weero.measix.pilot.data.ai.tools.local.LocalToolOption.TextToImage in
                        requireNotNull(latest.configuration.assistants[assistant.id]).localTools) { "tool_revoked" }
                }
                requireAvailable(latest.configuration, ConfigurationCategory.MODEL, modelId)
                val target = if (platformExecution != null) {
                    check(requireNotNull(bindings).execution == platformExecution) { "platform_execution_changed" }
                    var endpoint = platformExecution.connection.runtime(modelId.id,
                        platformExecution.runtimePaths.getValue(modelId.id))
                    if (platformProvider is ProviderSetting.Google) endpoint += "?alt=sse"
                    ModelRequestTarget.Remote(requireNotNull(platformProvider), listOf(
                        CustomHeader("X-Measix-Managed-Generation", bindings.configuration.generation.toString()),
                        CustomHeader("X-Measix-Interaction-Id", "int_$interactionId"),
                    ), RequestCredentials.Routed(endpoint, requireNotNull(platformToken).value))
                } else ModelRequestTarget.Remote(mergeProviderTransportCredentials(
                    requireNotNull(frozenShape),
                    resolveProviderTransportOwner(latest.userSettings, requireNotNull(credentialOwner)),
                ))
                accept(target)
            }
        }
        if (role == ModelSelectionRole.ATTACHMENT_INSPECTION) {
            check(media.userImages == RequestImageSupport.STRUCTURED) {
                "Provider contract violation: IMAGE model cannot encode structured USER images"
            }
        }
        return ModelExecutionSnapshot(model, requestView(modelAdmission), snapshot.userRevision, bindings?.version, media,
            resolvedModel.imageGeneration)
    }

    private suspend fun <T> withConfiguration(
        access: RealmAccess,
        page: RealmSelection? = null,
        operation: suspend (ExecutionConfigurationSnapshot) -> T,
    ): T {
        suspend fun read(state: EnterpriseState): T {
            if (access is RealmAccess.Enterprise) {
                check((state as? EnterpriseState.Available)?.manifest?.phase ==
                    net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase.READY) { "enterprise_session_not_ready" }
            }
            return settings.withExecutionConfiguration(access.scope, state) { snapshot ->
                // Session time can expire while waiting for the user configuration transaction.
                if (page != null) sessions.requirePublishedSelection(page)
                else sessions.requirePublishedRealmAccess(access)
                operation(snapshot)
            }
        }
        return if (page != null) {
            check(page.access == access) { "model_execution_page_scope_mismatch" }
            sessions.withSelectedRealmSelection(page) { read(sessions.state.value) }
        } else when (access) {
            RealmAccess.Personal -> read(sessions.state.value)
            is RealmAccess.Enterprise -> sessions.withAppliedConfiguration(access) { read(it) }
        }
    }

    private fun requireAvailable(configuration: ResolvedConfiguration, category: ConfigurationCategory, id: ConfigurationReference) {
        val access = configuration.access(category, id)
        check(access.canExecute) { "configuration_resource_unavailable:${category.name}:${access.unavailableReason}" }
    }

}
