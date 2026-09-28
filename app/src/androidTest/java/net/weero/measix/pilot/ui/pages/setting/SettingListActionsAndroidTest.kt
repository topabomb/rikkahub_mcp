package net.weero.measix.pilot.ui.pages.setting

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import me.rerere.search.SearchServiceOptions
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingListActionsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun searchMenuRequiresConfirmationWithoutTriggeringCardEditAndProtectsLastProvider() {
        var edits = 0
        var deletions = 0
        val canDelete = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                SearchProviderCard(SearchServiceOptions.BingLocalOptions(), { edits++ }, { deletions++ }, canDelete.value)
            }
        }
        compose.onNodeWithText("Bing").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
        val more = compose.activity.getString(R.string.more_options)
        val delete = compose.activity.getString(R.string.delete)
        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(delete).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(0, deletions) }
        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(delete).performClick()
        compose.onNodeWithText(delete).performClick()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(1, deletions); canDelete.value = false }
        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(delete).assertIsNotEnabled()
    }

    @Test
    fun speechRadioSelectsWithoutEditingAndManagedDefinitionHasNoEditOrDeleteAction() {
        var edits = 0
        var selections = 0
        var deletions = 0
        val managed = mutableStateOf(false)
        val providerName = mutableStateOf("Speech test")
        compose.setContent {
            val isManaged = managed.value
            val reference = if (isManaged) ConfigurationReference.Enterprise(EnterpriseAuthority("deployment"), "tts_probe") else ConfigurationReference.random()
            MaterialTheme {
                SpeechProviderItem(
                    resource = ConfigurationCatalogItem(ConfigurationKey(ConfigurationCategory.TTS, reference), providerName.value, ConfigurationAccess(!isManaged)),
                    details = "System TTS", selected = false, showSource = false, modifier = Modifier,
                    onSelect = { selections++ }, onEdit = if (isManaged) null else ({ edits++ }),
                    onDelete = if (isManaged) null else ({ deletions++ }), test = null,
                )
            }
        }
        compose.onNode(hasContentDescription("Speech test") and isSelectable()).performClick()
        compose.runOnIdle { assertEquals(1, selections); assertEquals(0, edits) }
        compose.onNodeWithText("Speech test").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.more_options)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.delete)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0, deletions); managed.value = true }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.more_options)).assertDoesNotExist()
        compose.onNodeWithText("Speech test").assertHasNoClickAction()
        compose.onNode(hasContentDescription("Speech test") and isSelectable()).performClick()
        compose.runOnIdle { assertEquals(2, selections); assertEquals(1, edits); providerName.value = "" }
        compose.onNode(hasContentDescription("System TTS") and isSelectable()).performClick()
        compose.runOnIdle { assertEquals(3, selections); assertEquals(1, edits) }
    }
}
