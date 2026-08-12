package net.weero.measix.pilot.service

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore

private const val TAG = "AssistantDataRecovery"

/** 进程启动后消费持久化清理 tombstone，并修复中断的子助手运行。 */
class AssistantDataRecovery(
    appScope: AppScope,
    settingsStore: SettingsStore,
    assistantManagementService: AssistantManagementService,
    subAssistantCoordinator: SubAssistantCoordinator,
) {
    init {
        appScope.launch {
            settingsStore.settingsFlow.first { !it.init }
            runCatching {
                subAssistantCoordinator.performRecovery()
            }.onFailure { error ->
                Log.e(TAG, "Unable to recover sub-assistant conversations", error)
            }
            runCatching {
                assistantManagementService.performPendingDeletionCleanup()
            }.onFailure { error ->
                Log.e(TAG, "Unable to recover pending assistant deletions", error)
            }
        }
    }
}
