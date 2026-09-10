package net.weero.measix.pilot.data.datastore

import me.rerere.search.SearchServiceOptions

class SettingsLockedException(
    val path: String,
    val reason: String,
) : IllegalStateException("Configuration change rejected at $path: $reason")

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
