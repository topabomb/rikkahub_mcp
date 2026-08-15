package net.weero.measix.pilot.data.datastore

/**
 * 统一配置提交顺序：策略校验与持久化归一化 → 落盘 → 发布读取模型。
 *
 * 把顺序约束提取为可测试的纯协调器，确保 DataStore 写入失败或协程取消时不会发布超前的内存状态。
 */
internal suspend fun commitSettings(
    current: Settings,
    proposed: Settings,
    source: SettingsWriteSource,
    policy: SettingsWritePolicy,
    persist: suspend (Settings) -> Unit,
    publish: (Settings) -> Unit,
): Settings {
    val prepared = prepareSettingsForWrite(
        current = current,
        proposed = proposed,
        source = source,
        policy = policy,
    )
    persist(prepared)
    val committed = prepared.materializeForRead()
    publish(committed)
    return committed
}
