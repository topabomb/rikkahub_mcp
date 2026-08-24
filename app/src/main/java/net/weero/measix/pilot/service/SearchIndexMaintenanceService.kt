package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.repository.ConversationRepository

/** Explicit application port for the destructive FTS rebuild operation. */
class SearchIndexMaintenanceService(
    private val repository: ConversationRepository,
    private val recoveryGate: ApplicationRecoveryGate,
) {
    suspend fun rebuild(onProgress: (current: Int, total: Int) -> Unit) {
        recoveryGate.awaitReady()
        repository.rebuildAllIndexes(onProgress)
    }
}
