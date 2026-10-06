package net.weero.measix.pilot.data.enterprise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Device coverage for AtomicFile and the real Android Keystore; protocol combinations live in JVM tests. */
@RunWith(AndroidJUnit4::class)
class EnterpriseStarterPersistenceAndroidTest {
    @Test
    fun openingAndHistoricalAppliedReopenWithoutChangingPublicationOrCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(false, true).forEach { historical ->
            val root = File(context.noBackupFilesDir, "starter-state-test-${Uuid.random()}").apply { check(mkdirs()) }
            try {
                val candidate = starterDeviceCandidate(historical).let { value ->
                    if (historical) value.copy(execution = (value.execution as EnterpriseExecution.Platform).copy(snapshotSchemaVersion = null)) else value
                }
                val store = EnterpriseAppliedStore(root)
                val applied = store.prepare(candidate)
                val sessionId = id("ses")
                val credential = PlatformRefreshCredential(sessionId, "instrumentation-refresh", 1_900_000_000_000L)
                val credentialVersion = store.prepareCredential(credential)
                val execution = candidate.execution as EnterpriseExecution.Platform
                val session = EnterpriseSession(sessionId, candidate.identity, credential.refreshExpiresAtMillis,
                    PlatformSessionDetails(execution.connection, id("dev"), credentialVersion))
                var manifest = EnterpriseManifest(ENTERPRISE_MANIFEST_SCHEMA_VERSION, EnterpriseSessionPhase.READY, session, applied,
                    candidate.identity.scope, candidate.identity)
                store.commit(manifest)

                val configurationFile = File(root, "revisions/${applied.revision}/configuration.json")
                val executionFile = File(root, "revisions/${applied.revision}/execution.json")
                val manifestFile = File(root, "manifest.json")
                if (historical) {
                    val json = EnterpriseConfigurationCodec.json
                    val document = json.parseToJsonElement(configurationFile.readText()).jsonObject
                    val configuration = document.getValue("configuration").jsonObject
                    val starters = configuration.getValue("starters").jsonArray.map { JsonObject(it.jsonObject - "openingSnapshot") }
                    val oldConfiguration = JsonObject(configuration + ("starters" to JsonArray(starters)))
                    val bytes = JsonObject(document + ("configuration" to oldConfiguration)).toString().toByteArray()
                    configurationFile.writeBytes(bytes)
                    val executionBytes = JsonObject(json.parseToJsonElement(executionFile.readText()).jsonObject - "snapshotSchemaVersion")
                        .toString().toByteArray()
                    executionFile.writeBytes(executionBytes)
                    manifest = manifest.copy(applied = applied.copy(configurationHash = hash(bytes), executionHash = hash(executionBytes)))
                    manifestFile.writeText(json.encodeToString(EnterpriseManifest.serializer(), manifest))
                }

                val beforeConfiguration = configurationFile.readBytes()
                val beforeExecution = executionFile.readBytes()
                val beforeManifest = manifestFile.readBytes()
                val reopened = EnterpriseAppliedStore(root)
                val state = reopened.load()
                assertEquals(candidate.configuration, state.configuration)
                assertEquals(manifest, state.manifest)
                assertEquals(candidate.execution, reopened.execution(manifest))
                assertEquals(credential, reopened.credential(credentialVersion, sessionId))
                assertArrayEquals(beforeConfiguration, configurationFile.readBytes())
                assertArrayEquals(beforeExecution, executionFile.readBytes())
                assertArrayEquals(beforeManifest, manifestFile.readBytes())
                val starter = requireNotNull(state.configuration).starters.single()
                if (historical) {
                    assertNull(starter.openingSnapshot)
                    assertEquals(listOf(4L), state.manifest.session!!.platform!!.connection.discovery.supportedSnapshotSchemaVersions)
                } else {
                    assertEquals(listOf("second", "first"), starter.openingSnapshot!!.initialContexts.map { it.id })
                    assertEquals("  {{user}} 企业指令\n", starter.openingSnapshot.systemPrompt)
                }
            } finally {
                check(root.deleteRecursively())
            }
        }
    }

    @Test
    fun manifestSixMcpMigrationPublishesAtomicallyAndRetainsRealKeystoreCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "mcp-migration-test-${Uuid.random()}").apply { check(mkdirs()) }
        try {
            val source = starterDeviceCandidate(historical = true)
            val server = EnterpriseMcpResource(id("mcp"), "Managed", authOwnership = PlatformMcpDefinitionAuthOwnership.NONE,
                toolAccessMode = PlatformMcpDefinitionToolAccessMode.ALL, allowedTools = emptyList())
            val binding = PlatformAssistantMcpBinding(server.id, PlatformAssistantMcpBindingToolSelection.ALL, emptyList())
            val execution = source.execution as EnterpriseExecution.Platform
            val candidate = source.copy(
                configuration = source.configuration.copy(mcpServers = listOf(server),
                    assistants = source.configuration.assistants.map { it.copy(mcpBindings = listOf(binding)) }),
                execution = execution.copy(runtimePaths = execution.runtimePaths + (server.id to "/mcp/v1")),
            )
            val store = EnterpriseAppliedStore(root)
            val applied = store.prepare(candidate)
            val sessionId = id("ses")
            val credential = PlatformRefreshCredential(sessionId, "migration-refresh", 1_900_000_000_000L)
            val credentialVersion = store.prepareCredential(credential)
            val session = EnterpriseSession(sessionId, candidate.identity, credential.refreshExpiresAtMillis,
                PlatformSessionDetails(execution.connection, id("dev"), credentialVersion))
            val current = EnterpriseManifest(ENTERPRISE_MANIFEST_SCHEMA_VERSION, EnterpriseSessionPhase.READY, session, applied,
                candidate.identity.scope, candidate.identity)
            store.commit(current)
            val json = EnterpriseConfigurationCodec.json
            val configurationFile = File(root, "revisions/${applied.revision}/configuration.json")
            val document = json.parseToJsonElement(configurationFile.readText()).jsonObject
            val configuration = document.getValue("configuration").jsonObject
            val old = JsonObject(configuration + mapOf(
                "mcpServers" to JsonArray(configuration.getValue("mcpServers").jsonArray.map {
                    JsonObject(it.jsonObject - "toolAccessMode" - "allowedTools")
                }),
                "assistants" to JsonArray(configuration.getValue("assistants").jsonArray.map {
                    JsonObject((it.jsonObject - "mcpBindings") + ("mcpServerIds" to JsonArray(listOf(JsonPrimitive(server.id)))))
                }),
            ))
            val oldBytes = JsonObject(document + ("configuration" to old)).toString().toByteArray()
            configurationFile.writeBytes(oldBytes)
            val historical = current.copy(schemaVersion = 6, applied = applied.copy(configurationHash = hash(oldBytes)))
            val manifestFile = File(root, "manifest.json")
            manifestFile.writeText(json.encodeToString(EnterpriseManifest.serializer(), historical))
            val reopened = EnterpriseAppliedStore(root)
            val state = reopened.load()
            assertNull(state.configurationError)
            assertEquals(ENTERPRISE_MANIFEST_SCHEMA_VERSION, state.manifest.schemaVersion)
            assertEquals(historical.session, state.manifest.session)
            assertEquals(historical.selectedScope, state.manifest.selectedScope)
            assertNotEquals(applied.revision, state.manifest.applied!!.revision)
            assertEquals(candidate.configuration, state.configuration)
            assertEquals(candidate.execution, reopened.execution(state.manifest))
            assertEquals(credential, reopened.credential(credentialVersion, sessionId))
            assertArrayEquals(oldBytes, configurationFile.readBytes())
            val publishedBytes = manifestFile.readBytes()
            assertEquals(state, EnterpriseAppliedStore(root).load())
            assertArrayEquals(publishedBytes, manifestFile.readBytes())
        } finally { check(root.deleteRecursively()) }
    }

    private fun id(prefix: String) = "${prefix}_12345678-1234-4234-8234-123456789012"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
