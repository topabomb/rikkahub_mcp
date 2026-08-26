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
    val totalPromptTokens: Long,
    val totalCompletionTokens: Long,
    val totalCachedTokens: Long,
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
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = settingsStore.effectiveSettings.value.settings.launchCount,
        )
    }
}
