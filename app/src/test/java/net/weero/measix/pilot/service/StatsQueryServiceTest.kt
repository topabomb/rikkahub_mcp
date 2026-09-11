package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.MessageDayCount
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.MessageTokenStats
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.enrollFixture
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsQueryServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test
    fun `expiry during aggregation does not return enterprise counts`() = runTest {
        var now = 1000L
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())) { now }
        val ready = sessions.enrollFixture(exampleEnterprisePackage())
        val access = sessions.captureSelectedRealmAccess()
        val conversationDao = mockk<ConversationDAO>()
        val messageNodeDao = mockk<MessageNodeDAO>()
        val settings = mockk<SettingsStore>()
        coEvery { messageNodeDao.getMessageCountPerDayRaw(any()) } returns emptyList()
        coEvery { messageNodeDao.getTokenStatsRaw(any()) } returns MessageTokenStats(8, 1000, 200, 700, 2, 5)
        coEvery { conversationDao.countAll(access.scope) } coAnswers {
            now = ready.manifest.session!!.expiresAtMillis
            3
        }
        every { settings.userSettings } returns MutableStateFlow(Settings())
        val query = StatsQueryService(conversationDao, messageNodeDao, settings, sessions, ApplicationRecoveryGate().apply { ready() })
        try {
            query.load(access, LocalDate.of(2026, 1, 1))
            org.junit.Assert.fail("Expired statistics must not leave the query owner")
        } catch (error: EnterpriseConfigurationException) {
            assertEquals("enterprise_data_access_unavailable", error.reason)
        }
        assertEquals(ready, sessions.state.value)
    }

    @Test
    fun `load propagates exactness counts from token projection`() = runTest {
        val conversationDao = mockk<ConversationDAO>()
        val messageNodeDao = mockk<MessageNodeDAO>()
        val settingsStore = mockk<SettingsStore>()
        coEvery { conversationDao.countAll(ConfigurationScope.Personal) } returns 3
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
        every { settingsStore.userSettings } returns MutableStateFlow(
            Settings(launchCount = 9)
        )

        val result = StatsQueryService(conversationDao, messageNodeDao, settingsStore,
            EnterpriseSessionController(EnterpriseAppliedStore(temporary.newFolder())),
            ApplicationRecoveryGate().apply { ready() })
            .load(RealmAccess.Personal, LocalDate.of(2026, 1, 1))

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
