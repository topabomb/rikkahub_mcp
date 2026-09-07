package net.weero.measix.pilot.service

import kotlinx.coroutines.flow.Flow
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
