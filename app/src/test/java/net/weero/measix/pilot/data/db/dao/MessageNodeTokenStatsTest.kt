package net.weero.measix.pilot.data.db.dao

import androidx.sqlite.db.SupportSQLiteQuery
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNodeTokenStatsTest {
    @Test
    fun `token stats preserve legacy json keys and expose exactness counts`() = runTest {
        val dao = mockk<MessageNodeDAO>()
        val query = slot<SupportSQLiteQuery>()
        coEvery { dao.getTokenStatsRaw(capture(query)) } returns MessageTokenStats()

        dao.getTokenStats()

        val sql = query.captured.sql
        assertTrue(sql.contains("$.usage.promptTokens"))
        assertTrue(sql.contains("$.usage.completionTokens"))
        assertTrue(sql.contains("$.usage.cachedTokens"))
        assertTrue(sql.contains("$.usage.coreCompleteness"))
        assertTrue(sql.contains("AS coreNonExactMessages"))
        assertTrue(sql.contains("$.usage.cacheReadCompleteness"))
        assertTrue(sql.contains("AS cacheReadNonExactMessages"))
        assertTrue(sql.contains("'LEGACY'"))
    }

}
