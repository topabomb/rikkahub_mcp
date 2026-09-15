package net.weero.measix.pilot.ui.activity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafeModeActivityAndroidTest {
    @get:Rule
    val compose = createAndroidComposeRule<SafeModeActivity>()

    @Test
    fun assistantPickerOpensInsideSafeModeComposition() {
        compose.onNodeWithText(compose.activity.getString(R.string.safe_mode_switch_assistant))
            .performClick()

        compose.onNodeWithText(compose.activity.getString(R.string.safe_mode_assistants))
            .assertIsDisplayed()
    }
}
