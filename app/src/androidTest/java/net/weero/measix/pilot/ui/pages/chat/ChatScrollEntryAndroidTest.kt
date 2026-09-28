package net.weero.measix.pilot.ui.pages.chat

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.fts.MessageSearchSort
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.selectEnterpriseFixture
import net.weero.measix.pilot.data.enterprise.selectPersonalFixture
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.*
import net.weero.measix.pilot.ui.hooks.readBooleanPreference
import net.weero.measix.pilot.ui.hooks.writeBooleanPreference
import net.weero.measix.pilot.ui.theme.MeasixTheme
import net.weero.measix.pilot.ui.pages.favorite.FavoritePage
import net.weero.measix.pilot.ui.pages.search.SearchPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Real navigation entries, ChatVM, application commands and Room; no generated response is needed. */
@RunWith(AndroidJUnit4::class)
class ChatScrollEntryAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var navigator: Navigator? = null
    private var activity: ActivityScenario<RouteActivity>? = null

    @Test fun validAndInvalidNodeEntriesAndRecreationUseTheirDeclaredInitialPosition() = withHistory { first, _ ->
        val historical = first.route(first.nodes[3])
        launch(historical)
        assertHistory(first, 3)
        recreate(historical)
        assertHistory(first, 3)

        navigate(first.route(Uuid.random()))
        assertTail(first)
        navigate(first.route())
        assertTail(first)
        history().performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.8f, durationMillis = 800) }
        compose.waitForIdle()
        assertTrue(position() < maximum())
        // A normal entry reinitializes at the tail; this is not durable draft/reading-position recovery.
        recreate(first.route())
        assertTail(first)
    }

    @Test fun switchingEqualLengthConversationsSeparatesHistoryIntentAndKeepsRealVolumeAndImeInput() = withHistory { first, second ->
        assertEquals(first.nodes.size, second.nodes.size)
        launch(first.route(first.nodes[3]))
        assertHistory(first, 3)
        navigate(second.route())
        assertTail(second)
        compose.onNodeWithText(first.texts[3]).assertDoesNotExist()
        navigate(first.route(first.nodes[3]))
        assertHistory(first, 3)

        val beforeUp = position()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
        compose.waitUntil(30_000) { position() < beforeUp }
        compose.waitForIdle()
        val beforeDown = position()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.waitUntil(30_000) { position() > beforeDown }
        compose.waitForIdle()
        assertTrue(position() < maximum())

        repeat(2) {
            compose.onNodeWithTag("chat_input").performClick()
            compose.waitUntil(30_000) { imeVisible() }
            compose.waitForIdle()
            assertTrue("IME expansion must not jump a middle history entry to the tail", position() < maximum())
            closeSoftKeyboard()
            compose.waitUntil(30_000) { !imeVisible() }
            compose.waitForIdle()
            assertTrue("IME dismissal must not reset the route to the tail", position() < maximum())
        }
        navigate(second.route())
        assertTail(second)
    }

    @Test fun namedMessageJumpersMoveThroughHistoryAndReturnToItsStart() = withHistory { first, _ ->
        launch(first.route())
        assertTail(first)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = context.getString(R.string.chat_page_previous_message)
        val next = context.getString(R.string.chat_page_next_message)
        val start = context.getString(R.string.chat_page_scroll_to_top)
        val observations = mutableListOf<String>()
        fun observe(label: String) {
            observations += "$label: value=${position()}, max=${maximum()}, " +
                "list=${history().fetchSemanticsNode().boundsInRoot}, " +
                "input=${compose.onNodeWithTag("chat_input").fetchSemanticsNode().boundsInRoot}, " +
                "messages=${first.texts.mapIndexed { index, text ->
                    index to compose.onAllNodesWithText(text).fetchSemanticsNodes().map { it.boundsInRoot }
                }}, controls=${listOf(previous, next, start).associateWith { description ->
                    compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().map { it.boundsInRoot }
                }}"
            android.util.Log.i("ChatScrollEntry", observations.last())
        }
        fun revealJumpers() {
            val list = history().fetchSemanticsNode().boundsInRoot
            val input = compose.onNodeWithTag("chat_input").fetchSemanticsNode().boundsInRoot
            val touchableHeight = input.top - list.top
            assertTrue("History controls need a touchable area above the composer", touchableHeight > 0f)
            history().performTouchInput {
                swipeDown(startY = touchableHeight * 0.3f, endY = touchableHeight * 0.45f, durationMillis = 250)
            }
            compose.onNodeWithContentDescription(start).assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithContentDescription(previous).assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithContentDescription(next).assertIsDisplayed().assertHasClickAction()
        }

        revealJumpers()
        val previousAnchor = compose.onNodeWithText(first.texts[6]).assertIsDisplayed()
        val beforePrevious = previousAnchor.fetchSemanticsNode().boundsInRoot.top
        observe("before previous")
        compose.onNodeWithContentDescription(previous).performClick()
        compose.waitUntil(30_000) { previousAnchor.fetchSemanticsNode().boundsInRoot.top > beforePrevious }
        compose.waitForIdle()
        assertTrue("Previous must move the same visible message downward",
            previousAnchor.fetchSemanticsNode().boundsInRoot.top > beforePrevious)
        assertTrue("Previous must leave the page reading history", position() < maximum())
        observe("after previous")

        revealJumpers()
        val nextAnchor = compose.onNodeWithText(first.texts[5]).assertIsDisplayed()
        val beforeNext = nextAnchor.fetchSemanticsNode().boundsInRoot.top
        observe("before next")
        compose.onNodeWithContentDescription(next).performClick()
        observe("after next click")
        try {
            compose.waitUntil(30_000) { nextAnchor.fetchSemanticsNode().boundsInRoot.top < beforeNext }
        } catch (failure: Throwable) {
            try {
                observe("next timeout")
                val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: ApplicationProvider.getApplicationContext<Context>().cacheDir.absolutePath
                val directory = java.io.File(output, "scroll-entry").apply { mkdirs() }
                java.io.File(directory, "message-jumpers.txt").writeText(observations.joinToString("\n") + "\n" +
                    compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.joinToString("\n") {
                        compose.onAllNodes(isRoot(), useUnmergedTree = true)[it].printToString()
                    })
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            throw failure
        }
        compose.waitForIdle()
        assertTrue("Next must move the same visible message upward",
            nextAnchor.fetchSemanticsNode().boundsInRoot.top < beforeNext)
        assertTrue("Next from the middle must not jump directly to the tail", position() < maximum())

        revealJumpers()
        compose.onNodeWithContentDescription(start).performClick()
        compose.waitUntil(30_000) { position() == 0f }
        compose.waitForIdle()
        compose.onNodeWithText(first.texts.first()).assertIsDisplayed()
        compose.onNodeWithText(first.texts.last()).assertIsNotDisplayed()
    }

    @Test fun realSearchResultOpensItsHistoryNodeWithoutReturningToTheTail() = withHistory { first, _ ->
        val query = GlobalContext.get().get<ConversationQueryService>()
        val keyword = first.texts[3].lineSequence().first()
        val result = runBlocking {
            query.searchMessages(RealmAccess.Personal, keyword, MessageSearchSort.RELEVANCE)
                .single { it.conversationId == first.id.toString() && it.nodeId == first.nodes[3].toString() }
        }
        val snippet = result.snippet.replace("[", "").replace("]", "")
        assertTrue("The real indexed result must identify the requested message", snippet.contains("message 3"))
        launch(first.route())
        assertTail(first)
        navigate(Screen.MessageSearch)
        compose.onNode(hasSetTextAction()).performTextReplacement(keyword)
        compose.onNode(hasSetTextAction()).performImeAction()
        closeSoftKeyboard()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1
        }
        val resultCard = hasText(snippet) and hasClickAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(resultCard)
        compose.onNode(resultCard).assertIsDisplayed().performClick()
        assertHistory(first, 3)
        compose.waitForIdle()
        assertHistory(first, 3)
    }

    @Test fun realFavoriteCardOpensItsHistoryNodeWithoutReturningToTheTail() = withHistory { first, _ ->
        val application = GlobalContext.get().get<ConversationApplicationService>()
        val favorites = GlobalContext.get().get<FavoriteService>()
        val lease = runBlocking { application.initialize(first.route().request) }
        var item: NodeFavoriteItem? = null
        var primary: Throwable? = null
        try {
            runBlocking { favorites.toggleNode(lease.commandTarget, first.nodes[3]) }
            item = runBlocking { withTimeout(30_000) {
                favorites.observeNodeFavorites().first { entries ->
                    entries.any { it.conversationId == first.id && it.nodeId == first.nodes[3] }
                }.single { it.conversationId == first.id && it.nodeId == first.nodes[3] }
            } }
            val favorite = requireNotNull(item)
            launch(first.route())
            assertTail(first)
            navigate(Screen.Favorite)
            val card = hasClickAction() and hasAnyAncestor(hasTestTag("favorite-${favorite.id}"))
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(card)
            compose.onNode(card).assertIsDisplayed().performClick()
            assertHistory(first, 3)
            compose.waitForIdle()
            assertHistory(first, 3)
            val retained = runBlocking { withTimeout(30_000) {
                favorites.observeNodeFavorites().first { entries -> entries.any { it.id == favorite.id } }
            } }.single { it.id == favorite.id }
            assertEquals(favorite.refKey, retained.refKey)
            assertEquals(favorite.preview, retained.preview)
            assertEquals(favorite.nodeId, retained.nodeId)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                item?.let { favorite -> runBlocking { assertNotNull(favorites.removeForUndo(favorite)) } }
            } catch (cleanup: Throwable) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            } finally {
                lease.close()
            }
        }
    }

    @Test fun nonTouchScrollCanLeaveTheTailWithoutBeingPulledBack() = withHistory { first, _ ->
        launch(first.route())
        assertTail(first)
        val viewport = history().fetchSemanticsNode().boundsInRoot.height
        history().performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            assertTrue(scroll(0f, -viewport / 2f))
        }
        compose.waitForIdle()
        assertTrue("A non-touch history scroll must not be pulled back by FOLLOW_TAIL", position() < maximum())
    }

    @Test fun emptyDraftSetupKeepsItsStartAndAllowsUserScrollingWhenTallerThanViewport() = withHistory { first, _ ->
        val koin = GlobalContext.get()
        val application = koin.get<ConversationApplicationService>()
        val query = koin.get<ConversationQueryService>()
        val repository = koin.get<ConversationRepository>()
        val assistant = runBlocking { requireNotNull(query.aggregateSnapshot(first.id)).header.assistantId }
        val request = runBlocking { application.newDraftRequest(RealmAccess.Personal, assistant) }
        val lease = runBlocking { application.initialize(request) }
        try {
            // A short available window makes the real personal configuration card taller than
            // its viewport, without inserting fake rows or replacing the page/VM scroll owner.
            launch(Screen.Chat(request), contentHeight = 300.dp)
            compose.waitUntil(30_000) { maximum() > 0f }
            compose.waitForIdle()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val title = compose.onNode(hasText("Scroll entry fixture") and hasAnyAncestor(historyMatcher()),
                useUnmergedTree = true).getUnclippedBoundsInRoot()
            val last = compose.onNode(hasText(context.getString(R.string.chat_readiness_workspace_title)) and
                hasAnyAncestor(historyMatcher()), useUnmergedTree = true).getUnclippedBoundsInRoot()
            val viewport = history().getUnclippedBoundsInRoot()
            assertTrue("The production setup must exceed the actual list viewport",
                last.bottom - title.top > viewport.bottom - viewport.top)
            assertEquals("An empty draft must open at the start of its setup", 0f, position(), 0f)
            val initial = runBlocking { query.conversationUiModel(lease).first { it != null }!! }
            assertTrue(initial.snapshot.nodes.isEmpty())
            assertNull(initial.presentation.activeTurnId)
            assertNull(runBlocking { repository.getConversationSnapshotById(request.id) })

            val list = history().fetchSemanticsNode().boundsInRoot
            val input = compose.onNodeWithTag("chat_input").fetchSemanticsNode().boundsInRoot
            val touchableHeight = input.top - list.top
            assertTrue("The setup needs a real gesture area above the composer", touchableHeight > 0f)
            history().performTouchInput {
                swipeUp(startY = touchableHeight * 0.75f, endY = touchableHeight * 0.2f, durationMillis = 800)
            }
            compose.waitForIdle()
            assertTrue("The guard must not disable user scrolling", position() > 0f)
            repeat(8) {
                if (position() > 0f) {
                    history().performTouchInput {
                        swipeDown(startY = touchableHeight * 0.2f, endY = touchableHeight * 0.8f, durationMillis = 800)
                    }
                    compose.waitForIdle()
                }
            }
            assertEquals("The user can return to the setup start", 0f, position(), 0f)
            assertTrue(runBlocking { query.conversationUiModel(lease).first { it != null }!! }.snapshot.nodes.isEmpty())
            assertNull(runBlocking { repository.getConversationSnapshotById(request.id) })
        } finally {
            try { activity?.close() } finally {
                activity = null
                navigator = null
                lease.close()
            }
        }
    }

    private data class History(val id: Uuid, val nodes: List<Uuid>, val texts: List<String>) {
        fun route(node: Uuid? = null) = Screen.Chat(
            ConversationOpenRequest.OpenExisting(id, RealmAccess.Personal), nodeId = node?.toString(),
        )
    }

    private fun withHistory(check: (History, History) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val sessions = koin.get<EnterpriseSessionController>()
        val application = koin.get<ConversationApplicationService>()
        val query = koin.get<ConversationQueryService>()
        val repository = koin.get<ConversationRepository>()
        koin.get<ApplicationRecoveryCoordinator>()
        runBlocking { withTimeout(30_000) { koin.get<ApplicationRecoveryGate>().awaitReady() } }
        val turns = runBlocking { withContext(Dispatchers.Main.immediate) { koin.get<ConversationTurnService>() } }
        val before = runBlocking { settings.userSettings.first { !it.init } }
        val previousRealm = runBlocking { sessions.readPresentation().selection }
        val previousConversation = runBlocking { settings.lastConversation(ConfigurationScope.Personal) }
        val previousNewChat = context.readBooleanPreference("create_new_conversation_on_start", true)
        val model = Model(modelId = "scroll-entry-unused", displayName = "Scroll entry model")
        val provider = ProviderSetting.OpenAI(name = "Scroll entry fixture", models = listOf(model), baseUrl = "http://127.0.0.1:9/v1")
        val assistant = Assistant(name = "Scroll entry fixture", chatModelId = model.id)
        val leases = mutableListOf<ConversationViewLease>()
        var primary: Throwable? = null
        try {
            runBlocking {
                sessions.selectPersonalFixture()
                settings.updateLocal { it.copy(
                    assistants = it.assistants + assistant, providers = it.providers + provider,
                    assistantId = assistant.id,
                    displaySetting = it.displaySetting.copy(enableAutoScroll = true, showMessageJumper = true, enableVolumeKeyScroll = true, volumeKeyScrollRatio = 0.5f),
                ) }
            }
            context.writeBooleanPreference("create_new_conversation_on_start", false)
            fun create(label: String): History = runBlocking { withTimeout(30_000) {
                val lease = application.initialize(ConversationOpenRequest.NewDraft(Uuid.random(), RealmAccess.Personal, assistant.id))
                leases += lease
                val texts = (0..7).map { index ->
                    (0..13).joinToString("\n") { line -> "$label message $index line $line" }
                }
                texts.forEach { text ->
                    assertNotNull(turns.sendMessage(lease.commandTarget, listOf(UIMessagePart.Text(text)), answer = false))
                    query.conversationUiModel(lease).first { it != null && it.presentation.activeTurnId == null }
                }
                val stored = requireNotNull(repository.getConversationSnapshotById(lease.conversationId))
                assertEquals(texts, stored.currentMessages().map { it.toText() })
                History(lease.conversationId, stored.nodes.map { it.id }, texts)
            } }
            val first = create("Scroll A")
            val second = create("Scroll B")
            val snapshots = runBlocking { listOf(first, second).associate { it.id to requireNotNull(repository.getConversationSnapshotById(it.id)) } }
            check(first, second)
            runBlocking {
                snapshots.forEach { (id, beforeNavigation) ->
                    val afterNavigation = requireNotNull(repository.getConversationSnapshotById(id))
                    assertEquals(beforeNavigation.nodes, afterNavigation.nodes)
                    assertEquals(beforeNavigation.contextAdmissions, afterNavigation.contextAdmissions)
                    assertEquals(beforeNavigation.modelContextEntries, afterNavigation.modelContextEntries)
                }
            }
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val original = primary ?: cleanupFailure
                    if (original == null) cleanupFailure = error else original.addSuppressed(error)
                }
            }
            cleanup { activity?.close(); activity = null; navigator = null }
            leases.forEach { lease -> cleanup { runBlocking { withTimeout(30_000) {
                try { application.stopGeneration(lease.commandTarget); application.delete(lease.commandTarget) }
                finally { lease.close() }
            } } } }
            cleanup { runBlocking {
                settings.updateLocal { it.copy(
                    assistants = it.assistants.filterNot { item -> item.id == assistant.id },
                    providers = it.providers.filterNot { item -> item.id == provider.id },
                    assistantId = before.assistantId,
                    displaySetting = it.displaySetting.copy(
                        enableAutoScroll = before.displaySetting.enableAutoScroll,
                        showMessageJumper = before.displaySetting.showMessageJumper,
                        enableVolumeKeyScroll = before.displaySetting.enableVolumeKeyScroll,
                        volumeKeyScrollRatio = before.displaySetting.volumeKeyScrollRatio,
                    ),
                ) }
                settings.rememberConversation(ConfigurationScope.Personal, previousConversation)
                if (previousRealm?.access is RealmAccess.Enterprise) sessions.selectEnterpriseFixture()
            } }
            cleanup { context.writeBooleanPreference("create_new_conversation_on_start", previousNewChat) }
            cleanupFailure?.let { throw it }
        }
    }

    private fun launch(route: Screen.Chat, contentHeight: Dp? = null) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java).putExtra("conversationId", route.request.id.toString()))
        installRoutes(route, contentHeight)
    }

    private fun recreate(route: Screen.Chat) {
        lateinit var old: RouteActivity
        requireNotNull(activity).onActivity { old = it }
        requireNotNull(activity).recreate()
        assertEquals(Lifecycle.State.DESTROYED, old.lifecycle.currentState)
        requireNotNull(activity).onActivity { assertNotSame(old, it) }
        // Supply the same entry argument after a real Activity recreation; no test saves list state.
        installRoutes(route)
    }

    private fun installRoutes(route: Screen.Chat, contentHeight: Dp? = null) {
        navigator = null
        requireNotNull(activity).onActivity { host -> host.setContent {
            val settings by GlobalContext.get().get<SettingsStore>().userSettings.collectAsStateWithLifecycle()
            val speech = remember { GlobalContext.get().get<SpeechApplicationService>() }
            val stack = rememberNavBackStack(route)
            val navigation = remember(stack) { Navigator(stack) }
            SideEffect { navigator = navigation }
            MeasixTheme {
                SharedTransitionLayout {
                    CompositionLocalProvider(
                        LocalNavController provides navigation,
                        LocalSharedTransitionScope provides this,
                        LocalSettings provides settings,
                        LocalToaster provides rememberToasterState(),
                        LocalTTSState provides speech.playback,
                        LocalASRState provides speech.recognition,
                        LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                    ) {
                        NavDisplay(
                            backStack = stack,
                            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                            modifier = contentHeight?.let { Modifier.fillMaxWidth().height(it) } ?: Modifier.fillMaxSize(),
                            onBack = { if (stack.size > 1) stack.removeLastOrNull() },
                            entryProvider = entryProvider {
                                entry<Screen.MessageSearch> { SearchPage() }
                                entry<Screen.Favorite> { FavoritePage() }
                                entry<Screen.Chat> { key ->
                                    ChatPage(key.request, key.text, emptyList(), stack.lastOrNull() == key, key.nodeId?.let(Uuid::parse))
                                }
                            },
                        )
                    }
                }
            }
        } }
        compose.waitUntil(30_000) { navigator != null && compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }

    private fun navigate(route: Screen) {
        compose.runOnIdle { requireNotNull(navigator).clearAndNavigate(route) }
        compose.waitForIdle()
    }

    private fun historyMatcher() = hasScrollToIndexAction() and SemanticsMatcher("visible history") {
        it.boundsInRoot.left >= 0f && it.boundsInRoot.width > 0f
    }

    private fun history() = compose.onNode(historyMatcher())

    private fun position() = history().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun maximum() = history().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    private fun assertHistory(history: History, index: Int) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(history.texts[index]).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText(history.texts[index]).assertIsDisplayed()
        assertTrue(position() > 0f && position() < maximum())
        compose.onNodeWithText(history.texts.last()).assertIsNotDisplayed()
    }

    private fun assertTail(history: History) {
        try {
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(history.texts.last()).fetchSemanticsNodes().isNotEmpty() && position() == maximum()
            }
        } catch (failure: Throwable) {
            try {
                val nodes = compose.onAllNodesWithText(history.texts.last()).fetchSemanticsNodes()
                val diagnostic = "Tail ${history.id}: value=${position()}, max=${maximum()}, lastBounds=${nodes.map { it.boundsInRoot }}"
                android.util.Log.e("ChatScrollEntry", diagnostic)
                val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: ApplicationProvider.getApplicationContext<Context>().cacheDir.absolutePath
                val directory = java.io.File(output, "scroll-entry").apply { mkdirs() }
                java.io.File(directory, "tail-${history.id}.txt").writeText(diagnostic + "\n" +
                    compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.joinToString("\n") {
                        compose.onAllNodes(isRoot(), useUnmergedTree = true)[it].printToString()
                    })
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            throw failure
        }
        compose.onNodeWithText(history.texts.last()).assertIsDisplayed()
        compose.onNodeWithText(history.texts.first()).assertIsNotDisplayed()
    }

    private fun imeVisible(): Boolean {
        var visible = false
        requireNotNull(activity).onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        return visible
    }
}
