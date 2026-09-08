package net.weero.measix.pilot.ui.pages.subassistant

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
