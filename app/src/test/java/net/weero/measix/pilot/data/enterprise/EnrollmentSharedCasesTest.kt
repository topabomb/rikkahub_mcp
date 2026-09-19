package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test

class EnrollmentSharedCasesTest {
    @Test
    fun `shared envelopes agree with the platform-only parser`() {
        val root = requireNotNull(javaClass.getResourceAsStream("/contracts/portal/cases.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        root.getValue("cases").jsonArray.forEach { item ->
            val fields = item.jsonObject
            val raw = fields.getValue("raw").jsonPrimitive.content
            val declared = fields.getValue("result").jsonPrimitive.content
            val platform = raw.contains("\"kind\":\"PLATFORM_ENROLLMENT\"")
            if (platform && declared != "invalid") {
                EnrollmentMaterialParser().parse(raw)
            } else {
                assertThrows(
                    fields.getValue("name").jsonPrimitive.content,
                    EnterpriseConfigurationException::class.java,
                ) { EnrollmentMaterialParser().parse(raw) }
            }
        }
    }
}
