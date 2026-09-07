package net.weero.measix.pilot.data.configuration

import me.rerere.common.configuration.EnterpriseAuthority
import org.junit.Assert.*
import org.junit.Test

class ConfigurationScopeKeyTest {
    @Test
    fun `keys preserve every principal component and cannot collide with personal or another source`() {
        val scopes = listOf(
            ConfigurationScope.Personal,
            ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "dep_example"), "user~one"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("platform:example", "dep_example"), "user~one"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "dep_other"), "user~one"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "dep_example"), "用户🙂"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "dep_example"), " user~one "),
        )
        assertEquals(scopes.size, scopes.map { it.storageKey() }.toSet().size)
        scopes.forEach { assertEquals(it, configurationScopeFromStorageKey(it.storageKey())) }
        assertEquals("personal", ConfigurationScope.Personal.storageKey())
    }

    @Test
    fun `invalid and noncanonical keys fail without falling back to personal`() {
        listOf("", "PERSONAL", "enterprise", "enterprise~local:example~dep_example~",
            "enterprise~local:example~dep_example~dXNlcg==", "enterprise~local:example~dep_example~_w",
            "enterprise~local:example~dep_example~dXNlcg~extra").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { configurationScopeFromStorageKey(key) }
        }
    }
}
