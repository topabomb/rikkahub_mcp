package net.weero.measix.pilot.data.files

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore

/**
 * Settings artifact roots 的唯一串行化入口。Artifact GC/删除与引用字段替换共用同一锁，
 * 从而不会在 root 检查后、进入 DELETING 前插入新 Settings 引用。
 */
class ArtifactSettingsCoordinator(private val settingsStore: SettingsStore) {
    private val mutex = Mutex()

    suspend fun <T> withRootsLock(block: suspend (Settings) -> T): T = mutex.withLock {
        block(settingsStore.effectiveSettings.value.settings)
    }

    suspend fun update(transform: (Settings) -> Settings): Settings = mutex.withLock {
        settingsStore.updateLocal(transform = transform)
    }

    suspend fun updateChecked(
        transform: (Settings) -> Settings,
        validate: (before: Settings, after: Settings) -> Unit,
    ): Settings = mutex.withLock {
        settingsStore.updateLocal { before ->
            val after = transform(before)
            validate(before, after)
            after
        }
    }

    suspend fun detach(fileUris: Set<String>): Boolean = mutex.withLock {
        val committed = settingsStore.updateLocal { current ->
            ArtifactReferencePolicy.detach(current, fileUris)
        }
        ArtifactReferencePolicy.roots(committed).none(fileUris::contains)
    }
}
