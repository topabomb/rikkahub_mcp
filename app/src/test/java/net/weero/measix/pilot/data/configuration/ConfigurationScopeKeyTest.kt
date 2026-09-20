package net.weero.measix.pilot.data.configuration

import me.rerere.common.configuration.EnterpriseAuthority
import org.junit.Assert.*
import org.junit.Test

class ConfigurationScopeKeyTest {
    @Test
    fun `keys preserve deployment and user and cannot collide with another principal`() {
        val scopes = listOf(
            ConfigurationScope.Personal,
            ConfigurationScope.Enterprise(EnterpriseAuthority("dep_example"), "user~one"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("dep_other"), "user~one"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("dep_example"), "用户🙂"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("dep_example"), " user~one "),
        )
        assertEquals(scopes.size, scopes.map { it.storageKey() }.toSet().size)
        scopes.forEach { assertEquals(it, configurationScopeFromStorageKey(it.storageKey())) }
        assertEquals("personal", ConfigurationScope.Personal.storageKey())
    }

    @Test
    fun `invalid and noncanonical keys fail without falling back to personal`() {
        listOf("", "PERSONAL", "enterprise", "enterprise~dep_example~",
            "enterprise~dep_example~dXNlcg==", "enterprise~dep_example~_w",
            "enterprise~platform:example~dep_example~dXNlcg", "enterprise~dep_example~dXNlcg~extra").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { configurationScopeFromStorageKey(key) }
        }
    }
}
