package net.weero.measix.pilot.data.ai.tools.local

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.toEffectiveSettingsSnapshot
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.imggen.AssistantBackgroundService
import net.weero.measix.pilot.data.imggen.BackgroundUpdateResult
import net.weero.measix.pilot.data.imggen.CommittedGeneratedMedia
import net.weero.measix.pilot.data.imggen.ImageGenerationCoordinator
import net.weero.measix.pilot.data.imggen.ImageGenerationModelDescriptor
import net.weero.measix.pilot.data.imggen.ImageGenerationOutcome
import net.weero.measix.pilot.data.imggen.ImageGenerationSelection
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
import net.weero.measix.pilot.data.imggen.TINY_PNG
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageGenerationToolCompensationTest {
    private val ownerId = Uuid.random()
    private val model = Model(modelId = "gpt-image-1", displayName = "GPT Image", type = ModelType.IMAGE)
    private val providerSetting = ProviderSetting.OpenAI(name = "OpenAI", models = listOf(model))

    @Test
    fun `registered image tool rejects bad input before approval without invoking generation`() {
        val settings = Settings(assistants = listOf(Assistant(id = ownerId, localTools = listOf(LocalToolOption.TextToImage))))
        val resolver = mockk<ImageGenerationSelectionResolver>()
        every { resolver.resolve(any()) } returns available()
        val coordinator = mockk<ImageGenerationCoordinator>()
        val artifactStore = mockk<ArtifactStore>()
        val factory = ImageGenerationToolFactory(
            filesDir = File("unused"), settingsStore = mockk(), resolver = resolver,
            coordinator = coordinator, backgroundService = mockk(), artifactStore = artifactStore,
            rewriter = ToolArtifactRewriter(File("unused"), artifactStore),
        )
        val tool = factory.create(AssistantToolBuildContext(ownerId, settings))!!
        val failure = assertThrows(ToolArgumentsException::class.java) { tool.parseArguments("{}", Json) }
        val replay = Json.parseToJsonElement((failure.output.single() as UIMessagePart.Text).text).jsonObject
        val domainFailure = requireNotNull(tool.validateArguments(buildJsonObject {}))
        val toolFailure = assertThrows(ToolExecutionFailure::class.java) { failedResult("invalid_arguments") }
        val executionFailure = Json.parseToJsonElement((toolFailure.output.single() as UIMessagePart.Text).text)
        assertEquals(domainFailure, executionFailure)
        assertFalse(domainFailure.containsKey("type"))
        assertFalse(domainFailure.containsKey("error"))
        assertEquals(domainFailure, JsonObject(replay.filterKeys { it != "type" && it != "error" }))
        assertEquals("invalid_arguments", replay["error"]!!.jsonPrimitive.content)
        assertEquals("error", replay["type"]!!.jsonPrimitive.content)
        assertEquals(
            ToolInteractionRequirement.None,
            tool.interactionRequirement(tool.parseArguments("""{"prompt":"a cat"}""", Json)),
        )
        assertEquals(
            ToolInteractionRequirement.Approval,
            tool.interactionRequirement(tool.parseArguments("""{"prompt":"a cat","set_as_background":true}""", Json)),
        )
        coVerify(exactly = 0) { coordinator.enqueue(any()) }
    }

    private fun available(): ImageGenerationSelection.Available {
        val provider = mockk<Provider<*>>()
        every { provider.supportsImageGeneration } returns true
        return ImageGenerationSelection.Available(
            model = model,
            sourceProvider = providerSetting,
            effectiveProvider = providerSetting,
            provider = provider,
            descriptor = ImageGenerationModelDescriptor.from(model, providerSetting),
        )
    }

    @Test
    fun `cancel after chat artifact commit deletes the unowned copy`() = runTest {
        val filesDir = createTempDirectory("tool-compensate").toFile()
        val canonical = File(filesDir, "images/canonical.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val artifact = ownedArtifact(filesDir, "upload/chat.png")
        File(filesDir, artifact.localRef.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val settings = Settings(
            assistants = listOf(Assistant(id = ownerId, localTools = listOf(LocalToolOption.TextToImage))),
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns MutableStateFlow(settings.toEffectiveSettingsSnapshot())
        val resolver = mockk<ImageGenerationSelectionResolver>()
        every { resolver.resolve(any()) } returns available()
        val coordinator = mockk<ImageGenerationCoordinator>()
        coEvery { coordinator.enqueue(any()) } returns ImageGenerationOutcome.Success(
            listOf(
                CommittedGeneratedMedia(
                    mediaId = 11L,
                    canonicalRelativePath = "images/canonical.png",
                    canonicalFile = canonical,
                    mimeType = "image/png",
                    chatArtifact = artifact,
                )
            )
        )
        val backgroundService = mockk<AssistantBackgroundService>()
        coEvery { backgroundService.replaceGeneratedBackground(any(), any(), any()) } throws
            CancellationException("stop after persist")
        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.discardUnpublished(artifact) } returns ArtifactDeleteResult.Completed(artifact.entity.id)
        every { artifactStore.unpublishedLease(artifact) } returns ToolResourceLease(
            publish = {},
            discard = { artifactStore.discardUnpublished(artifact) },
        )
        val factory = ImageGenerationToolFactory(
            filesDir = filesDir,
            settingsStore = settingsStore,
            resolver = resolver,
            coordinator = coordinator,
            backgroundService = backgroundService,
            artifactStore = artifactStore,
            rewriter = ToolArtifactRewriter(filesDir, artifactStore),
        )
        val tool = factory.create(AssistantToolBuildContext(ownerId, settings))!!
        val resources = mutableListOf<ToolResourceLease>()
        val result = runCatching {
            tool.executeWithContext(
                ToolExecutionContext(
                    locator = ToolCallLocator(Uuid.random(), Uuid.random(), Uuid.random()), providerCallId = "call",
                    reportMetadata = { _, _ -> },
                    resolveAttachments = { ToolAttachmentResolution(failureReason = "not_used") },
                    reportChildConversation = { },
                    registerUnpublishedResource = resources::add,
                ),
                buildJsonObject {
                    put("prompt", "a cat")
                    put("set_as_background", true)
                },
            )
        }
        resources.forEach { it.discard() }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, resources.size)
        coVerify { artifactStore.discardUnpublished(artifact) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `successful output keeps the chat artifact`() = runTest {
        val filesDir = createTempDirectory("tool-keep").toFile()
        val canonical = File(filesDir, "images/canonical.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val artifact = ownedArtifact(filesDir, "upload/chat.png")
        val settings = Settings(
            assistants = listOf(Assistant(id = ownerId, localTools = listOf(LocalToolOption.TextToImage))),
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns MutableStateFlow(settings.toEffectiveSettingsSnapshot())
        val resolver = mockk<ImageGenerationSelectionResolver>()
        every { resolver.resolve(any()) } returns available()
        val coordinator = mockk<ImageGenerationCoordinator>()
        coEvery { coordinator.enqueue(any()) } returns ImageGenerationOutcome.Success(
            listOf(
                CommittedGeneratedMedia(
                    mediaId = 12L,
                    canonicalRelativePath = "images/canonical.png",
                    canonicalFile = canonical,
                    mimeType = "image/png",
                    chatArtifact = artifact,
                )
            )
        )
        val backgroundService = mockk<AssistantBackgroundService>()
        coEvery { backgroundService.replaceGeneratedBackground(any(), any(), any()) } returns
            BackgroundUpdateResult(requested = true, updated = true)
        val artifactStore = mockk<ArtifactStore>()
        every { artifactStore.unpublishedLease(artifact) } returns ToolResourceLease(
            publish = {},
            discard = { error("must not discard") },
        )
        val factory = ImageGenerationToolFactory(
            filesDir = filesDir,
            settingsStore = settingsStore,
            resolver = resolver,
            coordinator = coordinator,
            backgroundService = backgroundService,
            artifactStore = artifactStore,
            rewriter = ToolArtifactRewriter(filesDir, artifactStore),
        )
        val tool = factory.create(AssistantToolBuildContext(ownerId, settings))!!
        val resources = mutableListOf<ToolResourceLease>()
        val metadataPatches = mutableListOf<Pair<JsonObject, ToolMetadataDelivery>>()
        val parts = tool.executeWithContext(
            ToolExecutionContext(
                locator = ToolCallLocator(Uuid.random(), Uuid.random(), Uuid.random()), providerCallId = "call",
                reportMetadata = { patch, delivery -> metadataPatches += patch to delivery },
                resolveAttachments = { ToolAttachmentResolution(failureReason = "not_used") },
                reportChildConversation = { },
                registerUnpublishedResource = resources::add,
            ),
            buildJsonObject {
                put("prompt", "a cat")
                put("set_as_background", true)
            },
        )
        assertEquals(2, parts.size)
        assertEquals(1, resources.size)
        assertEquals(
            ToolMetadataDelivery.CHECKPOINT,
            metadataPatches.single { (patch, _) ->
                patch["phase"]?.jsonPrimitive?.content == "setting_background"
            }.second,
        )
        val (terminalMetadata, terminalDelivery) = metadataPatches.last()
        assertEquals("completed", terminalMetadata["phase"]?.jsonPrimitive?.content)
        assertTrue(terminalMetadata.containsKey(ToolArtifactRewriter.ARTIFACT_KEY))
        assertEquals(ToolMetadataDelivery.DEFERRED, terminalDelivery)
        coVerify(exactly = 0) { artifactStore.discardUnpublished(any()) }
        filesDir.deleteRecursively()
    }

    private fun ownedArtifact(filesDir: File, relativePath: String): OwnedArtifact {
        val file = File(filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val entity = ArtifactEntity(
            id = 42,
            folder = "upload",
            relativePath = relativePath,
            displayName = file.name,
            mimeType = "image/png",
            sizeBytes = file.length(),
            createdAt = 1,
            updatedAt = 1,
            state = ArtifactState.ACTIVE.name,
            origin = ArtifactOrigin.GENERATED.name,
        )
        val uri = mockk<android.net.Uri>()
        every { uri.toString() } returns "file:///${file.absolutePath.replace('\\', '/')}"
        return OwnedArtifact(
            entity,
            uri,
            LocalArtifactRef(relativePath = relativePath, mimeType = "image/png"),
        )
    }
}
