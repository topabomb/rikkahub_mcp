package net.weero.measix.pilot.ui.pages.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.ContextPlacement
import net.weero.measix.pilot.data.model.ConversationContextBody
import net.weero.measix.pilot.data.model.ConversationContextSource
import net.weero.measix.pilot.data.model.ContextAdmissionReason
import net.weero.measix.pilot.data.model.DisclosureSection
import net.weero.measix.pilot.data.model.DisclosureSectionChange
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.TurnLivePhase
import net.weero.measix.pilot.ui.hooks.readBooleanPreference
import net.weero.measix.pilot.ui.hooks.writeBooleanPreference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** Explicit opt-in on a dedicated, unbound device, against either isolated HTTP fixtures or real Core. */
@RunWith(AndroidJUnit4::class)
class StarterV5ChatFlowAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun syncedV5CardSurvivesFirstSendDetailsRoomReadbackAndActivityReopen() {
        assumeTrue("Use a dedicated unbound AVD and starterV5MockLive=true",
            InstrumentationRegistry.getArguments().getString("starterV5MockLive") == "true")
        runOpeningFlow(core = false)
    }

    @Test
    fun syncedCoreV5CardSurvivesFirstSendDetailsRoomReadbackAndActivityReopen() {
        assumeTrue("Use a dedicated unbound AVD and starterV5CoreLive=true",
            InstrumentationRegistry.getArguments().getString("starterV5CoreLive") == "true")
        runOpeningFlow(core = true)
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun runOpeningFlow(core: Boolean) {
        val arguments = InstrumentationRegistry.getArguments()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = GlobalContext.get()
        val sessions = koin.get<EnterpriseSessionController>()
        val enterprise = koin.get<EnterpriseApplicationService>()
        val queries = koin.get<ConfigurationQueryService>()
        val conversations = koin.get<ConversationApplicationService>()
        val query = koin.get<ConversationQueryService>()
        val repository = koin.get<ConversationRepository>()
        val settings = koin.get<SettingsStore>()
        koin.get<ApplicationRecoveryCoordinator>()
        runBlocking { withTimeout(30_000) { koin.get<ApplicationRecoveryGate>().awaitReady() } }
        val initial = (sessions.state.value as EnterpriseState.Available).manifest
        // Never replace a binding, resume somebody else's enrollment, or restore private files by copying them.
        check(initial.phase == EnterpriseSessionPhase.SIGNED_OUT && initial.session == null &&
            initial.pendingEnrollment == null && initial.applied == null && initial.lastIdentity == null) {
            "starter_v5_requires_fresh_unbound_device"
        }
        val coreInput = if (core) File(requireNotNull(arguments.getString("coreStarterInput"))) else null
        val fixture = if (coreInput != null) {
            var inputFailure: Throwable? = null
            try { Json.parseToJsonElement(coreInput.readText()).jsonObject }
            catch (error: Throwable) { inputFailure = error; throw error }
            finally {
                if (!coreInput.delete()) {
                    val cleanup = IllegalStateException("core_starter_input_cleanup_failed")
                    inputFailure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        } else InstrumentationRegistry.getInstrumentation().context.assets
            .open("contracts/starter-v5-e2e.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val server = if (core) null else StarterV5HttpMock(fixture)
        val userModel = Model(modelId = "starter-v5-mock", displayName = "Starter V5 user-owned model",
            abilities = listOf(ModelAbility.TOOL))
        val userProvider = server?.let { ProviderSetting.OpenAI(name = "Starter V5 user-owned provider",
            models = listOf(userModel), baseUrl = "${it.origin}/user/v1", apiKey = "starter-v5-user-only") }
        val snapshot = fixture.getValue("snapshot").jsonObject
        assertEquals(5L, snapshot.getValue("schemaVersion").jsonPrimitive.long)
        val deploymentId = snapshot.getValue("deploymentId").jsonPrimitive.content
        val expectedStarter = fixture["starterId"]?.jsonPrimitive?.content
        val starter = snapshot.getValue("starters").jsonArray.map { it.jsonObject }.single {
            expectedStarter == null || it.getValue("starterId").jsonPrimitive.content == expectedStarter
        }
        val starterId = starter.getValue("starterId").jsonPrimitive.content
        val assistantSource = snapshot.getValue("assistants").jsonArray.map { it.jsonObject }.single {
            it.getValue("assistantDefinitionId") == starter.getValue("assistantDefinitionId")
        }
        val expectedAnswer = if (core) fixture.getValue("expectedAnswer").jsonPrimitive.content else "V5 mock response"
        val opening = starter.getValue("openingSnapshot").jsonObject
        val title = starter.getValue("title").jsonPrimitive.content
        val prompt = starter.getValue("prompt").jsonPrimitive.content
        val system = opening.getValue("systemPrompt").jsonPrimitive.content
        val backgrounds = opening.getValue("initialContexts").jsonArray.map { it.jsonObject }
        val priorNewChat = context.readBooleanPreference("create_new_conversation_on_start", true)
        val priorDisplay = runBlocking { settings.userSettings.first { !it.init }.displaySetting }
        val priorImageLoader = coil3.SingletonImageLoader.get(context)
        val evidence = File(arguments.getString("additionalTestOutputDir")?.let(::File)
            ?: context.getExternalFilesDir(null), "starter-v5-chat-evidence").apply { check(isDirectory || mkdirs()) }
        var activity: ActivityScenario<RouteActivity>? = null
        var view: ConversationViewLease? = null
        var createdId: Uuid? = null
        var forkedId: Uuid? = null
        var createdMemory: Pair<MemoryAccess, Int>? = null
        var fixtureAccess: RealmAccess.Enterprise? = null
        var fixtureEnrollment: String? = null
        var reenrollmentSessionId: String? = null
        var reenrollmentMaterial: String? = null
        var revokedView: ConversationViewLease? = null
        var personalProbeView: ConversationViewLease? = null
        var failure: Throwable? = null
        fun captureFixtureSession(): RealmAccess.Enterprise? {
            val session = (sessions.state.value as EnterpriseState.Available).manifest.session ?: return null
            val access = RealmAccess.Enterprise(session.identity.scope, session.id)
            val expected = fixtureAccess
            check(session.identity.authority.deploymentId == deploymentId &&
                (expected == null || access == expected ||
                    access.scope == expected.scope && access.sessionId == reenrollmentSessionId)) {
                "refuse_replaced_fixture_session_cleanup"
            }
            // Bootstrap publishes before synchronization and switching; ownership survives either failure.
            fixtureAccess = access
            return access
        }
        suspend fun cleanupAccess(): RealmAccess.Enterprise {
            val current = (sessions.state.value as EnterpriseState.Available).manifest
            val expected = fixtureAccess
            if (current.session == null) {
                check(server != null && expected != null && current.phase == EnterpriseSessionPhase.SIGNED_OUT &&
                    current.lastIdentity?.scope == expected.scope) { "refuse_foreign_fixture_reenrollment" }
                if (reenrollmentSessionId == null) {
                    check(current.pendingEnrollment == null) { "refuse_foreign_fixture_pending_enrollment" }
                    reenrollmentSessionId = server.prepareReenrollment()
                    reenrollmentMaterial = JsonObject(Json.parseToJsonElement(requireNotNull(fixtureEnrollment)).jsonObject +
                        ("code" to JsonPrimitive("starter-v5-reenrollment-${Uuid.random()}"))).toString()
                }
                current.pendingEnrollment?.let { pending ->
                    check(pending.sessionId == reenrollmentSessionId && pending.userId == expected.scope.userId &&
                        pending.platform.connection.authority == expected.scope.authority &&
                        pending.platform.connection.origin == server.origin) { "refuse_foreign_fixture_pending_enrollment" }
                }
                var joinFailure: Throwable? = null
                try {
                    check(enterprise.confirmJoin(requireNotNull(enterprise.join(requireNotNull(reenrollmentMaterial)))) ==
                        EnterpriseSynchronizationCommandResult.COMPLETED) { "fixture_reenrollment_did_not_complete" }
                } catch (error: Throwable) {
                    joinFailure = error
                    throw error
                } finally {
                    try { captureFixtureSession() }
                    catch (cleanup: Throwable) { joinFailure?.addSuppressed(cleanup) ?: throw cleanup }
                }
                return requireNotNull(captureFixtureSession())
            }
            val access = requireNotNull(captureFixtureSession())
            val presentation = sessions.readPresentation()
            if (current.phase !in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE) ||
                presentation.selection?.access != access) {
                check(enterprise.synchronize(access) == EnterpriseSynchronizationCommandResult.COMPLETED) {
                    "fixture_reenrollment_did_not_complete"
                }
                val selection = requireNotNull(sessions.readPresentation().selection)
                if (selection.access != access) enterprise.switchRealm(RealmSwitchRequest(selection, access))
            }
            return access
        }
        try {
            userProvider?.let { provider -> runBlocking {
                settings.updateLocal { it.copy(
                    providers = it.providers + provider,
                    displaySetting = it.displaySetting.copy(enableVolumeKeyScroll = true, volumeKeyScrollRatio = 0.5f),
                ) }
            } }
            val personalSettings = runBlocking { settings.withExecutionConfiguration(
                ConfigurationScope.Personal, sessions.state.value) { it.userSettings } }
            val personalSelections = runBlocking { queries.read(RealmAccess.Personal).storedSelections }
            val personalConversation = runBlocking { settings.lastConversation(ConfigurationScope.Personal) }
            val material = if (core) fixture.getValue("enrollment").toString() else buildJsonObject {
                put("formatVersion", 1); put("kind", "PLATFORM_ENROLLMENT")
                put("platformUrl", requireNotNull(server).origin); put("code", "starter-v5-dedicated-fixture")
                put("expiresAt", Instant.now().plusSeconds(3600).toString())
            }.toString()
            fixtureEnrollment = material
            runBlocking { withTimeout(60_000) { enterprise.confirmJoin(requireNotNull(enterprise.join(material))) } }
            val selection = runBlocking { requireNotNull(sessions.observeSelectedRealmSelection()
                .first { it?.access is RealmAccess.Enterprise }) }
            val access = selection.access as RealmAccess.Enterprise
            fixtureAccess = access
            assertEquals(deploymentId, access.scope.authority.deploymentId)
            server?.let {
                assertEquals(1, it.enrollments.get())
                assertTrue(it.downloads.get() >= 1)
                assertTrue(it.appliedReports.get() >= 1)
            }
            val resolved = runBlocking { queries.read(access) }
            val assistant = resolved.assistants.values.single { it.name == assistantSource.getValue("displayName").jsonPrimitive.content }
            val candidate = (sessions.state.value as EnterpriseState.Available).configuration!!
            val definition = candidate.starters.single { it.id == starterId }
            assertEquals(backgrounds.size, definition.openingSnapshot!!.initialContexts.size)
            assertEquals(system, definition.openingSnapshot.systemPrompt)
            runBlocking { koin.get<ConfigurationApplicationService>().selectResource(selection, ResourceSelectionSlot.ASSISTANT, assistant.id) }
            assertTrue(runBlocking { query.recentConversations(access, assistant.id, 10) }.isEmpty())
            context.writeBooleanPreference("create_new_conversation_on_start", true)
            coil3.SingletonImageLoader.reset()
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java))
            var uiContext: Context = context
            activity.onActivity { uiContext = it }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().size == 1 }
            // Actual empty-chat card -> ChatPage callback -> ChatVM input lock -> application binding.
            compose.onNode(hasScrollToIndexAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
                SemanticsMatcher("visible Starter row") { it.boundsInRoot.left >= 0f && it.boundsInRoot.width > 0f })
                .performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("chat_input") and hasText(prompt)).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("chat_input").assertTextEquals(prompt)
            compose.onNodeWithText(title).assertIsSelected()
            assertTrue(runBlocking { query.recentConversations(access, assistant.id, 10) }.isEmpty())
            server?.let { assertTrue(it.modelRequests.isEmpty()) }
            capture(evidence, "01-card-prefill.png")
            closeSoftKeyboard()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("chat_send_button") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("chat_send_button").performClick()
            val row = awaitUi(30_000) { query.conversationsOfAssistant(assistant.id).map { it.getOrThrow() }.first { it.isNotEmpty() }.single() }
            createdId = row.id
            val lease = runBlocking { conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, access)) }
            view = lease
            val completed = awaitUi(60_000) { query.conversationUiModel(lease).first { model ->
                val last = model?.snapshot?.currentMessages()?.lastOrNull()
                model != null && last?.role == MessageRole.ASSISTANT && model.presentation.activeTurnId == null &&
                    (last.terminalStatus != null || last.parts.filterIsInstance<UIMessagePart.Step>().lastOrNull()?.outcome != null)
            }!! }
            val transcript = completed.snapshot.currentMessages()
            val answer = transcript.last()
            assertNull("${answer.terminalReason}: ${answer.terminalDetail}", answer.terminalStatus)
            assertEquals(StepOutcome.Final, answer.parts.filterIsInstance<UIMessagePart.Step>().last().outcome)
            val actualAnswer = answer.toText()
            val allowOuterWhitespace = core && fixture["allowAnswerOuterWhitespace"]?.jsonPrimitive?.booleanOrNull == true
            assertEquals(expectedAnswer, if (allowOuterWhitespace) actualAnswer.trim() else actualAnswer)
            File(evidence, "provider-answer.txt").writeText(actualAnswer)
            assertEquals(listOf(prompt), transcript.filter { it.role == MessageRole.USER }.map { it.toText() })
            assertTrue(completed.snapshot.context.messages.values.none { it.updates.isNotEmpty() })
            val durable = runBlocking { requireNotNull(repository.getConversationSnapshotById(row.id)) }
            val saved = requireNotNull(durable.opening)
            assertEquals(definition, saved.definition)
            assertEquals(snapshot.getValue("releaseId").jsonPrimitive.content, saved.releaseId)
            assertEquals(snapshot.getValue("snapshotHash").jsonPrimitive.content, saved.snapshotHash)
            assertEquals(snapshot.getValue("managedGeneration").jsonPrimitive.long, saved.generation)
            assertEquals(1, durable.contextAdmissions.size)
            // The real-Core harness verifies actual adapter requests independently of device projections.
            if (server != null) {
                val request = synchronized(server.modelRequests) { server.modelRequests.toList() }.single { body -> body.getValue("messages").jsonArray.any {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "system" && it.toString().contains("STARTER_V5_SYSTEM_LITERAL")
                } }
                val messages = request.getValue("messages").jsonArray.map { it.jsonObject }
                val wireSystem = messages.first { it["role"]?.jsonPrimitive?.content == "system" }.getValue("content").jsonPrimitive.content
                assertTrue(wireSystem.contains(system))
                assertFalse(wireSystem.contains("ASSISTANT_FALLBACK_MUST_NOT_WIN"))
                val userWire = messages.single { it["role"]?.jsonPrimitive?.content == "user" &&
                    it.getValue("content").toString().contains("FIRST_BACKGROUND") }.getValue("content").toString()
                assertTrue(userWire.contains("FIRST_BACKGROUND"))
                assertTrue(userWire.contains("{{unchanged}}"))
                assertTrue(userWire.indexOf("FIRST_BACKGROUND") < userWire.indexOf("SECOND_BACKGROUND"))
                assertTrue(userWire.contains(prompt))
                File(evidence, "provider-request.json").writeText(request.toString())
            }
            capture(evidence, "02-first-send.png")
            val details = runBlocking { query.contextDetails(lease, row.id, answer.id) }.requests.single()
            val item = details.items.single { ConversationContextCategory.OPENING in it.categories }
            val original = runBlocking { query.contextContent(lease, row.id, answer.id, details.id, item.key).text }
            val originalBlocks = Json.parseToJsonElement(original).jsonObject.getValue("blocks").jsonArray
            assertEquals(backgrounds.map { it.getValue("content").jsonPrimitive.content }, originalBlocks.map { it.jsonPrimitive.content })
            assertEquals(setOf("type", "blocks"), Json.parseToJsonElement(original).jsonObject.keys)
            val systemItem = details.items.single { ConversationContextCategory.SYSTEM in it.categories }
            val admittedSystem = runBlocking { query.contextContent(lease, row.id, answer.id, details.id, systemItem.key).text }
            assertTrue(admittedSystem.contains(system))
            compose.onNodeWithText(original).assertDoesNotExist()
            compose.onNodeWithText(admittedSystem).assertDoesNotExist()
            compose.onAllNodesWithContentDescription(uiContext.getString(R.string.context_updated_accessibility)).assertCountEquals(0)
            capture(evidence, "03-opening-kept-out-of-transcript.png")
            view.close(); view = null
            activity.close(); activity = null
            // A direct repository read proves Room, independently of whether the runtime has been evicted.
            val reopened = runBlocking { requireNotNull(repository.getConversationSnapshotById(row.id)) }
            assertEquals(saved, reopened.opening)
            assertEquals(durable.modelContextEntries, reopened.modelContextEntries)
            assertEquals(durable.contextAdmissions, reopened.contextAdmissions)
            context.writeBooleanPreference("create_new_conversation_on_start", false)
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java).putExtra("conversationId", row.id.toString()))
            activity.onActivity { uiContext = it }
            compose.waitUntil(30_000) { compose.onAllNodesWithText(expectedAnswer).fetchSemanticsNodes().isNotEmpty() }
            val reopenedView = runBlocking { conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, access)) }
            view = reopenedView
            val reopenedDetails = runBlocking { query.contextDetails(reopenedView, row.id, answer.id) }.requests.single()
            assertEquals(details, reopenedDetails)
            assertEquals(original, runBlocking { query.contextContent(reopenedView, row.id, answer.id, details.id, item.key).text })
            assertEquals(admittedSystem, runBlocking { query.contextContent(reopenedView, row.id, answer.id, details.id, systemItem.key).text })
            compose.onNodeWithText(original).assertDoesNotExist()
            compose.onNodeWithText(admittedSystem).assertDoesNotExist()
            compose.onAllNodesWithContentDescription(uiContext.getString(R.string.context_updated_accessibility)).assertCountEquals(0)
            capture(evidence, "04-chat-reopened.png")
            if (server != null) {
                val provider = requireNotNull(userProvider)
                val managedModel = requireNotNull(resolved.assistantModel(assistant.id).reference)
                val managedWireId = resolved.models.getValue(managedModel).model.modelId
                val selector = compose.onAllNodes(hasContentDescription(managedWireId) and hasClickAction())
                    .fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }
                compose.onNode(SemanticsMatcher("chat model selector") { it.id == selector.id }).performClick()
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasSetTextAction() and !hasTestTag("chat_input")).fetchSemanticsNodes().size == 1
                }
                compose.onNode(hasSetTextAction() and !hasTestTag("chat_input")).performTextInput(userModel.displayName)
                closeSoftKeyboard()
                compose.waitForIdle()
                compose.onNode(hasText(userModel.displayName) and hasClickAction() and !hasSetTextAction())
                    .assertIsDisplayed().performClick()
                awaitUi(30_000) { query.conversationUiModel(reopenedView).first { it?.configuration?.model?.id == userModel.id } }
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasSetTextAction() and !hasTestTag("chat_input")).fetchSemanticsNodes().isEmpty()
                }
                val selected = awaitUi(30_000) { queries.read(access) }
                assertEquals(userModel.id, selected.assistantModel(assistant.id).reference)
                assertEquals(provider.id, selected.models.getValue(userModel.id).userProviderId)
                assertEquals(candidate.assistants, requireNotNull(selected.enterpriseConfiguration).assistants)
                val followUp = "Continue this enterprise conversation using my own model"
                compose.onNodeWithTag("chat_input").performTextInput(followUp)
                closeSoftKeyboard()
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasTestTag("chat_send_button") and isEnabled()).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithTag("chat_send_button").performClick()
                val continued = awaitUi(60_000) { query.conversationUiModel(reopenedView).first { model ->
                    model != null && model.presentation.activeTurnId == null &&
                        model.snapshot.currentMessages().lastOrNull()?.toText() == "V5 user model response"
                }!! }
                val nextAnswer = continued.snapshot.currentMessages().last()
                assertNull(nextAnswer.terminalStatus)
                assertEquals(StepOutcome.Final, nextAnswer.parts.filterIsInstance<UIMessagePart.Step>().last().outcome)
                compose.onNodeWithText("V5 user model response").assertIsDisplayed()
                val direct = synchronized(server.directRequests) { server.directRequests.toList() }.single()
                assertEquals("/user/v1/chat/completions", direct.path)
                assertEquals("Bearer starter-v5-user-only", direct.authorization)
                assertEquals(userModel.modelId, direct.body.getValue("model").jsonPrimitive.content)
                val wireMessages = direct.body.getValue("messages").jsonArray
                assertTrue(wireMessages.first { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                    .jsonObject.getValue("content").toString().contains(system))
                val originalInput = wireMessages.first { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                    .jsonObject.getValue("content").toString()
                assertTrue(originalInput.contains(prompt))
                assertTrue(originalInput.indexOf("FIRST_BACKGROUND") >= 0)
                assertTrue(originalInput.indexOf("FIRST_BACKGROUND") < originalInput.indexOf("SECOND_BACKGROUND"))
                assertTrue(wireMessages.any { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" &&
                    it.jsonObject.getValue("content").toString().contains(expectedAnswer) })
                assertTrue(wireMessages.last().jsonObject.getValue("content").toString().contains(followUp))
                val nextDurable = awaitUi(30_000) { requireNotNull(repository.getConversationSnapshotById(row.id)) }
                assertEquals(access.scope, nextDurable.header.scope)
                assertEquals(saved, nextDurable.opening)
                assertEquals(reopened.nodes, nextDurable.nodes.take(reopened.nodes.size))
                assertEquals(reopened.contextAdmissions, nextDurable.contextAdmissions.take(reopened.contextAdmissions.size))
                assertEquals(2, nextDurable.contextAdmissions.size)
                val retainedIds = reopened.modelContextEntries.map { it.id }.toSet()
                assertEquals(reopened.modelContextEntries, nextDurable.modelContextEntries.filter { it.id in retainedIds })
                assertEquals(nextAnswer.id, nextDurable.contextAdmissions.last().owner.messageId)
                assertEquals(personalSelections, awaitUi(30_000) { queries.read(RealmAccess.Personal).storedSelections })
                val personalAfter = awaitUi(30_000) { settings.withExecutionConfiguration(
                    ConfigurationScope.Personal, sessions.state.value) { it.userSettings } }
                assertEquals(personalSettings.providers, personalAfter.providers)
                assertEquals(personalSettings.assistants, personalAfter.assistants)
                assertEquals(personalConversation, awaitUi(30_000) { settings.lastConversation(ConfigurationScope.Personal) })
                File(evidence, "user-model-request.json").writeText(direct.body.toString())
                capture(evidence, "05-enterprise-user-model-next-start.png")

                // Fork the first answer after a later START: the real owner must prune the suffix
                // and remap its retained admissions, without changing the original enterprise tree.
                val beforeForkIds = awaitUi(30_000) { query.conversationsOfAssistant(assistant.id).map { it.getOrThrow() }
                    .first { rows -> rows.any { it.id == row.id } }.map { it.id }.toSet() }
                clickMessageAction(uiContext, expectedAnswer, nextDurable.currentMessages().map { it.toText() }.toSet(), R.string.more_options)
                compose.onNodeWithText(uiContext.getString(R.string.create_fork)).assertIsDisplayed().performClick()
                val forkId = awaitUi(30_000) { query.conversationsOfAssistant(assistant.id).map { it.getOrThrow() }
                    .first { rows -> rows.any { it.id !in beforeForkIds } }.single { it.id !in beforeForkIds }.id }
                forkedId = forkId
                compose.waitUntil(30_000) { runBlocking { settings.lastConversation(access.scope) } == forkId }
                val fork = awaitUi(30_000) { requireNotNull(repository.getConversationSnapshotById(forkId)) }
                assertEquals(access.scope, fork.header.scope)
                assertEquals(assistant.id, fork.header.assistantId)
                assertEquals(saved, fork.opening)
                assertEquals(reopened.nodes.size, fork.nodes.size)
                val nodeIds = reopened.nodes.zip(fork.nodes).associate { (source, copied) ->
                    assertNotEquals(source.id, copied.id)
                    assertEquals(source.copy(id = copied.id), copied)
                    source.id to copied.id
                }
                assertEquals(reopened.modelContextEntries.size, fork.modelContextEntries.size)
                val entryIds = reopened.modelContextEntries.associate { source ->
                    val copied = fork.modelContextEntries.single {
                        it.ownerMessageId == source.ownerMessageId && it.occurrence == source.occurrence
                    }
                    assertNotEquals(source.id, copied.id)
                    assertEquals(source.copy(ownerNodeId = nodeIds.getValue(source.ownerNodeId),
                        anchorNodeId = nodeIds.getValue(source.anchorNodeId), id = copied.id), copied)
                    source.id to copied.id
                }
                assertEquals(reopened.contextAdmissions.size, fork.contextAdmissions.size)
                reopened.contextAdmissions.forEach { source ->
                    val copied = fork.contextAdmissions.single {
                        it.owner.messageId == source.owner.messageId && it.stepId == source.stepId
                    }
                    assertNotEquals(source.id, copied.id)
                    assertEquals(source.copy(
                        owner = source.owner.copy(nodeId = nodeIds.getValue(source.owner.nodeId)),
                        windowStart = source.windowStart.copy(nodeId = nodeIds.getValue(source.windowStart.nodeId)),
                        selection = source.selection?.let { it.copy(
                            systemEntryId = entryIds.getValue(it.systemEntryId),
                            ruleEntryIds = it.ruleEntryIds.map(entryIds::getValue),
                        ) },
                        uses = source.uses.map { use -> use.copy(
                            entryId = entryIds.getValue(use.entryId),
                            placement = when (val placement = use.placement) {
                                is ContextPlacement.BeforeMessage -> placement.copy(message = placement.message.copy(
                                    nodeId = nodeIds.getValue(placement.message.nodeId),
                                ))
                                is ContextPlacement.MessagePart -> placement.copy(message = placement.message.copy(
                                    nodeId = nodeIds.getValue(placement.message.nodeId),
                                ))
                                else -> placement
                            },
                        ) },
                        id = copied.id,
                    ), copied)
                }
                assertTrue(fork.currentMessages().none { it.id == nextAnswer.id || it.toText() == followUp })
                val forkView = awaitUi(30_000) {
                    conversations.initialize(ConversationOpenRequest.OpenExisting(forkId, access))
                }
                val staleTarget = forkView.commandTarget
                forkView.close()
                val rejected = awaitUi(30_000) {
                    try {
                        conversations.updateTitle(staleTarget, "must not replace fork title")
                        null
                    } catch (error: IllegalStateException) {
                        if (error is CancellationException) throw error
                        error
                    }
                }
                assertTrue(rejected is IllegalStateException)
                assertEquals("conversation_view_closed", rejected?.message)
                assertEquals(fork, awaitUi(30_000) { repository.getConversationSnapshotById(forkId) })
                val sourceAfterFork = awaitUi(30_000) { requireNotNull(repository.getConversationSnapshotById(row.id)) }
                assertEquals(nextDurable.nodes, sourceAfterFork.nodes)
                assertEquals(nextDurable.opening, sourceAfterFork.opening)
                assertEquals(nextDurable.modelContextEntries, sourceAfterFork.modelContextEntries)
                assertEquals(nextDurable.contextAdmissions, sourceAfterFork.contextAdmissions)
                assertEquals(1, server.directRequests.size)
                File(evidence, "enterprise-fork-proof.txt").writeText(
                    "actual_fork_menu=true\nreal_application_fork=true\nroom_readback=true\nopening_preserved=true\n" +
                        "context_remapped=true\nlater_start_pruned=true\nclosed_page_callback_rejected=true\n",
                )

                // Return to the source through the same Activity entry used above; the fork menu
                // navigates to its new conversation, while this test's source lease stays open.
                activity.close()
                activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java).putExtra("conversationId", row.id.toString()))
                activity.onActivity { uiContext = it }
                compose.waitUntil(30_000) { compose.onAllNodesWithText("V5 user model response").fetchSemanticsNodes().isNotEmpty() }
                val memory = koin.get<MemoryService>()
                val beforeUsage = awaitUi(30_000) { queries.read(access).assistants.getValue(assistant.id) }
                awaitUi(30_000) { koin.get<ConfigurationApplicationService>().changeAssistantPreference(
                    ConversationAssistantTarget(reopenedView.commandTarget, assistant.id),
                    AssistantPreferenceChange.EditUsage(beforeUsage, beforeUsage.copy(enableMemory = true, useGlobalMemory = false)),
                ) }
                val memoryAssistant = awaitUi(30_000) { queries.read(access).assistants.getValue(assistant.id) }
                assertTrue(memoryAssistant.enableMemory)
                assertFalse(memoryAssistant.useGlobalMemory)
                val memoryAccess = awaitUi(30_000) { requireNotNull(memory.captureExecution(access, memoryAssistant)) }
                val memoriesBefore = awaitUi(30_000) { memory.read(memoryAccess) }
                assertTrue(memoriesBefore.isEmpty())
                compose.onNodeWithTag("chat_input").performTextInput("Ask before continuing this enterprise turn")
                closeSoftKeyboard()
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasTestTag("chat_send_button") and isEnabled()).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithTag("chat_send_button").performClick()
                val waiting = awaitUi(60_000) { query.conversationUiModel(reopenedView).first {
                    it?.presentation?.phase == TurnLivePhase.AWAITING_USER
                }!! }
                val waitingStream = requireNotNull(waiting.snapshot.stream)
                val originalHandle = TurnHandle(row.id, waitingStream.epoch, waitingStream.turnId, waitingStream.assistantMessageId)
                val waitingRoom = awaitUi(30_000) { requireNotNull(repository.getConversationSnapshotById(row.id)) }
                val awaitingCall = waitingRoom.currentMessages().last().parts.filterIsInstance<UIMessagePart.Tool>().single()
                assertEquals("ask_user", awaitingCall.toolName)
                assertEquals("enterprise-answer", awaitingCall.providerCallId)
                assertTrue(awaitingCall.interactionState is ToolInteractionState.AwaitingInput)
                assertEquals(originalHandle.assistantMessageId, waitingRoom.currentMessages().last().id)
                assertEquals(nextDurable.nodes, waitingRoom.nodes.take(nextDurable.nodes.size))
                val waitingTurns = awaitUi(30_000) { repository.getTurnExecutions(row.id) }
                assertEquals(3, waitingTurns.size)
                val originalTurn = waitingTurns.single { it.turnId == originalHandle.turnId.toString() }
                assertEquals(TurnExecutionStatus.AWAITING_USER, originalTurn.status)
                assertEquals(originalHandle.assistantMessageId.toString(), originalTurn.assistantMessageId)
                assertEquals(3, waitingRoom.contextAdmissions.size)
                val initialAsk = synchronized(server.directRequests) { server.directRequests.toList() }.last()
                assertTrue(initialAsk.body.getValue("tools").jsonArray.any {
                    it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content == "ask_user"
                })
                // A waiting turn stays active, but the visible question must settle without
                // repeatedly requesting another layout before the user can answer it.
                compose.waitForIdle()
                compose.onNode(hasText("Proceed with original turn") and hasClickAction()).assertIsDisplayed()

                val memoryText = "Enterprise memory added while the original turn awaits its answer"
                val addedMemory = awaitUi(30_000) { memory.add(memoryAccess, memoryText) }
                createdMemory = memoryAccess to addedMemory.id
                assertEquals(memoriesBefore + addedMemory, awaitUi(30_000) { memory.read(memoryAccess) })

                // Change the enterprise preference through its real command while the original
                // execution lease waits. The resumed request must still use the user transport.
                awaitUi(30_000) { koin.get<ConfigurationApplicationService>().changeAssistantPreference(
                    ConversationAssistantTarget(reopenedView.commandTarget, assistant.id),
                    AssistantPreferenceChange.Model(managedModel),
                ) }
                awaitUi(30_000) { query.conversationUiModel(reopenedView).first { it?.configuration?.model?.id == managedModel } }
                val managedRequestCount = server.modelRequests.size
                val historyMatcher = hasScrollToIndexAction() and SemanticsMatcher("visible chat history") {
                    it.boundsInRoot.left >= 0f && it.boundsInRoot.right > 0f
                }
                val history = compose.onNode(historyMatcher)
                fun revealAboveChatInput(target: SemanticsNodeInteraction) {
                    // The history viewport extends behind the overlaid composer. ScrollTo alone
                    // may report visibility there and send the physical click to the text field.
                    repeat(6) {
                        compose.waitForIdle()
                        val list = history.getUnclippedBoundsInRoot()
                        val input = compose.onNodeWithTag("chat_input").getUnclippedBoundsInRoot()
                        val safeTop = list.top + (list.bottom - list.top) * 0.03f
                        val safeBottom = input.top - (input.bottom - input.top) * 0.25f
                        val bounds = target.getUnclippedBoundsInRoot()
                        if (bounds.top >= safeTop && bounds.bottom <= safeBottom && bounds.bottom > bounds.top) return
                        val listPx = history.fetchSemanticsNode().boundsInRoot
                        val inputPx = compose.onNodeWithTag("chat_input").fetchSemanticsNode().boundsInRoot
                        val visibleHeight = inputPx.top - listPx.top
                        assertTrue("Chat history must have a touchable area above the composer", visibleHeight > 0f)
                        history.performTouchInput {
                            if (bounds.top < safeTop) {
                                swipeDown(startY = visibleHeight * 0.2f, endY = visibleHeight * 0.7f, durationMillis = 800)
                            } else {
                                swipeUp(startY = visibleHeight * 0.7f, endY = visibleHeight * 0.2f, durationMillis = 800)
                            }
                        }
                    }
                    error("enterprise_interaction_could_not_be_revealed_above_chat_input: ${target.printToString()}")
                }
                val option = compose.onNode(hasText("Proceed with original turn") and hasClickAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                revealAboveChatInput(option)
                option.assertIsEnabled().performClick().assertIsSelected()
                val submit = compose.onNode(hasText(uiContext.getString(R.string.chat_message_tool_submit)) and hasClickAction())
                revealAboveChatInput(submit)
                submit.assertIsEnabled().performClick()
                compose.waitUntil(60_000) { server.continuationReceived.count == 0L }
                val resuming = awaitUi(30_000) { query.conversationUiModel(reopenedView).first {
                    it?.snapshot?.stream != null && it.presentation.phase != TurnLivePhase.AWAITING_USER
                }!! }
                val resumedStream = requireNotNull(resuming.snapshot.stream)
                assertEquals(originalHandle, TurnHandle(row.id, resumedStream.epoch, resumedStream.turnId, resumedStream.assistantMessageId))
                val resumedRoom = awaitUi(30_000) { requireNotNull(repository.getConversationSnapshotById(row.id)) }
                assertEquals(waitingRoom.nodes.map { it.id to it.messages.map { message -> message.id } },
                    resumedRoom.nodes.map { it.id to it.messages.map { message -> message.id } })
                assertEquals(waitingRoom.nodes.dropLast(1), resumedRoom.nodes.dropLast(1))
                assertEquals(waitingRoom.opening, resumedRoom.opening)
                val previousAdmissions = waitingRoom.contextAdmissions.associateBy { it.id }
                val resumedAdmissions = resumedRoom.contextAdmissions.associateBy { it.id }
                assertEquals(waitingRoom.contextAdmissions.size, previousAdmissions.size)
                assertEquals(resumedRoom.contextAdmissions.size, resumedAdmissions.size)
                assertEquals(4, resumedRoom.contextAdmissions.size)
                assertEquals(previousAdmissions, resumedAdmissions.filterKeys { it in previousAdmissions })
                val continuedAdmission = resumedAdmissions.filterKeys { it !in previousAdmissions }.values.single()
                val previousAdmission = previousAdmissions.values.single {
                    it.owner.messageId == originalHandle.assistantMessageId
                }
                assertEquals(previousAdmission.owner, continuedAdmission.owner)
                val waitingSteps = waitingRoom.currentMessages().last().parts.filterIsInstance<UIMessagePart.Step>()
                val continuedStep = resumedRoom.currentMessages().last().parts.filterIsInstance<UIMessagePart.Step>()
                    .single { it.stepId == continuedAdmission.stepId }
                assertTrue(waitingSteps.none { it.stepId == continuedStep.stepId })
                assertEquals(waitingSteps.maxOf { it.ordinal } + 1, continuedStep.ordinal)
                val oldEntries = waitingRoom.modelContextEntries.associateBy { it.id }
                val resumedEntries = resumedRoom.modelContextEntries.associateBy { it.id }
                assertEquals(waitingRoom.modelContextEntries.size, oldEntries.size)
                assertEquals(resumedRoom.modelContextEntries.size, resumedEntries.size)
                assertEquals(oldEntries, resumedEntries.filterKeys { it in oldEntries })
                val external = resumedEntries.filterKeys { it !in oldEntries }.values.single()
                val disclosure = external.payload.source as ConversationContextSource.Disclosure
                assertEquals(mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE), disclosure.reasons)
                assertEquals(mapOf(DisclosureSection.MEMORY to DisclosureSectionChange(addedIds = listOf(addedMemory.id.toString()))),
                    disclosure.changes)
                assertEquals(assistant.id, requireNotNull(disclosure.namespace).caller)
                assertEquals(assistant.id.toString(), disclosure.namespace?.memoryOwner)
                assertEquals(originalHandle.assistantMessageId, external.ownerMessageId)
                assertEquals(continuedAdmission.owner.nodeId, external.ownerNodeId)
                assertEquals(waitingRoom.nodes[waitingRoom.nodes.lastIndex - 1].id, external.anchorNodeId)
                assertEquals(waitingRoom.nodes[waitingRoom.nodes.lastIndex - 1].currentMessage.id, external.anchorMessageId)
                assertEquals(continuedStep.stepId, external.stepId)
                val externalUse = continuedAdmission.uses.single { it.entryId == external.id }
                assertEquals(ContextPlacement.BeforeStep(continuedStep.stepId), externalUse.placement)
                assertEquals(MessageRole.USER, externalUse.role)
                val externalBody = (external.payload.body as ConversationContextBody.Inline).text
                val externalSections = ConversationDisclosureSnapshotService.readSections(externalBody)
                assertEquals(setOf(DisclosureSection.MEMORY), externalSections.keys)
                assertEquals(buildJsonObject {
                    put("enabled", true); put("scope", "local")
                    putJsonArray("header") { add("id"); add("content") }
                    putJsonArray("rows") { add(buildJsonArray { add(addedMemory.id); add(memoryText) }) }
                }, externalSections.getValue(DisclosureSection.MEMORY))
                val answeredCall = resumedRoom.currentMessages().last().parts.filterIsInstance<UIMessagePart.Tool>().single()
                assertEquals(awaitingCall.localCallId, answeredCall.localCallId)
                assertEquals(awaitingCall.stepId, answeredCall.stepId)
                assertEquals(awaitingCall.providerCallId, answeredCall.providerCallId)
                val submittedAnswer = (answeredCall.interactionState as ToolInteractionState.Answered).answer
                assertEquals("Proceed with original turn", Json.parseToJsonElement(submittedAnswer).jsonObject
                    .getValue("answers").jsonObject.getValue("continue").jsonPrimitive.content)
                val resumedTurns = awaitUi(30_000) { repository.getTurnExecutions(row.id) }
                assertEquals(waitingTurns.map { it.turnId }.toSet(), resumedTurns.map { it.turnId }.toSet())
                val resumedTurn = resumedTurns.single { it.turnId == originalTurn.turnId }
                assertEquals(TurnExecutionStatus.RUNNING, resumedTurn.status)
                assertEquals(originalTurn.copy(status = resumedTurn.status, reason = resumedTurn.reason,
                    updatedAt = resumedTurn.updatedAt), resumedTurn)
                val requests = synchronized(server.directRequests) { server.directRequests.toList() }
                assertEquals(3, requests.size)
                val continuation = requests.last()
                assertEquals(true, continuation.body["stream"]?.jsonPrimitive?.booleanOrNull)
                assertEquals(initialAsk.path, continuation.path)
                assertEquals(initialAsk.authorization, continuation.authorization)
                assertEquals(initialAsk.body["model"], continuation.body["model"])
                assertEquals(initialAsk.body["tools"], continuation.body["tools"])
                fun systemMessage(request: JsonObject) = request.getValue("messages").jsonArray.single {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "system"
                }
                assertEquals(systemMessage(initialAsk.body), systemMessage(continuation.body))
                val replay = continuation.body.getValue("messages").jsonArray.map { it.jsonObject }
                val answerResult = replay.single { it["role"]?.jsonPrimitive?.content == "tool" }
                assertEquals("enterprise-answer", answerResult.getValue("tool_call_id").jsonPrimitive.content)
                assertTrue(answerResult.getValue("content").toString().contains("Proceed with original turn"))
                val externalWireIndex = replay.indexOfFirst { it["content"]?.toString()?.contains(memoryText) == true }
                assertTrue("External memory must follow the answered tool in the resumed request",
                    externalWireIndex > replay.indexOf(answerResult))
                assertEquals(1, replay.count { it["content"]?.toString()?.contains(memoryText) == true })
                assertTrue(replay[externalWireIndex].getValue("content").toString().contains(addedMemory.id.toString()))
                assertFalse(initialAsk.body.toString().contains(memoryText))
                assertEquals(managedRequestCount, server.modelRequests.size)
                fun awaitStreamingProjection(text: String) {
                    awaitUi(60_000) { query.conversationUiModel(reopenedView).first {
                        it != null && it.presentation.isActive &&
                            it.snapshot.currentMessages().lastOrNull()?.parts
                                ?.filterIsInstance<UIMessagePart.Text>()?.singleOrNull()?.text == text
                    } }
                }
                fun awaitStreamingText(text: String, paragraphs: List<String>) {
                    awaitStreamingProjection(text)
                    // Markdown parsing runs off the main thread; wait for the new layout as well
                    // as the projection while the response and original turn are still open.
                    compose.waitUntil(30_000) {
                        compose.onAllNodesWithText(paragraphs.last(), useUnmergedTree = true)
                            .fetchSemanticsNodes().size == 1
                    }
                    compose.waitForIdle()
                    val first = compose.onNodeWithText(paragraphs.first(), useUnmergedTree = true).getUnclippedBoundsInRoot()
                    val last = compose.onNodeWithText(paragraphs.last(), useUnmergedTree = true).getUnclippedBoundsInRoot()
                    val viewport = history.getUnclippedBoundsInRoot()
                    assertTrue("Each new response segment must exceed the actual chat viewport",
                        last.bottom - first.top > viewport.bottom - viewport.top)
                }
                fun assertStreamingTail(paragraphs: List<String>) {
                    // No test-driven scroll or terminal turn may manufacture automatic following.
                    compose.waitUntil(30_000) {
                        val range = history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                        range.value() == range.maxValue()
                    }
                    compose.onNodeWithText(paragraphs.last(), useUnmergedTree = true).assertIsDisplayed()
                    compose.onNodeWithText(paragraphs.first(), useUnmergedTree = true).assertIsNotDisplayed()
                }
                server.releaseContinuation.countDown()
                val firstText = server.continuationParagraphs[0].joinToString("\n\n")
                awaitStreamingText(firstText, server.continuationParagraphs[0])
                assertStreamingTail(server.continuationParagraphs[0])

                // The Provider remains at the first-segment gate while actual IME insets change.
                // Neither focus nor keyboard dismissal may revoke the page's tail intent.
                fun imeVisible(): Boolean {
                    var visible = false
                    requireNotNull(activity).onActivity {
                        visible = ViewCompat.getRootWindowInsets(it.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    return visible
                }
                compose.onNodeWithTag("chat_input").performClick()
                compose.waitUntil(30_000) { imeVisible() }
                compose.waitForIdle()
                assertStreamingTail(server.continuationParagraphs[0])
                closeSoftKeyboard()
                compose.waitUntil(30_000) { !imeVisible() }
                compose.waitForIdle()
                assertStreamingTail(server.continuationParagraphs[0])

                // Read movement from a paragraph inside this same oversized message. Lazy list
                // semantics uses estimated offsets which are not monotonic across item boundaries.
                val volumeAnchor = compose.onNodeWithText(server.continuationParagraphs[0].last(), useUnmergedTree = true)
                fun volumeAnchorTop() = volumeAnchor.getUnclippedBoundsInRoot().top.value
                repeat(2) {
                    val beforeUp = volumeAnchorTop()
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
                    compose.waitUntil(30_000) { volumeAnchorTop() > beforeUp + 1f }
                    compose.waitForIdle()
                }
                val beforeDown = volumeAnchorTop()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
                compose.waitUntil(30_000) { volumeAnchorTop() < beforeDown - 1f }
                compose.waitForIdle()
                val volumeRange = history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue("Volume input must permit reading away from the streaming tail", volumeRange.value() != volumeRange.maxValue())
                repeat(2) {
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
                    compose.waitForIdle()
                }
                assertStreamingTail(server.continuationParagraphs[0])
                awaitStreamingProjection(firstText)
                server.releaseContinuationTail.countDown()
                val secondText = firstText + "\n\n" + server.continuationParagraphs[1].joinToString("\n\n")
                awaitStreamingText(secondText, server.continuationParagraphs[1])
                assertStreamingTail(server.continuationParagraphs[1])

                // A real user gesture leaves the tail; later growth must preserve this reading
                // position instead of treating every active response as permission to scroll.
                history.performTouchInput { swipeDown(startY = height * 0.25f, endY = height * 0.75f, durationMillis = 800) }
                compose.waitForIdle()
                val awayRange = history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue("The user gesture must leave the tail", awayRange.value() < awayRange.maxValue())
                val viewport = history.fetchSemanticsNode().boundsInRoot
                val anchor = compose.onAllNodes(SemanticsMatcher("stream paragraph") {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                        text.text in server.continuationParagraphs[1]
                    } == true
                }, useUnmergedTree = true).fetchSemanticsNodes().first {
                    it.boundsInRoot.height > 0f && it.boundsInRoot.top > viewport.top + viewport.height * 0.25f &&
                        it.boundsInRoot.bottom < viewport.bottom - viewport.height * 0.25f
                }.config[SemanticsProperties.Text].single().text
                val anchorBefore = compose.onNodeWithText(anchor, useUnmergedTree = true).getUnclippedBoundsInRoot()
                server.releaseContinuationWhileReading.countDown()
                fun textThrough(segment: Int) = server.continuationParagraphs.take(segment + 1)
                    .flatten().joinToString("\n\n")
                awaitStreamingText(textThrough(2), server.continuationParagraphs[2])
                val afterGrowthRange = history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue("Appending while reading must not return to the tail", afterGrowthRange.value() < afterGrowthRange.maxValue())
                compose.onNodeWithText(anchor, useUnmergedTree = true).assertIsDisplayed()
                val anchorAfter = compose.onNodeWithText(anchor, useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertEquals("The visible paragraph must retain its position", anchorBefore.top.value, anchorAfter.top.value, 2f)
                compose.onNodeWithText(server.continuationParagraphs[2].last(), useUnmergedTree = true).assertIsNotDisplayed()

                fun clickTail() {
                    // Reveal the real transient jumper with a gesture, then use its actual button.
                    history.performTouchInput {
                        swipeDown(startY = height * 0.35f, endY = height * 0.5f, durationMillis = 250)
                    }
                    compose.onNodeWithContentDescription(context.getString(R.string.chat_page_scroll_to_bottom))
                        .assertIsDisplayed().performClick()
                    compose.waitUntil(30_000) {
                        val range = history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                        range.value() == range.maxValue()
                    }
                }
                fun assertReadingStart() {
                    compose.waitUntil(30_000) {
                        history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() == 0f
                    }
                    compose.onNode(hasText(prompt) and hasAnyAncestor(historyMatcher), useUnmergedTree = true)
                        .assertIsDisplayed()
                }
                clickTail()
                compose.onNodeWithTag("ChatMessageJumperStart").assertIsDisplayed().performClick()
                assertReadingStart()
                server.releaseContinuationAtStart.countDown()
                awaitStreamingProjection(textThrough(3))
                compose.waitForIdle()
                assertReadingStart()

                // The preview transition recreates Normal's effects. A real history selection
                // must keep the page intent instead of deriving it from an unmeasured list.
                clickTail()
                compose.onNodeWithContentDescription(context.getString(R.string.chat_page_chat_options))
                    .performClick()
                compose.onNode(hasText(prompt) and hasClickAction() and hasAnyAncestor(historyMatcher))
                    .assertIsDisplayed().performClick()
                assertReadingStart()
                server.releaseContinuationAfterPreview.countDown()
                awaitStreamingProjection(textThrough(4))
                compose.waitForIdle()
                assertReadingStart()

                clickTail()
                server.releaseContinuationFollowingAgain.countDown()
                awaitStreamingText(textThrough(5), server.continuationParagraphs[5])
                assertStreamingTail(server.continuationParagraphs[5])
                history.performTouchInput {
                    swipeDown(startY = height * 0.3f, endY = height * 0.5f, durationMillis = 800)
                }
                compose.waitForIdle()
                val beforeFling = history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue(beforeFling.value() < beforeFling.maxValue())
                history.performTouchInput {
                    swipeUp(startY = height * 0.75f, endY = height * 0.25f, durationMillis = 100)
                }
                assertStreamingTail(server.continuationParagraphs[5])
                server.releaseContinuationAfterFling.countDown()
                awaitStreamingText(server.continuationText, server.continuationParagraphs[6])
                assertStreamingTail(server.continuationParagraphs[6])
                server.releaseContinuationFinish.countDown()
                val finished = awaitUi(60_000) { query.conversationUiModel(reopenedView).first {
                    it != null && it.presentation.activeTurnId == null &&
                        it.snapshot.currentMessages().lastOrNull()?.parts
                            ?.filterIsInstance<UIMessagePart.Text>()?.singleOrNull()?.text == server.continuationText
                }!! }
                assertEquals(originalHandle.assistantMessageId, finished.snapshot.currentMessages().last().id)
                assertNull(finished.snapshot.currentMessages().last().terminalStatus)
                assertEquals(StepOutcome.Final, finished.snapshot.currentMessages().last().parts
                    .filterIsInstance<UIMessagePart.Step>().last().outcome)
                val finalTurns = awaitUi(30_000) { repository.getTurnExecutions(row.id) }
                assertEquals(waitingTurns.map { it.turnId }.toSet(), finalTurns.map { it.turnId }.toSet())
                assertEquals(TurnExecutionStatus.COMPLETED, finalTurns.single { it.turnId == originalTurn.turnId }.status)

                // Regenerating a middle USER uses the destructive UI confirmation. Cancel must
                // preserve both Room and the published tree; confirm must prune the later Turn.
                val beforeRegenerate = awaitUi(30_000) { requireNotNull(query.aggregateSnapshot(row.id)) }
                val beforeRegenerateRoom = awaitUi(30_000) { requireNotNull(repository.getConversationSnapshotById(row.id)) }
                val targetIndex = beforeRegenerate.nodes.indexOfFirst {
                    it.currentMessage.role == MessageRole.USER && it.currentMessage.toText() == followUp
                }
                assertTrue(targetIndex > 0)
                val retainedNodes = beforeRegenerate.nodes.take(targetIndex + 1)
                val removedNodes = beforeRegenerate.nodes.drop(targetIndex + 1)
                assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
                    removedNodes.map { it.currentMessage.role })
                val removedMessageIds = removedNodes.flatMap { it.messages }.map { it.id }.toSet()
                val transcript = beforeRegenerate.currentMessages().map { it.toText() }.toSet()
                val warning = uiContext.getString(R.string.regenerate_confirm_message)
                val beforeRegenerateRequests = server.modelRequests.size
                val beforeRegenerateDirect = server.directRequests.size
                clickMessageAction(uiContext, followUp, transcript, R.string.regenerate)
                compose.onNodeWithText(warning).assertIsDisplayed()
                compose.onNode(hasText(uiContext.getString(R.string.cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
                compose.onNodeWithText(warning).assertDoesNotExist()
                assertEquals(beforeRegenerate, awaitUi(30_000) { query.aggregateSnapshot(row.id) })
                assertEquals(beforeRegenerateRoom, awaitUi(30_000) { repository.getConversationSnapshotById(row.id) })
                assertEquals(beforeRegenerateRequests, server.modelRequests.size)
                assertEquals(beforeRegenerateDirect, server.directRequests.size)

                clickMessageAction(uiContext, followUp, transcript, R.string.regenerate)
                compose.onNodeWithText(warning).assertIsDisplayed()
                compose.onNode(hasText(uiContext.getString(R.string.confirm)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(60_000) { server.modelRequests.size == beforeRegenerateRequests + 1 }
                awaitUi(30_000) { query.conversationUiModel(reopenedView).first {
                    it != null && it.presentation.activeTurnId == null &&
                        it.snapshot.currentMessages().lastOrNull()?.toText() == "V5 enterprise USER regenerated answer"
                } }
                compose.onNodeWithText(warning).assertDoesNotExist()
                val regenerated = awaitUi(30_000) { requireNotNull(query.aggregateSnapshot(row.id)) }
                assertEquals(retainedNodes, regenerated.nodes.take(retainedNodes.size))
                assertEquals(retainedNodes.size + 1, regenerated.nodes.size)
                assertEquals(saved, regenerated.opening)
                assertTrue(regenerated.nodes.none { node -> node.messages.any { it.id in removedMessageIds } })
                val retainedAdmissions = beforeRegenerate.contextAdmissions.filter { it.owner.messageId !in removedMessageIds }.associateBy { it.id }
                assertEquals(1, retainedAdmissions.size)
                assertEquals(retainedAdmissions, regenerated.contextAdmissions.filter { it.id in retainedAdmissions }.associateBy { it.id })
                assertEquals(2, regenerated.contextAdmissions.size)
                assertTrue(regenerated.contextAdmissions.none { it.owner.messageId in removedMessageIds })
                val newAdmission = regenerated.contextAdmissions.single { it.id !in retainedAdmissions }
                assertEquals(regenerated.nodes.last().currentMessage.id, newAdmission.owner.messageId)
                val retainedEntries = beforeRegenerate.modelContextEntries.filter {
                    it.ownerMessageId !in removedMessageIds && it.anchorMessageId !in removedMessageIds
                }.associateBy { it.id }
                assertEquals(retainedEntries, regenerated.modelContextEntries.filter { it.id in retainedEntries }.associateBy { it.id })
                assertTrue(regenerated.modelContextEntries.none {
                    it.ownerMessageId in removedMessageIds || it.anchorMessageId in removedMessageIds
                })
                val regeneratedIds = regenerated.modelContextEntries.map { it.id }.toSet()
                assertTrue(regenerated.contextAdmissions.all { admission -> admission.uses.all { it.entryId in regeneratedIds } &&
                    admission.selection?.let { it.systemEntryId in regeneratedIds && it.ruleEntryIds.all { id -> id in regeneratedIds } } != false })
                val regeneratedRoom = awaitUi(30_000) { requireNotNull(repository.getConversationSnapshotById(row.id)) }
                assertEquals(regenerated.nodes, regeneratedRoom.nodes)
                assertEquals(regenerated.modelContextEntries.associateBy { it.id }, regeneratedRoom.modelContextEntries.associateBy { it.id })
                assertEquals(regenerated.contextAdmissions.associateBy { it.id }, regeneratedRoom.contextAdmissions.associateBy { it.id })
                val regeneratedRequest = synchronized(server.modelRequests) { server.modelRequests.last() }.getValue("messages").toString()
                assertTrue(regeneratedRequest.contains(followUp))
                listOf("V5 user model response", "Ask before continuing this enterprise turn", server.continuationParagraphs.first().first())
                    .forEach { assertFalse("Pruned transcript replayed: $it", regeneratedRequest.contains(it)) }
                assertEquals(beforeRegenerateDirect, server.directRequests.size)
                capture(evidence, "07-enterprise-user-regenerated.png")

                clickMessageAction(uiContext, "V5 enterprise USER regenerated answer",
                    regenerated.currentMessages().map { it.toText() }.toSet(), R.string.regenerate)
                compose.onNodeWithText(warning).assertDoesNotExist()
                compose.waitUntil(60_000) { server.modelRequests.size == beforeRegenerateRequests + 2 }
                awaitUi(30_000) { query.conversationUiModel(reopenedView).first {
                    it != null && it.presentation.activeTurnId == null &&
                        it.snapshot.currentMessages().lastOrNull()?.toText() == "V5 enterprise ASSISTANT regenerated answer"
                } }
                val variant = awaitUi(30_000) { requireNotNull(query.aggregateSnapshot(row.id)) }
                assertEquals(regenerated.nodes.dropLast(1), variant.nodes.dropLast(1))
                assertEquals(regenerated.nodes.last().id, variant.nodes.last().id)
                assertEquals(regenerated.nodes.last().messages.size + 1, variant.nodes.last().messages.size)
                assertTrue(variant.nodes.last().messages.containsAll(regenerated.nodes.last().messages))
                assertEquals("V5 enterprise ASSISTANT regenerated answer", variant.nodes.last().currentMessage.toText())
                assertEquals(saved, variant.opening)
                assertEquals(regenerated.contextAdmissions.associateBy { it.id },
                    variant.contextAdmissions.filter { it.id in regenerated.contextAdmissions.map { a -> a.id } }.associateBy { it.id })
                assertEquals(regenerated.modelContextEntries.associateBy { it.id },
                    variant.modelContextEntries.filter { it.id in regeneratedIds }.associateBy { it.id })
                val variantRoom = awaitUi(30_000) { requireNotNull(repository.getConversationSnapshotById(row.id)) }
                assertEquals(variant.nodes, variantRoom.nodes)
                assertEquals(variant.contextAdmissions.associateBy { it.id }, variantRoom.contextAdmissions.associateBy { it.id })
                assertEquals(variant.modelContextEntries.associateBy { it.id }, variantRoom.modelContextEntries.associateBy { it.id })
                assertEquals(beforeRegenerateDirect, server.directRequests.size)
                capture(evidence, "08-enterprise-assistant-variant.png")
                File(evidence, "enterprise-regenerate-proof.txt").writeText(
                    "actual_user_cancel_preserves_room_and_projection=true\nactual_user_confirm_prunes_suffix=true\n" +
                        "retained_context_preserved=true\nretired_context_not_replayed=true\n" +
                        "actual_assistant_regenerate_retains_variant=true\nmanaged_next_start_transport=true\n",
                )
                val finalConfiguration = awaitUi(30_000) { queries.read(access) }
                assertEquals(managedModel, finalConfiguration.assistantModel(assistant.id).reference)
                assertEquals(candidate.assistants, requireNotNull(finalConfiguration.enterpriseConfiguration).assistants)
                assertEquals(personalSelections, awaitUi(30_000) { queries.read(RealmAccess.Personal).storedSelections })
                val personalFinal = awaitUi(30_000) { settings.withExecutionConfiguration(
                    ConfigurationScope.Personal, sessions.state.value) { it.userSettings } }
                assertEquals(personalSettings.providers, personalFinal.providers)
                assertEquals(personalSettings.assistants, personalFinal.assistants)
                assertEquals(personalConversation, awaitUi(30_000) { settings.lastConversation(ConfigurationScope.Personal) })
                File(evidence, "enterprise-continue-proof.txt").writeText(
                    "actual_ask_user_ui=true\noriginal_turn_handle=true\nroom_turn_reused=true\n" +
                        "admissions_retained=true\nfrozen_user_transport=true\nfrozen_system_and_tools=true\n" +
                        "typed_memory_usage=true\nexternal_memory_only_new_entry=true\nexternal_memory_before_resumed_step=true\n" +
                        "managed_preference_changed_without_retargeting=true\n" +
                        "waiting_ui_idle=true\nstreaming_chunks_exceed_viewport=true\n" +
                        "streaming_ime_show_hide_keeps_tail=true\nstreaming_volume_keys_move_and_resume_tail=true\n" +
                        "streaming_chunks_idle_at_tail=true\nuser_scroll_anchor_preserved_during_growth=true\n" +
                        "jump_start_preserved_during_growth=true\npreview_jump_preserved_during_growth=true\n" +
                        "explicit_tail_resumes_following=true\nfling_to_tail_resumes_following=true\n",
                )

                // This lease remains open: rejection must come from the original Session, not page closure.
                val originalView = awaitUi(30_000) {
                    conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, access))
                }.also { revokedView = it }
                val exitResult = awaitUi(60_000) {
                    enterprise.exit(requireNotNull(enterprise.captureExitRequest()))
                }
                assertNull(exitResult.maintenanceFailure)
                assertNull(exitResult.remoteLogoutFailure)
                val exited = (sessions.state.value as EnterpriseState.Available).manifest
                assertEquals(EnterpriseSessionPhase.SIGNED_OUT, exited.phase)
                assertNull(exited.session)
                assertNull(exited.applied)
                assertEquals(ConfigurationScope.Personal, exited.selectedScope)
                assertNull(awaitUi(30_000) { sessions.pendingExit() })
                assertFalse(awaitUi(30_000) { sessions.observeRealmAccess(access).first() })
                val afterExit = awaitUi(30_000) { requireNotNull(repository.getConversationSnapshotById(row.id)) }
                val afterExitTurns = awaitUi(30_000) { repository.getTurnExecutions(row.id) }
                val afterExitRequests = server.modelRequests.size to server.directRequests.size
                suspend fun rejectOriginalTarget() {
                    originalView.requireOpen()
                    assertFalse(originalView.closed.value)
                    try {
                        conversations.updateTitle(originalView.commandTarget, "Must not replace the retained title")
                        fail("An exited Session must not authorize its still-open target")
                    } catch (rejected: EnterpriseConfigurationException) {
                        assertEquals("enterprise_data_access_unavailable", rejected.reason)
                    }
                    assertEquals(afterExit, repository.getConversationSnapshotById(row.id))
                    assertEquals(afterExitTurns, repository.getTurnExecutions(row.id))
                    assertEquals(afterExitRequests, server.modelRequests.size to server.directRequests.size)
                }
                awaitUi(30_000) { rejectOriginalTarget() }

                // A real personal durable write needs no model selection change or external generation.
                val personal = awaitUi(30_000) { queries.read(RealmAccess.Personal) }
                assertEquals(personalSelections, personal.storedSelections)
                val personalPage = awaitUi(30_000) {
                    conversations.initialize(ConversationOpenRequest.NewDraft(
                        Uuid.random(), RealmAccess.Personal, requireNotNull(personal.selections.assistantId),
                    ))
                }.also { personalProbeView = it }
                val probeText = "Personal durable input remains available after enterprise exit"
                val receipt = awaitUi(30_000) {
                    requireNotNull(koin.get<ConversationTurnService>().sendMessage(
                        personalPage.commandTarget, listOf(UIMessagePart.Text(probeText)), answer = false,
                    ))
                }
                val personalRow = awaitUi(30_000) {
                    requireNotNull(repository.getConversationSnapshotById(personalPage.conversationId))
                }
                assertEquals(ConfigurationScope.Personal, personalRow.header.scope)
                assertEquals(probeText, personalRow.currentMessages().single { it.id == receipt.userMessageId }.toText())
                awaitUi(30_000) { conversations.delete(personalPage.commandTarget) }
                assertNull(awaitUi(30_000) { repository.getConversationSnapshotById(personalPage.conversationId) })
                personalPage.close()
                personalProbeView = null
                assertEquals(personalConversation, awaitUi(30_000) { settings.lastConversation(ConfigurationScope.Personal) })

                // Fail after exchange, then after Bootstrap. Both retries must resume this exact
                // session through the real owners without exchanging another one-use code.
                server.failNextBootstrap.set(true)
                val pendingFailure = awaitUi(60_000) {
                    try { cleanupAccess(); null }
                    catch (error: PlatformHttpException) { error }
                }
                assertEquals("fixture_reenrollment_bootstrap_failed", pendingFailure?.problem?.code)
                assertTrue(pendingFailure?.message.orEmpty().contains("Fixture Bootstrap unavailable"))
                val pendingManifest = (sessions.state.value as EnterpriseState.Available).manifest
                val pendingEnrollment = requireNotNull(pendingManifest.pendingEnrollment)
                assertNull(pendingManifest.session)
                assertEquals(reenrollmentSessionId, pendingEnrollment.sessionId)
                assertEquals(access.scope.userId, pendingEnrollment.userId)
                assertEquals(access, fixtureAccess)
                assertEquals(2, server.enrollments.get())
                awaitUi(30_000) { rejectOriginalTarget() }

                server.failNextSnapshot.set(true)
                val synchronizationFailure = awaitUi(60_000) {
                    try { cleanupAccess(); null }
                    catch (error: IllegalStateException) {
                        if (error is CancellationException) throw error
                        error
                    }
                }
                assertEquals("fixture_reenrollment_did_not_complete", synchronizationFailure?.message)
                val publishedManifest = (sessions.state.value as EnterpriseState.Available).manifest
                assertNull(publishedManifest.pendingEnrollment)
                assertEquals(pendingEnrollment.sessionId, requireNotNull(publishedManifest.session).id)
                assertEquals(access.scope, publishedManifest.session.identity.scope)
                assertEquals(EnterpriseSessionPhase.CONFIGURATION_PENDING, publishedManifest.phase)
                assertEquals(ConfigurationScope.Personal, publishedManifest.selectedScope)
                assertEquals(RealmAccess.Enterprise(access.scope, pendingEnrollment.sessionId), fixtureAccess)
                val presentedFailure = awaitUi(30_000) { enterprise.observe().first { it.synchronization?.failure != null } }
                assertTrue(requireNotNull(presentedFailure.synchronization?.failure).diagnostic
                    .contains("Fixture snapshot unavailable"))
                assertEquals(2, server.enrollments.get())
                awaitUi(30_000) { rejectOriginalTarget() }

                // The same published session can synchronize, switch, and clean only its principal.
                val renewed = awaitUi(60_000) { cleanupAccess() }
                assertEquals(access.scope, renewed.scope)
                assertNotEquals(access.sessionId, renewed.sessionId)
                assertEquals(pendingEnrollment.sessionId, renewed.sessionId)
                assertEquals(2, server.enrollments.get())
                assertFalse(awaitUi(30_000) { sessions.observeRealmAccess(access).first() })
                awaitUi(30_000) { rejectOriginalTarget() }
                val renewedPage = awaitUi(30_000) {
                    conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, renewed))
                }
                renewedPage.close()
                File(evidence, "enterprise-exit-proof.txt").writeText(
                    "real_exit_owner=true\nstill_open_old_target_rejected=true\nretained_room_unchanged=true\n" +
                        "personal_durable_append_and_delete=true\nsame_principal_new_session=true\nold_session_not_revived=true\n" +
                        "pending_enrollment_retry_same_session=true\npublished_session_retry_same_session=true\none_exchange_per_session=true\n",
                )
            }
            File(evidence, "evidence.txt").writeText("schema=5\nhttp_sync=true\nentry=actual_empty_chat_card\n" +
                "real_chat_vm=true\nprovider_adapter=true\nroom_readback=true\nactivity_reopen=true\nrelease=${saved.releaseId}\n" +
                "snapshot_hash=${saved.snapshotHash}\nordered_backgrounds=${backgrounds.size}\ncore_http=$core\n" +
                "enterprise_user_model_ui=${server != null}\ndirect_user_transport=${server != null}\n" +
                "personal_settings_preserved=${server != null}\nresult=PASS\n")
        } catch (error: Throwable) {
            failure = error
            try {
                server?.let { File(evidence, "request-diagnostics.txt").writeText(it.diagnostics()) }
                capture(evidence, "failure.png")
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                File(evidence, "failure-semantics.txt").writeText(roots.fetchSemanticsNodes().indices
                    .joinToString("\n\n") { roots[it].printToString() })
            } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
            throw error
        } finally {
            server?.releaseContinuation?.countDown()
            server?.releaseContinuationTail?.countDown()
            server?.releaseContinuationWhileReading?.countDown()
            server?.releaseContinuationAtStart?.countDown()
            server?.releaseContinuationAfterPreview?.countDown()
            server?.releaseContinuationFollowingAgain?.countDown()
            server?.releaseContinuationAfterFling?.countDown()
            server?.releaseContinuationFinish?.countDown()
            val primaryFailure = failure
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            cleanup { activity?.close() }
            cleanup { view?.close() }
            cleanup { revokedView?.close() }
            cleanup { personalProbeView?.let { page -> runBlocking { withTimeout(30_000) {
                val presentation = sessions.readPresentation()
                if (presentation.selection?.access != RealmAccess.Personal) {
                    throw IllegalStateException("personal_probe_cleanup_requires_original_personal_selection")
                }
                try { conversations.delete(page.commandTarget) } finally { page.close() }
            } } } }
            cleanup { createdMemory?.let { (previous, id) -> runBlocking { withTimeout(60_000) {
                val current = cleanupAccess()
                val memories = koin.get<MemoryService>()
                val renewed = requireNotNull(memories.observe(current, previous.assistantId).first { it.access != null }.access)
                check(renewed.address == previous.address) { "fixture_memory_namespace_changed" }
                memories.delete(renewed, id)
            } } } }
            cleanup { runBlocking { withTimeout(60_000) {
                val current = (sessions.state.value as EnterpriseState.Available).manifest
                if (current.session != null || fixtureAccess != null && listOfNotNull(forkedId, createdId).isNotEmpty()) {
                    val access = cleanupAccess()
                    listOfNotNull(forkedId, createdId).forEach { id ->
                        val lease = conversations.initialize(ConversationOpenRequest.OpenExisting(id, access))
                        try { conversations.delete(lease.commandTarget) } finally { lease.close() }
                    }
                    val result = enterprise.exit(requireNotNull(enterprise.captureExitRequest()))
                    check(result.maintenanceFailure == null && result.remoteLogoutFailure == null) { "starter_exit_cleanup_failed: $result" }
                }
            } } }
            cleanup { userProvider?.let { provider -> runBlocking {
                settings.updateLocal { it.copy(
                    providers = it.providers.filterNot { candidate -> candidate.id == provider.id },
                    displaySetting = it.displaySetting.copy(
                        enableVolumeKeyScroll = priorDisplay.enableVolumeKeyScroll,
                        volumeKeyScrollRatio = priorDisplay.volumeKeyScrollRatio,
                    ),
                ) }
            } } }
            cleanup { context.writeBooleanPreference("create_new_conversation_on_start", priorNewChat) }
            cleanup {
                val current = coil3.SingletonImageLoader.get(context)
                coil3.SingletonImageLoader.setUnsafe(priorImageLoader)
                if (current !== priorImageLoader) current.shutdown()
            }
            cleanup { server?.close() }
            cleanup { server?.let { File(evidence, "request-diagnostics.txt").writeText(it.diagnostics()) } }
            if (primaryFailure == null) failure?.let { throw it }
        }
    }

    private fun clickMessageAction(context: Context, messageText: String, transcriptTexts: Set<String>, actionLabel: Int) {
        val historyMatcher = hasScrollToIndexAction() and SemanticsMatcher("visible chat history") {
            it.boundsInRoot.left >= 0f && it.boundsInRoot.right > 0f
        }
        val history = compose.onNode(historyMatcher)
        // Enter history through the same user action as the page before locating an old message.
        history.performTouchInput {
            swipeDown(startY = height * 0.25f, endY = height * 0.65f, durationMillis = 500)
        }
        compose.onNodeWithTag("ChatMessageJumperStart").assertIsDisplayed().performClick()
        history.performScrollToNode(hasText(messageText))
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        val nodes = flatten(compose.onNode(historyMatcher, useUnmergedTree = true).fetchSemanticsNode())
        val text = nodes.single { hasText(messageText).matches(it) }
        val matcher = hasContentDescription(context.getString(actionLabel)) and hasClickAction()
        // Stop at the next transcript body, so a missing action cannot select another message.
        val action = nodes.drop(nodes.indexOf(text) + 1).first {
            matcher.matches(it) || transcriptTexts.any { body -> body != messageText && hasText(body).matches(it) }
        }
        check(matcher.matches(action)) { "enterprise_message_action_missing: $messageText" }
        val target = compose.onNode(SemanticsMatcher("message action for $messageText") { it.id == action.id }, useUnmergedTree = true)
        target.performScrollTo()
        repeat(6) {
            compose.waitForIdle()
            val list = history.fetchSemanticsNode().boundsInRoot
            val input = compose.onNodeWithTag("chat_input").fetchSemanticsNode().boundsInRoot
            val bounds = target.fetchSemanticsNode().boundsInRoot
            val height = input.top - list.top
            assertTrue("Chat action needs a touchable viewport", height > 0f)
            if (bounds.top >= list.top + height * 0.03f && bounds.bottom <= input.top - height * 0.03f) {
                target.assertIsDisplayed().assertIsEnabled().performClick()
                return
            }
            history.performTouchInput {
                if (bounds.top < list.top + height * 0.03f) {
                    swipeDown(startY = height * 0.2f, endY = height * 0.7f, durationMillis = 800)
                } else {
                    swipeUp(startY = height * 0.7f, endY = height * 0.2f, durationMillis = 800)
                }
            }
        }
        error("enterprise_message_action_hidden_by_composer: $messageText")
    }

    private fun <T> awaitUi(timeout: Long, action: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val pending = scope.async { withTimeout(timeout) { action() } }
        return try { compose.waitUntil(timeout + 5_000) { pending.isCompleted }; runBlocking { pending.await() } }
        finally { scope.cancel() }
    }

    private fun capture(directory: File, name: String) {
        if (name != "failure.png") {
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}

/** Local Core control and OpenAI runtime endpoints, with captured requests and no upstream forwarding. */
private class StarterV5HttpMock(private val fixture: JsonObject) : AutoCloseable {
    private val socket = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    val origin = "http://127.0.0.1:${socket.localPort}"
    val deploymentId = fixture.getValue("snapshot").jsonObject.getValue("deploymentId").jsonPrimitive.content
    val enrollments = AtomicInteger()
    val downloads = AtomicInteger()
    val appliedReports = AtomicInteger()
    // Chat requests only. The same endpoint also receives single-USER auxiliary generations.
    val modelRequests = Collections.synchronizedList(mutableListOf<JsonObject>())
    data class DirectRequest(val path: String, val authorization: String?, val body: JsonObject)
    val directRequests = Collections.synchronizedList(mutableListOf<DirectRequest>())
    private val requestDiagnostics = ArrayDeque<String>()
    private val initialChatRequests = AtomicInteger()
    private val regenerationRequests = AtomicInteger()
    private val consumedEnrollmentCodes = mutableSetOf<String>()
    private val sessionId = java.util.concurrent.atomic.AtomicReference(
        fixture.getValue("enrollment-response").jsonObject.getValue("sessionId").jsonPrimitive.content,
    )
    private val starter = fixture.getValue("snapshot").jsonObject.getValue("starters").jsonArray.single().jsonObject
    private val openingSystem = starter.getValue("openingSnapshot").jsonObject.getValue("systemPrompt").jsonPrimitive.content
    val failNextBootstrap = java.util.concurrent.atomic.AtomicBoolean()
    val failNextSnapshot = java.util.concurrent.atomic.AtomicBoolean()
    fun prepareReenrollment(): String = "ses_${Uuid.random()}".also(sessionId::set)
    fun diagnostics(): String = synchronized(requestDiagnostics) { requestDiagnostics.joinToString("\n") }
    private fun recordDiagnostic(message: String) = synchronized(requestDiagnostics) {
        if (requestDiagnostics.size == 32) requestDiagnostics.removeFirst()
        requestDiagnostics.addLast(message.take(512))
    }
    private fun contentText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        else -> ""
    }
    private fun isAuxiliaryRequest(path: String, request: JsonObject): Boolean {
        val messages = request.getValue("messages").jsonArray.map { it.jsonObject }
        val roles = messages.map { it.getValue("role").jsonPrimitive.content }
        val streaming = request["stream"]?.jsonPrimitive?.booleanOrNull == true
        val tools = request["tools"]?.jsonArray?.size ?: 0
        val auxiliary = roles == listOf("user") && tools == 0
        val chat = messages.any { it["role"]?.jsonPrimitive?.content in setOf("system", "developer") &&
            contentText(it["content"]).contains(openingSystem) } && "user" in roles
        recordDiagnostic("route=${if (path.startsWith("/user/")) "direct" else "managed"} " +
            "kind=${if (auxiliary) "auxiliary" else if (chat) "chat" else "unexpected"} " +
            "roles=${roles.joinToString(",")} stream=$streaming tools=$tools " +
            "contentLengths=${messages.joinToString(",") { contentText(it["content"]).length.toString() }}")
        check(auxiliary || chat) { "unexpected_fixture_request_shape" }
        return auxiliary
    }
    val continuationReceived = CountDownLatch(1)
    val releaseContinuation = CountDownLatch(1)
    val releaseContinuationTail = CountDownLatch(1)
    val releaseContinuationWhileReading = CountDownLatch(1)
    val releaseContinuationAtStart = CountDownLatch(1)
    val releaseContinuationAfterPreview = CountDownLatch(1)
    val releaseContinuationFollowingAgain = CountDownLatch(1)
    val releaseContinuationAfterFling = CountDownLatch(1)
    val releaseContinuationFinish = CountDownLatch(1)
    val continuationParagraphs = listOf("first", "tail", "while reading", "at start", "after preview", "following again", "after fling").map { segment ->
        (1..48).map { line -> "V5 stream $segment paragraph $line" }
    }
    val continuationText = continuationParagraphs.flatten().joinToString("\n\n")
    private val handlers = Executors.newCachedThreadPool()
    private val worker = thread(name = "starter-v5-http") {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
            handlers.submit { client.use {
                try { serve(it) }
                catch (error: Exception) {
                    recordDiagnostic("handler_failure=${error.javaClass.simpleName}")
                    // Return a bounded failure without echoing request bodies, headers or credentials.
                    val body = "{\"error\":{\"message\":\"starter_fixture_request_failed\",\"type\":\"${error.javaClass.simpleName}\"}}".toByteArray()
                    try { it.getOutputStream().apply {
                        write("HTTP/1.1 500 Fixture Failure\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    } } catch (_: java.io.IOException) { /* The cancelled request may already be closed. */ }
                }
            } }
        }
    }
    private val expiry = Instant.now().plusSeconds(7200).toString()
    private fun live(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            when {
                key.endsWith("ExpiresAt") || key == "expiresAt" -> JsonPrimitive(expiry)
                key == "sessionId" -> JsonPrimitive(sessionId.get())
                else -> live(child)
            }
        })
        is JsonArray -> JsonArray(value.map(::live))
        else -> value
    }
    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val input = client.getInputStream().buffered()
        fun line(): String = buildString {
            while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) append(b.toChar()) }
        }
        val path = line().split(' ').getOrNull(1)?.substringBefore('?') ?: return
        val headers = buildMap { while (true) {
            val value = line(); if (value.isEmpty()) break
            put(value.substringBefore(':').lowercase(), value.substringAfter(':').trim())
        } }
        val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
        var offset = 0
        while (offset < bytes.size) { val count = input.read(bytes, offset, bytes.size - offset); check(count > 0); offset += count }
        var status = 200
        var type = "application/json"
        var extra = ""
        var continuationChunks: List<String>? = null
        val request = if (path == "/user/v1/chat/completions" ||
            path.startsWith("/runtime/v1/resources/") && path.endsWith("/v1/chat/completions")) {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } else null
        val body = when {
            request != null && isAuxiliaryRequest(path, request) -> {
                if (request["stream"]?.jsonPrimitive?.booleanOrNull == true) {
                    type = "text/event-stream"
                    "data: {\"id\":\"mock-auxiliary\",\"model\":\"starter-v5-mock\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                } else """{"id":"mock-auxiliary","model":"starter-v5-mock","choices":[{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"stop"}]}"""
            }
            path == "/.well-known/measix" -> fixture.getValue("discovery").toString()
            path.endsWith("/enrollments/exchange") -> {
                val code = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("code").jsonPrimitive.content
                check(synchronized(consumedEnrollmentCodes) { consumedEnrollmentCodes.add(code) }) { "fixture_enrollment_code_reused" }
                enrollments.incrementAndGet(); status = 201; live(fixture.getValue("enrollment-response")).toString()
            }
            path.endsWith("/bootstrap") && failNextBootstrap.compareAndSet(true, false) -> {
                status = 503
                """{"type":"about:blank","title":"Fixture Bootstrap unavailable","status":503,"code":"fixture_reenrollment_bootstrap_failed","detail":"Fixture Bootstrap unavailable"}"""
            }
            path.endsWith("/bootstrap") -> live(fixture.getValue("bootstrap")).toString()
            path.endsWith("/sessions/refresh") -> live(fixture.getValue("refresh-response")).toString()
            path.endsWith("/managed/state") -> fixture.getValue("bootstrap").jsonObject.getValue("managedState").toString()
            path.endsWith("/managed/snapshots/42") && failNextSnapshot.compareAndSet(true, false) -> {
                status = 503
                """{"type":"about:blank","title":"Fixture snapshot unavailable","status":503,"code":"fixture_reenrollment_snapshot_failed","detail":"Fixture snapshot unavailable"}"""
            }
            path.endsWith("/managed/snapshots/42") -> {
                downloads.incrementAndGet()
                extra = "ETag: \"${fixture.getValue("snapshot").jsonObject.getValue("snapshotHash").jsonPrimitive.content}\"\r\n"
                fixture.getValue("snapshot").toString()
            }
            path.endsWith("/managed/applied") -> { appliedReports.incrementAndGet(); status = 204; "" }
            path.endsWith("/sessions/logout") -> { status = 204; "" }
            path == "/user/v1/chat/completions" -> {
                val request = requireNotNull(request)
                directRequests += DirectRequest(path, headers["authorization"], request)
                val messages = request.getValue("messages").jsonArray.map { it.jsonObject }
                val continuing = messages.any { it["role"]?.jsonPrimitive?.content == "tool" &&
                    it["tool_call_id"]?.jsonPrimitive?.content == "enterprise-answer" }
                val asking = !continuing && messages.lastOrNull { it["role"]?.jsonPrimitive?.content == "user" }
                    ?.get("content")?.toString()?.contains("Ask before continuing this enterprise turn") == true
                if (continuing) {
                    continuationReceived.countDown()
                    check(releaseContinuation.await(60, TimeUnit.SECONDS)) { "enterprise_continue_response_not_released" }
                }
                val message = buildJsonObject {
                    put("role", "assistant")
                    if (asking) putJsonArray("tool_calls") { addJsonObject {
                        put("index", 0); put("id", "enterprise-answer"); put("type", "function")
                        putJsonObject("function") {
                            put("name", "ask_user")
                            put("arguments", """{"questions":[{"id":"continue","question":"Keep this enterprise turn?","selection_type":"single","options":["Proceed with original turn"]}]}""")
                        }
                    } } else put("content", if (continuing) continuationText else "V5 user model response")
                }
                val finishReason = if (asking) "tool_calls" else "stop"
                if (request["stream"]?.jsonPrimitive?.booleanOrNull == true) {
                    type = "text/event-stream"
                    val terminal = "data: {\"id\":\"mock-user-v5\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"starter-v5-mock\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"$finishReason\"}]}\n\ndata: [DONE]\n\n"
                    if (continuing) {
                        continuationChunks = continuationParagraphs.mapIndexed { index, paragraphs ->
                            val text = (if (index == 0) "" else "\n\n") + paragraphs.joinToString("\n\n")
                            val delta = buildJsonObject { put("role", "assistant"); put("content", text) }
                            "data: {\"id\":\"mock-user-v5\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"starter-v5-mock\",\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":null}]}\n\n"
                        } + terminal
                        continuationChunks!!.joinToString("")
                    } else "data: {\"id\":\"mock-user-v5\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"starter-v5-mock\",\"choices\":[{\"index\":0,\"delta\":$message,\"finish_reason\":null}]}\n\n" + terminal
                } else """{"id":"mock-user-v5","object":"chat.completion","created":1,"model":"starter-v5-mock","choices":[{"index":0,"message":$message,"finish_reason":"$finishReason"}]}"""
            }
            path.startsWith("/runtime/v1/resources/") && path.endsWith("/v1/chat/completions") -> {
                val request = requireNotNull(request)
                modelRequests += request
                val lastUser = request.getValue("messages").jsonArray.map { it.jsonObject }
                    .last { it["role"]?.jsonPrimitive?.content == "user" }.let { contentText(it["content"]) }
                val response = when {
                    lastUser.contains("Continue this enterprise conversation using my own model") -> when (regenerationRequests.incrementAndGet()) {
                        1 -> "V5 enterprise USER regenerated answer"
                        2 -> "V5 enterprise ASSISTANT regenerated answer"
                        else -> error("unexpected_enterprise_regeneration_request")
                    }
                    lastUser.contains(starter.getValue("prompt").jsonPrimitive.content) -> {
                        check(initialChatRequests.incrementAndGet() == 1) { "unexpected_repeated_starter_request" }
                        "V5 mock response"
                    }
                    else -> error("unexpected_enterprise_chat_source")
                }
                if (request["stream"]?.jsonPrimitive?.booleanOrNull == true) {
                    type = "text/event-stream"
                    "data: {\"id\":\"mock-v5\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"starter-v5-mock\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"$response\"},\"finish_reason\":null}]}\n\n" +
                        "data: {\"id\":\"mock-v5\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"starter-v5-mock\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                } else """{"id":"mock-v5","object":"chat.completion","created":1,"model":"starter-v5-mock","choices":[{"index":0,"message":{"role":"assistant","content":"$response"},"finish_reason":"stop"}]}"""
            }
            else -> { status = 404; """{"unexpected":"$path"}""" }
        }
        val content = body.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write("HTTP/1.1 $status OK\r\nContent-Type: $type\r\nContent-Length: ${content.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
            val chunks = continuationChunks
            if (chunks == null) {
                write(content); flush()
            } else {
                write(chunks[0].toByteArray(Charsets.UTF_8)); flush()
                check(releaseContinuationTail.await(60, TimeUnit.SECONDS)) { "enterprise_continue_tail_not_released" }
                write(chunks[1].toByteArray(Charsets.UTF_8)); flush()
                check(releaseContinuationWhileReading.await(60, TimeUnit.SECONDS)) { "enterprise_continue_reading_segment_not_released" }
                write(chunks[2].toByteArray(Charsets.UTF_8)); flush()
                check(releaseContinuationAtStart.await(60, TimeUnit.SECONDS)) { "enterprise_continue_start_segment_not_released" }
                write(chunks[3].toByteArray(Charsets.UTF_8)); flush()
                check(releaseContinuationAfterPreview.await(60, TimeUnit.SECONDS)) { "enterprise_continue_preview_segment_not_released" }
                write(chunks[4].toByteArray(Charsets.UTF_8)); flush()
                check(releaseContinuationFollowingAgain.await(60, TimeUnit.SECONDS)) { "enterprise_continue_following_segment_not_released" }
                write(chunks[5].toByteArray(Charsets.UTF_8)); flush()
                check(releaseContinuationAfterFling.await(60, TimeUnit.SECONDS)) { "enterprise_continue_fling_segment_not_released" }
                write(chunks[6].toByteArray(Charsets.UTF_8)); flush()
                check(releaseContinuationFinish.await(60, TimeUnit.SECONDS)) { "enterprise_continue_finish_not_released" }
                write(chunks[7].toByteArray(Charsets.UTF_8)); flush()
            }
        }
    }
    override fun close() {
        releaseContinuation.countDown()
        releaseContinuationTail.countDown()
        releaseContinuationWhileReading.countDown()
        releaseContinuationAtStart.countDown()
        releaseContinuationAfterPreview.countDown()
        releaseContinuationFollowingAgain.countDown()
        releaseContinuationAfterFling.countDown()
        releaseContinuationFinish.countDown()
        socket.close(); worker.join(5000); handlers.shutdown(); check(handlers.awaitTermination(30, TimeUnit.SECONDS))
    }
}
