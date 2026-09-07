package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.datastore.ResourceSelections
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController

internal class ConfigurationApplicationService(
    private val settings: SettingsStore,
    private val enterpriseSessions: EnterpriseSessionController,
    private val recoveryGate: ApplicationRecoveryGate,
) {
    suspend fun selectResource(
        scope: ConfigurationScope.Enterprise,
        slot: ResourceSelectionSlot,
        reference: ConfigurationReference?,
    ) = updateSelections(scope) { slot.replace(it, reference) }

    suspend fun setModelFavorite(scope: ConfigurationScope.Enterprise, reference: ConfigurationReference, favorite: Boolean) =
        updateSelections(scope) { current ->
            current.copy(favoriteModels = if (favorite) (current.favoriteModels + reference).distinct()
                else current.favoriteModels.filterNot { it == reference })
        }

    suspend fun setSuggestionEnabled(scope: ConfigurationScope.Enterprise, enabled: Boolean) =
        updateSelections(scope) { it.copy(enableSuggestion = enabled) }

    private suspend fun updateSelections(scope: ConfigurationScope.Enterprise, transform: (ResourceSelections) -> ResourceSelections) {
        recoveryGate.awaitReady()
        enterpriseSessions.withAppliedConfiguration(scope) { applied -> settings.updateResourceSelections(scope, applied, transform) }
    }

    suspend fun updateAssistantUsage(
        scope: ConfigurationScope.Enterprise,
        assistantId: ConfigurationReference,
        transform: (AssistantUsagePreferences?) -> AssistantUsagePreferences?,
    ) {
        recoveryGate.awaitReady()
        enterpriseSessions.withAppliedConfiguration(scope) { applied ->
            settings.updateAssistantUsage(scope, applied, assistantId, transform)
        }
    }
}
