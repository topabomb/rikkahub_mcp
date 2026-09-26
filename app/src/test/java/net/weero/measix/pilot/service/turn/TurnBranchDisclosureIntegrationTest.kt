package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.test.testTurnContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class TurnBranchDisclosureIntegrationTest {
    @Test fun `editing the causal user permits a new START and restores state without replaying its old anchor`() =
        verifyChangedUser(selectOriginal = false)

    @Test fun `selecting another causal user variant permits a new START and preserves historical admissions`() =
        verifyChangedUser(selectOriginal = true)

    private fun verifyChangedUser(selectOriginal: Boolean) = runTest {
        val model = Model(modelId = "test")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val assistant = Assistant(enableMemory = true, streamOutput = false)
        val settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant))
        val configuration = ConfigurationResolver.resolve(UserSettingsDocument.empty().withPersonalSettings(settings),
            ConfigurationScope.Personal, EnterpriseState.Loading)
        var memory = "original memory"
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        val manager = mockk<ProviderManager>()
        every { manager.getProviderByType(providerSetting) } returns provider
        every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
        val requests = mutableListOf<List<ModelRequestMessage>>()
        coEvery { provider.generateText(providerSetting, any(), any()) } coAnswers {
            requests += secondArg<List<ModelRequestMessage>>()
            MessageChunk(id = Uuid.random().toString(), model = "test", choices = listOf(UIMessageChoice(
                index = 0, delta = null, message = UIMessage.assistant("answer"), finishReason = "stop")))
        }
        val outputs = mockk<ToolOutputStore>()
        coEvery { outputs.stageCompaction(any(), any()) } returns ToolOutputStore.StagedCompactionBatch(emptyMap(), null)
        val runner = TurnRunner(mockk<Context>(relaxed = true), manager, Json,
            mockk<AttachmentResolver>(relaxed = true), outputs, mockk<ArtifactStore>(relaxed = true))
        var snapshot = Conversation.ofId(Uuid.random(), assistant.id).toSnapshot()
        snapshot = ConversationTransition.apply(snapshot, AppendUserMessage(UIMessage.user("original question")))
        val originalUser = snapshot.nodes.single()
        if (selectOriginal) snapshot = ConversationTransition.apply(snapshot,
            EditMessageVariant(originalUser.id, UIMessage.user("alternate question")))

        suspend fun runTurn() {
            val start = TurnTransition.buildStartTurnCommand(snapshot, Uuid.random())
            snapshot = ConversationTransition.apply(snapshot, start)
            val handle = TurnHandle(snapshot.conversationId, start.epoch, start.turnId, start.assistantMessageId)
            val access = object : TurnRequestContextAccess {
                override suspend fun read() = TurnRequestHistory(snapshot, emptyMap())
                override suspend fun admit(entries: List<ConversationModelContextEntry>, admission: ConversationContextAdmission) {
                    snapshot = ConversationTransition.apply(snapshot, AdmitRequestContext(handle, entries, admission))
                }
            }
            val context = testTurnContext(settings, model, assistant).copy(disclosure = TurnDisclosureSource.capture(
                configuration, assistant, DisclosureNamespace(null, assistant.id), { configuration },
                { listOf(AssistantMemory(1, memory)) }))
            val result = runner.run(TurnRunInputs(context, handle, snapshot.currentMessages(), access,
                assistantMessageId = start.assistantMessageId,
                onCheckpoint = { snapshot = ConversationTransition.apply(snapshot, it) }, onStreamDelta = {},
                onResult = { outcome ->
                    assertTrue("Unexpected outcome: $outcome", outcome is TurnOutcome.Completed)
                    val completed = outcome as TurnOutcome.Completed
                    snapshot = ConversationTransition.apply(snapshot, FinalizeTurn(handle, completed.assistantMessage,
                        completed.status, completed.terminalReason, toolOutputCompactionPatches = completed.toolOutputCompactionPatches))
                }))
            assertTrue(result is TurnOutcome.Completed)
        }
        runTurn()
        val originalEntries = snapshot.modelContextEntries
        val originalAdmissions = snapshot.contextAdmissions
        val priorUserId = snapshot.nodes.first().currentMessage.id
        snapshot = ConversationTransition.apply(snapshot, if (selectOriginal) SelectNodeVariant(originalUser.id, 0)
            else EditMessageVariant(originalUser.id, UIMessage.user("edited without resending")))
        assertNotEquals(priorUserId, snapshot.nodes.first().currentMessage.id)
        memory = "current memory"
        snapshot = ConversationTransition.apply(snapshot, AppendUserMessage(UIMessage.user("continue")))
        runTurn()

        assertEquals(2, requests.size)
        assertTrue(requests.last().filter { it.role == MessageRole.USER }.none {
            it.toText() == if (selectOriginal) "alternate question" else "original question"
        })
        val wireText = requests.last().joinToString("\n") { it.toText() }
        assertFalse(wireText.contains("original memory"))
        assertTrue(wireText.contains("current memory"))
        assertTrue(snapshot.modelContextEntries.containsAll(originalEntries))
        assertTrue(snapshot.contextAdmissions.containsAll(originalAdmissions))
        val restored = snapshot.modelContextEntries.filter { it !in originalEntries }.mapNotNull {
            it.payload.source as? ConversationContextSource.Disclosure
        }.single()
        assertEquals(setOf(ContextAdmissionReason.BASELINE_RESTORE), restored.reasons.values.toSet())
    }
}
