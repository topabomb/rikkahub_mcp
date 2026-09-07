package net.weero.measix.pilot.data.enterprise

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class EnrollmentSharedCasesTest(
    private val name: String,
    private val raw: String,
    private val expected: String,
    private val now: Instant,
    private val installedSources: Set<String>,
) {
    @Test
    fun productionParserAndPreflightConsumeRawSharedMaterial() {
        val parser = EnrollmentMaterialParser()
        if (expected == "invalid") {
            assertThrows(name, EnterpriseConfigurationException::class.java) { parser.parse(raw) }
            return
        }
        val material = parser.parse(raw)
        if (expected == "expired") {
            val failure = assertThrows(EnterpriseConfigurationException::class.java) { requireEnrollmentNotExpired(material, now) }
            assertEquals("enterprise_enrollment_expired", failure.reason)
            return
        }
        requireEnrollmentNotExpired(material, now)
        if (expected == "unknown_source") {
            val failure = assertThrows(EnterpriseConfigurationException::class.java) {
                requireInstalledEnrollmentSource(material as EnrollmentMaterial.LocalExample, installedSources)
            }
            assertEquals("unknown_local_enterprise_source", failure.reason)
        } else {
            assertEquals("valid", expected)
            if (material is EnrollmentMaterial.LocalExample) requireInstalledEnrollmentSource(material, installedSources)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val raw = requireNotNull(EnrollmentSharedCasesTest::class.java.getResourceAsStream("/contracts/enrollment/cases.json"))
                .bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(raw).jsonObject
            val now = Instant.parse(root.getValue("now").jsonPrimitive.content)
            val sources = root.getValue("installedSources").jsonArray.map { it.jsonPrimitive.content }.toSet()
            return root.getValue("cases").jsonArray.map { item ->
                val fields = item.jsonObject
                arrayOf(fields.getValue("name").jsonPrimitive.content, fields.getValue("raw").jsonPrimitive.content,
                    fields.getValue("result").jsonPrimitive.content, now, sources)
            }
        }
    }
}
