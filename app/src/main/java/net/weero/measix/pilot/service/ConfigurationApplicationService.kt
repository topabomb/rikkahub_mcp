package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.OptionalString
import net.weero.measix.pilot.service.runtime.UpdateHeader
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import kotlinx.coroutines.CancellationException
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
    private val conversations: ConversationCommandCoordinator,
    private val workspaces: WorkspaceQueryService,
) {
    suspend fun selectConversationSearch(target: ConversationAssistantTarget, reference: ConfigurationReference) {
        recoveryGate.awaitReady()
        val page = target.conversation
        enterpriseSessions.withSelectedRealmSelection(page.selection) {
            settings.updateResourceSelections(page.selection.access.scope, enterpriseSessions.state.value, page::requireOpen,
                withCommit = { commit ->
                    conversations.withRootHeaders(page.selection.access.scope, listOf(page.conversationId)) { headers ->
                        page.requireOpen()
                        check(headers.single().assistantId == target.assistantId) { "conversation_assistant_changed" }
                        commit()
                    }
                }) { ResourceSelectionSlot.SEARCH.replace(it, reference) }
        }
    }

    suspend fun changeAssistantPreference(target: ConversationAssistantTarget, change: AssistantPreferenceChange) {
        recoveryGate.awaitReady()
        val page = target.conversation
        enterpriseSessions.withSelectedRealmSelection(page.selection) {
            page.requireOpen()
            if (change is AssistantPreferenceChange.Workspace && change.id != null) {
                check(workspaces.getWorkspace(change.id.toString()) != null) { "workspace_not_found" }
            }
            settings.changeAssistantPreference(page.selection.access.scope, enterpriseSessions.state.value,
                target.assistantId, change, page::requireOpen) { commit ->
                conversations.withRootHeaders(page.selection.access.scope, listOf(page.conversationId)) { headers ->
                    page.requireOpen()
                    check(headers.single().assistantId == target.assistantId) { "conversation_assistant_changed" }
                    var cwdReset = false
                    if (change is AssistantPreferenceChange.Workspace && headers.single().workspaceCwd != null) {
                        conversations.executeOrThrow(page.conversationId, UpdateHeader(workspaceCwd = OptionalString.Set(null)))
                        cwdReset = true
                    }
                    try { commit() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (cwdReset) throw WorkspacePreferenceException(error)
                        throw error
                    }
                }
            }
        }
    }

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
            settings.updateResourceSelections(RealmAccess.Personal.scope, enterpriseSessions.state.value, transform = transform)
        } else enterpriseSessions.withSelectedRealmSelection(selection) {
            settings.updateResourceSelections(selection.access.scope, enterpriseSessions.state.value, transform = transform)
        }
    }

    suspend fun setGatewayEnabled(access: RealmAccess.Enterprise, gateway: ConfigurationReference.Enterprise, enabled: Boolean) {
        recoveryGate.awaitReady()
        enterpriseSessions.withAppliedConfiguration(access) { applied ->
            settings.updateGatewayPreference(access.scope, applied, gateway, enabled)
        }
    }


}

/** Directory reset is already durable; the previous workspace selection remains if its write failed. */
internal class WorkspacePreferenceException(cause: Throwable) :
    IllegalStateException("workspace_preference_failed_after_directory_reset", cause)

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
