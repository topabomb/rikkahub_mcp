package net.weero.measix.pilot.data.configuration

import me.rerere.common.configuration.EnterpriseAuthority
import me.rerere.common.configuration.ConfigurationReference
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConfigurationPolicyTest {
    private val authority = EnterpriseAuthority("local:example", "dep_example")
    private val scope = ConfigurationScope.Enterprise(authority, "usr_example")
    private val userReference = ConfigurationReference.User(
        Uuid.parse("00000000-0000-0000-0000-000000000123"),
    )
    private val closed = EnterprisePolicy(false, false, false, false, false)

    @Test
    fun `each switch admits existing user references without affecting personal management`() {
        val switches = listOf(
            ConfigurationCategory.PROVIDER to closed.copy(allowLocalProviders = true),
            ConfigurationCategory.MODEL to closed.copy(allowLocalProviders = true),
            ConfigurationCategory.TTS to closed.copy(allowLocalTts = true),
            ConfigurationCategory.ASR to closed.copy(allowLocalAsr = true),
            ConfigurationCategory.MCP to closed.copy(allowLocalMcp = true),
            ConfigurationCategory.ASSISTANT to closed.copy(allowLocalAssistants = true),
        )
        switches.forEach { (category, opened) ->
            val blocked = configurationAccess(scope, userReference, category, true, closed)
            assertEquals(ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED, blocked.unavailableReason)
            assertTrue(blocked.canEditDefinition)
            assertFalse(blocked.canExecute)
            assertTrue(configurationAccess(scope, userReference, category, true, opened).canExecute)
            assertTrue(configurationAccess(ConfigurationScope.Personal, userReference, category, true, closed).canExecute)
            switches.map { it.first }.forEach { other ->
                val sameProviderGroup = category in setOf(ConfigurationCategory.PROVIDER, ConfigurationCategory.MODEL) &&
                    other in setOf(ConfigurationCategory.PROVIDER, ConfigurationCategory.MODEL)
                assertEquals(
                    other == category || sameProviderGroup,
                    configurationAccess(scope, userReference, other, true, opened).canExecute,
                )
            }
        }
    }

    @Test
    fun `unlisted user capabilities remain admitted when all five switches are closed`() {
        listOf(
            ConfigurationCategory.SEARCH,
            ConfigurationCategory.SKILL,
            ConfigurationCategory.PROMPT_INJECTION,
            ConfigurationCategory.QUICK_MESSAGE,
            ConfigurationCategory.LOCAL_TOOL,
            ConfigurationCategory.WORKSPACE,
        ).forEach { category ->
            assertTrue(configurationAccess(scope, userReference, category, true, closed).canExecute)
        }
    }

    @Test
    fun `enterprise MCP is required and read only independently of local MCP policy`() {
        val reference = ConfigurationReference.Enterprise(authority, "mcp_example")
        val access = configurationAccess(scope, reference, ConfigurationCategory.MCP, true, closed)
        assertTrue(access.canSelect)
        assertTrue(access.requiredEnabled)
        assertFalse(access.canEditDefinition)
        val disabled = configurationAccess(scope, reference, ConfigurationCategory.MCP, false, closed)
        assertFalse(disabled.canExecute)
        assertFalse(disabled.requiredEnabled)
        assertFalse(configurationAccess(ConfigurationScope.Personal, reference, ConfigurationCategory.MCP, true, closed).canSelect)
    }

    @Test
    fun `mock and platform cannot share resource or data identity even with identical IDs`() {
        val platform = authority.copy(sourceNamespace = "platform:example")
        val managed = ConfigurationReference.Enterprise(platform, "mdl_example")
        assertNotEquals(scope, scope.copy(authority = platform))
        assertNotEquals(managed, managed.copy(authority = authority))
        assertEquals(
            ConfigurationUnavailableReason.DIFFERENT_ENTERPRISE,
            configurationAccess(scope, managed, ConfigurationCategory.MODEL, true, closed).unavailableReason,
        )
        assertNotEquals(scope, scope.copy(userId = "usr_another"))
    }

    @Test
    fun `gateway required ignores but does not overwrite saved disabled preference`() {
        val gateway = ConfigurationReference.Enterprise(authority, "tgw_example")
        val preference = GatewayPreference(gateway, false)
        assertTrue(effectiveGatewayEnabled(GatewayEnablementPolicy.REQUIRED, preference, gateway))
        assertFalse(effectiveGatewayEnabled(GatewayEnablementPolicy.USER_CONTROLLABLE_DEFAULT_ON, preference, gateway))
        assertTrue(effectiveGatewayEnabled(GatewayEnablementPolicy.USER_CONTROLLABLE_DEFAULT_ON, null, gateway))
        assertFalse(preference.enabled)
        assertThrows(IllegalArgumentException::class.java) {
            effectiveGatewayEnabled(GatewayEnablementPolicy.REQUIRED, preference, gateway.copy(id = "tgw_other"))
        }
    }

    @Test
    fun `missing or null policy is never treated as permission`() {
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<EnterprisePolicy>("""{"allowLocalProviders":true}""")
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<EnterprisePolicy>("""{"allowLocalProviders":null,"allowLocalTts":true,"allowLocalAsr":true,"allowLocalMcp":true,"allowLocalAssistants":true}""")
        }
        assertEquals(
            ConfigurationUnavailableReason.ENTERPRISE_CONFIGURATION_NOT_READY,
            configurationAccess(scope, userReference, ConfigurationCategory.MODEL, true, null).unavailableReason,
        )
    }

    @Test
    fun `references round trip without hashing or rewriting original identifiers`() {
        val values = listOf(userReference, ConfigurationReference.Enterprise(authority, "asd_original-value"))
        values.forEach { reference ->
            val encoded = Json.encodeToString<ConfigurationReference>(reference)
            assertEquals(reference, Json.decodeFromString<ConfigurationReference>(encoded))
        }
    }
}
