package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlatformWireTest {
    private fun cases() = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/cases.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }

    @Test
    fun `published Core v4 snapshots retain their declared version and absent opening`() {
        cases().filter {
            it.jsonObject.getValue("schema").jsonPrimitive.content == "ManagedSnapshotV4" &&
                it.jsonObject.getValue("valid").jsonPrimitive.boolean
        }.forEach { case ->
            val decoded = PlatformWireCodec.decode<PlatformManagedSnapshot>(case.jsonObject.getValue("value").toString())
            assertEquals(4L, decoded.schemaVersion)
            assertTrue(decoded.starters.all { it.openingSnapshot == null })
        }
    }

    @Test
    fun `Core shared cases validate the wire schemas consumed by Android`() {
        var checked = 0
        cases().forEach { value ->
            val case = value.jsonObject
            val raw = case.getValue("value").toString()
            val decode: () -> Any = when (case.getValue("schema").jsonPrimitive.content) {
                "ManagedSnapshot", "ManagedSnapshotV4" -> { { PlatformWireCodec.decode<PlatformManagedSnapshot>(raw) } }
                "Discovery" -> { { PlatformWireCodec.decode<PlatformDiscovery>(raw) } }
                "EnrollmentExchangeRequest" -> { { PlatformWireCodec.decode<PlatformEnrollmentExchangeRequest>(raw) } }
                "EnrollmentExchangeResponse" -> { { PlatformWireCodec.decode<PlatformEnrollmentExchangeResponse>(raw) } }
                "Bootstrap" -> { { PlatformWireCodec.decode<PlatformBootstrap>(raw) } }
                "ManagedState" -> { { PlatformWireCodec.decode<PlatformManagedState>(raw) } }
                "ManagedAppliedReport" -> { { PlatformWireCodec.decode<PlatformManagedAppliedReport>(raw) } }
                "RefreshResponse" -> { { PlatformWireCodec.decode<PlatformRefreshResponse>(raw) } }
                else -> return@forEach
            }
            checked++
            if (case.getValue("valid").jsonPrimitive.boolean) decode()
            else assertThrows(case.getValue("name").jsonPrimitive.content, IllegalArgumentException::class.java) { decode() }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `absent optional fields differ from explicit null and unknown enum`() {
        val snapshot = Json.parseToJsonElement(withStarterOpeningMock(cases().first().jsonObject.getValue("value").toString())).jsonObject
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

    @Test
    fun `image generation wire accepts both declared protocols and rejects unknown values`() {
        val snapshot = Json.parseToJsonElement(withStarterOpeningMock(cases().first().jsonObject.getValue("value").toString())).jsonObject
        val image = snapshot.getValue("imageGenerators").jsonArray.single().jsonObject
        val dashScope = JsonObject(image + ("clientProtocol" to JsonPrimitive("DASHSCOPE_MULTIMODAL_GENERATION")))
        val decoded = PlatformWireCodec.decode<PlatformManagedSnapshot>(
            JsonObject(snapshot + ("imageGenerators" to JsonArray(listOf(dashScope)))).toString(),
        )
        assertEquals(
            PlatformImageGenerationDefinitionClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION,
            requireNotNull(decoded.imageGenerators).single().clientProtocol,
        )
        val unknown = JsonObject(image + ("clientProtocol" to JsonPrimitive("UNKNOWN_IMAGE_PROTOCOL")))
        assertThrows(IllegalArgumentException::class.java) {
            PlatformWireCodec.decode<PlatformManagedSnapshot>(
                JsonObject(snapshot + ("imageGenerators" to JsonArray(listOf(unknown)))).toString(),
            )
        }
    }
}
