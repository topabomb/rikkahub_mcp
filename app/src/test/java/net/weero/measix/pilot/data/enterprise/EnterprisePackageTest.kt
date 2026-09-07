package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test

internal fun exampleEnterprisePackage(): EnterprisePackage =
    requireNotNull(EnterprisePackageTest::class.java.getResourceAsStream("/enterprise.local.example.json"))
        .use(EnterprisePackageCodec::decode)

internal suspend fun EnterpriseSessionController.enrollFixture(packet: EnterprisePackage): EnterpriseState.Available =
    enrollLocal(packet.identity, redeem = { packet.identity }, configuration = { packet })

class EnterprisePackageTest {
    @Test
    fun `published gateway rejects a second enabled bit and requires an explicit known policy`() {
        val root = EnterprisePackageCodec.json.parseToJsonElement(
            EnterprisePackageCodec.encode(exampleEnterprisePackage()).decodeToString()).jsonObject
        val config = root.getValue("configuration").jsonObject
        val gateways = config.getValue("gateways").jsonArray
        val gateway = gateways.first().jsonObject
        val invalid = listOf(JsonObject(gateway + ("enabled" to JsonPrimitive(false))),
            JsonObject(gateway - "enablement"), JsonObject(gateway + ("enablement" to JsonPrimitive("UNKNOWN"))))
        invalid.forEach { item ->
            val candidate = JsonObject(root + ("configuration" to JsonObject(config +
                ("gateways" to JsonArray(listOf(item) + gateways.drop(1))))))
            assertThrows(EnterpriseConfigurationException::class.java) { EnterprisePackageCodec.decode(candidate.toString().encodeToByteArray()) }
        }
    }

    @Test
    fun `bundled example is a complete credential-free graph`() {
        val example = exampleEnterprisePackage()
        assertEquals(example, EnterprisePackageCodec.decode(EnterprisePackageCodec.encode(example)))
        assertTrue(example.configuration.models.any { it.id == "mdl_chat" })
        assertTrue(example.configuration.models.any { it.id == "mdl_image" })
        assertTrue(example.configuration.tts.isNotEmpty())
        assertTrue(example.configuration.asr.isNotEmpty())
        assertTrue(example.configuration.mcpServers.isNotEmpty())
        assertTrue(example.configuration.gateways.isNotEmpty())
        assertTrue(example.configuration.assistants.any { it.allowedSubAssistantIds.isNotEmpty() })
        assertTrue(example.configuration.memorySeeds.isNotEmpty())
        assertTrue(example.configuration.starters.isNotEmpty())
        assertTrue(example.runtimeBindings.all { it.protocol == EnterpriseRuntimeProtocol.EXAMPLE && it.credential == null })
    }

    @Test
    fun `each of the five policy flags is required`() {
        val root = EnterprisePackageCodec.json.parseToJsonElement(
            EnterprisePackageCodec.encode(exampleEnterprisePackage()).decodeToString(),
        ).jsonObject
        val config = root.getValue("configuration").jsonObject
        val policy = config.getValue("policy").jsonObject
        assertEquals(5, policy.size)
        policy.keys.forEach { key ->
            val invalidConfig = JsonObject(config + ("policy" to JsonObject(policy - key)))
            val bytes = JsonObject(root + ("configuration" to invalidConfig)).toString().encodeToByteArray()
            assertThrows(EnterpriseConfigurationException::class.java) { EnterprisePackageCodec.decode(bytes) }
        }
    }

    @Test
    fun `incomplete or inconsistent candidate cannot be partially accepted`() {
        val good = exampleEnterprisePackage()
        val config = good.configuration
        val candidates = listOf(
            good.copy(formatVersion = 1),
            good.copy(runtimeBindings = good.runtimeBindings.dropLast(1)),
            good.copy(runtimeBindings = good.runtimeBindings + good.runtimeBindings.first()),
            good.copy(configuration = config.copy(models = config.models + config.models.first())),
            good.copy(configuration = config.copy(models = config.models.filterNot { it.id == "mdl_chat" })),
            good.copy(configuration = config.copy(mcpServers = config.mcpServers.map { it.copy(enabled = false) })),
            good.copy(configuration = config.copy(memorySeeds = emptyList())),
            good.copy(configuration = config.copy(assistants = config.assistants.map { it.copy(allowAsSubAssistant = false) })),
        )
        candidates.forEach { value -> assertThrows(EnterpriseConfigurationException::class.java) { EnterprisePackageCodec.validate(value) } }
    }

    @Test
    fun `bindings validate protocols and prevent injected credentials from appearing in errors`() {
        val example = exampleEnterprisePackage()
        val secret = "private-test-value"
        val first = example.runtimeBindings.first()
        val invalid = listOf(
            first.copy(credential = secret),
            first.copy(protocol = EnterpriseRuntimeProtocol.OPENAI_TTS, endpoint = "https://example.invalid", credential = secret),
            first.copy(protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT, endpoint = "https://$secret@example.invalid"),
            first.copy(protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT, endpoint = "https://example.invalid", credential = "$secret\r\nInjected: x"),
            first.copy(protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT, endpoint = "https://example.invalid", headers = mapOf("Authorization" to secret, "authorization" to secret)),
        )
        invalid.forEach { binding ->
            val candidate = example.copy(runtimeBindings = listOf(binding) + example.runtimeBindings.drop(1))
            val error = assertThrows(EnterpriseConfigurationException::class.java) { EnterprisePackageCodec.validate(candidate) }
            assertFalse(error.toString().contains(secret))
            assertNull(error.cause)
            assertFalse(binding.toString().contains(secret))
            assertFalse(candidate.toString().contains(secret))
        }
        val error = assertThrows(EnterpriseConfigurationException::class.java) {
            EnterprisePackageCodec.decode("{\"credential\":\"$secret\"".encodeToByteArray())
        }
        assertFalse(error.toString().contains(secret))
        assertNull(error.cause)
    }

    @Test
    fun `malformed encoding and oversized input fail before decoding`() {
        val encoding = assertThrows(EnterpriseConfigurationException::class.java) {
            EnterprisePackageCodec.decode(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertEquals("invalid_enterprise_package_encoding", encoding.reason)
        val size = assertThrows(EnterpriseConfigurationException::class.java) {
            EnterprisePackageCodec.decode(ByteArray(EnterprisePackageCodec.MAX_BYTES + 1).inputStream())
        }
        assertEquals("enterprise_package_too_large", size.reason)
    }
}
