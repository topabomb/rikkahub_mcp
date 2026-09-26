package net.weero.measix.pilot.service.turn

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
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
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.test.testTurnContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/** The shared loop and Memory write owner are real; platform delegation authorization is tested at its own boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChildSharedMemoryDisclosureIntegrationTest {
    @Test fun `child writes shared memory but its summary cannot substitute for parent disclosure`() = runTest {
        val android = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(android, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val memory = MemoryRepository(database.memoryDao(), RoomDatabaseTransactionRunner(database))
            val parent = Assistant(name = "Parent", useGlobalMemory = true, enableMemory = true, streamOutput = false)
            val child = Assistant(name = "Child", useGlobalMemory = true, enableMemory = true, streamOutput = false)
            val address = parent.memoryAddress(ConfigurationScope.Personal)
            assertEquals(address, child.memoryAddress(ConfigurationScope.Personal))
            val initial = memory.add(address, "before child") {}
            val parentModel = Model(modelId = "parent-model")
            val childModel = Model(modelId = "child-model")
            val setting = ProviderSetting.OpenAI(models = listOf(parentModel, childModel))
            val settings = Settings(providers = listOf(setting), assistants = listOf(parent, child))
            val configuration = ConfigurationResolver.resolve(UserSettingsDocument.empty().withPersonalSettings(settings),
                ConfigurationScope.Personal, EnterpriseState.Loading)
            val requests = mutableMapOf("parent-model" to mutableListOf<List<ModelRequestMessage>>(),
                "child-model" to mutableListOf<List<ModelRequestMessage>>())
            val completed = mutableMapOf<String, ConversationAggregateSnapshot>()
            val provider = mockk<Provider<ProviderSetting.OpenAI>>()
            val manager = mockk<ProviderManager>()
            every { manager.getProviderByType(setting) } returns provider
            coEvery { provider.generateText(setting, any(), any()) } coAnswers {
                val model = thirdArg<TextGenerationParams>().model.modelId
                val history = requests.getValue(model)
                history += secondArg<List<ModelRequestMessage>>()
                if (history.size == 1) {
                    val name = if (model == "parent-model") "assistant_call" else "memory_tool"
                    val input = if (model == "parent-model") "{}" else
                        """{"action":"edit","id":${initial.id},"content":"written by child"}"""
                    reply(model, listOf(UIMessagePart.Tool(Uuid.random(), Uuid.random(), "$model-call", name, input)))
                } else reply(model, listOf(UIMessagePart.Text(if (model == "child-model") "Child completed." else "Parent completed.")))
            }
            val runner = TurnRunner(android, manager, Json, mockk<AttachmentResolver>(relaxed = true),
                mockk(relaxed = true), mockk<ArtifactStore>(relaxed = true))
            suspend fun run(assistant: Assistant, model: Model, tools: List<Tool>): TurnOutcome.Completed {
                var snapshot = Conversation.ofId(Uuid.random(), assistant.id)
                    .copy(messageNodes = listOf(MessageNode.of(UIMessage.user("perform task")))).toSnapshot()
                val start = TurnTransition.buildStartTurnCommand(snapshot, Uuid.random())
                snapshot = ConversationTransition.apply(snapshot, start)
                val handle = TurnHandle(snapshot.conversationId, start.epoch, start.turnId, start.assistantMessageId)
                val outcomes = mutableMapOf<ToolCallLocator, ToolExecutionStatus>()
                val access = object : TurnRequestContextAccess {
                    override suspend fun read() = TurnRequestHistory(snapshot, outcomes.toMap())
                    override suspend fun admit(entries: List<ConversationModelContextEntry>, admission: ConversationContextAdmission) {
                        snapshot = ConversationTransition.apply(snapshot, AdmitRequestContext(handle, entries, admission))
                    }
                }
                val context = testTurnContext(settings, model, assistant, tools).copy(
                    disclosure = TurnDisclosureSource.capture(configuration, assistant,
                        DisclosureNamespace(address.owner.storageId, assistant.id), readConfiguration = { configuration },
                        readMemory = { memory.read(address) }))
                val result = runner.run(TurnRunInputs(context, handle, snapshot.currentMessages(), access,
                    assistantMessageId = start.assistantMessageId,
                    onCheckpoint = { checkpoint ->
                        snapshot = ConversationTransition.apply(snapshot, checkpoint)
                        (checkpoint as? ToolExecutionCheckpoint)?.toolExecution?.let {
                            outcomes[ToolCallLocator(it.assistantMessageId, it.stepId, it.localCallId)] = it.status
                        }
                    }, onStreamDelta = {}, onResult = { result ->
                        assertTrue("Unexpected result: $result", result is TurnOutcome.Completed)
                        val final = result as TurnOutcome.Completed
                        snapshot = ConversationTransition.apply(snapshot, FinalizeTurn(handle, final.assistantMessage,
                            final.status, final.terminalReason, toolOutputCompactionPatches = final.toolOutputCompactionPatches))
                    }))
                completed[model.modelId] = snapshot
                return result as TurnOutcome.Completed
            }
            val childTools = buildMemoryTools(onCreation = { memory.add(address, it) {} },
                onUpdate = { id, text -> memory.update(address, id, text) {} }, onDelete = { memory.delete(address, it) {} })
            val delegate = Tool(name = "assistant_call", description = "Run the Child.", execute = {
                val result = run(child, childModel, childTools)
                listOf(UIMessagePart.Text(result.assistantMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }))
            })
            run(parent, parentModel, listOf(delegate))

            assertEquals(listOf(AssistantMemory(initial.id, "written by child")), memory.read(address))
            val childRequests = requests.getValue("child-model")
            val parentRequests = requests.getValue("parent-model")
            assertEquals(2, childRequests.size)
            assertEquals(2, parentRequests.size)
            assertEquals(disclosures(childRequests.first()), disclosures(childRequests.last()))
            assertEquals(1, childRequests.last().count { it.role == MessageRole.USER })
            val finalRequest = parentRequests.last()
            val disclosure = disclosures(finalRequest)
            assertEquals(2, disclosure.size)
            val update = ConversationDisclosureSnapshotService.readSections(disclosure.last())
            assertEquals(setOf(DisclosureSection.MEMORY), update.keys)
            assertEquals(Json.parseToJsonElement("""[[${initial.id},"written by child"]]"""),
                update.getValue(DisclosureSection.MEMORY).getValue("rows"))
            val delegatedResult = finalRequest.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().single()
            assertEquals("assistant_call", delegatedResult.toolName)
            assertEquals("Child completed.", (delegatedResult.output.single() as UIMessagePart.Text).text)
            assertFalse(delegatedResult.input.contains("written by child"))
            val toolIndex = finalRequest.indexOfFirst { delegatedResult in it.parts }
            val updateIndex = finalRequest.indexOfFirst { it.role == MessageRole.USER && it.toText() == disclosure.last() }
            assertTrue(updateIndex > toolIndex)
            assertEquals(1, completed.getValue("child-model").modelContextEntries.count { it.payload.source is ConversationContextSource.Disclosure })
            assertEquals(2, completed.getValue("parent-model").modelContextEntries.count { it.payload.source is ConversationContextSource.Disclosure })
        } finally { database.close() }
    }

    private fun disclosures(messages: List<ModelRequestMessage>) = messages.filter { it.role == MessageRole.USER }
        .flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>().map { it.text }
        .filter { it.startsWith("{\"type\":\"conversation_disclosure_snapshot\"") }

    private fun reply(model: String, parts: List<UIMessagePart>) = MessageChunk(id = Uuid.random().toString(), model = model,
        choices = listOf(UIMessageChoice(0, delta = null, message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
            finishReason = if (parts.any { it is UIMessagePart.Tool }) "tool_calls" else "stop")))
}
