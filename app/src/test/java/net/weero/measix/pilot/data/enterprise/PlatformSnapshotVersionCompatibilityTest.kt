package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlatformSnapshotVersionCompatibilityTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/cases.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        .first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject.getValue("value").toString()

    @Test
    fun `pinned Core v4 and target v5 map through their declared contracts`() {
        val discovery = PlatformWireCodec.decode<PlatformDiscovery>(fixture("discovery"))
        val connection = PlatformConnection("https://platform.test", discovery)
        val bootstrap = PlatformWireCodec.decode<PlatformBootstrap>(fixture("bootstrap"))
        val identity = EnterpriseIdentity(connection.authority, bootstrap.deployment.name, bootstrap.user.userId, bootstrap.user.displayName)
        val v4 = PlatformWireCodec.decode<PlatformManagedSnapshot>(fixture("v4-full"))
        val v5 = PlatformWireCodec.decode<PlatformManagedSnapshot>(withStarterOpeningMock(fixture("v4-full")))
        val old = PlatformSnapshotMapper.map(connection, identity, v4)
        val target = PlatformSnapshotMapper.map(connection, identity, v5)
        assertEquals(4L, (old.execution as EnterpriseExecution.Platform).snapshotSchemaVersion)
        assertEquals(v4.snapshotHash, old.execution.snapshotHash)
        assertTrue(old.configuration.starters.isNotEmpty())
        assertTrue(old.configuration.starters.all { it.openingSnapshot == null })
        assertTrue(target.configuration.starters.all { it.openingSnapshot != null })
        assertEquals(old.configuration.starters, target.configuration.starters.map { it.copy(openingSnapshot = null) })
        assertEquals(old.configuration.assistants, target.configuration.assistants)
        assertEquals(old.configuration.models, target.configuration.models)
    }

    @Test
    fun `snapshot version rejects missing current bindings and opening without inventing defaults`() {
        val v4 = Json.parseToJsonElement(fixture("v4-full")).jsonObject
        val v5 = Json.parseToJsonElement(withStarterOpeningMock(v4.toString())).jsonObject
        listOf(
            JsonObject(v4 + ("schemaVersion" to JsonPrimitive(5))),
            JsonObject(v5 + ("schemaVersion" to JsonPrimitive(4))),
            JsonObject(v4 + ("schemaVersion" to JsonPrimitive(6))),
            JsonObject(v5 + ("starters" to JsonArray(v5.getValue("starters").jsonArray.map {
                JsonObject(it.jsonObject + ("openingSnapshot" to JsonNull))
            }))),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                PlatformWireCodec.decode<PlatformManagedSnapshot>(invalid.toString())
            }
        }
    }

    @Test
    fun `future and retired snapshot schemas have typed actionable diagnostics before payload decoding`() {
        for (version in listOf(3L, 6L, Long.MAX_VALUE)) {
            val failure = assertThrows(EnterpriseSnapshotCompatibilityException::class.java) {
                PlatformWireCodec.decode<PlatformManagedSnapshot>(
                    """{"futureField":{"instructions":"unknown"},"schemaVersion":$version}""",
                )
            }
            assertEquals(listOf(version), failure.receivedSchemas)
            assertEquals(setOf(4L, 5L), failure.supportedSchemas)
            assertEquals("enterprise_configuration_version_unsupported", failure.reason)
            assertTrue(failure.message.orEmpty().contains(version.toString()))
            assertTrue(failure.message.orEmpty().contains("[4, 5]"))
        }
    }

    @Test
    fun `malformed version and duplicate keys are invalid content rather than compatibility failures`() {
        val invalid = listOf(
            "[]", "null", "{}", """{"schemaVersion":null}""", """{"schemaVersion":"6"}""",
            """{"schemaVersion":-1}""", """{"schemaVersion":0}""", """{"schemaVersion":6.5}""",
            """{"schemaVersion":true}""", """{"schemaVersion":9223372036854775808}""",
            """{"schemaVersion":6,"schemaVersion":5}""",
            """{"schemaVersion":6,"future":{"key":1,"key":2}}""",
        )
        for (raw in invalid) {
            val failure = runCatching { PlatformWireCodec.decode<PlatformManagedSnapshot>(raw) }.exceptionOrNull()
            assertNotNull(raw, failure)
            assertFalse(raw, failure is EnterpriseSnapshotCompatibilityException)
        }
    }

    @Test
    fun `supported snapshots ignore response extensions but validate consumed fields with safe paths`() {
        fun extend(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { extend(it.value) } +
                ("futureField" to JsonObject(mapOf("enabled" to JsonPrimitive(true), "secret" to JsonPrimitive("private-marker")))))
            is JsonArray -> JsonArray(value.map(::extend))
            else -> value
        }
        for (raw in listOf(fixture("v4-full"), withStarterOpeningMock(fixture("v4-full")))) {
            val original = Json.parseToJsonElement(raw).jsonObject
            val expected = PlatformWireCodec.decode<PlatformManagedSnapshot>(raw)
            assertEquals(expected, PlatformWireCodec.decode<PlatformManagedSnapshot>(extend(original).toString()))
            val ignored = JsonObject(original + ("metadata" to JsonNull) +
                ("starters" to JsonArray(original.getValue("starters").jsonArray.map { starter ->
                    JsonObject(starter.jsonObject + ("description" to JsonPrimitive(123)))
                })))
            assertEquals(expected, PlatformWireCodec.decode<PlatformManagedSnapshot>(ignored.toString()))
            if (expected.schemaVersion == 4L) {
                val v5Fields = JsonObject(original +
                    ("mcp" to JsonArray(original.getValue("mcp").jsonArray.map {
                        JsonObject(it.jsonObject + ("toolAccessMode" to JsonNull) + ("allowedTools" to JsonPrimitive(false)))
                    })) + ("assistants" to JsonArray(original.getValue("assistants").jsonArray.map {
                        JsonObject(it.jsonObject + ("mcpBindings" to JsonNull))
                    })) + ("starters" to JsonArray(original.getValue("starters").jsonArray.map {
                        JsonObject(it.jsonObject + ("openingSnapshot" to JsonPrimitive(false)))
                    })))
                assertEquals(expected, PlatformWireCodec.decode<PlatformManagedSnapshot>(v5Fields.toString()))
            }
            for ((invalid, path) in listOf(
                JsonObject(original + ("managedGeneration" to JsonPrimitive("private-marker"))) to "$.managedGeneration",
                JsonObject(original + ("assistants" to JsonNull)) to "$.assistants",
                JsonObject(original - "assistants") to "$.assistants",
            )) {
                val failure = assertThrows(EnterpriseConfigurationException::class.java) {
                    PlatformWireCodec.decode<PlatformManagedSnapshot>(invalid.toString())
                }
                assertFalse(failure is EnterpriseSnapshotCompatibilityException)
                assertTrue(failure.message.orEmpty().contains(path))
                assertFalse(failure.message.orEmpty().contains("private-marker"))
            }
        }
    }

    @Test
    fun `snapshot capability intersection rejects unavailable configuration with version evidence`() {
        PlatformSnapshotCompatibility.requireAdvertisedSupport(listOf(4, 6))
        PlatformSnapshotCompatibility.requireAdvertisedSupport(listOf(5))
        for (advertised in listOf(emptyList(), listOf(3L), listOf(6L, 7L))) {
            val failure = assertThrows(EnterpriseSnapshotCompatibilityException::class.java) {
                PlatformSnapshotCompatibility.requireAdvertisedSupport(advertised)
            }
            assertEquals(advertised, failure.receivedSchemas)
        }
    }

    @Test
    fun `content boundary preserves invalid details and never converts compatibility or cancellation`() {
        val malformed = IllegalStateException("Duplicate object key")
        val invalid = assertThrows(EnterpriseSnapshotContentException::class.java) {
            validateSnapshotContent { throw malformed }
        }
        org.junit.Assert.assertSame(malformed, invalid.cause)
        assertEquals("enterprise_snapshot_invalid", invalid.reason)
        val unsupported = EnterpriseSnapshotCompatibilityException(listOf(6))
        org.junit.Assert.assertSame(unsupported, assertThrows(EnterpriseSnapshotCompatibilityException::class.java) {
            validateSnapshotContent { throw unsupported }
        })
        val cancelled = kotlinx.coroutines.CancellationException("cancel validation")
        org.junit.Assert.assertSame(cancelled, assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            validateSnapshotContent { throw cancelled }
        })
    }

}
