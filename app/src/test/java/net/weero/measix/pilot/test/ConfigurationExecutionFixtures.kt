package net.weero.measix.pilot.test

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.combine
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ModelExecutionService

internal fun testResolvedConfiguration(
    settings: Settings,
    scope: ConfigurationScope = ConfigurationScope.Personal,
    state: EnterpriseState = EnterpriseState.Loading,
): ResolvedConfiguration = ConfigurationResolver.resolve(UserSettingsDocument.empty().withPersonalSettings(settings), scope, state)

/** Reuse real resolution and admission while an existing test owns its in-memory Settings publisher. */
internal fun installExecutionConfigurationFixture(store: SettingsStore) {
    coEvery { store.withExecutionConfiguration<Any?>(any(), any(), any()) } coAnswers {
        val settings = store.userSettings.value
        thirdArg<suspend (ExecutionConfigurationSnapshot) -> Any?>()(ExecutionConfigurationSnapshot(
            settings, testResolvedConfiguration(settings, firstArg(), secondArg()), "fixture-${settings.hashCode()}",
        ))
    }
    coEvery { store.withResolvedConfiguration<Any?>(any(), any(), any()) } coAnswers {
        thirdArg<suspend (ResolvedConfiguration) -> Any?>()(
            testResolvedConfiguration(store.userSettings.value, firstArg(), secondArg()),
        )
    }
    every { store.observeConfiguration(any(), any()) } answers {
        val enterprise = firstArg<kotlinx.coroutines.flow.StateFlow<EnterpriseState>>()
        val requested = secondArg<ConfigurationScope?>()
        combine(store.userSettings, enterprise) { settings, state ->
            testResolvedConfiguration(settings,
                requested ?: (state as? EnterpriseState.Available)?.manifest?.selectedScope ?: ConfigurationScope.Personal, state)
        }
    }
}

internal fun testModelExecutionService(store: SettingsStore, sessions: EnterpriseSessionController, gate: ApplicationRecoveryGate): ModelExecutionService {
    val providers = mockk<ProviderManager>()
    val provider = mockk<Provider<ProviderSetting>>()
    every { providers.getProviderByType(any<ProviderSetting>()) } returns provider
    every { provider.requestMediaCapabilities(any(), any()) } answers {
        if (me.rerere.ai.provider.Modality.IMAGE in secondArg<me.rerere.ai.provider.Model>().inputModalities)
            RequestMediaCapabilities(userImages = me.rerere.ai.provider.RequestImageSupport.STRUCTURED)
        else RequestMediaCapabilities.NONE
    }
    return ModelExecutionService(store, sessions, gate, providers)
}
