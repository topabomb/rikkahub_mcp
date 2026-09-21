package net.weero.measix.pilot.service.portal

import net.weero.measix.pilot.data.enterprise.PlatformPortalGrant
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PortalPageSourceTest {
    private val source = PortalPageSource(
        PlatformPortalGrant(
            exchangeUrl = "https://enterprise.example/portal/session/exchange",
            ticket = "ticket",
            expiresAt = "2030-01-01T00:00:00Z",
        ),
    )

    @Test
    fun permitsOnlyPortalReadModelsAndSessionClosure() {
        listOf(
            "/api/portal/v1/budgets",
            "/api/portal/v1/usage/summary",
            "/api/portal/v1/usage/trend",
            "/api/portal/v1/usage/distribution",
            "/api/portal/v1/usage/requests",
            "/api/portal/v1/usage/requests/req_12345678-1234-4123-8123-123456789abc",
            "/api/client/v1/enterprise/updates",
            "/api/client/v1/enterprise/updates/eup_12345678-1234-4123-8123-123456789abc",
        ).forEach { assertTrue(it, source.permitsSubresource("GET", it)) }
        assertTrue(source.permitsSubresource("GET", "/api/portal/v1/session"))
        assertTrue(source.permitsSubresource("DELETE", "/api/portal/v1/session"))

        assertFalse(source.permitsSubresource("POST", "/api/portal/v1/budgets"))
        assertFalse(source.permitsSubresource("GET", "/api/portal/v1/usage/requests/"))
        assertFalse(source.permitsSubresource("GET", "/api/portal/v1/usage/requests/req_123/nested"))
        assertFalse(source.permitsSubresource("GET", "/api/client/v1/enterprise/updates/eup_123"))
        assertFalse(source.permitsSubresource("GET", "/api/portal/v1/admin"))
        assertFalse(source.permitsSubresource("GET", "/api/client/v1/sessions"))
    }

    @Test
    fun usageDestinationSelectsOnlyTheFixedPortalView() {
        val usage = PortalPageSource(source.grant, PortalDestination.USAGE)
        assertTrue(usage.initialLocationScript.contains("/portal/?view=usage"))
        assertFalse(usage.initialLocationScript.contains(source.grant.exchangeUrl))
        assertTrue(PortalPageSource(source.grant).initialLocationScript.isEmpty())
    }

    @Test
    fun invalidExchangeUrlUsesAStableConfigurationReason() {
        listOf("https://enterprise.example/other", "not a URI", "https:/portal/session/exchange").forEach { url ->
            val error = assertThrows(EnterpriseConfigurationException::class.java) {
                PortalPageSource(source.grant.copy(exchangeUrl = url))
            }
            assertEquals("invalid_platform_portal_exchange_url", error.reason)
        }
    }
}
