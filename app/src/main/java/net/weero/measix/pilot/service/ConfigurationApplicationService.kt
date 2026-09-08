package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.datastore.ResourceSelections
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection

internal class ConfigurationApplicationService(
    private val settings: SettingsStore,
    private val enterpriseSessions: EnterpriseSessionController,
    private val recoveryGate: ApplicationRecoveryGate,
) {
    suspend fun selectResource(selection: RealmSelection, slot: ResourceSelectionSlot, reference: ConfigurationReference?) =
        updateSelectedPreferences(selection) { slot.replace(it, reference) }

    suspend fun setSuggestionEnabled(selection: RealmSelection, enabled: Boolean) =
        updateSelectedPreferences(selection) { it.copy(enableSuggestion = enabled) }

    suspend fun setModelFavorite(selection: RealmSelection?, reference: ConfigurationReference, favorite: Boolean) =
        updateSelectedPreferences(selection) { current ->
            current.copy(favoriteModels = if (favorite) (current.favoriteModels + reference).distinct()
                else current.favoriteModels.filterNot { it == reference })
        }

    suspend fun moveModelFavorite(selection: RealmSelection?, from: ConfigurationReference, to: ConfigurationReference) =
        updateSelectedPreferences(selection) { it.copy(favoriteModels = moveFavoriteModel(it.favoriteModels, from, to)) }

    /** Null addresses the personal favorites used while editing shared user definitions. */
    private suspend fun updateSelectedPreferences(selection: RealmSelection?, transform: (ResourceSelections) -> ResourceSelections) {
        recoveryGate.awaitReady()
        if (selection == null) {
            settings.updateResourceSelections(RealmAccess.Personal.scope, enterpriseSessions.state.value, transform)
        } else enterpriseSessions.withSelectedRealmSelection(selection) {
            settings.updateResourceSelections(selection.access.scope, enterpriseSessions.state.value, transform)
        }
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

internal fun moveFavoriteModel(
    current: List<ConfigurationReference>,
    fromModelId: ConfigurationReference,
    toModelId: ConfigurationReference,
): List<ConfigurationReference> {
    val fromIndex = current.indexOf(fromModelId)
    val toIndex = current.indexOf(toModelId)
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return current
    return current.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
