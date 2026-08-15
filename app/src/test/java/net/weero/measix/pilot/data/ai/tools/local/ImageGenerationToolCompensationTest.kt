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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ManagedLocalArtifactStore
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
import org.junit.Test

class ImageGenerationToolCompensationTest {
    private val ownerId = Uuid.random()
    private val model = Model(modelId = "gpt-image-1", displayName = "GPT Image", type = ModelType.IMAGE)
    private val providerSetting = ProviderSetting.OpenAI(name = "OpenAI", models = listOf(model))

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
        val artifact = LocalArtifactRef(relativePath = "upload/chat.png", mimeType = "image/png")
        File(filesDir, artifact.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val settings = Settings(
            assistants = listOf(Assistant(id = ownerId, localTools = listOf(LocalToolOption.TextToImage))),
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns MutableStateFlow(settings)
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
        coEvery { backgroundService.replaceBackground(any(), any(), any()) } throws
            CancellationException("stop after persist")
        val artifactStore = mockk<ManagedLocalArtifactStore>()
        coEvery { artifactStore.delete(artifact) } returns Unit
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
        val result = runCatching {
            tool.executeWithContext(
                ToolExecutionContext(
                    messageId = Uuid.random(),
                    toolOrdinal = 0,
                    toolCallId = "call",
                    reportMetadata = { _, _ -> },
                ),
                buildJsonObject {
                    put("prompt", "a cat")
                    put("set_as_background", true)
                },
            )
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        coVerify { artifactStore.delete(artifact) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `successful output keeps the chat artifact`() = runTest {
        val filesDir = createTempDirectory("tool-keep").toFile()
        val canonical = File(filesDir, "images/canonical.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val artifact = LocalArtifactRef(relativePath = "upload/chat.png", mimeType = "image/png")
        val settings = Settings(
            assistants = listOf(Assistant(id = ownerId, localTools = listOf(LocalToolOption.TextToImage))),
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns MutableStateFlow(settings)
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
        coEvery { backgroundService.replaceBackground(any(), any(), any()) } returns
            BackgroundUpdateResult(requested = true, updated = true)
        val artifactStore = mockk<ManagedLocalArtifactStore>()
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
        val parts = tool.executeWithContext(
            ToolExecutionContext(
                messageId = Uuid.random(),
                toolOrdinal = 0,
                toolCallId = "call",
                reportMetadata = { _, _ -> },
            ),
            buildJsonObject {
                put("prompt", "a cat")
                put("set_as_background", true)
            },
        )
        assertEquals(2, parts.size)
        coVerify(exactly = 0) { artifactStore.delete(any()) }
        filesDir.deleteRecursively()
    }
}
