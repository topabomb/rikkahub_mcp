package net.weero.measix.pilot.ui.pages.assistant.detail

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalTTSState
import net.weero.measix.pilot.ui.hooks.CustomTtsState
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantPromptPageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun promptCallbackRetainsItsRenderedBaselineAcrossBackgroundUpdates() {
        val original = Assistant(name = "Definition", systemPrompt = "Initial prompt")
        val assistant = MutableStateFlow(Assistant())
        val settings = MutableStateFlow(Settings(assistants = listOf(original)))
        val updates = mutableListOf<Pair<Assistant, Assistant>>()
        val vm = mockk<AssistantDetailVM>()
        every { vm.assistant } returns assistant
        every { vm.settings } returns settings
        every { vm.lockedSettingsChanges } returns MutableSharedFlow()
        every { vm.update(any(), any()) } answers { updates += firstArg<Assistant>() to secondArg<Assistant>(); Unit }
        val tts = mockk<CustomTtsState>()
        every { tts.isSpeaking } returns MutableStateFlow(false)
        every { tts.isAvailable } returns MutableStateFlow(false)
        compose.setContent {
            val backStack = rememberNavBackStack()
            MaterialTheme {
                CompositionLocalProvider(LocalNavController provides Navigator(backStack),
                    LocalSettings provides settings.collectAsState().value, LocalToaster provides rememberToasterState(), LocalTTSState provides tts) {
                    AssistantPromptPage(original.id.toString(), vm)
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.sub_assistant_reason_assistant_not_found)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(updates.isEmpty()); assistant.value = original }
        compose.onNodeWithText("Initial prompt").assertIsDisplayed()
        compose.runOnIdle {
            updates.clear()
            assistant.value = original.copy(background = "updated.png", useGradientBackground = false)
            settings.value = settings.value.copy(assistants = listOf(assistant.value))
        }
        compose.onNodeWithText("Initial prompt").performTextReplacement("Edited prompt")
        compose.waitForIdle()
        compose.runOnIdle {
            val (baseline, edited) = updates.last { it.second.systemPrompt == "Edited prompt" }
            assertEquals(original, baseline)
            assertEquals(original.copy(systemPrompt = "Edited prompt"), edited)
        }
    }
}
