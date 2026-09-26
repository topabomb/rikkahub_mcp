package net.weero.measix.pilot.data.enterprise

import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlatformContractSourceTest {
    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }

    @Test
    fun `Core export manifest matches the consumed schema bytes`() {
        val manifest = Json.parseToJsonElement(resource("/contracts/platform/manifest.json").toString(Charsets.UTF_8)).jsonObject
        val source = resource("/contracts/platform/client-control.openapi.yaml").toString(Charsets.UTF_8)
            .replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
        assertEquals("sha256:$hash", manifest.getValue("sourceHash").jsonPrimitive.content)
        assertTrue(manifest.getValue("generated").jsonPrimitive.boolean)
        assertEquals("openapi-3.0.3-client-schema-only", manifest.getValue("format").jsonPrimitive.content)
    }
}
