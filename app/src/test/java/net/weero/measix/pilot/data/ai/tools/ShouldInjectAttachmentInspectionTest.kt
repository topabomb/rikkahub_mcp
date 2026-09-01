package net.weero.measix.pilot.data.ai.tools

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot
import net.weero.measix.pilot.data.ai.tools.local.LocalTools
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ShouldInjectAttachmentInspectionTest {
    private fun model(modalities: List<Modality>) = Model(
        id = Uuid.random(),
        modelId = "m",
        displayName = "M",
        type = ModelType.CHAT,
        inputModalities = modalities,
    )

    private fun settingsWith(inspection: Model?) = Settings(
        providers = listOf(ProviderSetting.OpenAI(models = listOfNotNull(inspection))),
        attachmentInspectionModelId = inspection?.id,
    )

    @Test
    fun `not injected without configured image model`() {
        assertFalse(shouldInjectAttachmentInspection(settingsWith(null)))
        assertFalse(shouldInjectAttachmentInspection(settingsWith(model(listOf(Modality.TEXT)))))
    }

    @Test
    fun `not injected when inspection model is absent from providers`() {
        assertFalse(
            shouldInjectAttachmentInspection(
                Settings(providers = emptyList(), attachmentInspectionModelId = Uuid.random()),
            ),
        )
    }

    @Test
    fun `not injected when inspection provider is disabled`() {
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertFalse(
            shouldInjectAttachmentInspection(
                Settings(
                    providers = listOf(ProviderSetting.OpenAI(enabled = false, models = listOf(inspection))),
                    attachmentInspectionModelId = inspection.id,
                ),
            ),
        )
    }

    @Test
    fun `valid inspection model is sufficient regardless of endpoint host`() {
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertTrue(shouldInjectAttachmentInspection(settingsWith(inspection)))
        assertTrue(
            shouldInjectAttachmentInspection(
                Settings(
                    providers = listOf(
                        ProviderSetting.OpenAI(baseUrl = "https://proxy.example.com/v1", models = listOf(inspection)),
                    ),
                    attachmentInspectionModelId = inspection.id,
                ),
            ),
        )
    }

    @Test
    fun `master and target keep inspection for native vision text and absent current model without workspace`() = runTest {
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        val settings = settingsWith(inspection)
        val adapter = mockk<Provider<ProviderSetting>>()
        every { adapter.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities(
            userImages = RequestImageSupport.STRUCTURED,
            assistantImages = RequestImageSupport.STRUCTURED,
            toolOutputImages = RequestImageSupport.STRUCTURED,
        )
        val providerManager = mockk<ProviderManager>()
        every { providerManager.getProviderByType(any()) } returns adapter
        val localTools = mockk<LocalTools>()
        every { localTools.getTools(any(), any(), any()) } returns emptyList()
        val factory = GenerationToolSetFactory(
            localTools = localTools,
            conversationQueryService = mockk(),
            skillManager = mockk(),
            workspaceApplicationService = mockk(),
            workspaceQueryService = mockk(),
            mcpManager = mockk(),
            providerManager = providerManager,
            artifactStore = mockk(),
        )

        listOf(inspection, model(listOf(Modality.TEXT)), null).forEach { currentModel ->
            ToolSetRunMode.entries.forEach { mode ->
                val tools = factory.buildTools(
                    assistant = Assistant(workspaceId = null),
                    settings = settings,
                    capabilityModel = currentModel,
                    runMode = mode,
                    mcpCapabilities = TurnMcpCapabilitySnapshot(tools = emptyList()),
                )
                assertEquals(listOf("read_tool_output", "grep_tool_output", ATTACHMENT_INSPECTION_TOOL_NAME), tools.map { it.name })
            }
        }
    }
}
