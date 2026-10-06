package net.weero.measix.pilot.data.enterprise

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseMcpAppliedMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = EnterpriseConfigurationCodec.json
    private val candidate = exampleEnterprisePackage().toCandidate()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun historical(folder: File, snapshotVersion: Long?): EnterpriseManifest {
        val store = enterpriseTestStore(folder)
        val execution = (candidate.execution as EnterpriseExecution.Platform).copy(snapshotSchemaVersion = snapshotVersion)
        val version = store.prepare(candidate.copy(execution = execution))
        val sessionId = "ses_12345678-1234-4234-8234-123456789012"
        val credential = store.prepareCredential(PlatformRefreshCredential(sessionId, "refresh", 2000000000000L))
        val session = EnterpriseSession(sessionId, candidate.identity, 2000000000000L,
            PlatformSessionDetails(execution.connection, "dev_12345678-1234-4234-8234-123456789012", credential))
        val file = File(folder, "revisions/${version.revision}/configuration.json")
        val envelope = json.parseToJsonElement(file.readText()).jsonObject
        val config = envelope.getValue("configuration").jsonObject
        val legacy = JsonObject(config + mapOf(
            "mcpServers" to JsonArray(config.getValue("mcpServers").jsonArray.map { JsonObject(it.jsonObject - "toolAccessMode" - "allowedTools") }),
            "assistants" to JsonArray(config.getValue("assistants").jsonArray.map { value ->
                val assistant = value.jsonObject
                JsonObject((assistant - "mcpBindings") + ("mcpServerIds" to JsonArray(assistant.getValue("mcpBindings").jsonArray.map {
                    it.jsonObject.getValue("mcpServerId")
                })))
            }),
        ))
        val bytes = JsonObject(envelope + ("configuration" to legacy)).toString().toByteArray()
        file.writeBytes(bytes)
        return EnterpriseManifest(6, EnterpriseSessionPhase.READY, session,
            version.copy(configurationHash = hash(bytes)), candidate.identity.scope, candidate.identity,
            lastConfigurationSyncMillis = 1000L).also {
            File(folder, "manifest.json").writeText(json.encodeToString(EnterpriseManifest.serializer(), it))
        }
    }

    @Test fun `manifest six migrates once preserving release session credentials and original immutable files`() {
        listOf(4L, 5L, null).forEach { snapshotVersion ->
            val folder = temporary.newFolder()
            val original = historical(folder, snapshotVersion)
            val revision = File(folder, "revisions/${original.applied!!.revision}")
            val bytes = revision.listFiles()!!.associate { it.name to it.readBytes() }
            val store = enterpriseTestStore(folder)
            val loaded = store.load()
            assertEquals(7, loaded.manifest.schemaVersion)
            assertEquals(original.session, loaded.manifest.session)
            assertEquals(original.selectedScope, loaded.manifest.selectedScope)
            assertEquals(original.lastConfigurationSyncMillis, loaded.manifest.lastConfigurationSyncMillis)
            assertEquals(candidate.configuration, loaded.configuration)
            assertNotEquals(original.applied.revision, loaded.manifest.applied!!.revision)
            val execution = store.execution(loaded.manifest) as EnterpriseExecution.Platform
            assertEquals(snapshotVersion, execution.snapshotSchemaVersion)
            assertEquals((candidate.execution as EnterpriseExecution.Platform).snapshotHash, execution.snapshotHash)
            assertEquals(candidate.execution.releaseId, execution.releaseId)
            assertEquals("refresh", store.credential(loaded.manifest.session!!.platform!!.credential, original.session!!.id).refreshToken)
            bytes.forEach { (name, before) -> assertArrayEquals(before, File(revision, name).readBytes()) }
            val manifestBytes = File(folder, "manifest.json").readBytes()
            assertEquals(loaded, enterpriseTestStore(folder).load())
            assertArrayEquals(manifestBytes, File(folder, "manifest.json").readBytes())
        }
    }

    @Test fun `interrupted staging leaves old publication and allows deterministic migration retry`() {
        val folder = temporary.newFolder()
        val original = historical(folder, 5L)
        val bytes = File(folder, "manifest.json").readBytes()
        assertThrows(IOException::class.java) {
            enterpriseTestStore(folder, checkpoint = {
                if (it == EnterpriseStorageCheckpoint.EXECUTION_STAGED) throw IOException("staging interrupted")
            }).load()
        }
        assertArrayEquals(bytes, File(folder, "manifest.json").readBytes())
        val loaded = enterpriseTestStore(folder).load()
        assertEquals(original.session, loaded.manifest.session)
        assertEquals(candidate.configuration, loaded.configuration)
    }

    @Test fun `damaged legacy body retains original access identity and repair release facts with diagnostic`() {
        val folder = temporary.newFolder()
        val original = historical(folder, null)
        File(folder, "revisions/${original.applied!!.revision}/configuration.json").appendText("corrupt")
        val loaded = enterpriseTestStore(folder).load()
        assertEquals(original.session, loaded.manifest.session)
        assertEquals(original.applied, loaded.manifest.applied)
        assertNull(loaded.configuration)
        assertEquals("enterprise_revision_hash_mismatch", (loaded.configurationError as EnterpriseStorageException).reason)
        assertEquals((candidate.execution as EnterpriseExecution.Platform).snapshotHash,
            enterpriseTestStore(folder).repairExecution(loaded.manifest)!!.snapshotHash)
    }
}
