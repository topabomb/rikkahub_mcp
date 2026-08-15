package net.weero.measix.pilot.ui.pages.setting

import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/** 把提供商编辑表单应用到最新记录，保留并发写入的模型列表和运行时内置元数据。 */
internal fun applyProviderEditorSave(
    latest: ProviderSetting,
    edited: ProviderSetting,
): ProviderSetting {
    if (latest.id != edited.id) return latest
    return edited.copyProvider(
        models = latest.models,
        builtIn = latest.builtIn,
        description = latest.description,
        shortDescription = latest.shortDescription,
    )
}

internal fun moveProviderModelsById(
    provider: ProviderSetting,
    fromModelId: Uuid,
    toModelId: Uuid,
): ProviderSetting {
    val fromIndex = provider.models.indexOfFirst { it.id == fromModelId }
    val toIndex = provider.models.indexOfFirst { it.id == toModelId }
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return provider
    return provider.moveMove(fromIndex, toIndex)
}
