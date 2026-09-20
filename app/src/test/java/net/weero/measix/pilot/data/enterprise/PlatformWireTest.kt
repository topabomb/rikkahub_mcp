package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlatformWireTest {
    private fun cases() = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/cases.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }

    @Test
    fun `Core shared control and snapshot cases use the generated current schema`() {
        cases().forEach { value ->
            val case = value.jsonObject
            val raw = case.getValue("value").toString()
            val decode = {
                when (val schema = case.getValue("schema").jsonPrimitive.content) {
                    "ManagedSnapshot" -> PlatformWireCodec.decode<PlatformManagedSnapshot>(raw)
                    "Discovery" -> PlatformWireCodec.decode<PlatformDiscovery>(raw)
                    "EnrollmentExchangeRequest" -> PlatformWireCodec.decode<PlatformEnrollmentExchangeRequest>(raw)
                    "EnrollmentExchangeResponse" -> PlatformWireCodec.decode<PlatformEnrollmentExchangeResponse>(raw)
                    "Bootstrap" -> PlatformWireCodec.decode<PlatformBootstrap>(raw)
                    "ManagedState" -> PlatformWireCodec.decode<PlatformManagedState>(raw)
                    "ManagedAppliedReport" -> PlatformWireCodec.decode<PlatformManagedAppliedReport>(raw)
                    "RefreshResponse" -> PlatformWireCodec.decode<PlatformRefreshResponse>(raw)
                    else -> error("Uncovered shared schema: $schema")
                }
            }
            if (case.getValue("valid").jsonPrimitive.boolean) decode()
            else assertThrows(case.getValue("name").jsonPrimitive.content, IllegalArgumentException::class.java) { decode() }
        }
    }

    @Test
    fun `absent optional fields differ from explicit null and unknown enum`() {
        val snapshot = cases().first().jsonObject.getValue("value").jsonObject
        val model = snapshot.getValue("models").jsonArray.first().jsonObject
        val unknownModel = JsonObject(model + ("inputModalities" to JsonArray(listOf(JsonPrimitive("AUDIO")))))
        assertThrows(IllegalArgumentException::class.java) {
            PlatformWireCodec.decode<PlatformManagedSnapshot>(JsonObject(snapshot + ("models" to JsonArray(listOf(unknownModel)))).toString())
        }
        val policy = snapshot.getValue("policy").jsonObject
        assertThrows(EnterpriseConfigurationException::class.java) {
            PlatformWireCodec.decode<PlatformManagedSnapshot>(JsonObject(snapshot + ("policy" to JsonObject(policy + ("defaultModelId" to JsonNull)))).toString())
        }
        val withoutImages = JsonObject(snapshot - "imageGenerators")
        assertNull(PlatformWireCodec.decode<PlatformManagedSnapshot>(withoutImages.toString()).imageGenerators)
        assertThrows(EnterpriseConfigurationException::class.java) {
            PlatformWireCodec.decode<PlatformManagedSnapshot>(JsonObject(snapshot + ("imageGenerators" to JsonNull)).toString())
        }
    }

    @Test
    fun `credential objects do not print token values`() {
        val raw = cases().first { it.jsonObject.getValue("name").jsonPrimitive.content == "enrollment-response" }
            .jsonObject.getValue("value").toString()
        val credentials = PlatformWireCodec.decode<PlatformEnrollmentExchangeResponse>(raw)
        assertFalse(credentials.toString().contains(credentials.accessToken))
        assertFalse(credentials.toString().contains(credentials.refreshToken))
    }
}
