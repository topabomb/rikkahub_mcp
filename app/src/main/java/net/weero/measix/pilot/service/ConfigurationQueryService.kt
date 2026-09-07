package net.weero.measix.pilot.service

import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController

internal class ConfigurationQueryService(
    private val settings: SettingsStore,
    private val enterpriseSessions: EnterpriseSessionController,
) {
    fun observeCurrent(): Flow<ResolvedConfiguration> = settings.observeConfiguration(enterpriseSessions.state)

    fun observe(scope: ConfigurationScope): Flow<ResolvedConfiguration> =
        settings.observeConfiguration(enterpriseSessions.state, scope)
}
