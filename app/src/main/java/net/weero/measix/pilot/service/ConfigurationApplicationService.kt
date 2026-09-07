package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.datastore.ResourceSelections
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess

internal class ConfigurationApplicationService(
    private val settings: SettingsStore,
    private val enterpriseSessions: EnterpriseSessionController,
    private val recoveryGate: ApplicationRecoveryGate,
) {
    suspend fun selectResource(
        access: RealmAccess.Enterprise,
        slot: ResourceSelectionSlot,
        reference: ConfigurationReference?,
    ) = updateSelections(access) { slot.replace(it, reference) }

    suspend fun setModelFavorite(access: RealmAccess.Enterprise, reference: ConfigurationReference, favorite: Boolean) =
        updateSelections(access) { current ->
            current.copy(favoriteModels = if (favorite) (current.favoriteModels + reference).distinct()
                else current.favoriteModels.filterNot { it == reference })
        }

    suspend fun setSuggestionEnabled(access: RealmAccess.Enterprise, enabled: Boolean) =
        updateSelections(access) { it.copy(enableSuggestion = enabled) }

    private suspend fun updateSelections(access: RealmAccess.Enterprise, transform: (ResourceSelections) -> ResourceSelections) {
        recoveryGate.awaitReady()
        enterpriseSessions.withAppliedConfiguration(access) { applied -> settings.updateResourceSelections(access.scope, applied, transform) }
    }

    suspend fun setGatewayEnabled(access: RealmAccess.Enterprise, gateway: ConfigurationReference.Enterprise, enabled: Boolean) {
        recoveryGate.awaitReady()
        enterpriseSessions.withAppliedConfiguration(access) { applied ->
            settings.updateGatewayPreference(access.scope, applied, gateway, enabled)
        }
    }

    suspend fun updateAssistantUsage(
        access: RealmAccess.Enterprise,
        assistantId: ConfigurationReference,
        transform: (AssistantUsagePreferences?) -> AssistantUsagePreferences?,
    ) {
        recoveryGate.awaitReady()
        enterpriseSessions.withAppliedConfiguration(access) { applied ->
            settings.updateAssistantUsage(access.scope, applied, assistantId, transform)
        }
    }
}
