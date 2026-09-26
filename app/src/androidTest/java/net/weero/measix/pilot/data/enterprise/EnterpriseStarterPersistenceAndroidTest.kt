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
                var manifest = EnterpriseManifest(6, EnterpriseSessionPhase.READY, session, applied,
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

    private fun id(prefix: String) = "${prefix}_12345678-1234-4234-8234-123456789012"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
