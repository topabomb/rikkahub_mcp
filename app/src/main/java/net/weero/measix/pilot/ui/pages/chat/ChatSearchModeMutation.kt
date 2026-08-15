package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.provider.BuiltInTools
import net.weero.measix.pilot.data.datastore.Settings
import kotlin.uuid.Uuid

/** 在最新 Assistant / Model 上应用搜索模式，避免用页面快照整份覆盖模型其它字段。 */
internal fun applySearchMode(
    settings: Settings,
    assistantId: Uuid,
    modelId: Uuid?,
    enableWebSearch: Boolean,
    enableBuiltIn: Boolean,
): Settings = settings.copy(
    assistants = settings.assistants.map { assistant ->
        if (assistant.id == assistantId) {
            assistant.copy(enableWebSearch = enableWebSearch)
        } else {
            assistant
        }
    },
    providers = if (modelId == null) {
        settings.providers
    } else {
        settings.providers.map { provider ->
            val latestModel = provider.models.find { it.id == modelId } ?: return@map provider
            val updatedTools = if (enableBuiltIn) {
                latestModel.tools + BuiltInTools.Search
            } else {
                latestModel.tools - BuiltInTools.Search
            }
            if (updatedTools == latestModel.tools) {
                provider
            } else {
                provider.editModel(latestModel.copy(tools = updatedTools))
            }
        }
    },
)
