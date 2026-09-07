package net.weero.measix.pilot.ui.components.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.model.QuickMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigurationListStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun configurationListCanComposeSelectAndRestoreItsSaveableState() {
        val reference = ConfigurationReference.parse("managed~local~example~dep_example~quick_example")
        var selected by mutableStateOf(emptySet<ConfigurationReference>())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                QuickMessagesContent(
                    quickMessages = listOf(QuickMessage(id = reference, title = "Example quick message")),
                    selectedIds = selected,
                    onToggle = { id, enabled -> selected = if (enabled) selected + id else selected - id },
                )
            }
        }
        compose.onNodeWithText("Example quick message").assertExists()
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertEquals(setOf(reference), selected) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Example quick message").assertExists()
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertEquals(emptySet<ConfigurationReference>(), selected) }
    }
}
