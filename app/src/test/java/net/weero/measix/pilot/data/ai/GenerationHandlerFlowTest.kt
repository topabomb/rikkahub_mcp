package net.weero.measix.pilot.data.ai

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerFlowTest {
    @Test
    fun `metadata emitted from child job remains flow transparent`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        every {
            providerManager.getProviderByType(any<ProviderSetting.OpenAI>())
        } returns provider
        val handler = GenerationHandler(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "provider-call-id",
                    toolName = "metadata_tool",
                    input = "{}",
                )
            ),
        )
        val tool = Tool(
            name = "metadata_tool",
            description = "Reports metadata from a cancellable child run.",
            contextualExecute = {
                val childJob = Job(currentCoroutineContext()[Job])
                try {
                    withContext(childJob) {
                        reportMetadata(
                            buildJsonObject { put("phase", JsonPrimitive("running")) },
                            true,
                        )
                    }
                } finally {
                    childJob.cancel()
                }
                listOf(UIMessagePart.Text("ok"))
            },
            execute = { emptyList() },
        )

        val chunks = handler.generateText(
            settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
            model = model,
            messages = listOf(message),
            assistant = assistant,
            tools = listOf(tool),
            maxSteps = 1,
        ).toList()

        assertTrue(chunks.any { it == GenerationChunk.Phase("tool_executing", "metadata_tool") })
        assertTrue(chunks.any { it == GenerationChunk.Checkpoint(CheckpointKind.TOOL_STATE_CHANGED) })
        val finalTool = chunks.filterIsInstance<GenerationChunk.Messages>()
            .last().messages.last().getTools().single()
        assertEquals("running", finalTool.metadata?.get("phase")?.jsonPrimitive?.content)
        assertEquals("ok", (finalTool.output.single() as UIMessagePart.Text).text)
    }
}
