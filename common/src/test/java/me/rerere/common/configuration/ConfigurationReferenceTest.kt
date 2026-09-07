package me.rerere.common.configuration

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class ConfigurationReferenceTest {
    @Test
    fun `personal references retain the historical UUID scalar`() {
        val wire = "11111111-1111-1111-1111-111111111111"
        val reference: ConfigurationReference = ConfigurationReference.User(Uuid.parse(wire))
        assertEquals("\"$wire\"", Json.encodeToString(reference))
        assertEquals(reference, Json.decodeFromString<ConfigurationReference>("\"$wire\""))
    }

    @Test
    fun `enterprise identity retains source deployment and original resource id`() {
        val reference: ConfigurationReference = ConfigurationReference.Enterprise(
            EnterpriseAuthority("local:example", "dep_example"), "mdl_chat",
        )
        val wire = "managed~local~example~dep_example~mdl_chat"
        assertEquals(wire, reference.toString())
        assertEquals(reference, Json.decodeFromString<ConfigurationReference>(Json.encodeToString(reference)))
        assertNotEquals(reference, ConfigurationReference.parse(wire.replace("local", "platform")))
        assertNotEquals(reference, ConfigurationReference.parse(wire.replace("dep_example", "dep_other")))
    }

    @Test
    fun `invalid or ambiguous enterprise identities are rejected`() {
        listOf(
            "managed~local~example~dep~mdl_one~extra",
            "managed~local~example~dep~",
            "managed~unknown~example~dep~mdl_one",
            "managed~local~example~../dep~mdl_one",
            "managed~local~example~dep~mdl_one/another",
        ).forEach { wire ->
            assertThrows(IllegalArgumentException::class.java) { ConfigurationReference.parse(wire) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<ConfigurationReference.Enterprise>("\"11111111-1111-1111-1111-111111111111\"")
        }
    }
}
