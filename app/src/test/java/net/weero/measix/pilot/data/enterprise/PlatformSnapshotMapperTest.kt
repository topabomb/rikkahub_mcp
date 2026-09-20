package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformSnapshotMapperTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun cases() = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/cases.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
    private fun fixture(name: String) = cases().first { it.jsonObject.getValue("name").jsonPrimitive.content == name }
        .jsonObject.getValue("value").toString()
    private val connection get() = PlatformConnection("http://192.168.1.20:8080", PlatformWireCodec.decode(fixture("discovery")))
    private val identity get() = PlatformWireCodec.decode<PlatformBootstrap>(fixture("bootstrap")).let {
        EnterpriseIdentity(connection.authority, it.deployment.name, it.user.userId, it.user.displayName)
    }
    private fun snapshot(name: String = "v4-full") = PlatformWireCodec.decode<PlatformManagedSnapshot>(fixture(name))
    private fun map(snapshot: PlatformManagedSnapshot) = PlatformSnapshotMapper.map(connection, identity, snapshot)

    @Test fun `all published current protocol snapshots map without local bindings or invented defaults`() {
        cases().filter { it.jsonObject.getValue("schema").jsonPrimitive.content == "ManagedSnapshot" && it.jsonObject.getValue("valid").jsonPrimitive.boolean }
            .forEach { value ->
                val wire = PlatformWireCodec.decode<PlatformManagedSnapshot>(value.jsonObject.getValue("value").toString())
                val candidate = map(wire)
                assertEquals(wire.managedGeneration, candidate.configuration.generation)
                assertEquals(wire.policy.defaultModelId, candidate.configuration.defaults.chatModelId)
                assertEquals(wire.policy.defaultAssistantId, candidate.configuration.defaults.assistantId)
                assertEquals(wire.policy.defaultImageGenerationId, candidate.configuration.defaults.imageGenerationModelId)
                assertEquals(wire.imageGenerators.orEmpty().map { it.imageId }, candidate.configuration.imageGenerators.map { it.id })
                assertNull(candidate.configuration.defaults.titleModelId)
                assertTrue(candidate.configuration.gateways.isEmpty())
                assertTrue(candidate.configuration.assistants.all { !it.allowAsSubAssistant && it.allowedSubAssistantIds.isEmpty() })
                assertEquals(wire.providers.size, candidate.configuration.providers.size)
                assertTrue(candidate.execution is EnterpriseExecution.Platform)
            }
    }

    @Test fun `default assistant is optional but an explicit reference must be enabled and present`() {
        val original = snapshot()
        val assistant = original.assistants.first()
        val declared = original.copy(policy = original.policy.copy(defaultAssistantId = assistant.assistantDefinitionId))
        assertEquals(assistant.assistantDefinitionId, map(declared).configuration.defaults.assistantId)
        assertNull(map(original.copy(policy = original.policy.copy(defaultAssistantId = null))).configuration.defaults.assistantId)
        val missing = declared.copy(assistants = emptyList(), starters = emptyList())
        val disabled = declared.copy(assistants = original.assistants.map { it.copy(enabled = false) }, starters = emptyList())
        listOf(missing, disabled).forEach {
            assertEquals("invalid_platform_default_assistant",
                assertThrows(IllegalArgumentException::class.java) { map(it) }.message)
        }
    }

    @Test fun `Core permitted repeated MCP references bind once in the assistant`() {
        val original = snapshot()
        val assistant = original.assistants.first()
        val reference = assistant.mcpServerIds.first()
        val repeated = original.copy(assistants = original.assistants.map {
            if (it.assistantDefinitionId == assistant.assistantDefinitionId) it.copy(mcpServerIds = listOf(reference, reference)) else it
        })
        assertEquals(listOf(reference), map(repeated).configuration.assistants.first().mcpServerIds)
    }

    @Test fun `system and MiMo design preserve absence rather than fabricate model or voice`() {
        val candidate = map(snapshot("v4-speech"))
        val system = candidate.configuration.tts.single { it.protocol == EnterpriseTtsProtocol.SYSTEM }
        assertNull(system.modelId)
        assertNull(system.voice)
        assertNotNull(system.speechRate)
        assertFalse((candidate.execution as EnterpriseExecution.Platform).runtimePaths.containsKey(system.id))
        val design = candidate.configuration.tts.single { it.modelId?.contains("voicedesign") == true }
        assertNull(design.voice)
        assertFalse(design.voiceDesignPrompt.isNullOrBlank())
        assertThrows(IllegalArgumentException::class.java) { system.copy(voice = "fake").validate() }
        assertThrows(IllegalArgumentException::class.java) { design.copy(voiceDesignPrompt = null).validate() }
    }

    @Test fun `DashScope HTTP transcription remains a file protocol without realtime parameters`() {
        val original = snapshot("v4-asr")
        val file = original.asr.first { it.clientProtocol == PlatformAsrDefinitionClientProtocol.OPENAI_AUDIO_TRANSCRIPTIONS }
        val dashscope = file.copy(clientProtocol = PlatformAsrDefinitionClientProtocol.DASHSCOPE_HTTP_ASR,
            upstreamModelKey = "qwen-audio-3.0-asr-flash",
            runtimePath = "/api/v1/services/aigc/multimodal-generation/generation")
        val snapshot = original.copy(asr = listOf(dashscope), policy = original.policy.copy(defaultAsrId = dashscope.asrId))
        val candidate = map(snapshot)
        assertEquals(EnterpriseAsrProtocol.DASHSCOPE_HTTP, candidate.configuration.asr.single().protocol)
        assertThrows(IllegalArgumentException::class.java) {
            map(snapshot.copy(asr = listOf(dashscope.copy(sampleRate = 16000))))
        }
    }

    @Test fun `invalid references and path escapes reject the whole candidate`() {
        val original = snapshot()
        assertThrows(IllegalArgumentException::class.java) { map(original.copy(providers = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { map(original.copy(models = original.models + original.models.first())) }
        assertThrows(IllegalArgumentException::class.java) { map(original.copy(models = original.models.map { it.copy(runtimePath = "/%2e%2e/private") })) }
        assertThrows(IllegalArgumentException::class.java) { map(original.copy(mcp = emptyList())) }
    }

    @Test fun `Core reference closure cases reject disabled or missing resource dependencies`() {
        val shared = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/reference-cases.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        val base = Json.parseToJsonElement(fixture("v4-full")).jsonObject
        shared.forEach { case ->
            val fields = case.jsonObject
            val content = fields.getValue("content").jsonObject.filterKeys { it in base.keys }
            val wire = PlatformWireCodec.decode<PlatformManagedSnapshot>(JsonObject(base + content).toString())
            if (fields.getValue("expectedCode").jsonPrimitive.content.isEmpty()) map(wire)
            else assertThrows(fields.getValue("name").jsonPrimitive.content, IllegalArgumentException::class.java) { map(wire) }
        }
    }

    @Test fun `seed identity keeps repeated entries ordered and changes with generation`() {
        val original = snapshot()
        val sameText = original.copy(assistants = original.assistants.map { it.copy(memorySeed = listOf("same", "same")) })
        val first = map(sameText).configuration
        assertEquals(2, first.assistants.first().memorySeedIds.distinct().size)
        assertEquals(listOf("same", "same"), first.memorySeeds.take(2).map { it.content })
        assertNotEquals(first.memorySeeds.first().id, map(sameText.copy(managedGeneration = original.managedGeneration + 1)).configuration.memorySeeds.first().id)
    }

    @Test fun `platform candidate survives the single manifest publication and store recreation`() {
        val candidate = map(snapshot("v4-speech"))
        val folder = temporary.newFolder()
        val store = net.weero.measix.pilot.data.enterprise.enterpriseTestStore(folder)
        val version = store.prepare(candidate)
        val sessionId = "ses_12345678-1234-4234-8234-123456789012"
        val credential = store.prepareCredential(PlatformRefreshCredential(sessionId, "refresh", 2000000000000L))
        val execution = candidate.execution as EnterpriseExecution.Platform
        val session = EnterpriseSession(sessionId, identity, 2000000000000L,
            PlatformSessionDetails(execution.connection, "dev_12345678-1234-4234-8234-123456789012", credential))
        val manifest = EnterpriseManifest(ENTERPRISE_MANIFEST_SCHEMA_VERSION, EnterpriseSessionPhase.READY,
            session, version, identity.scope, identity)
        assertNull(store.load().configuration)
        store.commit(manifest)
        val reopened = net.weero.measix.pilot.data.enterprise.enterpriseTestStore(folder)
        assertEquals(candidate.configuration, reopened.load().configuration)
        assertEquals(candidate.execution, reopened.execution(manifest))
        assertTrue(reopened.execution(manifest) is EnterpriseExecution.Platform)
    }

    @Test fun `image generation remains an independent resource with a strict default and route`() {
        val original = snapshot()
        val image = requireNotNull(original.imageGenerators).single()
        val candidate = map(original)
        val definition = candidate.configuration.imageGenerators.single()
        assertEquals(image.imageId, definition.id)
        assertEquals(image.upstreamModelKey, definition.modelId)
        assertEquals(image.maxImagesPerRequest.toInt(), definition.maxImagesPerRequest)
        assertEquals(image.allowedSizes, definition.allowedSizes)
        assertEquals(image.imageId, candidate.configuration.defaults.imageGenerationModelId)
        assertEquals(image.runtimePath, (candidate.execution as EnterpriseExecution.Platform).runtimePaths.getValue(image.imageId))

        val unset = original.copy(policy = original.policy.copy(defaultImageGenerationId = null))
        assertNull(map(unset).configuration.defaults.imageGenerationModelId)
        val disabled = original.copy(imageGenerators = listOf(image.copy(enabled = false)))
        assertEquals("invalid_platform_default_image_generation",
            assertThrows(IllegalArgumentException::class.java) { map(disabled) }.message)
        val missing = original.copy(imageGenerators = emptyList())
        assertEquals("invalid_platform_default_image_generation",
            assertThrows(IllegalArgumentException::class.java) { map(missing) }.message)
    }

    @Test fun `DashScope image protocol maps to one domain protocol with canonical sizes`() {
        val original = snapshot()
        val image = requireNotNull(original.imageGenerators).single().copy(
            clientProtocol = PlatformImageGenerationDefinitionClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION,
            upstreamModelKey = "wan2.7-image",
            runtimePath = "/api/v1/services/aigc/multimodal-generation/generation",
            allowedSizes = listOf("1024x1024"),
        )
        val candidate = map(original.copy(imageGenerators = listOf(image)))
        val definition = candidate.configuration.imageGenerators.single()
        assertEquals(me.rerere.ai.provider.images.ImageGenerationClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION,
            definition.protocol)
        assertEquals(listOf("1024x1024"), definition.allowedSizes)
        assertEquals(image.runtimePath,
            (candidate.execution as EnterpriseExecution.Platform).runtimePaths.getValue(image.imageId))
    }

    @Test fun `persisted configuration without additive image collection decodes as empty without schema migration`() {
        val configuration = map(snapshot()).configuration
        val json = EnterpriseConfigurationCodec.json
        val encoded = json.encodeToJsonElement(EnterpriseConfiguration.serializer(), configuration).jsonObject
        val decoded = json.decodeFromJsonElement(EnterpriseConfiguration.serializer(), JsonObject(encoded - "imageGenerators"))
        assertTrue(decoded.imageGenerators.isEmpty())
        assertEquals(configuration.defaults, decoded.defaults)
    }

    @Test fun `schema five URL-qualified state migrates once without changing principal or session`() {
        val candidate = map(snapshot("v4-speech"))
        val folder = temporary.newFolder()
        val store = enterpriseTestStore(folder)
        val legacyVersion = store.prepare(candidate)
        val sessionId = "ses_12345678-1234-4234-8234-123456789012"
        val credential = store.prepareCredential(PlatformRefreshCredential(sessionId, "refresh", 2000000000000L))
        val execution = candidate.execution as EnterpriseExecution.Platform
        val session = EnterpriseSession(sessionId, identity, 2000000000000L,
            PlatformSessionDetails(execution.connection, "dev_12345678-1234-4234-8234-123456789012", credential))

        val sourceHash = MessageDigest.getInstance("SHA-256")
            .digest(connection.origin.toByteArray()).joinToString("") { "%02x".format(it) }
        fun legacy(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { legacy(it.value) }.toMutableMap().apply {
                if (keys == setOf("deploymentId")) put("sourceNamespace", JsonPrimitive("platform:$sourceHash"))
            })
            is JsonArray -> JsonArray(element.map(::legacy))
            is JsonPrimitive -> if (element.isString && element.content.startsWith("managed~")) {
                val parts = element.content.split('~')
                JsonPrimitive("managed~platform~$sourceHash~${parts[1]}~${parts[2]}")
            } else element
        }
        val json = EnterpriseConfigurationCodec.json
        val revision = java.io.File(folder, "revisions/${legacyVersion.revision}")
        val currentConfiguration = json.parseToJsonElement(java.io.File(revision, "configuration.json").readText()).jsonObject
        val legacyConfiguration = legacy(JsonObject(currentConfiguration +
            ("identity" to json.encodeToJsonElement(EnterpriseIdentity.serializer(), identity)))).toString().toByteArray()
        val legacyExecution = legacy(buildJsonObject {
            put("revision", legacyVersion.revision)
            put("execution", json.encodeToJsonElement(EnterpriseExecution.serializer(), execution))
        }).toString().toByteArray()
        java.io.File(revision, "configuration.json").writeBytes(legacyConfiguration)
        java.io.File(revision, "execution.json").writeBytes(legacyExecution)
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val legacyApplied = legacyVersion.copy(
            configurationHash = hash(legacyConfiguration),
            executionHash = hash(legacyExecution),
        )
        val manifest = EnterpriseManifest(5, EnterpriseSessionPhase.READY, session, legacyApplied, identity.scope, identity)
        val legacyManifest = legacy(json.encodeToJsonElement(EnterpriseManifest.serializer(), manifest)).toString()
        java.io.File(folder, "manifest.json").writeText(legacyManifest)

        val reopened = enterpriseTestStore(folder)
        val loaded = reopened.load()
        assertEquals(ENTERPRISE_MANIFEST_SCHEMA_VERSION, loaded.manifest.schemaVersion)
        assertEquals(sessionId, loaded.manifest.session?.id)
        assertEquals(identity.scope, loaded.manifest.selectedScope)
        assertEquals(candidate.configuration, loaded.configuration)
        assertEquals(candidate.execution, reopened.execution(loaded.manifest))
        assertNotEquals(legacyVersion.revision, loaded.manifest.applied?.revision)
    }

    @Test fun `same generation cannot replace the published platform hash`() = runBlocking {
        val candidate = map(snapshot())
        val store = net.weero.measix.pilot.data.enterprise.enterpriseTestStore(temporary.newFolder())
        val version = store.prepare(candidate)
        val sessionId = "ses_12345678-1234-4234-8234-123456789012"
        val credential = store.prepareCredential(PlatformRefreshCredential(sessionId, "refresh", 2000000000000L))
        val execution = candidate.execution as EnterpriseExecution.Platform
        val session = EnterpriseSession(sessionId, identity, 2000000000000L,
            PlatformSessionDetails(execution.connection, "dev_12345678-1234-4234-8234-123456789012", credential))
        store.commit(EnterpriseManifest(ENTERPRISE_MANIFEST_SCHEMA_VERSION, EnterpriseSessionPhase.READY,
            session, version, identity.scope, identity))
        val controller = EnterpriseSessionController(store) { 1000L }
        controller.recover()
        val altered = candidate.copy(execution = candidate.execution.copy(snapshotHash = "sha256:" + "0".repeat(64)))
        try {
            controller.synchronize(RealmAccess.Enterprise(identity.scope, session.id), altered)
            fail("published generation was replaced")
        } catch (error: EnterpriseConfigurationException) {
            assertEquals("enterprise_generation_conflict", error.reason)
        }
        assertEquals(candidate.execution, store.execution(store.readManifest()))
    }
}
