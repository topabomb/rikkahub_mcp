package net.weero.measix.pilot.data.datastore

import me.rerere.search.SearchServiceOptions

/**
 * 配置写入的调用来源。它不是持久化字段，只为写入边界提供稳定的授权上下文。
 *
 * 企业配置尚未实现；保留 [ENTERPRISE_DELIVERY] 是为了让后续实现只能从受控入口写入企业覆盖，
 * 而不是依赖 UI 是否禁用。市场导入与备份恢复同样必须经过策略层。
 */
enum class SettingsWriteSource {
    LOCAL,
    BACKUP_RESTORE,
    MARKETPLACE_IMPORT,
    ENTERPRISE_DELIVERY,
}

/**
 * Settings 的唯一写入策略边界。
 *
 * 当前策略保持现有行为。企业来源与锁定元数据落地后，应在这里基于 current/proposed/source 拒绝或
 * 剥离未授权变更；UI 只负责展示锁定状态，不能成为安全边界。
 */
fun interface SettingsWritePolicy {
    fun apply(current: Settings, proposed: Settings, source: SettingsWriteSource): Settings

    companion object {
        val AllowAll = SettingsWritePolicy { _, proposed, _ -> proposed }
    }
}

internal fun prepareSettingsForWrite(
    current: Settings,
    proposed: Settings,
    source: SettingsWriteSource,
    policy: SettingsWritePolicy,
): Settings = policy.apply(current, proposed, source)
    .normalizeForPersistence()
    .canonicalizeForDataStore()

/**
 * 把过去散落在 Preferences key 赋值处的标量裁剪前移，确保落盘值、提交返回值与随后回读一致。
 * 不增加 key 或序列化字段。
 */
private fun Settings.canonicalizeForDataStore(): Settings {
    val persistedSearchServices = searchServices.ifEmpty { listOf(SearchServiceOptions.DEFAULT) }
    return copy(
        searchServices = persistedSearchServices,
        searchServiceSelected = searchServiceSelected.coerceIn(0, persistedSearchServices.lastIndex),
        defaultTTSPlaybackSpeed = defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f),
    )
}
