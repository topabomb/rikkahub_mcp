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
import net.weero.measix.pilot.data.enterprise.RealmAccess

internal class ConfigurationQueryService(
    private val settings: SettingsStore,
    private val enterpriseSessions: EnterpriseSessionController,
    private val recoveryGate: ApplicationRecoveryGate,
) {
    fun observePersonalModelFavorites() = settings.observeConfiguration(enterpriseSessions.state, ConfigurationScope.Personal)
        .map { it.selections.favoriteModels }

    fun observeModelCatalog(): Flow<ModelCatalogReadState> = flow {
        recoveryGate.awaitReady()
        emitAll(enterpriseSessions.observeSelectedRealmSelection().flatMapLatest { selection ->
            if (selection == null) flowOf(ModelCatalogReadState.Unavailable("configuration_view_unavailable"))
            else settings.observeConfiguration(enterpriseSessions.state, selection.access.scope)
                .map<ResolvedConfiguration, ModelCatalogReadState> { enterpriseSessions.withSelectedRealmSelection(selection) {
                    settings.withResolvedConfiguration(selection.access.scope, enterpriseSessions.state.value) { configuration ->
                        ModelCatalogReadState.Available(configuration.modelCatalog(selection))
                    }
                } }
                .catch { error ->
                    if (error is CancellationException) throw error
                    emit(ModelCatalogReadState.Unavailable(error.message ?: "configuration_view_unavailable"))
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

    internal suspend fun read(access: RealmAccess): ResolvedConfiguration {
        recoveryGate.awaitReady()
        return enterpriseSessions.withRealmAccess(access) {
            settings.withResolvedConfiguration(access.scope, enterpriseSessions.state.value) { it }
        }
    }

    fun observeCurrent(): Flow<ResolvedConfiguration> = settings.observeConfiguration(enterpriseSessions.state)

    fun observe(scope: ConfigurationScope): Flow<ResolvedConfiguration> =
        settings.observeConfiguration(enterpriseSessions.state, scope)
}
