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
    fun `snapshot version rejects missing v5 opening and extra v4 opening without inventing defaults`() {
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
    fun `supported snapshot versions still reject unknown fields and malformed known fields`() {
        for (raw in listOf(fixture("v4-full"), withStarterOpeningMock(fixture("v4-full")))) {
            val original = Json.parseToJsonElement(raw).jsonObject
            for (invalid in listOf(
                JsonObject(original + ("futureField" to JsonPrimitive(true))),
                JsonObject(original + ("managedGeneration" to JsonPrimitive("1"))),
                JsonObject(original + ("assistants" to JsonNull)),
            )) {
                val failure = assertThrows(EnterpriseConfigurationException::class.java) {
                    PlatformWireCodec.decode<PlatformManagedSnapshot>(invalid.toString())
                }
                assertFalse(failure is EnterpriseSnapshotCompatibilityException)
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
