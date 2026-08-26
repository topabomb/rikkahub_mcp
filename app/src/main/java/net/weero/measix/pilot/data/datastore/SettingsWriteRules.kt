package net.weero.measix.pilot.data.datastore

import me.rerere.search.SearchServiceOptions

class SettingsLockedException(
    val path: String,
    val reason: String,
) : IllegalStateException("Managed configuration locks $path: $reason")

/**
 * Pure Local-shadow write rules. Managed records may hide a Local value in the effective
 * projection, but a lock still protects that Local value from being changed.
 */
internal fun requireLocalSettingsWriteAllowed(
    currentLocal: Settings,
    currentEffective: EffectiveSettingsSnapshot,
    proposedLocal: Settings,
) {
    require(!currentLocal.init && !currentEffective.settings.init && !proposedLocal.init) {
        "Dummy Settings cannot be written"
    }
    val normalizedProposal = proposedLocal.normalizeForPersistence().canonicalizeForDataStore()
    val changed = changedPaths(currentLocal.materializeForRead(), normalizedProposal.materializeForRead())
    currentEffective.access.firstLocked(changed)?.let { (path, reason) ->
        throw SettingsLockedException(path, reason)
    }
}

/** Standard persisted selection invariants; there is no index fallback after migration. */
internal fun Settings.canonicalizeForDataStore(): Settings {
    val persistedSearchServices = searchServices.ifEmpty { listOf(SearchServiceOptions.DEFAULT) }
    return copy(
        searchServices = persistedSearchServices,
        selectedSearchServiceId = selectedSearchServiceId
            ?.takeIf { selected -> persistedSearchServices.any { it.id == selected } }
            ?: persistedSearchServices.first().id,
        defaultTTSPlaybackSpeed = defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f),
    )
}

private fun changedPaths(before: Settings, after: Settings): Set<String> = buildSet {
    val beforeRecords = before.recordValues()
    val afterRecords = after.recordValues()
    ManagedConfigurationRecordKind.entries.forEach { kind ->
        val previous = beforeRecords.getValue(kind)
        val proposed = afterRecords.getValue(kind)
        (previous.keys + proposed.keys).filter { id -> previous[id] != proposed[id] }.forEach { id ->
            add("records/${kind.settingsPath}/$id")
        }
        if (previous.keys.toList() != proposed.keys.toList()) add("records/${kind.settingsPath}")
    }
    listOf(
        "chatModelId" to (before.chatModelId != after.chatModelId),
        "fastModelId" to (before.fastModelId != after.fastModelId),
        "titleModelId" to (before.titleModelId != after.titleModelId),
        "imageGenerationModelId" to (before.imageGenerationModelId != after.imageGenerationModelId),
        "attachmentInspectionModelId" to (before.attachmentInspectionModelId != after.attachmentInspectionModelId),
        "compressModelId" to (before.compressModelId != after.compressModelId),
        "assistantId" to (before.assistantId != after.assistantId),
        "selectedSearchServiceId" to (before.selectedSearchServiceId != after.selectedSearchServiceId),
        "selectedTTSProviderId" to (before.selectedTTSProviderId != after.selectedTTSProviderId),
        "selectedASRProviderId" to (before.selectedASRProviderId != after.selectedASRProviderId),
    ).filter { (_, changed) -> changed }.forEach { (path, _) -> add("defaults/$path") }
}
