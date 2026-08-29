package net.weero.measix.pilot.service

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class McpOAuthCallbackKeepAliveTest {
    @Test
    fun `concurrent authorization leases share one service lifetime`() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        var starts = 0
        var stops = 0
        val keepAlive = McpOAuthCallbackKeepAlive(
            startService = { starts++ },
            stopService = { stops++ },
        )

        val first = keepAlive.acquire(context)
        val second = keepAlive.acquire(context)

        assertEquals(1, starts)
        assertEquals(0, stops)
        first.close()
        assertEquals(0, stops)
        first.close()
        assertEquals(0, stops)
        second.close()
        assertEquals(1, stops)
    }
}
