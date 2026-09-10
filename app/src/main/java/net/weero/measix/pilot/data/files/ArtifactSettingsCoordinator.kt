package net.weero.measix.pilot.data.files

import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument

/** Adapts the Settings writer protocol; owns neither a lock nor a configuration copy. */
class ArtifactSettingsCoordinator(private val settingsStore: SettingsStore) {
    internal suspend fun <T> readCommitted(block: suspend (UserSettingsDocument) -> T): T =
        block(settingsStore.snapshotUserDocument())

    internal suspend fun update(
        transform: (Settings) -> Settings,
        withCommit: suspend (UserSettingsDocument, UserSettingsDocument, suspend () -> Unit) -> Unit,
    ): Settings = settingsStore.updateLocalWithArtifactCommit(withCommit, transform)

    internal suspend fun changeAssistantPreference(
        scope: net.weero.measix.pilot.data.configuration.ConfigurationScope,
        state: net.weero.measix.pilot.data.enterprise.EnterpriseState,
        assistantId: me.rerere.common.configuration.ConfigurationReference,
        change: net.weero.measix.pilot.data.configuration.AssistantPreferenceChange,
        requireOwner: () -> Unit,
        withCommit: suspend (suspend () -> Unit) -> Unit,
        withArtifactCommit: suspend (UserSettingsDocument, UserSettingsDocument, suspend () -> Unit) -> Unit,
    ) = settingsStore.changeAssistantPreference(scope, state, assistantId, change, requireOwner, withArtifactCommit, withCommit)

    internal suspend fun manageAssistant(
        scope: net.weero.measix.pilot.data.configuration.ConfigurationScope,
        state: net.weero.measix.pilot.data.enterprise.EnterpriseState,
        callerId: me.rerere.common.configuration.ConfigurationReference?,
        change: net.weero.measix.pilot.data.datastore.AssistantManagementChange,
        requireOwner: () -> Unit,
        withArtifactCommit: suspend (UserSettingsDocument, UserSettingsDocument, suspend () -> Unit) -> Unit,
    ) = settingsStore.manageAssistant(scope, state, callerId, change, requireOwner, withArtifactCommit)

    internal suspend fun restore(
        settings: Settings,
        withRestore: suspend (Settings, suspend (Settings) -> Settings) -> Settings,
    ): Settings = settingsStore.restoreLocal(settings, withRestore)

    internal suspend fun <T> withDetach(
        operation: suspend (detach: suspend (Set<String>) -> Boolean) -> T,
    ): T = settingsStore.withArtifactRootDetach(operation)
}
