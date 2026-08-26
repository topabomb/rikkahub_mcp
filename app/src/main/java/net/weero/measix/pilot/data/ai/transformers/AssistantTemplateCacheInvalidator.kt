package net.weero.measix.pilot.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore

/**
 * 把 Pebble 缓存副作用从 SettingsStore 中移出，避免持久化层通过 Koin 反向查找模板引擎。
 */
class AssistantTemplateCacheInvalidator(
    appScope: AppScope,
    settingsStore: SettingsStore,
    private val engine: PebbleEngine,
) {
    init {
        appScope.launch {
            settingsStore.effectiveSettings.map { it.settings }
                .filterNot { it.init }
                .map(Settings::assistantTemplateFingerprint)
                .distinctUntilChanged()
                .collect { engine.templateCache.invalidateAll() }
        }
    }
}

internal fun Settings.assistantTemplateFingerprint() =
    assistants.associate { it.id to it.messageTemplate }
