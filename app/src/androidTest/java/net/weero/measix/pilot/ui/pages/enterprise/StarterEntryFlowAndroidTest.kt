package net.weero.measix.pilot.ui.pages.enterprise

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.ui.adaptive.*
import net.weero.measix.pilot.ui.components.ai.PromptPresetButton
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.hooks.ChatInputState
import net.weero.measix.pilot.ui.pages.chat.EnterpriseStarterRow
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.uuid.Uuid

/** Same real application/runtime owners and Applied fixture drive all three production entry surfaces. */
@RunWith(AndroidJUnit4::class)
class StarterEntryFlowAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private enum class Entry { SPACE, QUICK, CARD }

    @Test fun v4AndV5UseAllDraftEntriesAndReadyOnlyAppendsPrompt() {
        var fixture by mutableStateOf<Fixture?>(null)
        var entry by mutableStateOf(Entry.SPACE)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalSettings provides Settings(), LocalToaster provides rememberToasterState(),
                LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                fixture?.let { current -> key(current) {
                    val scope = rememberCoroutineScope()
                    val select: (ConversationStarterUiModel) -> Unit = { scope.launch { current.select() } }
                    Column {
                        when (entry) {
                            Entry.SPACE -> EnterpriseStarterPicker(current.selection,
                                onOpenDraft = { request -> scope.launch { current.open(request) } }, onDismiss = {},
                                queries = current.queries, conversations = current.application)
                            Entry.QUICK -> PromptPresetButton(emptyList(), listOf(current.chatStarter()), current.input,
                                requireInputOwner = {}, opening = null, isDraft = current.isDraft,
                                onStarterClick = select, onOpeningDetails = {})
                            Entry.CARD -> EnterpriseStarterRow(listOf(current.chatStarter()), 1f, select)
                        }
                    }
                } }
            }
        } }
        for (historical in listOf(true, false)) for (surface in Entry.entries) {
            val current = runBlocking { Fixture.create(historical, surface != Entry.SPACE) }
            try {
                compose.runOnIdle { entry = surface; fixture = current }
                if (surface == Entry.QUICK) compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_input_presets)).performClick()
                compose.onNodeWithText(current.definition.title).performClick()
                if (surface == Entry.SPACE) {
                    compose.onNodeWithText(compose.activity.getString(R.string.opening_context)).performClick()
                    if (historical) compose.onNodeWithText(compose.activity.getString(R.string.opening_missing)).assertIsDisplayed()
                    else compose.onNodeWithText(compose.activity.getString(R.string.opening_system)).assertIsDisplayed()
                    compose.onNodeWithText(compose.activity.getString(R.string.enterprise_starter_open)).performClick()
                }
                compose.waitUntil(10_000) { current.accepted == 1 || current.failure != null }
                current.failure?.let { throw AssertionError("$historical/$surface", it) }
                val snapshot = current.snapshot()
                assertEquals(if (surface == Entry.SPACE) current.definition.prompt else "Existing draft\n\n${current.definition.prompt}", current.input.textContent.text.toString())
                assertTrue(snapshot.header.newConversation)
                assertEquals(if (historical) null else current.definition, snapshot.opening?.definition)
                assertEquals(if (historical) null else "sha256:" + "a".repeat(64), snapshot.opening?.snapshotHash)
                assertEquals(if (surface == Entry.SPACE) emptyList<UIMessagePart>() else listOf(current.attachment), current.input.messageContent)
                if (surface == Entry.CARD) {
                    if (historical) compose.onNodeWithText(current.definition.title).assertIsNotSelected()
                    else compose.onNodeWithText(current.definition.title).assertIsSelected()
                }
                coVerify(exactly = 0) { current.repository.commit(any()) }
                runBlocking { current.promote() }
                val savedOpening = current.snapshot().opening
                compose.runOnIdle { entry = Entry.QUICK }
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_input_presets)).performClick()
                compose.onNodeWithText(current.definition.title).performClick()
                compose.waitUntil(10_000) { current.accepted == 2 || current.failure != null }
                current.failure?.let { throw AssertionError("Ready $historical/$surface", it) }
                assertEquals(savedOpening, current.snapshot().opening)
                assertFalse(current.snapshot().header.newConversation)
                assertTrue(current.input.textContent.text.toString().endsWith("\n\n${current.definition.prompt}"))
                coVerify(exactly = 1) { current.repository.commit(any()) }
            } finally {
                compose.runOnIdle { fixture = null }
                current.close()
            }
        }
    }

    private class Fixture private constructor(val root: File, val sessions: EnterpriseSessionController,
        val candidate: EnterpriseCandidate, val selection: RealmSelection) : AutoCloseable {
        val definition = candidate.configuration.starters.single()
        val assistant = candidate.identity.reference(definition.assistantId)
        val reference = candidate.identity.reference(definition.id)
        val repository = mockk<ConversationRepository>()
        private val settings = mockk<SettingsStore>()
        private val artifacts = mockk<ArtifactStore>()
        private val scope = AppScope(Dispatchers.Main.immediate)
        private val locks = ConversationOperationLocks()
        private val registry = ConversationRuntimeRegistry(scope, repository, locks)
        private val gate = ApplicationRecoveryGate().apply { ready() }
        private val commands = ConversationCommandCoordinator(registry, repository, gate, locks)
        val application = ConversationApplicationService(settings, repository, mockk(), registry, commands, gate,
            mockk(), mockk(), artifacts, mockk(), mockk(), JsonInstant, mockk(), mockk(), sessions, mockk())
        val queries = mockk<ConfigurationQueryService>()
        val input = ChatInputState()
        val attachment = UIMessagePart.Image("file:///existing-input.png")
        private var view: ConversationViewLease? = null
        @Volatile var accepted = 0
        @Volatile var failure: Throwable? = null
        var isDraft by mutableStateOf(true)
        @Composable fun chatStarter(): ConversationStarterUiModel {
            val snapshot by registry.requireRuntime(requireNotNull(view).conversationId).snapshot.collectAsState()
            return ConversationStarterUiModel(reference, definition.title, definition.prompt,
                snapshot.durable.header.newConversation && snapshot.durable.opening != null)
        }

        init {
            val resolved = ConfigurationResolver.resolve(UserSettingsDocument.empty(), candidate.identity.scope, sessions.state.value)
            coEvery { settings.withResolvedConfiguration<Any?>(any(), any(), any()) } coAnswers {
                thirdArg<suspend (ResolvedConfiguration) -> Any?>()(resolved)
            }
            coEvery { repository.getConversationHeader(any()) } returns null
            coEvery { repository.commit(any()) } returns true
            coEvery { artifacts.publishAllUnpublished(emptyList()) } returns Unit
            coEvery { artifacts.materializeConfigurationMessages(candidate.identity.scope, emptyList(), any()) } returns emptyList()
            val catalog = resolved.enterpriseStarterCatalog(selection)!!
            every { queries.observeEnterpriseStarters(selection) } returns MutableStateFlow(EnterpriseStarterReadState.Available(catalog))
            every { queries.observeAssistantCatalog() } returns MutableStateFlow(AssistantCatalogReadState.Available(
                AssistantCatalogUiModel(selection, ConfigurationSelection(assistant, null), resolved.assistants, emptyList())))
            coEvery { queries.requireSelection(selection) } returns Unit
            coEvery { queries.readEnterpriseStarterDetails(any<EnterpriseStarterTarget>()) } returns definition.details(true)
        }
        fun snapshot() = registry.requireRuntime(requireNotNull(view).conversationId).durable
        suspend fun open(request: StarterDraftRequest) = attempt {
            view = application.initialize(request.request)
            input.appendText(request.text)
        }
        suspend fun select() = attempt {
            val lease = requireNotNull(view)
            val target = ConversationAssistantTarget(lease.commandTarget, assistant)
            val accept: (String) -> Unit = { text -> input.appendText(if (input.textContent.text.isNotEmpty()) "\n\n$text" else text) }
            if (snapshot().header.newConversation) application.selectDraftStarter(target, reference,
                snapshot().draftOpeningSelectionToken, Uuid.random(), accept)
            else application.appendStarterPrompt(target, reference, accept)
        }
        private suspend fun attempt(action: suspend () -> Unit) {
            try { action(); accepted++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { failure = error }
        }
        suspend fun promote() {
            commands.executeOrThrow(requireNotNull(view).conversationId, AppendUserMessage(UIMessage.user("Committed prompt")))
            isDraft = false
        }
        override fun close() { view?.close(); scope.cancel(); check(root.deleteRecursively()) }
        companion object {
            suspend fun create(historical: Boolean, draft: Boolean): Fixture {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val root = File(context.noBackupFilesDir, "starter-ui-fixture-${Uuid.random()}").apply { check(mkdirs()) }
                val candidate = starterDeviceCandidate(historical)
                val store = EnterpriseAppliedStore(root)
                val applied = store.prepare(candidate)
                val id = "ses_${Uuid.random()}"
                val expiry = 2_000_000_000_000L
                val credential = store.prepareCredential(PlatformRefreshCredential(id, "ui-fixture-refresh", expiry))
                val execution = candidate.execution as EnterpriseExecution.Platform
                val session = EnterpriseSession(id, candidate.identity, expiry,
                    PlatformSessionDetails(execution.connection, "dev_${Uuid.random()}", credential))
                store.commit(EnterpriseManifest(6, EnterpriseSessionPhase.READY, session, applied, candidate.identity.scope, candidate.identity))
                val sessions = EnterpriseSessionController(store).apply { recover() }
                val fixture = Fixture(root, sessions, candidate, requireNotNull(sessions.readPresentation().selection))
                if (draft) {
                    fixture.view = fixture.application.initialize(fixture.application.newDraftRequest(fixture.selection, fixture.assistant))
                    fixture.input.setMessageText("Existing draft")
                    fixture.input.messageContent = listOf(fixture.attachment)
                }
                return fixture
            }
        }
    }
}
