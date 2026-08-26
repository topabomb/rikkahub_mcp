package net.weero.measix.pilot.data.datastore

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class ManagedConfigurationVerifierTest {
    private val signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    @Test
    fun `oversized envelope is rejected before JSON decoding`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            decodeManagedConfigurationEnvelope(ByteArray(2 * 1024 * 1024 + 1))
        }

        assertEquals(true, error.message?.contains("too large") == true)
    }

    @Test
    fun `verified aggregate accepts public records and exposes a managed overlay`() {
        val assistant = Assistant(id = Uuid.random(), name = "Managed")
        val snapshot = verify(
            payload = ManagedConfigurationPayload(
                records = listOf(
                    ManagedConfigurationRecord(
                        ManagedConfigurationRecordKind.ASSISTANT,
                        assistant.id.toString(),
                        buildJsonObject {
                            put("id", assistant.id.toString())
                            put("name", assistant.name)
                        },
                    ),
                ),
            ),
        )

        assertEquals(ManagedConfigurationState.ACTIVE, snapshot.state)
        assertEquals(assistant, snapshot.overlay?.records?.assistants?.single { it.id == assistant.id })
    }

    @Test
    fun `managed records retain the existing plaintext settings fields`() {
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), apiKey = "configured-key")
        val snapshot = verify(
            payload = ManagedConfigurationPayload(
                records = listOf(
                    ManagedConfigurationRecord(
                        ManagedConfigurationRecordKind.PROVIDER,
                        provider.id.toString(),
                        JsonInstant.encodeToJsonElement(ProviderSetting.serializer(), provider) as JsonObject,
                    ),
                ),
            ),
        )

        assertEquals(provider, snapshot.overlay?.records?.providers?.single { it.id == provider.id })
    }

    @Test
    fun `managed overlay wins source attribution over a local shadow`() {
        val assistant = Assistant(id = Uuid.random(), name = "Managed")
        val verified = verify(
            payload = ManagedConfigurationPayload(
                records = listOf(
                    ManagedConfigurationRecord(
                        ManagedConfigurationRecordKind.ASSISTANT,
                        assistant.id.toString(),
                        JsonInstant.encodeToJsonElement(Assistant.serializer(), assistant) as JsonObject,
                    ),
                ),
            ),
        )

        val effective = EffectiveSettingsResolver.resolve(
            local = Settings(assistants = listOf(assistant.copy(name = "Local shadow"))),
            managed = verified,
            revision = 1,
        )

        assertEquals(SettingsValueSource.MANAGED, effective.access.sourceOf(ManagedConfigurationRecordKind.ASSISTANT, assistant.id))
        assertEquals("Managed", effective.settings.assistants.first { it.id == assistant.id }.name)
    }

    @Test
    fun `expired signed aggregate keeps its verified overlay and locks`() {
        val assistant = Assistant(id = Uuid.random(), name = "Managed")
        val snapshot = verify(
            payload = ManagedConfigurationPayload(
                records = listOf(assistant.asManagedRecord()),
                locks = mapOf("records/assistants/${assistant.id}" to "Read-only"),
            ),
            expiresAtEpochMillis = 1,
        )

        assertEquals(ManagedConfigurationState.DEGRADED, snapshot.state)
        assertEquals("Read-only", snapshot.overlay?.access?.reasonFor("records/assistants/${assistant.id}"))
        assertEquals(assistant, snapshot.overlay?.records?.assistants?.single { it.id == assistant.id })
    }

    @Test
    fun `managed locks cannot target device-only settings`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            verify(
                payload = ManagedConfigurationPayload(
                    locks = mapOf("displaySetting" to "Device presentation is not managed"),
                ),
            )
        }

        assertEquals(true, error.message?.contains("lock path") == true)
    }

    @Test
    fun `signed asset binding is the only way to attach managed assistant media`() {
        val assistant = Assistant(id = Uuid.random(), name = "Managed")
        val snapshot = verify(
            payload = ManagedConfigurationPayload(
                records = listOf(assistant.asManagedRecord()),
                assets = listOf(ManagedConfigurationAsset("avatar", "")),
                assetBindings = listOf(
                    ManagedAssistantAssetBinding(
                        assistantId = assistant.id,
                        avatarAssetId = "avatar",
                    ),
                ),
            ),
            assetUris = mapOf("avatar" to "content://managed/avatar"),
        )

        assertEquals(
            Avatar.Image("content://managed/avatar"),
            snapshot.overlay?.records?.assistants?.single { it.id == assistant.id }?.avatar,
        )
    }

    @Test
    fun `duplicate managed asset binding is rejected`() {
        val assistant = Assistant(id = Uuid.random(), name = "Managed")
        val binding = ManagedAssistantAssetBinding(
            assistantId = assistant.id,
            backgroundAssetId = "background",
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            verify(
                payload = ManagedConfigurationPayload(
                    records = listOf(assistant.asManagedRecord()),
                    assets = listOf(ManagedConfigurationAsset("background", "")),
                    assetBindings = listOf(binding, binding),
                ),
                assetUris = mapOf("background" to "content://managed/background"),
            )
        }

        assertEquals(true, error.message?.contains("duplicate asset bindings") == true)
    }

    @Test
    fun `startup rejects a stored asset whose bytes no longer match the accepted package`() {
        val expected = "signed-avatar".encodeToByteArray()
        val root = createTempDirectory("managed-configuration-asset").toFile()
        val payload = ManagedConfigurationPayload(
            assets = listOf(
                ManagedConfigurationAsset(
                    id = "avatar",
                    base64 = Base64.getEncoder().encodeToString(expected),
                ),
            ),
        )
        File(root, "generation-1/assets/avatar").apply {
            parentFile?.mkdirs()
            writeBytes("tampered-avatar".encodeToByteArray())
        }

        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                ManagedConfigurationVerifier.verify(
                    raw = signed(payload, generation = 1, expiresAtEpochMillis = null),
                    assetRoot = root,
                    previousGeneration = null,
                    nowMillis = 1,
                    trustedKey = { _, _ -> signingKey.public },
                )
            }

            assertEquals(true, error.message?.contains("content is invalid") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same generation cannot replace an accepted aggregate`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            verify(ManagedConfigurationPayload(), generation = 4, previousGeneration = 4)
        }

        assertEquals(true, error.message?.contains("not monotonic") == true)
    }

    @Test
    fun `negative expiry timestamp is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            verify(ManagedConfigurationPayload(), expiresAtEpochMillis = -1)
        }

        assertEquals(true, error.message?.contains("expiry") == true)
    }

    @Test
    fun `managed assistant cannot depend on a local shadow record`() {
        val assistant = Assistant(id = Uuid.random(), mcpServers = setOf(Uuid.random()))

        val error = assertThrows(IllegalArgumentException::class.java) {
            verify(ManagedConfigurationPayload(records = listOf(assistant.asManagedRecord())))
        }

        assertEquals(true, error.message?.contains("MCP server is missing") == true)
    }

    @Test
    fun `revocation must be an otherwise empty higher generation`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            verify(
                payload = ManagedConfigurationPayload(
                    revoked = true,
                    records = listOf(
                        ManagedConfigurationRecord(
                            ManagedConfigurationRecordKind.ASSISTANT,
                            Uuid.random().toString(),
                            buildJsonObject { put("name", "must-not-be-carried") },
                        ),
                    ),
                ),
            )
        }

        assertEquals(true, error.message?.contains("revocation") == true)
    }

    private fun verify(
        payload: ManagedConfigurationPayload,
        generation: Long = 1,
        previousGeneration: Long? = null,
        expiresAtEpochMillis: Long? = null,
        assetUris: Map<String, String> = emptyMap(),
    ): ManagedConfigurationSnapshot = ManagedConfigurationVerifier.verify(
        raw = signed(payload, generation, expiresAtEpochMillis),
        previousGeneration = previousGeneration,
        nowMillis = 1,
        assetUris = assetUris,
        trustedKey = { _, _ -> signingKey.public },
    )

    private fun signed(
        payload: ManagedConfigurationPayload,
        generation: Long,
        expiresAtEpochMillis: Long?,
    ): ByteArray {
        val unsigned = ManagedConfigurationEnvelope(
            schemaVersion = 1,
            keyId = "test",
            tenantId = "tenant",
            generation = generation,
            expiresAtEpochMillis = expiresAtEpochMillis,
            payload = payload,
            signature = "",
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signingKey.private)
            update(JsonInstant.encodeToString(unsigned).encodeToByteArray())
            sign()
        }
        return JsonInstant.encodeToString(
            unsigned.copy(signature = Base64.getEncoder().encodeToString(signature)),
        ).encodeToByteArray()
    }

    private fun Assistant.asManagedRecord() = ManagedConfigurationRecord(
        ManagedConfigurationRecordKind.ASSISTANT,
        id.toString(),
        JsonInstant.encodeToJsonElement(Assistant.serializer(), this) as JsonObject,
    )

}
