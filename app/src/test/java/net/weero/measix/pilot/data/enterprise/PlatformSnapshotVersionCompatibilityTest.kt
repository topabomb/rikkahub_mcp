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
}
