package net.weero.measix.pilot.ui.components.message

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
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

    private class ModalRead {
        val lease = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val conversationId = Uuid.random()
        val messageId = Uuid.random()
        val requestId = Uuid.random()
        val reads = AtomicInteger()
        val bodyReads = AtomicInteger()
        val query = mockk<ConversationQueryService>()
    }

    private fun modalRead(observe: (ConversationContextDetailsUiModel) -> Flow<ConversationContextDetailsUiModel>): ModalRead {
        val read = ModalRead()
        val update = item.copy(isCurrentUpdate = true, updatedCategories = item.categories)
        val detail = ConversationContextDetailsUiModel(listOf(ConversationContextRequestUiModel(
            read.requestId, 0, null, ConversationContextRequestState.ADDED, listOf(update))))
        every { read.query.observeViewAccess(read.lease) } returns flowOf(true)
        every { read.query.observeContextDetails(read.lease, read.conversationId, read.messageId, read.requestId) } answers {
            read.reads.incrementAndGet()
            observe(detail)
        }
        coEvery { read.query.contextContent(read.lease, read.conversationId, read.messageId, read.requestId, update.key) } answers {
            read.bodyReads.incrementAndGet()
            ConversationContextContentUiModel("Original request body", null)
        }
        return read
    }

    @Test fun immediateModalResultSurvivesUnrelatedParentRecompositionWithoutAnotherRead() {
        val read = modalRead { flowOf(it) }
        var parentRevision by mutableIntStateOf(0)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                androidx.compose.material3.Text("Parent $parentRevision")
                ConversationContextDetails(read.lease, read.conversationId, read.messageId, read.requestId, {}, read.query)
            }
        } }
        try {
            compose.onNodeWithText(context.getString(R.string.context_request, 1)).assertIsDisplayed()
            compose.onNodeWithText("Original request body").assertIsDisplayed()
            compose.runOnIdle { parentRevision++ }
            compose.onNodeWithText("Original request body").assertIsDisplayed()
            compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
            compose.runOnIdle { assertEquals(1, read.reads.get()); assertEquals(1, read.bodyReads.get()) }
        } finally { read.lease.close() }
    }

    @Test fun delayedModalResultReplacesLoadingUnderTheOriginalViewLease() {
        val release = CompletableDeferred<Unit>()
        val read = modalRead { detail -> flow { release.await(); emit(detail) } }
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                ConversationContextDetails(read.lease, read.conversationId, read.messageId, read.requestId, {}, read.query)
            }
        } }
        try {
            compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
            compose.runOnIdle { assertEquals(1, read.reads.get()); release.complete(Unit) }
            compose.onNodeWithText("Original request body").assertIsDisplayed()
            compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
            compose.runOnIdle { assertEquals(1, read.reads.get()); assertEquals(1, read.bodyReads.get()) }
        } finally { read.lease.close() }
    }

    @Test fun modalReadFailureCopiesFullCauseAndOnlyRetriesAfterExplicitAction() {
        var attempts = 0
        val read = modalRead { detail -> flow {
            if (++attempts == 1) throw java.io.IOException("context read failed", IllegalStateException("original source unavailable"))
            emit(detail)
        } }
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                ConversationContextDetails(read.lease, read.conversationId, read.messageId, read.requestId, {}, read.query)
            }
        } }
        try {
            val diagnostic = "IOException: context read failed\nCaused by: IllegalStateException: original source unavailable"
            compose.onNodeWithText(diagnostic).assertIsDisplayed()
            compose.runOnIdle { assertEquals(1, read.reads.get()); assertEquals(0, read.bodyReads.get()) }
            compose.onNodeWithContentDescription(context.getString(R.string.chat_page_copy_error)).performClick()
            compose.runOnIdle {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                assertEquals(diagnostic, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                assertEquals(1, read.reads.get())
            }
            compose.onNodeWithText(context.getString(R.string.application_recovery_retry)).performClick()
            compose.onNodeWithText(diagnostic).assertDoesNotExist()
            compose.onNodeWithText("Original request body").assertIsDisplayed()
            compose.runOnIdle { assertEquals(2, read.reads.get()); assertEquals(1, read.bodyReads.get()) }
        } finally {
            read.lease.close()
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).clearPrimaryClip()
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun narrowLargeFontWithImeAddsNoRowForUnchangedContextAndOnlyOneForExternalUpdate() {
        val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val step = me.rerere.ai.ui.UIMessagePart.Step(Uuid.random(), 0, kotlin.time.Instant.fromEpochMilliseconds(0))
        val node = MessageNode.of(UIMessage.assistant("Visible answer").copy(parts = listOf(step, me.rerere.ai.ui.UIMessagePart.Text("Visible answer"))))
        var summary by mutableStateOf<MessageContextSummary?>(null)
        var assistantBubble by mutableStateOf(false)
        var imeVisible = false
        compose.setContent {
            val density = LocalDensity.current
            val keyboard = LocalSoftwareKeyboardController.current
            var draft by remember { mutableStateOf("input") }
            val ime = WindowInsets.isImeVisible
            SideEffect { imeVisible = ime }
            MaterialTheme { CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1.8f),
                LocalSettings provides Settings.dummy().let { it.copy(displaySetting = it.displaySetting.copy(showAssistantBubble = assistantBubble)) },
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
            compose.runOnIdle { summary = MessageContextSummary() }
            compose.onAllNodesWithContentDescription(description).assertCountEquals(0)
            assertEquals(baseline, compose.onNodeWithTag("message").getUnclippedBoundsInRoot())
            compose.runOnIdle { summary = MessageContextSummary(updates = listOf(ConversationContextUpdateMarker(step.stepId, Uuid.random(), emptyList()))) }
            compose.onAllNodesWithContentDescription(description).assertCountEquals(1)
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
            org.junit.Assert.assertTrue(compose.onNodeWithContentDescription(description).getUnclippedBoundsInRoot().bottom <=
                compose.onNodeWithText("Visible answer").getUnclippedBoundsInRoot().top)
            val label = compose.onNodeWithText("${context.getString(R.string.context_updated)} ›", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertEquals(answer.left, label.left)
            compose.onNodeWithContentDescription(description).assertHeightIsEqualTo(32.dp)
            compose.runOnIdle { assistantBubble = true }
            assertEquals(compose.onNodeWithText("Visible answer").getUnclippedBoundsInRoot().left,
                compose.onNodeWithText("${context.getString(R.string.context_updated)} ›", useUnmergedTree = true).getUnclippedBoundsInRoot().left)
            compose.runOnIdle { summary = MessageContextSummary(); assistantBubble = false }
            assertEquals(baseline, compose.onNodeWithTag("message").getUnclippedBoundsInRoot())
        } finally { view.close() }
    }

    @Test fun compactUpdateLabelKeepsAnExpandedTouchTarget() {
        var clicks = 0
        compose.setContent { MaterialTheme { Column(Modifier.padding(24.dp)) {
            ContextMessageEntry( categories = listOf(ConversationContextCategory.MEMORY)) { clicks++ }
        } } }
        val label = context.getString(R.string.context_updated_categories, context.getString(R.string.context_memory))
        val entry = compose.onNodeWithContentDescription(context.getString(R.string.context_details_accessibility, label))
        entry.assertHeightIsEqualTo(32.dp)
        // Six dp above the compact row is inside Compose's 48 dp minimum touch target.
        val density = context.resources.displayMetrics.density
        entry.performTouchInput { click(androidx.compose.ui.geometry.Offset(center.x, -6f * density)) }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun laterRequestsDoNotReplaceOrReloadTheSelectedUpdate() {
        val update = item.copy(isCurrentUpdate = true, updatedCategories = item.categories)
        val requestId = Uuid.random()
        val changed = ConversationContextRequestUiModel(requestId, 0, null, ConversationContextRequestState.ADDED, listOf(update))
        var detail by mutableStateOf(ConversationContextDetailsUiModel(listOf(changed)))
        val reads = AtomicInteger()
        compose.setContent { MaterialTheme { Column {
            ContextUpdateRequest(detail.forUpdate(requestId).requests.single()) {
                reads.incrementAndGet()
                ConversationContextContentUiModel("Reading original body", null)
            }
        } } }
        compose.onNodeWithText("Reading original body").assertIsDisplayed()
        compose.runOnIdle { detail = ConversationContextDetailsUiModel((1..10).reversed().map {
            changed.copy(id = Uuid.random(), ordinal = it, items = listOf(update.copy(isCurrentUpdate = false)))
        } + changed) }
        compose.onNodeWithText("Reading original body").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.context_request, 11)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.context_request, 1)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, reads.get()) }
    }

    @Test fun categoryLabelAndChangeDetailsStayReadableWithoutExposingRawRecords() {
        val update = item.copy(isCurrentUpdate = true, updatedCategories = item.categories)
        val system = item.copy(key = "system", entryId = Uuid.random(), categories = listOf(ConversationContextCategory.SYSTEM))
        val changed = ConversationContextRequestUiModel(Uuid.random(), 0, null, ConversationContextRequestState.ADDED, listOf(system, update))
        val later = changed.copy(id = Uuid.random(), ordinal = 1, items = listOf(system))
        val reads = mutableListOf<String>()
        val original = "<conversation_disclosure_snapshot>raw-json</conversation_disclosure_snapshot>"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) { MaterialTheme {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    ContextMessageEntry( categories = listOf(ConversationContextCategory.ENTERPRISE_BACKGROUND,
                        ConversationContextCategory.ASSISTANTS, ConversationContextCategory.MEMORY, ConversationContextCategory.ASSISTANTS)) {}
                    ContextUpdateRequest(ConversationContextDetailsUiModel(listOf(later, changed)).forUpdate(requireNotNull(changed.id)).requests.single()) { entry ->
                        reads += entry.key
                        ConversationContextContentUiModel(original, "internal-source-json", presentation = ConversationContextPresentationUiModel(listOf(
                            ConversationContextSectionUiModel(category = ConversationContextCategory.MEMORY,
                                scope = ConversationContextScope.SHARED, reason = ConversationContextReason.EXTERNAL, exactChanges = true,
                                rows = listOf(
                                    ConversationContextRowUiModel(id = "7", after = "New shared note", change = ConversationContextChangeKind.ADDED),
                                    ConversationContextRowUiModel(id = "8", before = "Previous note", after = "Revised note", change = ConversationContextChangeKind.MODIFIED),
                                    ConversationContextRowUiModel(id = "9", before = "Removed note", change = ConversationContextChangeKind.REMOVED),
                                )),
                        )))
                    }
                }
            } }
        }
        val label = context.getString(R.string.context_updated_more, context.getString(R.string.context_memory), 3)
        compose.onNodeWithText("$label ›").assertIsDisplayed()
        val fullLabel = context.getString(R.string.context_updated_categories,
            listOf(R.string.context_memory, R.string.context_assistants, R.string.context_enterprise_background)
                .joinToString(" · ") { context.getString(it) })
        compose.onNodeWithContentDescription(context.getString(R.string.context_details_accessibility, fullLabel)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.context_request, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.context_request, 2)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.context_scope_shared)).assertDoesNotExist() // Combined scope and reason.
        compose.onNodeWithText("${context.getString(R.string.context_scope_shared)} · ${context.getString(R.string.context_reason_external)}")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New shared note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Revised note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Removed note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Previous note").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.context_before_change)).performScrollTo().performClick()
        compose.onNodeWithText("Previous note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithText("internal-source-json").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.context_system)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(update.key), reads) }
        compose.onNodeWithText(context.getString(R.string.context_raw_input)).performScrollTo().performClick()
        compose.onNodeWithText(original).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.context_system)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(update.key), reads) }
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
