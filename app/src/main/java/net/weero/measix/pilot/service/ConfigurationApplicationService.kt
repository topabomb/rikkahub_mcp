package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.files.requireDiscarded
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
    private val artifacts: net.weero.measix.pilot.data.files.ArtifactStore,
) {
    suspend fun selectConversationSearch(target: ConversationAssistantTarget, reference: ConfigurationReference) {
        recoveryGate.awaitReady()
        val page = target.conversation
        enterpriseSessions.withSelectedRealmSelection(page.selection) {
            settings.updateResourceSelections(page.selection.access.scope, enterpriseSessions.state.value, { page.requireOpen(); enterpriseSessions.requirePublishedSelection(page.selection) },
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
            val workspaceChanged = change is AssistantPreferenceChange.Workspace || change == AssistantPreferenceChange.ResetUsage ||
                change is AssistantPreferenceChange.EditUsage && change.baseline.workspaceId != change.edited.workspaceId
            val newWorkspace = when (change) {
                is AssistantPreferenceChange.Workspace -> change.id
                is AssistantPreferenceChange.EditUsage -> change.edited.workspaceId.takeIf { workspaceChanged }
                else -> null
            }
            if (newWorkspace != null) check(workspaces.getWorkspace(newWorkspace.toString()) != null) { "workspace_not_found" }
            artifacts.updateAssistantPreferenceReferences(page.selection.access.scope, enterpriseSessions.state.value,
                target.assistantId, change, { page.requireOpen(); enterpriseSessions.requirePublishedSelection(page.selection) }) { commit ->
                conversations.withRootHeaders(page.selection.access.scope, listOf(page.conversationId)) { headers ->
                    page.requireOpen()
                    check(headers.single().assistantId == target.assistantId) { "conversation_assistant_changed" }
                    var cwdReset = false
                    if (workspaceChanged && headers.single().workspaceCwd != null) {
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

    suspend fun importAssistantImage(target: ConversationAssistantTarget, uri: android.net.Uri, avatar: Boolean) {
        recoveryGate.awaitReady()
        val page = target.conversation
        enterpriseSessions.withSelectedRealmSelection(page.selection) { page.requireOpen() }
        val owned = artifacts.createConfigurationImage(page.selection.access.scope, uri)
        try {
            changeAssistantPreference(target, if (avatar) AssistantPreferenceChange.Avatar(
                net.weero.measix.pilot.data.model.Avatar.Image(owned.uri.toString())) else AssistantPreferenceChange.Background(owned.uri.toString()))
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { artifacts.publishUnpublished(owned) }
        } catch (error: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try { artifacts.discardUnpublished(owned).requireDiscarded("assistant usage image", allowAlreadyPublished = true) }
                catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            throw error
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
            settings.updateResourceSelections(RealmAccess.Personal.scope, enterpriseSessions.state.value, requireOwner = {}, transform = transform)
        } else enterpriseSessions.withSelectedRealmSelection(selection) {
            settings.updateResourceSelections(selection.access.scope, enterpriseSessions.state.value,
                requireOwner = { enterpriseSessions.requirePublishedSelection(selection) }, transform = transform)
        }
    }

    suspend fun setGatewayEnabled(selection: RealmSelection, gateway: ConfigurationReference.Enterprise, enabled: Boolean) {
        recoveryGate.awaitReady()
        val access = requireNotNull(selection.access as? RealmAccess.Enterprise)
        enterpriseSessions.withSelectedRealmSelection(selection) {
            val applied = enterpriseSessions.state.value as net.weero.measix.pilot.data.enterprise.EnterpriseState.Available
            settings.updateGatewayPreference(access.scope, applied, gateway, enabled) { enterpriseSessions.requirePublishedSelection(selection) }
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
