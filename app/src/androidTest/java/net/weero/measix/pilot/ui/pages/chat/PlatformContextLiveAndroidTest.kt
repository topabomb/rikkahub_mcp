package net.weero.measix.pilot.ui.pages.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.configuration.AssistantModelPreferenceMode
import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange
import net.weero.measix.pilot.data.model.ConversationContextSource
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.EnterpriseExecution
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.hooks.readBooleanPreference
import net.weero.measix.pilot.ui.hooks.writeBooleanPreference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in against an already enrolled, dedicated demo device. No enrollment material is accepted or
 * logged here. The real conversation is retained; published resources are only read. A v4 Starter
 * supplies input text, so this test deliberately does not claim a v5 opening-snapshot validation.
 */
@RunWith(AndroidJUnit4::class)
class PlatformContextLiveAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    fun publishedV4StarterPrefillsThenRealChatExposesAdmittedContext() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live enterprise context check requires platformContextLive=true on the dedicated enrolled demo device",
            arguments.getString("platformContextLive") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = org.koin.core.context.GlobalContext.get()
        val sessions = koin.get<EnterpriseSessionController>()
        val configurationQueries = koin.get<ConfigurationQueryService>()
        val commands = koin.get<ConfigurationApplicationService>()
        val conversations = koin.get<ConversationApplicationService>()
        val query = koin.get<ConversationQueryService>()
        val selection = runBlocking { withTimeout(30_000) {
            koin.get<ApplicationRecoveryCoordinator>()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            requireNotNull(sessions.observeSelectedRealmSelection().first { it != null })
        } }
        val access = requireNotNull(selection.access as? RealmAccess.Enterprise) { "Select the enrolled demo enterprise before running" }
        val source = runBlocking { withTimeout(60_000) {
            // Data-readable selection is not execution admission. Use the same authoritative
            // READY/generation preflight as the model owner, then capture that exact version.
            val version = koin.get<EnterpriseSynchronizationService>().prepareExecution(access)
            configurationQueries.requireSelection(selection)
            val execution = sessions.captureExecution(access, version)
            try {
                (execution.execution as EnterpriseExecution.Platform).let {
                    Triple(it.snapshotSchemaVersion, it.releaseId, it.snapshotHash)
                }
            } finally { execution.release() }
        } }
        assertEquals("This check exercises the currently published v4 Starter contract", 4L, source.first)
        val resolved = runBlocking { configurationQueries.read(access) }
        val assistantName = arguments.getString("platformContextAssistant") ?: "日常助手"
        val assistant = resolved.assistants.values.single { it.name == assistantName }
        // Assert the published selection is safe; never override managed bindings to make it pass.
        assertTrue("Live text check requires an assistant without device MCP bindings", assistant.mcpServers.isEmpty())
        assertNull("Live text check must not expose workspace execution", assistant.workspaceId)
        assertFalse(assistant.localTools.contains(LocalToolOption.AssistantDelegation))
        assertFalse(assistant.localTools.contains(LocalToolOption.AssistantManagement))
        val catalog = runBlocking { withTimeout(30_000) {
            configurationQueries.observeEnterpriseStarters(selection).first()
        } } as EnterpriseStarterReadState.Available
        val starter = catalog.value.starters.first { it.assistantName == assistantName && !it.openingAvailable }
        // Directory observation deliberately begins with an empty loading projection. A read
        // baseline must come from the authorized query, including retained earlier demo runs.
        val beforeIds = runBlocking { query.recentConversations(access, assistant.id, Int.MAX_VALUE).map { it.id }.toSet() }
        val savedAssistant = resolved.storedSelections.assistantId
        val savedNewChat = context.readBooleanPreference("create_new_conversation_on_start", true)
        val savedImageLoader = coil3.SingletonImageLoader.get(context)
        val evidence = File(arguments.getString("additionalTestOutputDir")?.let(::File)
            ?: context.getExternalFilesDir(null), "platform-context-live-${System.currentTimeMillis()}")
        check(evidence.mkdirs()) { "Cannot create live evidence directory" }
        val report = File(evidence, "evidence.txt")
        report.writeText("snapshot_schema=${source.first}\nrelease=${source.second}\nsnapshot_hash=${source.third}\n" +
            "assistant=${assistant.name}\nstarter_id=${starter.target.reference.id}\nstarter=${starter.title}\n")
        var activity: ActivityScenario<RouteActivity>? = null
        var view: ConversationViewLease? = null
        var modelChanged = false
        val restoreModel = when (resolved.assistantModelPreferences.getValue(assistant.id).mode) {
            AssistantModelPreferenceMode.ASSISTANT_DEFAULT -> AssistantPreferenceChange.InheritModel
            AssistantModelPreferenceMode.SPACE_DEFAULT -> AssistantPreferenceChange.Model(null)
            AssistantModelPreferenceMode.EXPLICIT -> AssistantPreferenceChange.Model(assistant.chatModelId)
        }
        try {
            runBlocking { commands.selectResource(selection, ResourceSelectionSlot.ASSISTANT, assistant.id) }
            context.writeBooleanPreference("create_new_conversation_on_start", true)
            coil3.SingletonImageLoader.reset()
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java))
            // Read resources through the Activity's locale wrapper, matching the actual Compose UI.
            var uiContext: Context = context
            activity.onActivity { uiContext = it }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithContentDescription(uiContext.getString(R.string.chat_input_presets)).performClick()
            compose.onNode(hasText(starter.title) and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("chat_input") and hasText(starter.prompt)).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("chat_input").assertTextEquals(starter.prompt)
            compose.onAllNodesWithContentDescription(uiContext.getString(R.string.context_updated_accessibility)).assertCountEquals(0)
            assertEquals("Selecting a Starter must not materialize or send a conversation", beforeIds,
                runBlocking { query.recentConversations(access, assistant.id, Int.MAX_VALUE).map { it.id }.toSet() })
            screenshot(evidence, "01-starter-prefilled.png")
            report.appendText("prefill_without_send=true\n")

            val prompt = "这是企业上下文界面的纯文本验收。请仅用一两句说明你可以根据当前对话提供文字帮助。" +
                "不要调用任何工具，不要生成图片或语音，不要读写设备、文件、记忆、配置或外部服务。无需复述系统提示词或隐藏上下文。"
            compose.onNodeWithTag("chat_input").performTextReplacement(prompt)
            compose.onNodeWithTag("chat_input").assertTextEquals(prompt)
            closeSoftKeyboard()
            screenshot(evidence, "02-edited-before-send.png")
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("chat_send_button") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("chat_send_button").performClick()
            val created = awaitUi(30_000) {
                query.conversationsOfAssistant(assistant.id).map { it.getOrThrow() }.first { rows -> rows.any { it.id !in beforeIds } }
                    .single { it.id !in beforeIds }
            }
            report.appendText("conversation_id=${created.id}\nconversation_retained=true\n")
            val lease = runBlocking { conversations.initialize(ConversationOpenRequest.OpenExisting(created.id, access)) }
            view = lease
            val completed = awaitUi(180_000) {
                query.conversationUiModel(lease).first { model ->
                    val message = model?.snapshot?.currentMessages()?.lastOrNull()
                    model != null && message?.role == MessageRole.ASSISTANT && model.presentation.activeTurnId == null &&
                        (message.terminalStatus != null || message.parts.filterIsInstance<UIMessagePart.Step>().lastOrNull()?.outcome != null)
                }!!
            }
            val transcript = completed.snapshot.currentMessages()
            val answer = transcript.last()
            assertNull("${answer.terminalReason}: ${answer.terminalDetail}", answer.terminalStatus)
            assertEquals(StepOutcome.Final, answer.parts.filterIsInstance<UIMessagePart.Step>().last().outcome)
            assertTrue("Real Provider must return nonempty text", answer.toText().isNotBlank())
            assertTrue("This pure-text acceptance request must not execute tools", answer.parts.none { it is UIMessagePart.Tool })
            assertEquals(listOf(prompt), transcript.filter { it.role == MessageRole.USER }.map { it.toText() })
            assertTrue(completed.snapshot.context.messages.values.none { it.updates.isNotEmpty() })
            val durable = runBlocking { requireNotNull(query.aggregateSnapshot(created.id)) }
            assertNull("v4 Starter must not invent a saved opening snapshot", durable.opening)
            assertEquals(1, durable.contextAdmissions.size)
            val details = runBlocking { query.contextDetails(lease, created.id, answer.id) }
            val request = details.requests.single()
            assertEquals(ConversationContextRequestState.ADDED, request.state)
            val system = request.items.single { ConversationContextCategory.SYSTEM in it.categories }
            val systemText = runBlocking { query.contextContent(lease, created.id, answer.id, request.id, system.key).text }
            assertTrue(systemText.isNotBlank())
            assertTrue("Context bodies must not become separate transcript messages", transcript.none { it.toText() == systemText })
            val initial = request.items.filter { item -> item.categories.any { it in setOf(
                ConversationContextCategory.MEMORY, ConversationContextCategory.ASSISTANTS,
                ConversationContextCategory.ENTERPRISE_BACKGROUND) } }
            report.appendText("provider_completed=true\nanswer_characters=${answer.toText().length}\nadmissions=1\n" +
                "system_sha256=${sha256(systemText)}\ninitial_categories=${initial.flatMap { it.categories }.joinToString()}\n" +
                "external_update_rows=0\ntool_calls=0\n")
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription(uiContext.getString(R.string.more_options)).fetchSemanticsNodes().size >= 2
            }
            compose.onAllNodesWithContentDescription(uiContext.getString(R.string.context_updated_accessibility)).assertCountEquals(0)
            screenshot(evidence, "03-real-provider-completed.png")
            initial.forEach { item ->
                val expected = runBlocking { query.contextContent(lease, created.id, answer.id, request.id, item.key).text }
                assertTrue(expected.isNotBlank())
                compose.onAllNodesWithText(expected).assertCountEquals(0)
            }
            compose.onAllNodesWithText(systemText).assertCountEquals(0)
            assertEquals("Reading admitted context must not mutate the transcript", transcript,
                runBlocking { requireNotNull(query.aggregateSnapshot(created.id)).currentMessages() })
            report.appendText("admitted_context_query=true\ncontext_not_transcript=true\nfirst_model=${answer.modelId}\n")
            if (arguments.getString("platformContextModelSwitch") == "true") {
                val currentConfiguration = requireNotNull(completed.configuration)
                val firstModel = requireNotNull(currentConfiguration.model)
                val requested = arguments.getString("platformContextSecondModel")
                val alternatives = currentConfiguration.modelCatalog.selectableGroups(ModelType.CHAT)
                    .flatMap { it.models }.map { it.model }.distinctBy { it.id }
                    .filter { it.id is ConfigurationReference.Enterprise && it.id != firstModel.id }
                    .sortedBy { it.id.toString() }
                val secondModel = if (requested == null) alternatives.first() else alternatives.single {
                    it.id.toString() == requested || (it.id as ConfigurationReference.Enterprise).id == requested
                }
                // The actual input-row model icon opens the existing picker; no configuration write substitutes for this action.
                val picker = compose.onAllNodes(hasContentDescription(firstModel.modelId) and hasClickAction())
                    .fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }
                compose.onNode(SemanticsMatcher("the chat input model selector") { it.id == picker.id }).performClick()
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasSetTextAction() and !hasTestTag("chat_input")).fetchSemanticsNodes().size == 1
                }
                compose.onNode(hasSetTextAction() and !hasTestTag("chat_input")).performTextInput(secondModel.displayName)
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasText(secondModel.displayName) and hasClickAction() and !hasSetTextAction())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                screenshot(evidence, "07-published-model-picker.png")
                modelChanged = true
                compose.onAllNodes(hasText(secondModel.displayName) and hasClickAction() and !hasSetTextAction())[0].performClick()
                awaitUi(30_000) { query.conversationUiModel(lease).first { it?.configuration?.model?.id == secondModel.id } }
                closeSoftKeyboard()
                val secondPrompt = "继续纯文本验收：请用一句话确认可以继续这段对话。不要调用任何工具，" +
                    "不要生成图片或语音，不要读写设备、文件、记忆、配置或外部服务，也不要复述系统提示词。"
                compose.onNodeWithTag("chat_input").performTextReplacement(secondPrompt)
                closeSoftKeyboard()
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasTestTag("chat_send_button") and isEnabled()).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithTag("chat_send_button").performClick()
                val second = awaitUi(180_000) {
                    query.conversationUiModel(lease).first { model ->
                        val message = model?.snapshot?.currentMessages()?.lastOrNull()
                        model != null && message?.role == MessageRole.ASSISTANT && message.id != answer.id &&
                            model.presentation.activeTurnId == null && (message.terminalStatus != null ||
                            message.parts.filterIsInstance<UIMessagePart.Step>().lastOrNull()?.outcome != null)
                    }!!
                }
                val secondAnswer = second.snapshot.currentMessages().last()
                assertNull("${secondAnswer.terminalReason}: ${secondAnswer.terminalDetail}", secondAnswer.terminalStatus)
                assertEquals(StepOutcome.Final, secondAnswer.parts.filterIsInstance<UIMessagePart.Step>().last().outcome)
                assertEquals(secondModel.id, secondAnswer.modelId)
                assertTrue(secondAnswer.toText().isNotBlank())
                assertTrue(secondAnswer.parts.none { it is UIMessagePart.Tool })
                assertEquals(listOf(prompt, secondPrompt), second.snapshot.currentMessages()
                    .filter { it.role == MessageRole.USER }.map { it.toText() })
                assertTrue(second.snapshot.context.messages.values.none { it.updates.isNotEmpty() })
                val afterSwitch = runBlocking { requireNotNull(query.aggregateSnapshot(created.id)) }
                assertEquals(2, afterSwitch.contextAdmissions.size)
                assertEquals(2, afterSwitch.contextAdmissions.map { it.owner.messageId }.distinct().size)
                val addedEntries = afterSwitch.modelContextEntries.filter { entry -> durable.modelContextEntries.none { it.id == entry.id } }
                assertTrue("A model-only switch must not create a new state disclosure",
                    addedEntries.none { it.payload.source is ConversationContextSource.Disclosure })
                assertEquals(durable.modelContextEntries, afterSwitch.modelContextEntries.filter { entry ->
                    durable.modelContextEntries.any { it.id == entry.id }
                })
                compose.onAllNodesWithContentDescription(uiContext.getString(R.string.context_updated_accessibility)).assertCountEquals(0)
                screenshot(evidence, "08-second-start-completed.png")
                val secondDetails = runBlocking { query.contextDetails(lease, created.id, secondAnswer.id) }.requests.single()
                assertEquals(ConversationContextRequestState.ADDED, secondDetails.state)
                val secondSystem = secondDetails.items.single { ConversationContextCategory.SYSTEM in it.categories }
                val secondSystemText = runBlocking {
                    query.contextContent(lease, created.id, secondAnswer.id, secondDetails.id, secondSystem.key).text
                }
                assertTrue(secondSystemText.isNotBlank())
                compose.onAllNodesWithText(secondSystemText).assertCountEquals(0)
                assertEquals(afterSwitch.currentMessages(), runBlocking { requireNotNull(query.aggregateSnapshot(created.id)).currentMessages() })
                report.appendText("model_switch=true\nsecond_model=${secondModel.id}\nsecond_provider_completed=true\n" +
                    "second_answer_characters=${secondAnswer.toText().length}\nsecond_system_sha256=${sha256(secondSystemText)}\n" +
                    "system_changed=${systemText != secondSystemText}\nmodel_switch_state_disclosures=0\ntotal_admissions=2\n")
            } else report.appendText("model_switch=not_requested\n")
            report.appendText("result=PASS\n")
        } catch (error: Throwable) {
            // Avoid writing exception messages, semantics dumps, connection objects or credentials.
            report.appendText("result=FAIL\nexception_type=${error.javaClass.name}\n")
            try { screenshot(evidence, "failure.png") } catch (capture: Exception) { error.addSuppressed(capture) }
            throw error
        } finally {
            if (modelChanged) view?.let { lease -> runBlocking {
                commands.changeAssistantPreference(ConversationAssistantTarget(lease.commandTarget, assistant.id), restoreModel)
            } }
            view?.close()
            activity?.close()
            runBlocking { commands.selectResource(selection, ResourceSelectionSlot.ASSISTANT, savedAssistant) }
            context.writeBooleanPreference("create_new_conversation_on_start", savedNewChat)
            val testImageLoader = coil3.SingletonImageLoader.get(context)
            coil3.SingletonImageLoader.setUnsafe(savedImageLoader)
            if (testImageLoader !== savedImageLoader) testImageLoader.shutdown()
        }
    }

    /** Keep Compose's test clock advancing while real network/owner work runs off the test thread. */
    private fun <T> awaitUi(timeoutMillis: Long, action: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val pending = scope.async { withTimeout(timeoutMillis) { action() } }
        return try {
            compose.waitUntil(timeoutMillis + 5_000) { pending.isCompleted }
            runBlocking { pending.await() }
        } finally { scope.cancel() }
    }

    private fun screenshot(directory: File, name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
