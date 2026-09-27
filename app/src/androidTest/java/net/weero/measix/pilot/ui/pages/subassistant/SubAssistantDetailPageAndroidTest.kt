package net.weero.measix.pilot.ui.pages.subassistant

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.ConversationViewLease
import net.weero.measix.pilot.service.SubAssistantDetailLink
import net.weero.measix.pilot.service.SubAssistantDetailUiState
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import com.dokar.sonner.rememberToasterState
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.Navigator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class SubAssistantDetailPageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun updateStaysAtChildRequestBoundaryThroughPendingCancellationAndOutput() {
        val source = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val target = ConfigurationReference.random()
        val task = net.weero.measix.pilot.data.model.MessageNode.of(me.rerere.ai.ui.UIMessage.user("Task"))
        val step = me.rerere.ai.ui.UIMessagePart.Step(Uuid.random(), 0, kotlin.time.Instant.fromEpochSeconds(1))
        val pending = net.weero.measix.pilot.data.model.MessageNode.of(me.rerere.ai.ui.UIMessage.assistant("").copy(parts = listOf(step)))
        val marker = net.weero.measix.pilot.service.ConversationContextUpdateMarker(step.stepId, Uuid.random(),
            listOf(net.weero.measix.pilot.service.ConversationContextCategory.MEMORY))
        val child = Conversation(assistantId = target, parentConversationId = source.conversationId, messageNodes = listOf(task, pending))
        val snapshot = ConversationRuntimeSnapshot(child.toSnapshot(), null).toPresentationSnapshot().copy(
            context = net.weero.measix.pilot.service.ConversationContextSummary(mapOf(
                pending.currentMessage.id to net.weero.measix.pilot.service.MessageContextSummary(updates = listOf(marker)))))
        val initial = SubAssistantDetailUiState.Ready(
            SubAssistantDetailLink(buildInitialSubAssistantCallMetadata("run", target, "Target"), "Task", child.id, task.currentMessage.id, target),
            snapshot, listOf(pending))
        val state = MutableStateFlow<SubAssistantDetailUiState>(initial)
        val vm = mockk<SubAssistantDetailVM>()
        every { vm.uiState } returns state
        every { vm.settings } returns MutableStateFlow(Settings.dummy())
        every { vm.attachmentPreviews() } returns emptyMap()
        compose.setContent {
            val backStack = rememberNavBackStack(Screen.SubAssistantDetail("run", source))
            MaterialTheme { CompositionLocalProvider(LocalNavController provides Navigator(backStack),
                LocalSettings provides Settings.dummy(), LocalToaster provides rememberToasterState()) {
                SubAssistantDetailPage(source, "run", vm)
            } }
        }
        try {
            val label = compose.activity.getString(R.string.context_updated_categories,
                compose.activity.getString(R.string.context_memory))
            val description = compose.activity.getString(R.string.context_details_accessibility, label)
            compose.onAllNodesWithContentDescription(description).assertCountEquals(1)
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
            val before = compose.onNodeWithContentDescription(description).getUnclippedBoundsInRoot()
            val execution = compose.onNodeWithText(compose.activity.getString(R.string.sub_assistant_detail_execution)).getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("Changes belong in the execution timeline, after the request area", before.top >= execution.bottom)
            val cancelled = pending.copy(messages = listOf(pending.currentMessage.copy(
                parts = listOf(step.copy(outcome = me.rerere.ai.ui.StepOutcome.Cancelled)),
                terminalStatus = me.rerere.ai.ui.MessageTerminalStatus.CANCELLED)))
            compose.runOnIdle { state.value = initial.copy(child = snapshot.copy(nodes = listOf(task, cancelled)), timeline = listOf(cancelled)) }
            compose.onAllNodesWithContentDescription(description).assertCountEquals(1)
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
            org.junit.Assert.assertEquals(before.top, compose.onNodeWithContentDescription(description).getUnclippedBoundsInRoot().top)
            val output = pending.copy(messages = listOf(pending.currentMessage.copy(parts = listOf(step, me.rerere.ai.ui.UIMessagePart.Text("Later answer")))))
            compose.runOnIdle { state.value = initial.copy(child = snapshot.copy(nodes = listOf(task, output)), timeline = listOf(output)) }
            compose.onNodeWithText("Later answer").assertIsDisplayed()
            compose.onAllNodesWithContentDescription(description).assertCountEquals(1)
            val after = compose.onNodeWithContentDescription(description).getUnclippedBoundsInRoot()
            org.junit.Assert.assertEquals(before.top, after.top)
            org.junit.Assert.assertTrue(after.bottom <= compose.onNodeWithText("Later answer").getUnclippedBoundsInRoot().top)
        } finally { source.close() }
    }

    @Test fun restoredNavigationCannotDisplayReadyRetainedByTheOldViewModel() {
        val source = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val target = ConfigurationReference.random()
        val child = Conversation(assistantId = target, parentConversationId = source.conversationId, messageNodes = emptyList())
        val ready = SubAssistantDetailUiState.Ready(
            link = SubAssistantDetailLink(
                metadata = buildInitialSubAssistantCallMetadata("run", target, "Private target").copy(state = SubAssistantCallState.COMPLETED),
                request = "Private task from original page",
                childConversationId = child.id,
                childTaskMessageId = Uuid.random(),
                targetAssistantId = target,
            ),
            child = ConversationRuntimeSnapshot(child.toSnapshot(), null).toPresentationSnapshot(),
            timeline = emptyList(),
        )
        val retainedState = MutableStateFlow<SubAssistantDetailUiState>(ready)
        val vm = mockk<SubAssistantDetailVM>()
        every { vm.uiState } returns retainedState
        every { vm.settings } returns MutableStateFlow(Settings.dummy())
        every { vm.attachmentPreviews() } returns emptyMap()
        var displayed: Screen.SubAssistantDetail? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val backStack = rememberNavBackStack(Screen.SubAssistantDetail("run", source))
            val route = backStack.last() as Screen.SubAssistantDetail
            displayed = route
            MaterialTheme {
                CompositionLocalProvider(LocalNavController provides Navigator(backStack), LocalSettings provides Settings.dummy(), LocalToaster provides rememberToasterState()) {
                    SubAssistantDetailPage(route.source, route.runId, vm)
                }
            }
        }
        compose.onNodeWithText(ready.link.request).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(compose.activity.getString(R.string.sub_assistant_detail_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(ready.link.request).assertDoesNotExist()
        compose.onNodeWithText("Private target").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(requireNotNull(displayed).source)
            assertSame(ready, retainedState.value)
            assertFalse(source.closed.value)
        }
    }
}
