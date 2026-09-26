package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.tools.buildMemoryTools
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.test.testTurnContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Production request admission, planner, tool loop and command reducers share the same committed history. */
class TurnRunnerDisclosureIntegrationTest {
    @Test
    fun `own writes remain tool facts and concurrent external facts wait for the whole batch across START`() = runTest {
        val model = Model(modelId = "test")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val assistant = Assistant(enableMemory = true, streamOutput = false)
        val settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant))
        val configuration = ConfigurationResolver.resolve(
            UserSettingsDocument.empty().withPersonalSettings(settings), ConfigurationScope.Personal, EnterpriseState.Loading)
        val memories = mutableMapOf(1 to "original one", 2 to "original two")
        val batchEntered = CompletableDeferred<Unit>()
        val finishBatch = CompletableDeferred<Unit>()
        val tools = buildMemoryTools(
            onCreation = { error("unexpected create") },
            onUpdate = { id, content -> memories[id] = content; AssistantMemory(id, content) },
            onDelete = { error("unexpected delete") },
        ) + Tool(name = "batch_barrier", description = "Completes the pending tool batch.", execute = {
            batchEntered.complete(Unit)
            finishBatch.await()
            listOf(UIMessagePart.Text("batch complete"))
        })
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        val manager = mockk<ProviderManager>()
        every { manager.getProviderByType(providerSetting) } returns provider
        every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
        val requests = mutableListOf<List<ModelRequestMessage>>()
        coEvery { provider.generateText(providerSetting, any(), any()) } coAnswers {
            requests += secondArg<List<ModelRequestMessage>>()
            when (requests.size) {
                1 -> reply(listOf(call("own-one", "memory_tool", """{"action":"edit","id":1,"content":"own one"}""")))
                2 -> reply(listOf(
                    call("own-two", "memory_tool", """{"action":"edit","id":2,"content":"own two"}"""),
                    call("barrier", "batch_barrier", "{}"),
                ))
                else -> reply(listOf(UIMessagePart.Text("done")))
            }
        }
        val runner = TurnRunner(mockk<Context>(relaxed = true), manager, Json,
            mockk<AttachmentResolver>(relaxed = true), mockk(relaxed = true), mockk<ArtifactStore>(relaxed = true))
        var snapshot = Conversation.ofId(Uuid.random(), assistant.id).toSnapshot()
        val outcomes = mutableMapOf<ToolCallLocator, ToolExecutionStatus>()
        var samples = 0
        fun captureContext() = testTurnContext(settings, model, assistant, tools).copy(
            disclosure = TurnDisclosureSource.capture(configuration, assistant, DisclosureNamespace(null, assistant.id),
                readConfiguration = { configuration }, readMemory = {
                    samples++
                    memories.toSortedMap().map { AssistantMemory(it.key, it.value) }
                }))
        suspend fun runTurn(question: String) {
            snapshot = ConversationTransition.apply(snapshot, AppendUserMessage(UIMessage.user(question)))
            val start = TurnTransition.buildStartTurnCommand(snapshot, Uuid.random())
            snapshot = ConversationTransition.apply(snapshot, start)
            val handle = TurnHandle(snapshot.conversationId, start.epoch, start.turnId, start.assistantMessageId)
            val access = object : TurnRequestContextAccess {
                override suspend fun read() = TurnRequestHistory(snapshot, outcomes.toMap())
                override suspend fun admit(entries: List<ConversationModelContextEntry>, admission: ConversationContextAdmission) {
                    snapshot = ConversationTransition.apply(snapshot, AdmitRequestContext(handle, entries, admission))
                }
            }
            val result = runner.run(TurnRunInputs(
                turnContext = captureContext(), handle = handle, messages = snapshot.currentMessages(),
                requestContext = access, assistantMessageId = start.assistantMessageId,
                onCheckpoint = { checkpoint ->
                    snapshot = ConversationTransition.apply(snapshot, checkpoint)
                    (checkpoint as? ToolExecutionCheckpoint)?.toolExecution?.let {
                        outcomes[ToolCallLocator(it.assistantMessageId, it.stepId, it.localCallId)] = it.status
                    }
                },
                onStreamDelta = {},
                onResult = { result ->
                    assertTrue("Unexpected turn result: $result", result is TurnOutcome.Completed)
                    val completed = result as TurnOutcome.Completed
                    snapshot = ConversationTransition.apply(snapshot, FinalizeTurn(handle, completed.assistantMessage,
                        completed.status, completed.terminalReason, toolOutputCompactionPatches = completed.toolOutputCompactionPatches))
                },
            ))
            assertTrue(result is TurnOutcome.Completed)
        }

        val firstTurn = async { runTurn("remember these changes") }
        batchEntered.await()
        assertEquals(2, requests.size)
        assertEquals(2, samples)
        assertEquals("own one", memories[1])
        assertEquals("own two", memories[2])
        assertEquals(2, outcomes.values.count { it == ToolExecutionStatus.COMPLETED })
        assertEquals(1, outcomes.values.count { it == ToolExecutionStatus.STARTED })
        assertEquals(1, disclosures(requests[0]).size)
        assertEquals(disclosures(requests[0]), disclosures(requests[1]))
        assertEquals(1, requests[1].count { it.role == MessageRole.USER })
        // A different session commits while the second call is still running. No request can observe a partial batch.
        memories[1] = "external one"
        assertEquals(2, requests.size)
        finishBatch.complete(Unit)
        firstTurn.await()

        assertEquals(3, requests.size)
        val updated = requests[2]
        val disclosureTexts = disclosures(updated)
        assertEquals(2, disclosureTexts.size)
        val changed = ConversationDisclosureSnapshotService.readSections(disclosureTexts.last())
        assertEquals(setOf(DisclosureSection.MEMORY), changed.keys)
        assertEquals(Json.parseToJsonElement("""[[1,"external one"],[2,"own two"]]"""),
            changed.getValue(DisclosureSection.MEMORY).getValue("rows"))
        val externalIndex = updated.indexOfFirst { it.role == MessageRole.USER && it.toText() == disclosureTexts.last() }
        val toolsBeforeExternal = updated.take(externalIndex).flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(listOf("own-one", "own-two", "barrier"), toolsBeforeExternal.map { it.providerCallId })
        assertTrue(toolsBeforeExternal.all { it.hasReplayResult })
        assertTrue(updated.drop(externalIndex).flatMap { it.parts }.none { it is UIMessagePart.Tool })
        assertEquals(3, samples)
        assertEquals(2, snapshot.modelContextEntries.count { it.payload.source is ConversationContextSource.Disclosure })
        assertEquals(1, requests.take(3).map { it.single { message -> message.role == MessageRole.SYSTEM }.toText() }.distinct().size)

        runTurn("continue")
        assertEquals(4, requests.size)
        assertEquals(4, samples)
        assertEquals(disclosureTexts, disclosures(requests.last()))
        assertEquals(2, snapshot.modelContextEntries.count { it.payload.source is ConversationContextSource.Disclosure })
        assertEquals(4, snapshot.contextAdmissions.size)
    }

    private fun disclosures(messages: List<ModelRequestMessage>): List<String> = messages
        .filter { it.role == MessageRole.USER }.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>()
        .map { it.text }.filter { it.startsWith("{\"type\":\"conversation_disclosure_snapshot\"") }

    private fun call(id: String, name: String, input: String) = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = id, toolName = name, input = input)

    private fun reply(parts: List<UIMessagePart>) = MessageChunk(id = Uuid.random().toString(), model = "test",
        choices = listOf(UIMessageChoice(index = 0, delta = null,
            message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
            finishReason = if (parts.any { it is UIMessagePart.Tool }) "tool_calls" else "stop")))
}
