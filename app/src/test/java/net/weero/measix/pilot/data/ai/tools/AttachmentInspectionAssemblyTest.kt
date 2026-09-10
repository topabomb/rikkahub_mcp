package net.weero.measix.pilot.data.ai.tools

import me.rerere.common.configuration.ConfigurationReference

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot
import net.weero.measix.pilot.data.ai.tools.local.LocalTools
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.runtime.TurnKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentInspectionAssemblyTest {
    private fun model(modalities: List<Modality>) = Model(
        id = ConfigurationReference.random(),
        modelId = "m",
        displayName = "M",
        type = ModelType.CHAT,
        inputModalities = modalities,
    )

    @Test
    fun `master and target keep inspection for native vision text and absent current model without workspace`() = runTest {
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        val providerManager = mockk<ProviderManager>()
        val localTools = mockk<LocalTools>()
        every { localTools.getTools(any(), any(), any()) } returns emptyList()
        val factory = TurnToolSetFactory(
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
            TurnKind.entries.forEach { mode ->
                val tools = factory.buildTools(
                    realmAccess = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
                    assistant = Assistant(workspaceId = null),
                    settings = Settings(providers = emptyList()),
                    configuration = net.weero.measix.pilot.test.testResolvedConfiguration(Settings(providers = emptyList())),
                    capabilityModel = currentModel,
                    inspectionModel = net.weero.measix.pilot.service.ModelExecutionSnapshot(
                        inspection,
                        net.weero.measix.pilot.service.runtime.ModelExecutionLease { error("catalog assembly cannot execute") },
                        "test", null, RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED),
                    ),
                    turnKind = mode,
                    mcpCapabilities = TurnMcpCapabilitySnapshot(tools = emptyList()),
                )
                assertEquals(listOf("read_tool_output", "grep_tool_output", ATTACHMENT_INSPECTION_TOOL_NAME), tools.map { it.name })
            }
        }
    }
}
