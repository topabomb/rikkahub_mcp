package net.weero.measix.pilot.data.enterprise

import java.io.File
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
class EnterpriseStarterAppliedStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = EnterpriseConfigurationCodec.json

    private fun publish(candidate: EnterpriseCandidate, folder: File): EnterpriseManifest {
        val store = enterpriseTestStore(folder)
        val version = store.prepare(candidate)
        val sessionId = "ses_12345678-1234-4234-8234-123456789012"
        val credential = store.prepareCredential(PlatformRefreshCredential(sessionId, "refresh", 2000000000000L))
        val execution = candidate.execution as EnterpriseExecution.Platform
        val session = EnterpriseSession(sessionId, candidate.identity, 2000000000000L,
            PlatformSessionDetails(execution.connection, "dev_12345678-1234-4234-8234-123456789012", credential))
        return EnterpriseManifest(6, EnterpriseSessionPhase.READY, session, version,
            candidate.identity.scope, candidate.identity).also(store::commit)
    }

    @Test
    fun `new opening survives publication and recreation with literal order and full source fields`() {
        val candidate = exampleEnterprisePackage().toCandidate()
        val folder = temporary.newFolder()
        val manifest = publish(candidate, folder)
        val files = folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to it.readBytes() }
        val loaded = enterpriseTestStore(folder).load()
        assertEquals(candidate.configuration, loaded.configuration)
        assertEquals(manifest, loaded.manifest)
        assertEquals(5L, (enterpriseTestStore(folder).execution(manifest) as EnterpriseExecution.Platform).snapshotSchemaVersion)
        val starter = requireNotNull(loaded.configuration).starters.first()
        val opening = requireNotNull(starter.openingSnapshot)
        assertEquals(listOf("second", "first"), opening.initialContexts.map { it.id })
        assertEquals("  企业任务 {{user}}\n保持原文。  ", opening.systemPrompt)
        assertEquals(candidate.configuration.starters.first(), starter)
        files.forEach { (path, bytes) -> assertArrayEquals(path, bytes, File(folder, path).readBytes()) }
    }

    @Test
    fun `verified v4 publication keeps absent opening and original schema on recreation`() {
        val source = exampleEnterprisePackage().toCandidate()
        val execution = source.execution as EnterpriseExecution.Platform
        val candidate = source.copy(
            configuration = source.configuration.copy(starters = source.configuration.starters.map { it.copy(openingSnapshot = null) }),
            execution = execution.copy(
                connection = execution.connection.copy(discovery = execution.connection.discovery.copy(
                    supportedSnapshotSchemaVersions = listOf(4L))),
                snapshotSchemaVersion = 4L,
            ),
        )
        val folder = temporary.newFolder()
        val manifest = publish(candidate, folder)
        val files = folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to it.readBytes() }
        val reopened = enterpriseTestStore(folder)
        val loaded = reopened.load()
        assertEquals(manifest, loaded.manifest)
        assertEquals(candidate.configuration, loaded.configuration)
        assertEquals(candidate.execution, reopened.execution(loaded.manifest))
        assertEquals(4L, (reopened.execution(loaded.manifest) as EnterpriseExecution.Platform).snapshotSchemaVersion)
        assertTrue(loaded.configuration!!.starters.isNotEmpty())
        assertTrue(loaded.configuration.starters.all { it.openingSnapshot == null })
        files.forEach { (path, bytes) -> assertArrayEquals(path, bytes, File(folder, path).readBytes()) }
    }

    @Test
    fun `historical manifest six reopens discovery four and absent opening without rewriting bytes or hashes`() {
        val source = exampleEnterprisePackage().toCandidate()
        val execution = source.execution as EnterpriseExecution.Platform
        val oldConnection = execution.connection.copy(discovery = execution.connection.discovery.copy(
            supportedSnapshotSchemaVersions = listOf(4),
        ))
        val candidate = source.copy(
            configuration = source.configuration.copy(starters = source.configuration.starters.map { it.copy(openingSnapshot = null) }),
            execution = execution.copy(connection = oldConnection, snapshotSchemaVersion = null),
        )
        val folder = temporary.newFolder()
        val manifest = publish(candidate, folder)
        val version = requireNotNull(manifest.applied)
        val file = File(folder, "revisions/${version.revision}/configuration.json")
        val payload = json.parseToJsonElement(file.readText()).jsonObject
        val config = payload.getValue("configuration").jsonObject
        val oldConfig = JsonObject(config + ("starters" to JsonArray(config.getValue("starters").jsonArray.map {
            JsonObject(it.jsonObject - "openingSnapshot")
        })))
        val oldBytes = JsonObject(payload + ("configuration" to oldConfig)).toString().toByteArray()
        file.writeBytes(oldBytes)
        val executionFile = File(folder, "revisions/${version.revision}/execution.json")
        val oldExecutionBytes = JsonObject(json.parseToJsonElement(executionFile.readText()).jsonObject - "snapshotSchemaVersion")
            .toString().toByteArray()
        executionFile.writeBytes(oldExecutionBytes)
        val oldManifest = manifest.copy(applied = version.copy(configurationHash = hash(oldBytes), executionHash = hash(oldExecutionBytes)))
        val manifestBytes = json.encodeToString(EnterpriseManifest.serializer(), oldManifest).toByteArray()
        File(folder, "manifest.json").writeBytes(manifestBytes)

        val reopened = enterpriseTestStore(folder)
        val loaded = reopened.load()
        assertEquals(oldManifest, loaded.manifest)
        assertEquals(candidate.configuration, loaded.configuration)
        assertEquals(listOf(4L), loaded.manifest.session!!.platform!!.connection.discovery.supportedSnapshotSchemaVersions)
        assertTrue(requireNotNull(loaded.configuration).starters.all { it.openingSnapshot == null })
        assertEquals(candidate.execution, reopened.execution(oldManifest))
        assertNull((reopened.execution(oldManifest) as EnterpriseExecution.Platform).snapshotSchemaVersion)
        assertArrayEquals(oldBytes, file.readBytes())
        assertArrayEquals(oldExecutionBytes, executionFile.readBytes())
        assertArrayEquals(manifestBytes, File(folder, "manifest.json").readBytes())
    }

    @Test
    fun `corrupt opening cannot bypass revision hash validation and never replaces the published files`() {
        val candidate = exampleEnterprisePackage().toCandidate()
        val folder = temporary.newFolder()
        val manifest = publish(candidate, folder)
        val file = File(folder, "revisions/${manifest.applied!!.revision}/configuration.json")
        val originalManifest = File(folder, "manifest.json").readBytes()
        val changed = file.readText().replace("\"format\":1", "\"format\":2").toByteArray()
        file.writeBytes(changed)
        val store = enterpriseTestStore(folder)
        val loaded = store.load()
        assertEquals(manifest, loaded.manifest)
        assertNull(loaded.configuration)
        val error = requireNotNull(loaded.configurationError)
        assertTrue(error.message.orEmpty().contains("enterprise_revision_hash_mismatch"))
        assertThrows(EnterpriseStorageException::class.java) { store.execution(manifest) }
        assertThrows(EnterpriseStorageException::class.java) { store.commit(manifest) }
        assertArrayEquals(originalManifest, File(folder, "manifest.json").readBytes())
        assertArrayEquals(changed, file.readBytes())
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
