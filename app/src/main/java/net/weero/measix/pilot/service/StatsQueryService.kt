package net.weero.measix.pilot.service

import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.getMessageCountPerDay
import net.weero.measix.pilot.data.db.dao.getTokenStats
import net.weero.measix.pilot.data.datastore.SettingsStore

data class StatsSnapshot(
    val totalConversations: Int,
    val totalMessages: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadInputTokens: Long,
    val coreNonExactMessages: Int,
    val cacheReadNonExactMessages: Int,
    val conversationsPerDay: Map<LocalDate, Int>,
    val launchCount: Int,
)

/** Query port that keeps Room and Settings aggregation out of the UI layer. */
class StatsQueryService(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
) {
    suspend fun load(startDate: LocalDate): StatsSnapshot = withContext(Dispatchers.IO) {
        val conversationsPerDay = messageNodeDAO
            .getMessageCountPerDay(startDate.toString())
            .mapNotNull { entry ->
                runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
            }
            .toMap()
        val tokenStats = messageNodeDAO.getTokenStats()
        StatsSnapshot(
            totalConversations = conversationDAO.countAll(),
            totalMessages = tokenStats.totalMessages,
            totalInputTokens = tokenStats.inputTokens,
            totalOutputTokens = tokenStats.outputTokens,
            totalCacheReadInputTokens = tokenStats.cacheReadInputTokens,
            coreNonExactMessages = tokenStats.coreNonExactMessages,
            cacheReadNonExactMessages = tokenStats.cacheReadNonExactMessages,
            conversationsPerDay = conversationsPerDay,
            launchCount = settingsStore.effectiveSettings.value.settings.launchCount,
        )
    }
}
