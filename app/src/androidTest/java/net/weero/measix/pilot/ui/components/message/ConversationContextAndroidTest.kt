package net.weero.measix.pilot.ui.components.message

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.rememberNavBackStack
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.ui.context.LocalNavController
import com.dokar.sonner.rememberToasterState
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.Navigator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationContextAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val item = ConversationContextItemUiModel("request/item", Uuid.random(),
        listOf(ConversationContextCategory.MEMORY), null, "USER", "BeforeStep(saved-step)")

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun narrowLargeFontWithImeAddsNoRowForUnchangedContextAndOnlyOneForExternalUpdate() {
        val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val node = MessageNode.of(UIMessage.assistant("Visible answer"))
        var summary by mutableStateOf<MessageContextSummary?>(null)
        var imeVisible = false
        compose.setContent {
            val density = LocalDensity.current
            val keyboard = LocalSoftwareKeyboardController.current
            var draft by remember { mutableStateOf("input") }
            val ime = WindowInsets.isImeVisible
            SideEffect { imeVisible = ime }
            MaterialTheme { CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1.8f),
                LocalSettings provides Settings.dummy(),
                LocalToaster provides rememberToasterState(),
                LocalNavController provides Navigator(rememberNavBackStack()),
            ) {
                Column(Modifier.width(320.dp).fillMaxHeight().imePadding()) {
                    ChatMessage(node = node, detailSource = view, modifier = Modifier.testTag("message"),
                        readOnly = true, contextSummary = summary, onFork = {}, onRegenerate = {}, onEdit = {},
                        onShare = {}, onDelete = {}, onUpdate = {})
                    Spacer(Modifier.weight(1f))
                    BasicTextField(draft, onValueChange = { draft = it },
                        modifier = Modifier.testTag("layout-input").fillMaxWidth())
                    androidx.compose.material3.TextButton(onClick = { keyboard?.show() }) {
                        androidx.compose.material3.Text("Show keyboard")
                    }
                }
            } }
        }
        try {
            compose.onNodeWithTag("layout-input").performClick()
            compose.onNodeWithText("Show keyboard").performClick()
            compose.waitUntil(10_000) { imeVisible }
            val baseline = compose.onNodeWithTag("message").getUnclippedBoundsInRoot()
            val answer = compose.onNodeWithText("Visible answer").getUnclippedBoundsInRoot()
            val description = context.getString(R.string.context_updated_accessibility)
            compose.runOnIdle { summary = MessageContextSummary(hasContent = true) }
            compose.onAllNodesWithContentDescription(description).assertCountEquals(0)
            assertEquals(baseline, compose.onNodeWithTag("message").getUnclippedBoundsInRoot())
            compose.runOnIdle { summary = MessageContextSummary(hasContent = true, hasExternalUpdate = true) }
            compose.onAllNodesWithContentDescription(description).assertCountEquals(1)
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
            assertEquals(answer, compose.onNodeWithText("Visible answer").getUnclippedBoundsInRoot())
            compose.runOnIdle { summary = MessageContextSummary(hasContent = true) }
            assertEquals(baseline, compose.onNodeWithTag("message").getUnclippedBoundsInRoot())
        } finally { view.close() }
    }

    @Test fun aNewRequestDoesNotCollapseTheBodyBeingReadOrOpenItsOwnBody() {
        val old = ConversationContextRequestUiModel(Uuid.random(), 0, null, ConversationContextRequestState.ADDED, listOf(item))
        val nextItem = item.copy(key = "next/item", entryId = Uuid.random(), categories = listOf(ConversationContextCategory.SYSTEM))
        val next = ConversationContextRequestUiModel(Uuid.random(), 1, null, ConversationContextRequestState.ADDED, listOf(nextItem))
        var detail by mutableStateOf(ConversationContextDetailsUiModel(listOf(old)))
        val reads = AtomicInteger()
        compose.setContent { MaterialTheme { Column {
            ContextRequestList(detail) { request, _ ->
                reads.incrementAndGet()
                ConversationContextContentUiModel(if (request.id == old.id) "Reading original body" else "Next body", null)
            }
        } } }
        compose.onNodeWithText(context.getString(R.string.context_memory)).performClick()
        compose.onNodeWithText("Reading original body").assertIsDisplayed()
        compose.runOnIdle { detail = ConversationContextDetailsUiModel(listOf(next, old)) }
        compose.onNodeWithText("Reading original body").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.context_system)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, reads.get()) }
        compose.onNodeWithText(context.getString(R.string.context_request, 2)).performClick()
        compose.onNodeWithText(context.getString(R.string.context_system)).performClick()
        compose.onNodeWithText("Next body").assertIsDisplayed()
        compose.onNodeWithText("Reading original body").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.context_request, 1)).performClick()
        compose.onNodeWithText("Reading original body").assertDoesNotExist()
    }

    @Test fun historicalContentShowsMissingRequestRecordWithoutClaimingItsReadableBodyIsMissing() {
        val historical = ConversationContextRequestUiModel(null, null, null, ConversationContextRequestState.HISTORICAL, listOf(item))
        var detail by mutableStateOf(ConversationContextDetailsUiModel(listOf(historical)))
        compose.setContent { MaterialTheme { Column {
            ContextRequestList(detail) { _, _ -> ConversationContextContentUiModel("Preserved original", null) }
        } } }
        compose.onNodeWithText(context.getString(R.string.context_unrecorded_request)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.context_memory)).performClick()
        compose.onNodeWithText("Preserved original").assertIsDisplayed()
        compose.runOnIdle { detail = ConversationContextDetailsUiModel(listOf(historical.copy(state = ConversationContextRequestState.SAVED_CONTENT))) }
        compose.onNodeWithText(context.getString(R.string.context_unrecorded_request)).assertDoesNotExist()
        compose.onNodeWithText("Preserved original").assertIsDisplayed()
    }

    @Test fun bodyIsLazyLiteralAndLoadedOnceAcrossExpansion() {
        val reads = AtomicInteger()
        val original = "{{verbatim}}\n<record>原文 & text</record>"
        compose.setContent { MaterialTheme { Column {
            ContextContentItem(item) { reads.incrementAndGet(); ConversationContextContentUiModel(original, "original source") }
        } } }
        compose.runOnIdle { assertEquals(0, reads.get()) }
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.context_memory)).performClick()
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithText("original source").assertDoesNotExist()
        val metadata = "${item.role} · ${item.location}"
        compose.onNodeWithText(metadata).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.context_source)).performClick()
        compose.onNodeWithText("original source").assertIsDisplayed()
        compose.onNodeWithText(metadata).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.context_source)).performClick()
        compose.onNodeWithText(metadata).assertDoesNotExist()
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.context_memory)).performClick()
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.context_memory)).performClick()
        compose.runOnIdle { assertEquals(1, reads.get()) }
    }

    @Test fun closingExpandedContentCancelsItsPendingReadAndDoesNotPublishLateText() {
        val pending = CompletableDeferred<ConversationContextContentUiModel>()
        val entered = AtomicInteger()
        var shown by mutableStateOf(true)
        compose.setContent { MaterialTheme { Column {
            if (shown) ContextContentItem(item) { entered.incrementAndGet(); pending.await() }
        } } }
        compose.onNodeWithText(context.getString(R.string.context_memory)).performClick()
        compose.waitUntil { entered.get() == 1 }
        compose.runOnIdle { shown = false }
        compose.runOnIdle { pending.complete(ConversationContextContentUiModel("late private input", null)) }
        compose.onNodeWithText("late private input").assertDoesNotExist()
    }

    @Test fun failureKeepsTypeMessageAndCauseAndCanBeRetried() {
        val reads = AtomicInteger()
        compose.setContent { MaterialTheme { Column {
            ContextContentItem(item) {
                if (reads.incrementAndGet() == 1) throw java.io.IOException("original payload unavailable", IllegalStateException("missing source"))
                ConversationContextContentUiModel("recovered original", null)
            }
        } } }
        val toggle = compose.onNodeWithText(context.getString(R.string.context_memory))
        toggle.performClick()
        compose.onNodeWithText("IOException: original payload unavailable\nCaused by: IllegalStateException: missing source").assertIsDisplayed()
        toggle.performClick()
        toggle.performClick()
        compose.onNodeWithText("recovered original").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, reads.get()) }
    }
}
