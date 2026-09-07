package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.ui.UIMessageChoice
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.test.testPromptInputs
import net.weero.measix.pilot.test.turnRunInputsFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory owner 策略：START 冻结的 memory owner id 由 [resolveMemoryOwnerId] 唯一决定，
 * 且 Memory 内容不进入 System prompt——START 只冻结 memory 工具 definition，
 * 无 memory 能力时连工具 schema 也不注入。
 */
class MemoryOwnerPolicyTest {
    @Test
    fun `memory owner policy derives START owner and rejects live namespace drift`() {
        val assistant = Assistant(enableMemory = false, useGlobalMemory = false)

        assertEquals(null, resolveMemoryOwnerId(assistant))
        assertEquals(assistant.id.toString(), resolveMemoryOwnerId(assistant.copy(enableMemory = true)))
        assertEquals(
            MemoryRepository.GLOBAL_MEMORY_ID,
            resolveMemoryOwnerId(assistant.copy(enableMemory = true, useGlobalMemory = true)),
        )

        val targetStartDisabled = assistant
        assertEquals(null, resolveMemoryOwnerId(targetStartDisabled))
        val targetStartLocal = assistant.copy(enableMemory = true)
        val capturedOwner = resolveMemoryOwnerId(targetStartLocal)
        assertEquals(targetStartLocal.id.toString(), capturedOwner)
        assertEquals(null, resolveMemoryOwnerId(targetStartLocal.copy(enableMemory = false)))
        // The write-time guard compares the captured owner with a fresh policy result.
        assertFalse(resolveMemoryOwnerId(targetStartLocal.copy(useGlobalMemory = true)) == capturedOwner)
    }

    @Test
    fun `START frozen memory tool never injects Memory system text`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(providerSetting) } returns provider
        val providerMessages = slot<List<ModelRequestMessage>>()
        val params = slot<TextGenerationParams>()
        coEvery { provider.generateText(providerSetting, capture(providerMessages), capture(params)) } returns MessageChunk(
            id = "response",
            model = model.modelId,
            choices = listOf(
                UIMessageChoice(0, null, UIMessage.assistant("done"), "stop")
            ),
        )
        val assistant = Assistant(enableMemory = false, streamOutput = false)
        val loop = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )

        loop.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = assistant,
                promptInputs = testPromptInputs(),
                tools = listOf(Tool(name = "memory_tool", description = "Memory", execute = { emptyList() })),
                maxSteps = 1,
            )
        )

        // Memory 内容不进入 System；START 只冻结工具 definition。
        assertFalse(providerMessages.captured.any { it.toText().contains("**Memories**") })
        assertTrue(params.captured.tools.any { it.name == "memory_tool" })
    }

    @Test
    fun `START without memory capability omits memory tool schema`() = runTest {
        val harness = createProviderHarness()

        harness.handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                tools = emptyList(),
                maxSteps = 1,
            )
        )

        assertFalse(harness.providerMessages.captured.any { it.toText().contains("**Memories**") })
        assertFalse(harness.providerParams.captured.tools.any { it.name == "memory_tool" })
    }
}
