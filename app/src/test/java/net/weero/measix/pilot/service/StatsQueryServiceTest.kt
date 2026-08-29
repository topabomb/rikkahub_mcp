package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.MessageDayCount
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.MessageTokenStats
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsQueryServiceTest {
    @Test
    fun `load propagates exactness counts from token projection`() = runTest {
        val conversationDao = mockk<ConversationDAO>()
        val messageNodeDao = mockk<MessageNodeDAO>()
        val settingsStore = mockk<SettingsStore>()
        coEvery { conversationDao.countAll() } returns 3
        coEvery { messageNodeDao.getMessageCountPerDayRaw(any()) } returns listOf(
            MessageDayCount("2026-08-29", 4)
        )
        coEvery { messageNodeDao.getTokenStatsRaw(any()) } returns MessageTokenStats(
            totalMessages = 8,
            inputTokens = 1_000,
            outputTokens = 200,
            cacheReadInputTokens = 700,
            coreNonExactMessages = 2,
            cacheReadNonExactMessages = 5,
        )
        every { settingsStore.effectiveSettings } returns MutableStateFlow(
            Settings(launchCount = 9).toEffectiveSettingsSnapshot()
        )

        val result = StatsQueryService(conversationDao, messageNodeDao, settingsStore)
            .load(LocalDate.of(2026, 1, 1))

        assertEquals(3, result.totalConversations)
        assertEquals(8, result.totalMessages)
        assertEquals(1_000L, result.totalInputTokens)
        assertEquals(200L, result.totalOutputTokens)
        assertEquals(700L, result.totalCacheReadInputTokens)
        assertEquals(2, result.coreNonExactMessages)
        assertEquals(5, result.cacheReadNonExactMessages)
        assertEquals(4, result.conversationsPerDay[LocalDate.of(2026, 8, 29)])
        assertEquals(9, result.launchCount)
    }
}
