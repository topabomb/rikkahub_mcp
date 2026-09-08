package net.weero.measix.pilot.service.portal

import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PortalProtocolTest {
    @Test
    fun `Android parser consumes all shared BridgeRequest cases with pinned contract bytes`() {
        val manifest = Json.parseToJsonElement(resource("manifest.json").decodeToString()).jsonObject
        assertEquals(PortalProtocol.VERSION, manifest.getValue("bridgeVersion").jsonPrimitive.int)
        assertEquals(PortalProtocol.LOCAL_READ_VERSION, manifest.getValue("localReadVersion").jsonPrimitive.int)
        assertEquals(setOf("portal-contract.openapi.json", "client-feed.schemas.json", "native-vectors.json",
            "local-context.json", "feed-vectors.json", "platform-v1.json", "local-v1.json", "cases.json"),
            manifest.getValue("artifacts").jsonObject.keys)
        manifest.getValue("artifacts").jsonObject.forEach { (name, entry) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(resource(name)).joinToString("") { "%02x".format(it) }
            assertEquals(name, entry.jsonObject.getValue("sha256").jsonPrimitive.content, actual)
        }
        val vectors = Json.parseToJsonElement(resource("native-vectors.json").decodeToString()).jsonArray
            .map { it.jsonObject }.filter { it.getValue("schema").jsonPrimitive.content == "BridgeRequest" }
        assertTrue(vectors.isNotEmpty())
        vectors.forEach { vector ->
            val raw = vector.getValue("value").toString()
            val name = vector.getValue("name").jsonPrimitive.content
            if (vector.getValue("valid").jsonPrimitive.boolean) {
                val request = PortalProtocol.request(PortalProtocol.decode(raw))
                assertEquals(name, vector.getValue("value").jsonObject.getValue("documentId").jsonPrimitive.content, request.documentId)
            } else assertThrows(name, PortalFailure::class.java) { PortalProtocol.request(PortalProtocol.decode(raw)) }
        }
    }

    @Test
    fun `raw duplicate decoded keys and malformed primitive types cannot be collapsed by JSON decoding`() {
        listOf(
            """{"bridgeVersion":3,"bridgeVersion":3,"documentId":"d","requestId":"r","method":"getStatus","params":{}}""",
            """{"bridgeVersion":3,"documentId":"d","requestId":"r","method":"recordAudio","params":{"maxDurationSeconds":1,"maxDurationSeconds":2}}""",
            """{"bridgeVersion":3,"documentId":"d","requestId":"r","method":"recordAudio","params":{"maxDurationSeconds":1,"maxDuration\u0053econds":2}}""",
            raw("getStatus", "[]"), raw("getStatus", "null"), raw("getStatus", "{\"x\":{}}"),
            raw("recordAudio", "{\"maxDurationSeconds\":\"10\"}"), raw("recordAudio", "{\"maxDurationSeconds\":1.5}"),
            raw("recordAudio", "{\"maxDurationSeconds\":1e999999999999999999999}"),
            raw("getStatus", "{}").replace("\"bridgeVersion\":3", "\"bridgeVersion\":\"3\""),
            raw("getStatus", "{}").replace("\"requestId\":\"r\"", "\"requestId\":null"),
            raw("getStatus", "{}").replace("\"requestId\":\"r\"", "\"requestId\":\"\\uD800\""),
            raw("getStatus", "{}") + "null",
        ).forEach { assertThrows(it, PortalFailure::class.java) { PortalProtocol.request(PortalProtocol.decode(it)) } }
        assertEquals(PortalCommand.RecordAudio(10), PortalProtocol.request(PortalProtocol.decode(raw("recordAudio", "{\"maxDurationSeconds\":1e1}"))).command)
    }

    @Test
    fun `method validation keeps opaque etags and rejects unsafe external URLs`() {
        val request = PortalProtocol.request(PortalProtocol.decode(raw("listLocalUpdates", """{"ifNoneMatch":" opaque-中文 ","limit":20}""")))
        assertEquals(" opaque-中文 ", (request.command as PortalCommand.ListLocalUpdates).ifNoneMatch)
        listOf("http://example.com", "https://user:secret@example.com", "https://example.com:0", "https://example.com:65536", "https://example.com:")
            .forEach { url -> assertThrows(PortalFailure::class.java) { PortalProtocol.request(PortalProtocol.decode(raw("openExternal", buildJsonObject { put("url", url) }.toString()))) } }
    }

    @Test
    fun `original UTF8 bound precedes parsing and request identifiers count Unicode characters`() {
        val valid = raw("getStatus", "{}")
        PortalProtocol.decode(" ".repeat(65536 - valid.length) + valid)
        assertThrows(PortalFailure::class.java) { PortalProtocol.decode(" ".repeat(65537 - valid.length) + valid) }
        val unicode = valid.replace("\"r\"", JsonPrimitive("😀".repeat(128)).toString())
        assertEquals(128, PortalProtocol.request(PortalProtocol.decode(unicode)).requestId.codePointCount(0, 256))
        assertThrows(PortalFailure::class.java) { PortalProtocol.decode("中".repeat(21846)) }
    }

    internal fun raw(method: String, params: String, document: String = "d", request: String = "r") =
        """{"bridgeVersion":3,"documentId":"$document","requestId":"$request","method":"$method","params":$params}"""

    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/contracts/portal/$name")).use { it.readBytes() }
}
