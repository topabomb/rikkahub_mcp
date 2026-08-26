package net.weero.measix.pilot.data.datastore

/**
 * 统一 Local Settings 提交顺序：归一化 → 落盘 → 发布 Local shadow。
 *
 * 把顺序约束提取为可测试的纯协调器，确保 DataStore 写入失败或协程取消时不会发布超前的内存状态。
 */
internal suspend fun commitSettings(
    proposed: Settings,
    persist: suspend (Settings) -> Unit,
    publish: suspend (Settings) -> Unit,
): Settings {
    val prepared = proposed.normalizeForPersistence().canonicalizeForDataStore()
    persist(prepared)
    publish(prepared)
    return prepared
}
