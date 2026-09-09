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
    @Test fun `private image models require the implemented image wire and preserve authentication ownership`() {
        val base = exampleEnterprisePackage()
        val model = base.configuration.models.first().copy(id = "mdl_image_test", type = me.rerere.ai.provider.ModelType.IMAGE)
        val binding = EnterpriseRuntimeBinding(model.id, EnterpriseRuntimeProtocol.OPENAI_IMAGES, "https://images.test/v1", "fixed")
        val packet = base.copy(configuration = base.configuration.copy(models = base.configuration.models + model),
            runtimeBindings = base.runtimeBindings + binding)
        assertEquals(packet, EnterprisePackageCodec.decode(EnterprisePackageCodec.encode(packet)))
        for (protocol in listOf(EnterpriseRuntimeProtocol.OPENAI_CHAT, EnterpriseRuntimeProtocol.CLAUDE_MESSAGES)) {
            assertThrows(EnterpriseConfigurationException::class.java) {
                EnterprisePackageCodec.validate(packet.copy(runtimeBindings = base.runtimeBindings + binding.copy(protocol = protocol)))
            }
        }
        assertThrows(EnterpriseConfigurationException::class.java) {
            EnterprisePackageCodec.validate(packet.copy(runtimeBindings = base.runtimeBindings + binding.copy(headers = mapOf("Authorization" to "other"))))
        }
    }

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
        assertEquals(1, example.configuration.gateways.size)
        LocalEnterpriseMcpSurface.validate(example)
        assertTrue(example.configuration.assistants.any { it.allowedSubAssistantIds.isNotEmpty() })
        assertTrue(example.configuration.memorySeeds.isNotEmpty())
        assertTrue(example.configuration.starters.isNotEmpty())
        assertTrue(example.runtimeBindings.all { it.protocol == EnterpriseRuntimeProtocol.EXAMPLE && it.credential == null })
    }

    @Test fun `gateway version and expected hash are required and multiple gateways are rejected`() {
        val packet = exampleEnterprisePackage()
        val gateway = packet.configuration.gateways.single()
        assertEquals("invalid_enterprise_gateway_id", assertThrows(EnterpriseConfigurationException::class.java) {
            EnterprisePackageCodec.validate(packet.copy(configuration = packet.configuration.copy(
                gateways = listOf(gateway.copy(id = "mcp_wrong")))))
        }.reason)
        assertEquals("invalid_enterprise_mcp_id", assertThrows(EnterpriseConfigurationException::class.java) {
            EnterprisePackageCodec.validate(packet.copy(configuration = packet.configuration.copy(
                mcpServers = packet.configuration.mcpServers.map { it.copy(id = "twg_wrong") })))
        }.reason)
        val invalid = listOf(gateway.copy(surfaceVersion = 2), gateway.copy(surfaceHash = ""),
            gateway.copy(surfaceHash = gateway.surfaceHash.uppercase()))
        invalid.forEach { value ->
            assertThrows(EnterpriseConfigurationException::class.java) {
                EnterprisePackageCodec.encode(packet.copy(configuration = packet.configuration.copy(gateways = listOf(value))))
            }
        }
        assertThrows(EnterpriseConfigurationException::class.java) {
            EnterprisePackageCodec.encode(packet.copy(configuration = packet.configuration.copy(
                gateways = listOf(gateway, gateway.copy(id = "twg_second")))),
            )
        }
        val root = EnterprisePackageCodec.json.parseToJsonElement(EnterprisePackageCodec.encode(packet).decodeToString()).jsonObject
        val config = root.getValue("configuration").jsonObject
        val original = config.getValue("gateways").jsonArray.single().jsonObject
        listOf("surfaceVersion", "surfaceHash").forEach { key ->
            val text = JsonObject(root + ("configuration" to JsonObject(config +
                ("gateways" to JsonArray(listOf(JsonObject(original - key))))))).toString()
            assertThrows(EnterpriseConfigurationException::class.java) { EnterprisePackageCodec.decode(text.encodeToByteArray()) }
        }
        assertThrows(EnterpriseConfigurationException::class.java) {
            LocalEnterpriseMcpSurface.validate(packet.copy(configuration = packet.configuration.copy(
                gateways = listOf(gateway.copy(surfaceHash = "sha256:" + "0".repeat(64))))))
        }
    }

    @Test fun `private managed MCP accepts only Streamable HTTP and rejects the unshipped SSE protocol`() {
        val packet = exampleEnterprisePackage()
        val managed = packet.runtimeBindings.first { it.resourceId == "mcp_example" }
        val http = managed.copy(protocol = EnterpriseRuntimeProtocol.MCP_STREAMABLE_HTTP, endpoint = "https://mcp.example/mcp")
        val valid = packet.copy(runtimeBindings = packet.runtimeBindings.map { if (it == managed) http else it })
        val encoded = EnterprisePackageCodec.encode(valid).decodeToString()
        assertEquals(valid, EnterprisePackageCodec.decode(encoded.encodeToByteArray()))
        assertThrows(EnterpriseConfigurationException::class.java) {
            EnterprisePackageCodec.decode(encoded.replace("MCP_STREAMABLE_HTTP", "MCP_SSE").encodeToByteArray())
        }
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
            first.copy(protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT, endpoint = "https://example.invalid", credential = secret, headers = mapOf("AUTHORIZATION" to secret)),
            first.copy(protocol = EnterpriseRuntimeProtocol.GOOGLE_GENERATE, endpoint = "https://example.invalid", credential = secret, headers = mapOf("X-Goog-Api-Key" to secret)),
            first.copy(protocol = EnterpriseRuntimeProtocol.CLAUDE_MESSAGES, endpoint = "https://example.invalid", credential = secret, headers = mapOf("X-Api-Key" to secret)),
            first.copy(protocol = EnterpriseRuntimeProtocol.OPENAI_CHAT, endpoint = "https://example.invalid", headers = mapOf("Host" to secret)),
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
