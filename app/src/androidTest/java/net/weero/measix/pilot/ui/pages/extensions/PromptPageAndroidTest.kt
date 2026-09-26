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
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.core.MessageRole
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.data.model.InjectionPosition
import net.weero.measix.pilot.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptPageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
