package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
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
import net.weero.measix.pilot.data.enterprise.EnterpriseBindingLease
import net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeBinding
import net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProtocol
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpec
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpecResolution
import net.weero.measix.pilot.data.ai.subassistant.resolveSubAssistantRunSpec
import net.weero.measix.pilot.data.ai.subassistant.resolvePreWriteBlockReason
import net.weero.measix.pilot.data.ai.subassistant.resolveActiveRunStopReason
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ModelExecutionLease
import net.weero.measix.pilot.service.runtime.ModelRequestTarget
import net.weero.measix.pilot.service.runtime.captureProviderCredentialOwner
import net.weero.measix.pilot.service.runtime.freezeProviderWireShape
import net.weero.measix.pilot.service.runtime.mergeProviderTransportCredentials
import net.weero.measix.pilot.service.runtime.resolveProviderTransportOwner
import net.weero.measix.pilot.service.turn.TurnModelSnapshot
import kotlin.uuid.Uuid

internal class CapturedTurnConfiguration(
    val userSettings: Settings,
    val assistant: Assistant,
    val model: TurnModelSnapshot,
    val mediaCapabilities: RequestMediaCapabilities,
)

internal data class ChildModelAdmission(val callerAssistantId: ConfigurationReference, val runSpec: SubAssistantRunSpec)

/** Captures model inputs once and revalidates original-resource admission at each external request. */
internal class ModelExecutionService(
    private val settings: SettingsStore,
    private val sessions: EnterpriseSessionController,
    private val recoveryGate: ApplicationRecoveryGate,
    private val providers: ProviderManager,
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
    ): CapturedTurnConfiguration {
        recoveryGate.awaitReady()
        check(runtime.durable.header.scope == access.scope) { "conversation_scope_mismatch" }
        check((runtime.durable.header.parentConversationId != null) == (child != null)) { "model_execution_lineage_mismatch" }
        var bindings: EnterpriseBindingLease? = null
        var admission: (suspend ((ModelRequestTarget) -> Unit) -> Unit)? = null
        val execution = ModelExecutionLease(releaseOwner = { bindings?.release() }) { accept ->
            requireNotNull(admission) { "model_execution_not_prepared" }(accept)
        }
        // Ownership precedes the first resource acquisition, including failed or cancelled preparation.
        runtime.bindModelExecution(turnId, worker, execution)
        if (access is RealmAccess.Enterprise) bindings = sessions.captureBindings(access)
        val originalBindings = bindings
        return withConfiguration(access) { snapshot ->
            if (originalBindings != null) {
                val current = sessions.state.value as EnterpriseState.Available
                check(current.manifest.applied == originalBindings.version) { "enterprise_configuration_changed_during_capture" }
            }
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
            val modelId = assistant.chatModelId ?: configuration.selections.chatModelId
                ?: error("chat_model_not_configured")
            requireAvailable(configuration, ConfigurationCategory.MODEL, modelId)
            requireFixedBinding(configuration, assistantId, modelId)
            val selected = configuration.models[modelId]?.model ?: error("chat_model_unavailable")
            check(selected.type == ModelType.CHAT) { "chat_model_capability_mismatch" }
            val model = selected.copy(
                customHeaders = selected.customHeaders.toList(), customBodies = selected.customBodies.toList(),
                inputModalities = selected.inputModalities.toList(), outputModalities = selected.outputModalities.toList(),
                abilities = selected.abilities.toList(), tools = selected.tools.toSet(), providerOverwrite = null,
            )
            val privateBinding = (modelId as? ConfigurationReference.Enterprise)?.let {
                requireNotNull(originalBindings) { "enterprise_binding_owner_missing" }.binding(it.id)
            }
            val initialTarget = if (privateBinding != null) enterpriseTarget(privateBinding, model)
                else ModelRequestTarget.Remote(selected.findProvider(snapshot.userSettings.providers)
                    ?: error("model_provider_unavailable"))
            val frozenShape = (initialTarget as? ModelRequestTarget.Remote)?.let { freezeProviderWireShape(it.provider, model) }
            val credentialOwner = if (privateBinding == null) {
                captureProviderCredentialOwner(snapshot.userSettings, selected, (initialTarget as ModelRequestTarget.Remote).provider)
            } else null
            val media = when (initialTarget) {
                ModelRequestTarget.LocalExample -> if (Modality.IMAGE in model.inputModalities) {
                    RequestMediaCapabilities(RequestImageSupport.STRUCTURED, RequestImageSupport.STRUCTURED, RequestImageSupport.STRUCTURED)
                } else RequestMediaCapabilities.NONE
                is ModelRequestTarget.Remote -> providers.getProviderByType(initialTarget.provider)
                    .requestMediaCapabilities(initialTarget.provider, model)
            }
            admission = { accept ->
                withConfiguration(access) { latest ->
                    currentCoroutineContext().ensureActive()
                    child?.let {
                        resolveActiveRunStopReason(latest.configuration, it.callerAssistantId, assistantId, it.runSpec)?.let { reason ->
                            runtime.requestCancel(turnId, reason)
                            throw CancellationException(reason)
                        }
                    }
                    requireAvailable(latest.configuration, ConfigurationCategory.ASSISTANT, assistantId)
                    requireAvailable(latest.configuration, ConfigurationCategory.MODEL, modelId)
                    requireFixedBinding(latest.configuration, assistantId, modelId)
                    val target = if (privateBinding != null) {
                        // The original lease checks revocation; replacement bindings belong to new Turns.
                        enterpriseTarget(requireNotNull(originalBindings).binding(modelId.id), model)
                    } else ModelRequestTarget.Remote(mergeProviderTransportCredentials(
                        requireNotNull(frozenShape),
                        resolveProviderTransportOwner(latest.userSettings, requireNotNull(credentialOwner)),
                    ))
                    accept(target)
                }
            }
            CapturedTurnConfiguration(snapshot.userSettings, assistant,
                TurnModelSnapshot(model, execution, snapshot.userRevision, originalBindings?.version), media)
        }
    }

    private suspend fun <T> withConfiguration(access: RealmAccess, operation: suspend (ExecutionConfigurationSnapshot) -> T): T =
        when (access) {
            RealmAccess.Personal -> settings.withExecutionConfiguration(access.scope, sessions.state.value, operation)
            is RealmAccess.Enterprise -> sessions.withAppliedConfiguration(access) { state ->
                check(state.manifest.phase == net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase.READY) {
                    "enterprise_session_not_ready"
                }
                settings.withExecutionConfiguration(access.scope, state, operation)
            }
        }

    private fun requireAvailable(configuration: ResolvedConfiguration, category: ConfigurationCategory, id: ConfigurationReference) {
        val access = configuration.access(category, id)
        check(access.canExecute) { "configuration_resource_unavailable:${category.name}:${access.unavailableReason}" }
    }

    private fun requireFixedBinding(configuration: ResolvedConfiguration, assistantId: ConfigurationReference, modelId: ConfigurationReference) {
        if (assistantId is ConfigurationReference.Enterprise) {
            check(configuration.assistants[assistantId]?.chatModelId == modelId) { "enterprise_assistant_binding_changed" }
        }
    }

    private fun enterpriseTarget(binding: EnterpriseRuntimeBinding, model: Model): ModelRequestTarget {
        val endpoint = binding.endpoint.orEmpty()
        val credential = RequestCredentials.fixed(binding.credential)
        val provider = when (binding.protocol) {
            EnterpriseRuntimeProtocol.EXAMPLE -> return ModelRequestTarget.LocalExample
            EnterpriseRuntimeProtocol.OPENAI_CHAT, EnterpriseRuntimeProtocol.OPENAI_RESPONSES -> ProviderSetting.OpenAI(
                id = model.id, name = model.displayName, models = listOf(model), baseUrl = endpoint, apiKey = "",
                useResponseApi = binding.protocol == EnterpriseRuntimeProtocol.OPENAI_RESPONSES,
            )
            EnterpriseRuntimeProtocol.GOOGLE_GENERATE -> ProviderSetting.Google(
                id = model.id, name = model.displayName, models = listOf(model), baseUrl = endpoint, apiKey = "",
            )
            EnterpriseRuntimeProtocol.CLAUDE_MESSAGES -> ProviderSetting.Claude(
                id = model.id, name = model.displayName, models = listOf(model), baseUrl = endpoint, apiKey = "",
            )
            else -> error("enterprise_model_protocol_mismatch")
        }
        return ModelRequestTarget.Remote(provider, binding.headers.map { CustomHeader(it.key, it.value) }, credential)
    }
}
