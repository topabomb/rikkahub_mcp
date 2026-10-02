package net.weero.measix.pilot.ui.pages.history

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import io.mockk.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ConfigurationSelection
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class HistoryPageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun undoClickedDuringPinWaitRestoresTheDeletedRowAfterTheCommandFinishes() {
        val selection = RealmSelection(RealmAccess.Personal, 1)
        val assistant = ConfigurationReference.random()
        fun row(title: String) = ConversationSummary(Uuid.random(), assistant, title, null, false, Instant.EPOCH, Instant.EPOCH, selection)
        val first = row("Deleted history")
        val second = row("Remaining history")
        val rows = MutableStateFlow(Result.success(listOf(first, second)))
        val query = mockk<ConversationQueryService>()
        every { query.observeCurrentSelection() } returns flowOf(selection)
        every { query.conversationsOfAssistant(assistant) } returns rows
        val configuration = mockk<ConfigurationQueryService>()
        every { configuration.observeAssistantCatalog() } returns flowOf(AssistantCatalogReadState.Available(
            AssistantCatalogUiModel(selection, ConfigurationSelection(assistant, null), emptyMap(), emptyList())))
        val application = mockk<ConversationApplicationService>()
        val token = mockk<ConversationApplicationService.RestoreToken>(relaxed = true)
        every { token.selection } returns selection
        every { token.deletion } returns ConversationDeletionReceipt(first.id, assistant, selection)
        coEvery { application.deleteForUndo(any()) } coAnswers { rows.value = Result.success(listOf(second)); token }
        coEvery { application.deletionContinuation(any()) } returns ConversationContinuation(
            ConversationOpenRequest.NewDraft(Uuid.random(), selection.access, assistant))
        val pinEntered = AtomicBoolean(false)
        val releasePin = CompletableDeferred<Unit>()
        coEvery { application.togglePin(any()) } coAnswers { pinEntered.set(true); releasePin.await() }
        coEvery { application.restore(token) } coAnswers { rows.value = Result.success(listOf(first, second)) }
        val isolated = koinApplication { modules(module {
            single { query }
            single { application }
        }) }
        val store = ViewModelStore()
        lateinit var vm: HistoryVM
        try {
            compose.runOnUiThread { vm = HistoryVM(query, configuration, application); store.put("history", vm) }
            compose.setContent {
                KoinIsolatedContext(isolated) {
                    MaterialTheme {
                        CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf(Screen.History)),
                            LocalToaster provides rememberToasterState()) {
                            HistoryPage(vm)
                        }
                    }
                }
            }
            compose.onNode(hasAnyDescendant(hasText(first.title)) and hasClickAction()).performTouchInput { swipeLeft() }
            compose.onNodeWithText(compose.activity.getString(R.string.history_page_undo)).assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.history_page_pin)).performClick()
            compose.waitUntil(5_000) { pinEntered.get() }
            compose.onNodeWithText(compose.activity.getString(R.string.history_page_undo)).performClick()
            compose.waitUntil(5_000) { vm.undo.value == null }
            coVerify(exactly = 0) { application.restore(any()) }
            assertTrue(vm.running.value)
            releasePin.complete(Unit)
            compose.onNodeWithText(first.title).assertIsDisplayed()
            compose.onNodeWithText(second.title).assertIsDisplayed()
            compose.waitUntil(5_000) { !vm.running.value }
            coVerify(exactly = 1) { application.restore(token) }
            verify(exactly = 1) { token.close() }
            val directory = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            if (directory != null) {
                val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                java.io.File(directory, "history-busy-undo-restored.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
        } finally {
            releasePin.complete(Unit)
            compose.runOnUiThread { store.clear() }
            isolated.close()
        }
    }
}
