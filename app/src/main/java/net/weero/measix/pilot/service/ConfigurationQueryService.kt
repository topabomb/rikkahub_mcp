package net.weero.measix.pilot.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationSelection
import net.weero.measix.pilot.data.configuration.ConfigurationCatalogItem
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.model.Assistant
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.RealmAccess

internal class ConfigurationQueryService(
    private val settings: SettingsStore,
    private val enterpriseSessions: EnterpriseSessionController,
    private val recoveryGate: ApplicationRecoveryGate,
) {
    fun observePersonalModelFavorites() = settings.observeConfiguration(enterpriseSessions.state, ConfigurationScope.Personal)
        .map { it.selections.favoriteModels }

    fun observeModelCatalog(): Flow<ModelCatalogReadState> = observeSelected(
        { ModelCatalogReadState.Unavailable(it) },
        { configuration, selection -> ModelCatalogReadState.Available(configuration.modelCatalog(selection)) },
    )

    fun observeAssistantCatalog(): Flow<AssistantCatalogUiModel?> = observeSelected(
        { null },
        { configuration, selection -> AssistantCatalogUiModel(selection,
            configuration.selection(ResourceSelectionSlot.ASSISTANT), configuration.assistants,
            configuration.catalog.values.filter { it.key.category == ConfigurationCategory.ASSISTANT }) },
    )

    fun observeSpeechCatalog(): Flow<SpeechCatalogUiModel?> = observeSelected(
        { null },
        { configuration, selection -> SpeechCatalogUiModel(selection,
            configuration.selection(ResourceSelectionSlot.TTS), configuration.selection(ResourceSelectionSlot.ASR),
            configuration.catalog.values.filter { it.key.category in setOf(ConfigurationCategory.TTS, ConfigurationCategory.ASR) },
            configuration.enterpriseConfiguration?.tts.orEmpty(), configuration.enterpriseConfiguration?.asr.orEmpty()) },
    )

    private fun <T> observeSelected(unavailable: (String) -> T, project: (ResolvedConfiguration, RealmSelection) -> T): Flow<T> = flow {
        recoveryGate.awaitReady()
        emitAll(enterpriseSessions.observeSelectedRealmSelection().flatMapLatest { selection ->
            if (selection == null) flowOf(unavailable("configuration_view_unavailable"))
            else settings.observeConfiguration(enterpriseSessions.state, selection.access.scope)
                .map { enterpriseSessions.withSelectedRealmSelection(selection) {
                    settings.withResolvedConfiguration(selection.access.scope, enterpriseSessions.state.value) { configuration ->
                        project(configuration, selection)
                    }
                } }
                .catch { error ->
                    if (error is CancellationException) throw error
                    emit(unavailable(error.message ?: "configuration_view_unavailable"))
                }
        })
    }

    suspend fun captureAccess(scope: ConfigurationScope): RealmAccess {
        recoveryGate.awaitReady()
        return enterpriseSessions.captureRealmAccess(scope)
    }

    suspend fun requireAccess(access: RealmAccess) {
        recoveryGate.awaitReady()
        enterpriseSessions.withRealmAccess(access) { }
    }

    suspend fun requireSelection(selection: RealmSelection) {
        recoveryGate.awaitReady()
        enterpriseSessions.withSelectedRealmSelection(selection) { }
    }

    internal suspend fun read(access: RealmAccess): ResolvedConfiguration = readExecution(access).configuration

    internal suspend fun readExecution(access: RealmAccess): net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot {
        recoveryGate.awaitReady()
        return enterpriseSessions.withRealmAccess(access) {
            settings.withExecutionConfiguration(access.scope, enterpriseSessions.state.value) { it }
        }
    }

    fun observeCurrent(): Flow<ResolvedConfiguration> = settings.observeConfiguration(enterpriseSessions.state)

    fun observe(scope: ConfigurationScope): Flow<ResolvedConfiguration> =
        settings.observeConfiguration(enterpriseSessions.state, scope)
}

internal data class AssistantCatalogUiModel(
    val selection: RealmSelection,
    val selected: ConfigurationSelection,
    val assistants: Map<ConfigurationReference, Assistant>,
    val resources: List<ConfigurationCatalogItem>,
)

internal data class SpeechCatalogUiModel(
    val selection: RealmSelection,
    val selectedTts: ConfigurationSelection,
    val selectedAsr: ConfigurationSelection,
    val resources: List<ConfigurationCatalogItem>,
    val managedTts: List<net.weero.measix.pilot.data.enterprise.EnterpriseTtsResource>,
    val managedAsr: List<net.weero.measix.pilot.data.enterprise.EnterpriseAsrResource>,
)
