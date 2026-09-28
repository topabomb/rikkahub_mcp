package net.weero.measix.pilot.ui.pages.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import androidx.test.espresso.Espresso.closeSoftKeyboard
import android.accessibilityservice.AccessibilityService
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.PromptInjection
import net.weero.measix.pilot.data.model.InjectionPosition
import net.weero.measix.pilot.data.model.ConversationContextSource
import net.weero.measix.pilot.data.model.ContextAdmissionReason
import net.weero.measix.pilot.data.model.DisclosureSection
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import me.rerere.ai.core.MessageRole
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.hooks.readBooleanPreference
import net.weero.measix.pilot.ui.hooks.writeBooleanPreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real ChatPage, configuration/turn owners, Room and OpenAI adapter; only HTTP is a local mock. */
@RunWith(AndroidJUnit4::class)
class ChatContextFlowAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun firstSendAndToolContinuationExposeExternalContextAtItsActualRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previousImageLoader = coil3.SingletonImageLoader.get(context)
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val sessions = koin.get<EnterpriseSessionController>()
        val memory = koin.get<MemoryService>()
        val query = koin.get<ConversationQueryService>()
        val conversations = koin.get<ConversationApplicationService>()
        koin.get<ApplicationRecoveryCoordinator>()
        runBlocking { withTimeout(30_000) { koin.get<ApplicationRecoveryGate>().awaitReady() } }
        val oldNewChat = context.readBooleanPreference("create_new_conversation_on_start", true)
        val before = runBlocking { settings.userSettings.first { !it.init } }
        val priorConversation = runBlocking { settings.lastConversation(ConfigurationScope.Personal) }
        val priorRealm = runBlocking { sessions.readPresentation() }.selection
        val model = Model(modelId = "deepseek-context-mock", displayName = "Context UI Mock", abilities = listOf(ModelAbility.TOOL))
        val secondModel = Model(modelId = "qwen-context-mock", displayName = "Context UI Second", abilities = listOf(ModelAbility.TOOL))
        val rule = PromptInjection.ModeInjection(name = "Context UI Rule", content = "CONTEXT_RULE_ONE",
            position = InjectionPosition.BOTTOM_OF_CHAT, role = MessageRole.USER)
        val ownChild = Assistant(name = "Context UI tool child", description = "Original own directory entry", allowAsSubAssistant = true)
        val externalChild = Assistant(name = "Context UI external child", description = "Original external directory entry", allowAsSubAssistant = true)
        val assistant = Assistant(name = "Context UI integration", chatModelId = model.id,
            systemPrompt = "Initial test system", streamOutput = false, localTools = listOf(LocalToolOption.AssistantManagement),
            allowedSubAssistantIds = setOf(ownChild.id, externalChild.id), allowConversationSystemPrompt = true,
            modeInjectionIds = setOf(rule.id),
            enableMemory = true, useGlobalMemory = false)
        val server = ContextMockServer(ownChild.id.toString())
        val provider = ProviderSetting.OpenAI(name = "Context UI local mock", models = listOf(model, secondModel),
            apiKey = "local-test-only", baseUrl = "http://127.0.0.1:${server.port}/v1")
        var activity: ActivityScenario<RouteActivity>? = null
        var view: ConversationViewLease? = null
        var testFailure: Throwable? = null
        try {
            runBlocking {
                sessions.selectPersonalFixture()
                settings.updateLocal { it.copy(providers = it.providers + provider, assistants = it.assistants + listOf(assistant, ownChild, externalChild),
                    modeInjections = listOf(rule) + it.modeInjections,
                    assistantId = assistant.id, chatModelId = model.id, fastModelId = model.id,
                    titleModelId = model.id, enableSuggestion = false) }
            }
            context.writeBooleanPreference("create_new_conversation_on_start", true)
            // Earlier component tests can initialize Coil's default singleton before RouteActivity.
            // Let the real entry point install its own factory, then restore the test process owner.
            coil3.SingletonImageLoader.reset()
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java))
            var uiContext: Context = context
            activity.onActivity { uiContext = it }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
            val systemBefore = "SYSTEM_UI_BEFORE {{model_name}}"
            val systemAfter = "SYSTEM_ASYNC_AFTER {{model_name}}"
            val systemButton = uiContext.getString(R.string.chat_page_conversation_system_prompt)
            compose.onNode(hasScrollToIndexAction() and SemanticsMatcher("visible chat history") { it.boundsInRoot.left >= 0f && it.boundsInRoot.right > 0f }).performScrollToNode(hasText(systemButton))
            compose.onNodeWithText(systemButton).performClick()
            compose.onNode(hasSetTextAction() and hasText(uiContext.getString(R.string.chat_page_conversation_system_prompt_hint)))
                .performTextReplacement(systemBefore)
            closeSoftKeyboard()
            compose.onNodeWithText(uiContext.getString(R.string.chat_page_conversation_system_prompt_save)).performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("$systemButton ✎").fetchSemanticsNodes().isNotEmpty() }
            capture(context, "00-system-edited-through-chat-ui.png")
            compose.onNodeWithTag("chat_input").performTextInput("Context integration request")
            // Draft/configuration and input projections become ready independently of composition.
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("chat_send_button") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("chat_send_button").assertIsEnabled().performClick()
            // Keep advancing Compose while the click's coroutine starts the real request.
            compose.waitUntil(30_000) { server.firstRequest.count == 0L }
            val row = awaitUi(30_000) { query.conversationsOfAssistant(assistant.id).first { it.isNotEmpty() }.single() }
            val lease = runBlocking { conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, RealmAccess.Personal)) }
            view = lease
            val original = runBlocking { requireNotNull(query.aggregateSnapshot(row.id)) }
            assertEquals(systemBefore, original.header.customSystemPrompt)
            val originalOwner = original.contextAdmissions.single().owner.messageId
            // This UI selection happens after the first request was admitted, while its HTTP response is held.
            val selector = compose.onAllNodes(hasContentDescription(model.modelId) and hasClickAction())
                .fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }
            compose.onNode(SemanticsMatcher("chat model selector") { it.id == selector.id }).performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction() and !hasTestTag("chat_input")).fetchSemanticsNodes().size == 1 }
            compose.onNode(hasSetTextAction() and !hasTestTag("chat_input")).performTextInput(secondModel.displayName)
            compose.onNode(hasText(secondModel.displayName) and hasClickAction() and !hasSetTextAction()).performClick()
            awaitUi(30_000) { query.conversationUiModel(lease).first { it?.configuration?.model?.id == secondModel.id } }
            closeSoftKeyboard()
            runBlocking {
                // Other asynchronous writers use the real owners, not UI events in this test.
                conversations.updateCustomSystemPrompt(ConversationAssistantTarget(lease.commandTarget, assistant.id), systemAfter)
                settings.updateLocal { current -> current.copy(
                    modeInjections = current.modeInjections.map { if (it.id == rule.id) it.copy(content = "CONTEXT_RULE_TWO") else it },
                    assistants = current.assistants.map { if (it.id == externalChild.id)
                        it.copy(description = "External directory changed while waiting") else it },
                ) }
                val access = requireNotNull(memory.captureExecution(RealmAccess.Personal, assistant))
                memory.add(access, "External context added while the model was waiting")
            }
            capture(context, "01-first-request-waiting.png")
            server.releaseFirst.countDown()
            // assistant_manage UPDATE requires the normal user approval. The original Turn resumes.
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription(uiContext.getString(R.string.chat_message_tool_approve)).fetchSemanticsNodes().isNotEmpty()
            }
            capture(context, "02-tool-approval.png")
            compose.onNodeWithContentDescription(uiContext.getString(R.string.chat_message_tool_approve)).performScrollTo().performClick()
            compose.waitUntil(60_000) { compose.onAllNodesWithText("Context mock answer", substring = true).fetchSemanticsNodes().isNotEmpty() }
            awaitUi(30_000) { query.conversationUiModel(lease).first { it != null && it.presentation.activeTurnId == null } }
            val firstDone = runBlocking { requireNotNull(query.aggregateSnapshot(row.id)) }
            assertEquals(setOf(originalOwner), firstDone.contextAdmissions.map { it.owner.messageId }.toSet())
            assertEquals(2, firstDone.contextAdmissions.size)
            val changed = firstDone.modelContextEntries.filter { it.payload.source is ConversationContextSource.Disclosure }
            assertEquals(2, changed.size)
            assertEquals(mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE,
                DisclosureSection.SUB_ASSISTANTS to ContextAdmissionReason.EXTERNAL_STATE),
                (changed.last().payload.source as ConversationContextSource.Disclosure).reasons)
            val actualMemory = runBlocking { memory.read(requireNotNull(memory.captureExecution(RealmAccess.Personal, assistant))) }
            assertEquals(setOf("External context added while the model was waiting", "Own tool note", "Own second tool note"),
                actualMemory.map { it.content }.toSet())
            assertEquals("Own directory changed by tool", runBlocking { settings.userSettings.first() }.assistants.single { it.id == ownChild.id }.description)
            val updateLabel = uiContext.getString(R.string.context_updated_categories,
                "${uiContext.getString(R.string.context_memory)} · ${uiContext.getString(R.string.context_assistants)}")
            val updated = uiContext.getString(R.string.context_details_accessibility, updateLabel)
            compose.onNode(hasScrollToIndexAction() and SemanticsMatcher("visible chat history") { it.boundsInRoot.left >= 0f && it.boundsInRoot.right > 0f }).performScrollToNode(hasContentDescription(updated))
            compose.onAllNodesWithContentDescription(updated).assertCountEquals(1)
            compose.onNodeWithContentDescription(updated).performScrollTo().assertIsDisplayed()
            val updateMarker = projectConversationContextSummary(firstDone).messages.getValue(originalOwner).updates.single()
            val updateAdmission = firstDone.contextAdmissions.single { it.id == updateMarker.requestId }
            assertEquals(changed.last().stepId, updateMarker.stepId)
            assertEquals(updateAdmission.stepId, updateMarker.stepId)
            val labelBounds = compose.onNodeWithContentDescription(updated).fetchSemanticsNode().boundsInRoot
            val answerBounds = compose.onAllNodes(hasText("Context mock answer", substring = true) and !hasClickAction())
                .fetchSemanticsNodes().single { it.boundsInRoot.left >= 0f }.boundsInRoot
            assertTrue("The update belongs before the answer generated by its request", labelBounds.bottom <= answerBounds.top)
            capture(context, "02-chat-external-context.png")
            compose.onNodeWithContentDescription(updated).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(uiContext.getString(R.string.context_changes_title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(uiContext.getString(R.string.context_changes_title)).assertIsDisplayed()
            compose.onNodeWithText(uiContext.getString(R.string.context_system)).assertDoesNotExist()
            val updateOrdinal = firstDone.currentMessages().single { it.id == originalOwner }.parts
                .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Step>().single { it.stepId == updateMarker.stepId }.ordinal
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(uiContext.getString(R.string.context_request, updateOrdinal + 1)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(uiContext.getString(R.string.context_request, updateOrdinal + 1)).performScrollTo().assertIsDisplayed()
            val initialOrdinal = firstDone.currentMessages().single { it.id == originalOwner }.parts
                .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Step>().first().ordinal
            compose.onNodeWithText(uiContext.getString(R.string.context_request, initialOrdinal + 1)).assertDoesNotExist()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("External context added while the model was waiting", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("External context added while the model was waiting", substring = true).performScrollTo().assertIsDisplayed()
            capture(context, "03-context-changes-detail.png")
            compose.onNodeWithText("Own directory changed by tool", substring = true).assertDoesNotExist()
            compose.onNodeWithText("External directory changed while waiting", substring = true).assertExists()
            compose.onNode(hasContentDescription(uiContext.getString(R.string.update_card_close)) and
                SemanticsMatcher("visible close action") { it.boundsInRoot.left >= 0f && it.boundsInRoot.right > 0f }).performClick()
            val requests = server.mainRequests.toList()
            assertEquals(2, requests.size)
            fun messages(request: JsonObject) = request.getValue("messages").jsonArray.map { it.jsonObject }
            val initial = messages(requests.first())
            val followUp = messages(requests.last())
            assertEquals(listOf(model.modelId, model.modelId), requests.map { it.getValue("model").jsonPrimitive.content })
            assertTrue(initial.first { it["role"]?.jsonPrimitive?.content == "system" }.toString().contains("SYSTEM_UI_BEFORE"))
            assertTrue(initial.toString().contains("CONTEXT_RULE_ONE"))
            assertTrue(followUp.toString().contains("CONTEXT_RULE_ONE"))
            assertFalse(followUp.toString().contains("CONTEXT_RULE_TWO"))
            assertEquals(initial.first { it["role"]?.jsonPrimitive?.content == "system" },
                followUp.first { it["role"]?.jsonPrimitive?.content == "system" })
            val toolResults = followUp.filter { it["role"]?.jsonPrimitive?.content == "tool" }
            assertEquals(listOf("context-memory", "context-memory-two", "context-assistant"), toolResults.map { it["tool_call_id"]?.jsonPrimitive?.content })
            val resultIndex = followUp.indexOfLast { it["role"]?.jsonPrimitive?.content == "tool" }
            val disclosureIndex = followUp.indexOfFirst { it.toString().contains("External context added while the model was waiting") }
            assertTrue("Tool result must precede external context", resultIndex >= 0 && disclosureIndex > resultIndex)
            assertTrue(followUp[resultIndex]["tool_call_id"]?.jsonPrimitive?.content == "context-assistant")
            compose.onNodeWithTag("chat_input").performTextInput("Next START uses updated configuration")
            closeSoftKeyboard()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chat_send_button") and isEnabled()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("chat_send_button").performClick()
            compose.waitUntil(60_000) { compose.onAllNodesWithText("Context second answer", substring = true).fetchSemanticsNodes().isNotEmpty() }
            awaitUi(30_000) { query.conversationUiModel(lease).first { it != null && it.presentation.activeTurnId == null } }
            val nextRequest = server.mainRequests.toList().last()
            assertEquals(3, server.mainRequests.size)
            assertEquals(secondModel.modelId, nextRequest.getValue("model").jsonPrimitive.content)
            val nextMessages = messages(nextRequest)
            val nextSystem = nextMessages.first { it["role"]?.jsonPrimitive?.content == "system" }.getValue("content").jsonPrimitive.content
            assertTrue(nextSystem.contains("SYSTEM_ASYNC_AFTER"))
            assertFalse(nextSystem.contains("SYSTEM_UI_BEFORE"))
            assertTrue(nextMessages.toString().contains("CONTEXT_RULE_TWO"))
            val nextSnapshot = runBlocking { requireNotNull(query.aggregateSnapshot(row.id)) }
            assertEquals(3, nextSnapshot.contextAdmissions.size)
            assertEquals(2, nextSnapshot.contextAdmissions.map { it.owner.messageId }.distinct().size)
            assertEquals(changed, nextSnapshot.modelContextEntries.filter { it.payload.source is ConversationContextSource.Disclosure })
            assertEquals(1, projectConversationContextSummary(nextSnapshot).messages.values.count { it.updates.isNotEmpty() })
            compose.onNode(hasScrollToIndexAction() and SemanticsMatcher("visible chat history") { it.boundsInRoot.left >= 0f && it.boundsInRoot.right > 0f }).performScrollToNode(hasContentDescription(updated))
            compose.onAllNodesWithContentDescription(updated).assertCountEquals(1)
            compose.onNode(hasScrollToIndexAction() and SemanticsMatcher("visible chat history") { it.boundsInRoot.left >= 0f && it.boundsInRoot.right > 0f }).performScrollToNode(hasText("Context second answer", substring = true))
            capture(context, "04-next-start-model-system-rule.png")
            val nextOwner = nextSnapshot.contextAdmissions.last().owner.messageId
            val nextDetails = runBlocking { query.contextDetails(lease, row.id, nextOwner) }.requests.single()
            assertTrue("A new START reusing known state must not report a new change",
                nextDetails.items.none { it.isCurrentUpdate })
            val systemItem = nextDetails.items.single { ConversationContextCategory.SYSTEM in it.categories }
            val savedSystem = runBlocking { query.contextContent(lease, row.id, nextOwner, nextDetails.id, systemItem.key) }
            assertEquals(nextSystem, savedSystem.text)
            assertTrue(savedSystem.presentation.sections.any {
                it.systemOwner == ConversationContextSystemOwner.CONVERSATION && it.text?.contains("SYSTEM_ASYNC_AFTER") == true
            })
            val ruleItem = nextDetails.items.single { ConversationContextCategory.PROMPT_RULE in it.categories }
            assertEquals(rule.name, ruleItem.name)
            assertEquals("CONTEXT_RULE_TWO", runBlocking {
                query.contextContent(lease, row.id, nextOwner, nextDetails.id, ruleItem.key).text
            })
            compose.onAllNodesWithText(nextSystem).assertCountEquals(0)
            assertEquals(listOf("Context integration request", "Next START uses updated configuration"),
                nextSnapshot.currentMessages().filter { it.role == MessageRole.USER }.map { it.toText() })

            // Disable the rule through the existing chat extension picker, then start a new Turn.
            val inputMore = compose.onAllNodesWithContentDescription(uiContext.getString(R.string.more_options))
                .fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }
            compose.onNode(SemanticsMatcher("input More") { it.id == inputMore.id }).performClick()
            compose.onNode(hasText(uiContext.getString(R.string.assistant_page_tab_extensions)) and hasClickAction())
                .performScrollTo().performClick()
            compose.onNodeWithText(uiContext.getString(R.string.extension_selector_tab_mode_injections)).performClick()
            compose.onNodeWithText(rule.name).performScrollTo()
            val ruleBounds = compose.onNodeWithText(rule.name).fetchSemanticsNode().boundsInRoot
            val toggle = compose.onAllNodes(isToggleable()).fetchSemanticsNodes().single {
                it.boundsInRoot.top < ruleBounds.center.y && it.boundsInRoot.bottom > ruleBounds.center.y
            }
            compose.onNode(SemanticsMatcher("test prompt rule switch") { it.id == toggle.id }).assertIsOn().performClick()
            awaitUi(30_000) { settings.userSettings.first { state -> rule.id !in state.assistants.single { it.id == assistant.id }.modeInjectionIds } }
            capture(context, "07-rule-disabled-through-chat-ui.png")
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(10_000) { compose.onAllNodesWithText(uiContext.getString(R.string.extension_selector_tab_mode_injections)).fetchSemanticsNodes().isEmpty() }
            compose.onNode(isDialog()).performTouchInput { click(androidx.compose.ui.geometry.Offset(center.x, 24f)) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(uiContext.getString(R.string.assistant_page_tab_extensions)).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("chat_input").performTextInput("Next START has the rule disabled")
            closeSoftKeyboard()
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chat_send_button") and isEnabled()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("chat_send_button").performClick()
            compose.waitUntil(60_000) { compose.onAllNodesWithText("Context third answer", substring = true).fetchSemanticsNodes().isNotEmpty() }
            awaitUi(30_000) { query.conversationUiModel(lease).first { it != null && it.presentation.activeTurnId == null } }
            val disabledSnapshot = runBlocking { requireNotNull(query.aggregateSnapshot(row.id)) }
            assertEquals(4, server.mainRequests.size)
            assertEquals(3, disabledSnapshot.contextAdmissions.map { it.owner.messageId }.distinct().size)
            assertTrue(requireNotNull(disabledSnapshot.contextAdmissions.last().selection).ruleEntryIds.isEmpty())
            val disabledMessages = messages(server.mainRequests.toList().last()).toString()
            assertFalse(disabledMessages.contains("CONTEXT_RULE_ONE"))
            assertFalse(disabledMessages.contains("CONTEXT_RULE_TWO"))
            assertEquals(changed, disabledSnapshot.modelContextEntries.filter { it.payload.source is ConversationContextSource.Disclosure })
            assertEquals(1, projectConversationContextSummary(disabledSnapshot).messages.values.count { it.updates.isNotEmpty() })
            capture(context, "08-next-start-rule-disabled.png")

            // Regenerate from the middle USER through its own action row, preserving the first Turn.
            val repository = koin.get<ConversationRepository>()
            val beforeRegenerate = runBlocking { requireNotNull(query.aggregateSnapshot(row.id)) }
            val beforeRegenerateRoom = runBlocking { requireNotNull(repository.getConversationSnapshotById(row.id)) }
            val targetText = "Next START uses updated configuration"
            val targetIndex = beforeRegenerate.nodes.indexOfFirst {
                it.currentMessage.role == MessageRole.USER && it.currentMessage.toText() == targetText
            }
            assertTrue(targetIndex > 0)
            val retainedNodes = beforeRegenerate.nodes.take(targetIndex + 1)
            val removedNodes = beforeRegenerate.nodes.drop(targetIndex + 1)
            assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
                removedNodes.map { it.currentMessage.role })
            val removedMessageIds = removedNodes.flatMap { it.messages }.map { it.id }.toSet()
            val oldTexts = beforeRegenerate.currentMessages().map { it.toText() }.toSet()
            val warning = uiContext.getString(R.string.regenerate_confirm_message)
            clickMessageRegenerate(uiContext, targetText, oldTexts)
            compose.onNodeWithText(warning).assertIsDisplayed()
            capture(context, "09-user-regenerate-confirmation.png")
            compose.onNode(hasText(uiContext.getString(R.string.cancel)) and hasClickAction() and hasAnyAncestor(isDialog()))
                .performClick()
            compose.onNodeWithText(warning).assertDoesNotExist()
            assertEquals(beforeRegenerate, runBlocking { query.aggregateSnapshot(row.id) })
            assertEquals(beforeRegenerateRoom, runBlocking { repository.getConversationSnapshotById(row.id) })
            assertEquals(4, server.mainRequests.size)

            clickMessageRegenerate(uiContext, targetText, oldTexts)
            compose.onNodeWithText(warning).assertIsDisplayed()
            compose.onNode(hasText(uiContext.getString(R.string.confirm)) and hasClickAction() and hasAnyAncestor(isDialog()))
                .performClick()
            compose.waitUntil(60_000) { server.mainRequests.size == 5 }
            awaitUi(30_000) { query.conversationUiModel(lease).first {
                it != null && it.presentation.activeTurnId == null &&
                    it.snapshot.currentMessages().lastOrNull()?.toText() == "Context user regenerated answer"
            } }
            compose.onNodeWithText(warning).assertDoesNotExist()
            val regenerated = runBlocking { requireNotNull(query.aggregateSnapshot(row.id)) }
            assertEquals(retainedNodes, regenerated.nodes.take(retainedNodes.size))
            assertEquals(retainedNodes.size + 1, regenerated.nodes.size)
            assertTrue(regenerated.nodes.none { node -> node.messages.any { it.id in removedMessageIds } })
            assertEquals(listOf("Context integration request", targetText),
                regenerated.currentMessages().filter { it.role == MessageRole.USER }.map { it.toText() })
            val retainedAdmissions = beforeRegenerate.contextAdmissions.filter { it.owner.messageId !in removedMessageIds }
            assertEquals(2, retainedAdmissions.size)
            assertEquals(retainedAdmissions.associateBy { it.id }, regenerated.contextAdmissions
                .filter { it.id in retainedAdmissions.map { admission -> admission.id } }.associateBy { it.id })
            assertEquals(3, regenerated.contextAdmissions.size)
            assertTrue(regenerated.contextAdmissions.none { it.owner.messageId in removedMessageIds })
            assertEquals(regenerated.nodes.last().currentMessage.id, regenerated.contextAdmissions.last().owner.messageId)
            assertTrue(requireNotNull(regenerated.contextAdmissions.last().selection).ruleEntryIds.isEmpty())
            val retainedEntries = beforeRegenerate.modelContextEntries.filter {
                it.ownerMessageId !in removedMessageIds && it.anchorMessageId !in removedMessageIds
            }.associateBy { it.id }
            assertEquals(retainedEntries, regenerated.modelContextEntries.filter { it.id in retainedEntries }.associateBy { it.id })
            assertTrue(regenerated.modelContextEntries.none {
                it.ownerMessageId in removedMessageIds || it.anchorMessageId in removedMessageIds
            })
            val regeneratedEntryIds = regenerated.modelContextEntries.map { it.id }.toSet()
            assertTrue(regenerated.contextAdmissions.all { admission ->
                admission.uses.all { it.entryId in regeneratedEntryIds } &&
                    admission.selection?.let { selection -> selection.systemEntryId in regeneratedEntryIds &&
                        selection.ruleEntryIds.all { it in regeneratedEntryIds } } != false
            })
            val regeneratedRoom = runBlocking { requireNotNull(repository.getConversationSnapshotById(row.id)) }
            assertEquals(regenerated.nodes, regeneratedRoom.nodes)
            assertEquals(regenerated.modelContextEntries.associateBy { it.id }, regeneratedRoom.modelContextEntries.associateBy { it.id })
            assertEquals(regenerated.contextAdmissions.associateBy { it.id }, regeneratedRoom.contextAdmissions.associateBy { it.id })
            val regeneratedRequest = messages(server.mainRequests.toList().last()).toString()
            assertTrue(regeneratedRequest.contains(targetText))
            listOf("Context second answer", "Next START has the rule disabled", "Context third answer", "CONTEXT_RULE_TWO")
                .forEach { assertFalse("Retired content replayed: $it", regeneratedRequest.contains(it)) }
            capture(context, "10-user-regenerated-history-pruned.png")

            // ASSISTANT regeneration starts immediately and retains the previous answer as a variant.
            clickMessageRegenerate(uiContext, "Context user regenerated answer", regenerated.currentMessages().map { it.toText() }.toSet())
            compose.onNodeWithText(warning).assertDoesNotExist()
            compose.waitUntil(60_000) { server.mainRequests.size == 6 }
            awaitUi(30_000) { query.conversationUiModel(lease).first {
                it != null && it.presentation.activeTurnId == null &&
                    it.snapshot.currentMessages().lastOrNull()?.toText() == "Context assistant regenerated answer"
            } }
            val assistantRegenerated = runBlocking { requireNotNull(repository.getConversationSnapshotById(row.id)) }
            assertEquals(regenerated.nodes.dropLast(1), assistantRegenerated.nodes.dropLast(1))
            assertEquals(regenerated.nodes.last().id, assistantRegenerated.nodes.last().id)
            assertEquals(regenerated.nodes.last().messages.size + 1, assistantRegenerated.nodes.last().messages.size)
            assertTrue(assistantRegenerated.nodes.last().messages.containsAll(regenerated.nodes.last().messages))
            assertEquals("Context assistant regenerated answer", assistantRegenerated.nodes.last().currentMessage.toText())
            capture(context, "11-assistant-regenerated-without-confirmation.png")
            assertNull(server.failure)
            File(outputDirectory(context), "requests.json").writeText(
                buildJsonArray { server.mainRequests.toList().forEach(::add) }.toString(), Charsets.UTF_8,
            )
        } catch (error: Throwable) {
            testFailure = error
            try {
                capture(context, "failure.png")
                File(outputDirectory(context), "failure-semantics.txt").writeText(compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n") { root -> compose.onNode(SemanticsMatcher("root") { it.id == root.id }).printToString() })
            } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
            try {
                view?.let { lease ->
                    val facts = awaitUi(3_000) {
                        val snapshot = requireNotNull(query.aggregateSnapshot(lease.conversationId))
                        val message = snapshot.currentMessages().last { it.role == MessageRole.ASSISTANT }
                        val direct = query.contextDetails(lease, snapshot.conversationId, message.id)
                        val request = direct.requests.first { it.items.any { item -> item.isCurrentUpdate } }
                        val observed = query.observeContextDetails(lease, snapshot.conversationId, message.id, requireNotNull(request.id)).first()
                        "directRequests=${direct.requests.size}; observedOrdinals=${observed.requests.map { it.ordinal }}"
                    }
                    File(outputDirectory(context), "failure-query.txt").writeText(facts)
                }
            } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
            throw error
        } finally {
            server.releaseFirst.countDown()
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val primary = testFailure ?: cleanupFailure
                    if (primary == null) cleanupFailure = error else if (primary !== error) primary.addSuppressed(error)
                }
            }
            cleanup { activity?.close() }
            cleanup { view?.close() }
            cleanup {
                val testImageLoader = coil3.SingletonImageLoader.get(context)
                coil3.SingletonImageLoader.setUnsafe(previousImageLoader)
                if (testImageLoader !== previousImageLoader) testImageLoader.shutdown()
            }
            cleanup { runBlocking {
                val access = memory.captureExecution(RealmAccess.Personal, assistant)
                if (access != null) memory.read(access).forEach { memory.delete(access, it.id) }
                query.recentConversations(RealmAccess.Personal, assistant.id, Int.MAX_VALUE).forEach { row ->
                    val view = conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, RealmAccess.Personal))
                    try { withTimeout(30_000) { conversations.stopGeneration(view.commandTarget) }; conversations.delete(view.commandTarget) }
                    finally { view.close() }
                }
            } }
            cleanup { runBlocking {
                settings.updateLocal { it.copy(providers = it.providers.filterNot { candidate -> candidate.id == provider.id },
                    assistants = it.assistants.filterNot { candidate -> candidate.id in setOf(assistant.id, ownChild.id, externalChild.id) },
                    modeInjections = it.modeInjections.filterNot { candidate -> candidate.id == rule.id },
                    assistantId = before.assistantId, chatModelId = before.chatModelId, fastModelId = before.fastModelId,
                    titleModelId = before.titleModelId, enableSuggestion = before.enableSuggestion) }
                settings.rememberConversation(ConfigurationScope.Personal, priorConversation)
                if (priorRealm?.access is RealmAccess.Enterprise) sessions.selectEnterpriseFixture()
            } }
            cleanup { context.writeBooleanPreference("create_new_conversation_on_start", oldNewChat) }
            cleanup { server.close() }
            cleanupFailure?.let { throw it }
        }
    }

    private fun clickMessageRegenerate(context: Context, messageText: String, transcriptTexts: Set<String>) {
        val historyMatcher = hasScrollToIndexAction() and SemanticsMatcher("visible chat history") {
            it.boundsInRoot.left >= 0f && it.boundsInRoot.right > 0f
        }
        compose.onNode(historyMatcher).performScrollToNode(hasText(messageText))
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        val nodes = flatten(compose.onNode(historyMatcher, useUnmergedTree = true).fetchSemanticsNode())
        val textNode = nodes.single { hasText(messageText).matches(it) }
        val actionMatcher = hasContentDescription(context.getString(R.string.regenerate)) and hasClickAction()
        // ChatMessage lays out its body before its action row. Stop at any other transcript body,
        // so a missing action cannot accidentally click the next message's regenerate button.
        val following = nodes.drop(nodes.indexOf(textNode) + 1)
        val action = following.first { node ->
            actionMatcher.matches(node) || transcriptTexts.any { it != messageText && hasText(it).matches(node) }
        }
        check(actionMatcher.matches(action)) { "message_regenerate_action_missing: $messageText" }
        compose.onNode(SemanticsMatcher("regenerate for $messageText") { it.id == action.id }, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().performClick()
    }

    private fun <T> awaitUi(timeoutMillis: Long, action: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val pending = scope.async { withTimeout(timeoutMillis) { action() } }
        return try {
            compose.waitUntil(timeoutMillis + 5_000) { pending.isCompleted }
            runBlocking { pending.await() }
        } finally { scope.cancel() }
    }

    private fun capture(context: Context, name: String) {
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(outputDirectory(context), name).outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }

    private fun outputDirectory(context: Context): File {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        return (output?.let { File(it, "context-ui-evidence") }
            ?: File(context.getExternalFilesDir(null), "context-ui-evidence")).apply { check(isDirectory || mkdirs()) }
    }
}

private class ContextMockServer(private val childId: String) : AutoCloseable {
    private val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    val port = socket.localPort
    val firstRequest = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val mainRequests: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    @Volatile var failure: Throwable? = null
    private val worker = thread(name = "context-ui-mock", isDaemon = true) {
        try { while (!socket.isClosed) socket.accept().use(::respond) }
        catch (error: Throwable) { if (!socket.isClosed) failure = error }
    }
    private fun respond(client: Socket) {
        client.soTimeout = 30_000
        val input = client.getInputStream().buffered()
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) { val value = input.read(); check(value >= 0) { "mock_request_header_incomplete" }; if (value == 10) break; if (value != 13) bytes.write(value) }
            return bytes.toString(Charsets.US_ASCII.name())
        }
        check(line().startsWith("POST ")) { "mock_request_method" }
        val headers = mutableMapOf<String, String>()
        while (true) { val value = line(); if (value.isEmpty()) break; headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim() }
        val bytes = input.readNBytes(requireNotNull(headers["content-length"]).toInt())
        val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val main = request["tools"]?.toString()?.contains("memory_tool") == true
        if (main) mainRequests += request
        val tool = main && mainRequests.size == 1
        if (tool) { firstRequest.countDown(); check(releaseFirst.await(120, TimeUnit.SECONDS)) { "mock_first_request_not_released" } }
        val message = if (tool) buildJsonObject {
            put("role", "assistant"); put("content", JsonNull)
            putJsonArray("tool_calls") { addJsonObject {
                put("id", "context-memory"); put("type", "function")
                putJsonObject("function") { put("name", "memory_tool"); put("arguments", "{\"action\":\"create\",\"content\":\"Own tool note\"}") }
            }; addJsonObject {
                put("id", "context-memory-two"); put("type", "function")
                putJsonObject("function") { put("name", "memory_tool"); put("arguments", "{\"action\":\"create\",\"content\":\"Own second tool note\"}") }
            }; addJsonObject {
                put("id", "context-assistant"); put("type", "function")
                putJsonObject("function") { put("name", "assistant_manage"); put("arguments", buildJsonObject {
                    put("action", "UPDATE"); put("assistant_id", childId); put("description", "Own directory changed by tool")
                }.toString()) }
            } }
        } else buildJsonObject { put("role", "assistant"); put("content", if (!main) "Context UI test"
            else when (mainRequests.size) {
                2 -> "Context mock answer"
                3 -> "Context second answer"
                4 -> "Context third answer"
                5 -> "Context user regenerated answer"
                6 -> "Context assistant regenerated answer"
                else -> error("unexpected_context_request: ${mainRequests.size}")
            }) }
        val response = buildJsonObject {
            put("id", "context-local-mock"); put("object", "chat.completion"); put("created", 1); put("model", request.getValue("model"))
            putJsonArray("choices") { addJsonObject { put("index", 0); put("message", message); put("finish_reason", if (tool) "tool_calls" else "stop") } }
            putJsonObject("usage") { put("prompt_tokens", 10); put("completion_tokens", 5); put("total_tokens", 15) }
        }.toString().encodeToByteArray()
        client.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(response); flush()
        }
    }
    override fun close() { releaseFirst.countDown(); socket.close(); worker.join(5_000) }
}
