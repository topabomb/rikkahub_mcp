package net.weero.measix.pilot.ui.pages.extensions

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.data.model.InjectionPosition
import net.weero.measix.pilot.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptPageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun collapsedAddActionKeepsItsNameAndPreservesLatestPromptList() {
        val originals = (0 until 20).map { index ->
            PromptInjection.ModeInjection(name = "Existing rule $index", content = "Original content $index",
                position = InjectionPosition.AT_DEPTH, role = MessageRole.ASSISTANT, injectDepth = 3)
        }
        val settings = MutableStateFlow(Settings(modeInjections = originals))
        val updates = mutableListOf<Settings>()
        val vm = mockk<PromptVM>()
        every { vm.settings } returns settings
        every { vm.lockedChanges } returns MutableSharedFlow()
        every { vm.updateSettings(any()) } answers {
            val updated = firstArg<(Settings) -> Settings>()(settings.value)
            updates += updated
            settings.value = updated
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalNavController provides Navigator(mutableListOf()),
                    LocalToaster provides rememberToasterState(),
                    LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                ) { PromptPage(vm) }
            }
        }
        val add = compose.activity.getString(R.string.prompt_page_add_mode_injection)
        compose.onNodeWithText(add).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.text_area_import_from_file))
            .assertHasClickAction()
        compose.onNode(hasScrollToIndexAction()).performTouchInput {
            swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 500)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(add).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        compose.onNodeWithText(add).assertDoesNotExist()
        compose.onNodeWithContentDescription(add).assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.prompt_page_cancel)).performClick()
        compose.runOnIdle { assertTrue(updates.isEmpty()); assertEquals(originals, settings.value.modeInjections) }

        compose.onNodeWithContentDescription(add).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.prompt_page_name))
            .performScrollTo().performTextReplacement("Added by named action")
        closeSoftKeyboard()
        compose.onNodeWithText(compose.activity.getString(R.string.prompt_page_injection_content))
            .performScrollTo().performTextReplacement("New literal {{message}}")
        closeSoftKeyboard()
        val concurrent = PromptInjection.ModeInjection(name = "Concurrent rule", content = "Keep concurrent content",
            position = InjectionPosition.BOTTOM_OF_CHAT, role = MessageRole.ASSISTANT)
        compose.runOnIdle { settings.value = settings.value.copy(modeInjections = originals + concurrent) }
        compose.onNodeWithText(compose.activity.getString(R.string.prompt_page_confirm)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, updates.size)
            assertEquals(originals + concurrent, settings.value.modeInjections.dropLast(1))
            val added = settings.value.modeInjections.last()
            assertEquals("Added by named action", added.name)
            assertEquals("New literal {{message}}", added.content)
            assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, added.position)
            assertEquals(MessageRole.USER, added.role)
            assertEquals(4, added.injectDepth)
            assertTrue(settings.value.modeInjections.dropLast(1).none { it.id == added.id })
        }
    }

    @Test fun positionSelectionKeepsRoleAndContentAndUsesExistingDepthField() {
        var injection by mutableStateOf(PromptInjection.ModeInjection(name = "Original rule",
            position = InjectionPosition.AFTER_SYSTEM_PROMPT, role = MessageRole.ASSISTANT, content = "{{literal}}"))
        var confirmed: PromptInjection.ModeInjection? = null
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
            ModeInjectionEditSheet(injection, onDismiss = {}, onConfirm = { confirmed = injection }, onEdit = { injection = it })
        } } }
        val roleLabel = compose.activity.getString(R.string.prompt_page_injection_role)
        compose.onNodeWithText(roleLabel).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.prompt_page_position_after_system)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.prompt_page_position_at_depth)).performClick()
        val depthLabel = compose.activity.getString(R.string.prompt_page_inject_depth)
        compose.onNodeWithText(depthLabel).performScrollTo().performTextReplacement("1")
        closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithText(roleLabel).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("{{literal}}").performScrollTo().assertHeightIsEqualTo(200.dp)
        compose.runOnIdle {
            assertEquals(MessageRole.ASSISTANT, injection.role)
            assertEquals(1, injection.injectDepth)
            assertEquals("{{literal}}", injection.content)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.prompt_page_position_at_depth)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.prompt_page_position_bottom_of_chat)).performClick()
        compose.onNodeWithText(depthLabel).assertDoesNotExist()
        compose.onNodeWithText(roleLabel).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.prompt_page_confirm)).performClick()
        compose.runOnIdle {
            assertEquals(InjectionPosition.BOTTOM_OF_CHAT, confirmed!!.position)
            assertEquals(MessageRole.ASSISTANT, confirmed!!.role)
            assertEquals("{{literal}}", confirmed!!.content)
        }
    }
}
