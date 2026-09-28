package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import me.rerere.ai.core.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.tools.buildMemoryTools
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.*
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.test.testTurnContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/** Real tool/checkpoint/admission loop, application Fork and the fork's next START share copied facts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForkDisclosureReplayTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `forked successful write is already known and an external reversal is still notified`() = runTest {
        for (reverse in listOf(false, true)) {
            val f = Fixture()
            f.runTurn("edit memory")
            assertEquals("B", f.memory)
            assertEquals(1, f.disclosures().size)
            assertEquals(ToolExecutionStatus.COMPLETED, f.outcomes.values.single())
            f.snapshot = fork(f.snapshot)
            f.outcomes.clear() // Fork does not manufacture new local executions for historical calls.
            if (reverse) f.memory = "A"
            f.runTurn("continue the fork")
            val entries = f.disclosures()
            assertEquals(if (reverse) 2 else 1, entries.size)
            if (reverse) {
                assertEquals(mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE),
                    (entries.last().payload.source as ConversationContextSource.Disclosure).reasons)
                val text = (entries.last().payload.body as ConversationContextBody.Inline).text
                assertEquals(Json.parseToJsonElement("[[1,\"A\"]]"),
                    ConversationDisclosureSnapshotService.readSections(text).getValue(DisclosureSection.MEMORY)["rows"])
                val sent = f.requests.last()
                // A new START prefixes its real USER input with a separate context Text part.
                // The whole USER container therefore also includes the user's question.
                val notification = sent.indexOfLast { message -> message.role == MessageRole.USER &&
                    message.parts.any { it is UIMessagePart.Text && it.text == text } }
                assertTrue("The external state must occur in a USER container", notification >= 0)
                assertEquals(listOf(text, "continue the fork"),
                    sent[notification].parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
                val toolsBefore = sent.take(notification).flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
                assertEquals(listOf("write"), toolsBefore.map { it.providerCallId })
                assertTrue(toolsBefore.all { it.resultStatus == ToolResultStatus.COMPLETED })
                assertTrue(sent.drop(notification).flatMap { it.parts }.none { it is UIMessagePart.Tool })
            }
        }
    }

    @Test fun `forked ambiguous or compacted effects restore while typed denial and foreign identities do not apply`() = runTest {
        for (variant in listOf("failed", "unknown", "denied", "untrusted", "foreign", "compacted", "contradiction", "tracked-rejection")) {
            val f = Fixture()
            f.runTurn("edit memory")
            f.snapshot = fork(f.snapshot)
            f.outcomes.clear()
            val sourceMessage = f.snapshot.nodes.last().currentMessage
            val tool = sourceMessage.parts.filterIsInstance<UIMessagePart.Tool>().single()
            val altered = when (variant) {
                "failed", "tracked-rejection" -> tool.copy(resultStatus = ToolResultStatus.FAILED)
                "unknown" -> tool.copy(resultStatus = ToolResultStatus.UNKNOWN)
                "denied" -> tool.copy(resultStatus = ToolResultStatus.DENIED,
                    interactionState = ToolInteractionState.Denied("user_denied"))
                "compacted" -> tool.copy(output = listOf(UIMessagePart.Text("[archived tool output]")))
                else -> tool
            }
            f.snapshot = f.snapshot.copy(nodes = f.snapshot.nodes.map { node ->
                if (node.currentMessage.id != sourceMessage.id) node else node.copy(messages = node.messages.map { message ->
                    if (message.id != sourceMessage.id) message else message.copy(parts = message.parts.map { if (it == tool) altered else it })
                })
            }, contextAdmissions = f.snapshot.contextAdmissions.map { admission ->
                admission.copy(selection = admission.selection?.let { selection -> when (variant) {
                    "untrusted" -> selection.copy(builtinTools = emptyMap())
                    "foreign" -> selection.copy(disclosureNamespace = requireNotNull(selection.disclosureNamespace)
                        .copy(memoryOwner = ConfigurationReference.random().toString()))
                    else -> selection
                } })
            })
            if (variant == "contradiction") f.outcomes[ToolCallLocator(sourceMessage.id, tool.stepId, tool.localCallId)] = ToolExecutionStatus.FAILED
            if (variant == "tracked-rejection") f.tracked = setOf(sourceMessage.id)
            f.memory = "A"
            f.runTurn("continue after $variant")
            val requiresRestore = variant in setOf("failed", "unknown", "compacted", "contradiction")
            assertEquals(variant, if (requiresRestore) 2 else 1, f.disclosures().size)
            if (requiresRestore) assertEquals(variant,
                mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.BASELINE_RESTORE),
                (f.disclosures().last().payload.source as ConversationContextSource.Disclosure).reasons)
        }
    }

    private suspend fun TestScope.fork(snapshot: ConversationAggregateSnapshot): ConversationAggregateSnapshot {
        val repository = mockk<ConversationRepository>()
        val appScope = AppScope(StandardTestDispatcher(testScheduler))
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val sessions = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()))
        sessions.recover()
        registry.registerSnapshot(snapshot)
        val created = slot<ConversationAggregateSnapshot>()
        coEvery { repository.getChildConversationIds(snapshot.conversationId) } returns emptyList()
        coEvery { repository.getChildConversationSnapshots(snapshot.conversationId) } returns emptyList()
        coEvery { repository.existsConversationById(any()) } returns false
        coEvery { repository.getRootConversationTitles(snapshot.header.scope, snapshot.header.assistantId) } returns emptyList()
        coEvery { repository.insertConversationTree(capture(created), any()) } returns Unit
        val lifecycle = mockk<SubAssistantLifecycle>()
        coEvery { lifecycle.requireClosedRunsBeforeTreeMutation(snapshot) } returns snapshot
        val artifacts = mockk<ArtifactStore>(relaxed = true)
        val application = ConversationApplicationService(
            settingsStore = mockk(relaxed = true), conversationRepo = repository, folderRepository = mockk(),
            runtimeRegistry = registry, commandCoordinator = coordinator, recoveryGate = gate,
            subAssistantLifecycle = lifecycle, sideEffects = mockk(), artifactStore = artifacts,
            artifactUseCase = mockk(), turnFinalizer = TurnFinalizer(repository, registry, coordinator, Json), json = Json,
            toolArtifactRewriter = ToolArtifactRewriter(temporary.newFolder(), artifacts),
            titleCoordinator = net.weero.measix.pilot.service.ConversationTitleCoordinator(), sessions = sessions, subAssistantRunGate = mockk(),
        )
        try {
            val selected = sessions.observeSelectedRealmSelection().first { it != null }!!
            val view = ConversationViewLease(snapshot.conversationId, selected.access, selected.revision) {}
            try { application.forkAtMessage(view.commandTarget, snapshot.nodes.last().currentMessage.id) }
            finally { view.close() }
            return created.captured.also { copy ->
                assertNotEquals(snapshot.conversationId, copy.conversationId)
                assertEquals(snapshot.contextAdmissions.size, copy.contextAdmissions.size)
                assertEquals(snapshot.nodes.last().currentMessage.parts, copy.nodes.last().currentMessage.parts)
            }
        } finally { appScope.cancel() }
    }

    private class Fixture {
        val model = Model(modelId = "test")
        val setting = ProviderSetting.OpenAI(models = listOf(model))
        val assistant = Assistant(enableMemory = true, streamOutput = false)
        val settings = Settings(providers = listOf(setting), assistants = listOf(assistant))
        val configuration = ConfigurationResolver.resolve(UserSettingsDocument.empty().withPersonalSettings(settings),
            ConfigurationScope.Personal, EnterpriseState.Loading)
        var memory = "A"
        val tools = buildMemoryTools(onCreation = { error("unexpected create") },
            onUpdate = { id, content -> memory = content; AssistantMemory(id, content) }, onDelete = { error("unexpected delete") })
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        val manager = mockk<ProviderManager>()
        val requests = mutableListOf<List<ModelRequestMessage>>()
        val outcomes = mutableMapOf<ToolCallLocator, ToolExecutionStatus>()
        var tracked = emptySet<Uuid>()
        var snapshot = Conversation.ofId(Uuid.random(), assistant.id).toSnapshot()
        val runner: TurnRunner
        init {
            every { manager.getProviderByType(setting) } returns provider
            every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
            coEvery { provider.generateText(setting, any(), any()) } coAnswers {
                requests += secondArg<List<ModelRequestMessage>>()
                val parts = if (requests.size == 1) listOf(UIMessagePart.Tool(Uuid.random(), Uuid.random(), "write",
                    "memory_tool", "{\"action\":\"edit\",\"id\":1,\"content\":\"B\"}")) else listOf(UIMessagePart.Text("done"))
                MessageChunk(id = Uuid.random().toString(), model = "test", choices = listOf(UIMessageChoice(
                    index = 0, delta = null, message = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                    finishReason = if (requests.size == 1) "tool_calls" else "stop")))
            }
            runner = TurnRunner(mockk<Context>(relaxed = true), manager, Json, mockk(relaxed = true),
                mockk(relaxed = true), mockk<ArtifactStore>(relaxed = true))
        }
        fun disclosures() = snapshot.modelContextEntries.filter { it.payload.source is ConversationContextSource.Disclosure }

        suspend fun runTurn(question: String) {
            snapshot = ConversationTransition.apply(snapshot, AppendUserMessage(UIMessage.user(question)))
            val start = TurnTransition.buildStartTurnCommand(snapshot, Uuid.random())
            snapshot = ConversationTransition.apply(snapshot, start)
            val handle = TurnHandle(snapshot.conversationId, start.epoch, start.turnId, start.assistantMessageId)
            val access = object : TurnRequestContextAccess {
                override suspend fun read() = TurnRequestHistory(snapshot, outcomes.toMap(), tracked + outcomes.keys.map { it.assistantMessageId })
                override suspend fun admit(entries: List<ConversationModelContextEntry>, admission: ConversationContextAdmission) {
                    snapshot = ConversationTransition.apply(snapshot, AdmitRequestContext(handle, entries, admission))
                }
            }
            val context = testTurnContext(settings, model, assistant, tools).copy(disclosure = TurnDisclosureSource.capture(
                configuration, assistant, DisclosureNamespace(null, assistant.id), readConfiguration = { configuration },
                readMemory = { listOf(AssistantMemory(1, memory)) }))
            val result = runner.run(TurnRunInputs(context, handle, snapshot.currentMessages(), requestContext = access,
                assistantMessageId = start.assistantMessageId, onStreamDelta = {}, onCheckpoint = { command ->
                    snapshot = ConversationTransition.apply(snapshot, command)
                    (command as? ToolExecutionCheckpoint)?.toolExecution?.let {
                        outcomes[ToolCallLocator(it.assistantMessageId, it.stepId, it.localCallId)] = it.status
                    }
                }, onResult = { result ->
                    assertTrue("Unexpected result: $result", result is TurnOutcome.Completed)
                    val completed = result as TurnOutcome.Completed
                    snapshot = ConversationTransition.apply(snapshot, FinalizeTurn(handle, completed.assistantMessage,
                        completed.status, completed.terminalReason, toolOutputCompactionPatches = completed.toolOutputCompactionPatches))
                }))
            assertTrue(result is TurnOutcome.Completed)
        }
    }
}
