package net.weero.measix.pilot.ui.pages.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.WebDavConfig
import net.weero.measix.pilot.data.sync.webdav.WebDavBackupItem
import net.weero.measix.pilot.data.sync.webdav.WebDavSync
import net.weero.measix.pilot.data.sync.S3BackupItem
import net.weero.measix.pilot.data.sync.S3Sync
import net.weero.measix.pilot.utils.UiState
import android.net.Uri
import java.io.File
import net.weero.measix.pilot.service.BackupRestoreApplicationService

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val restoreApplicationService: BackupRestoreApplicationService,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.updateAtomic(fn = transform)
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            try {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                webDavBackupItems.emit(UiState.Error(error))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        restoreApplicationService.restoreWebDav(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        val file = webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = WebDavConfig.BackupItem.entries)
        )
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalUri(uri: Uri) {
        restoreApplicationService.restoreLocal(
            uri,
            settings.value.webDavConfig.copy(items = WebDavConfig.BackupItem.entries),
        )
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            try {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                s3BackupItems.emit(UiState.Error(error))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        restoreApplicationService.restoreS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}
