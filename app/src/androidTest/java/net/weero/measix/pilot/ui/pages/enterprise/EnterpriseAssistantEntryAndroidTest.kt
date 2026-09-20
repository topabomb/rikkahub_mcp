package net.weero.measix.pilot.ui.pages.enterprise

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ConfigurationSelection
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class EnterpriseAssistantEntryAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun noDefaultAndNoStartersStillOpenAnExplicitlyChosenAssistant() = verifyEntry(false)

    @Test fun invalidSavedAssistantKeepsItsDiagnosticUntilExplicitReselection() = verifyEntry(true)

    private fun verifyEntry(invalidSavedSelection: Boolean) {
        val authority = EnterpriseAuthority("deployment")
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(authority, "user"), "entry-session")
        val selection = RealmSelection(access, 1)
        val id = ConfigurationReference.Enterprise(authority, "asd_available")
        val selected = if (invalidSavedSelection) ConfigurationReference.Enterprise(authority, "asd_missing") else null
        val assistant = Assistant(id = id, name = "Available enterprise assistant")
        val queries = mockk<ConfigurationQueryService>()
        val conversations = mockk<ConversationApplicationService>()
        every { queries.observeEnterpriseStarters(selection) } returns MutableStateFlow(
            EnterpriseStarterReadState.Available(EnterpriseStarterCatalogUiModel("Entry test enterprise", emptyList())))
        every { queries.observeAssistantCatalog() } returns MutableStateFlow(AssistantCatalogReadState.Available(
            AssistantCatalogUiModel(selection, ConfigurationSelection(selected,
                if (invalidSavedSelection) ConfigurationUnavailableReason.REFERENCE_MISSING else null),
                mapOf(id to assistant), emptyList())))
        coEvery { queries.requireSelection(selection) } returns Unit
        val draft = ConversationOpenRequest.NewDraft(Uuid.random(), access, id)
        coEvery { conversations.selectAssistantRequest(selection, id, true) } returns draft
        val opened = AtomicReference<StarterDraftRequest?>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings(),
                    LocalToaster provides rememberToasterState(),
                    LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                    EnterpriseStarterPicker(selection, opened::set, {}, queries, conversations)
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.enterprise_starters_empty)).assertIsDisplayed()
        if (invalidSavedSelection) compose.onNodeWithText(compose.activity.getString(R.string.configuration_reason_missing)).assertIsDisplayed()
        coVerify(exactly = 0) { conversations.selectAssistantRequest(any(), any(), any()) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.safe_mode_switch_assistant)).performClick()
        compose.onNodeWithText(assistant.name).performClick()
        compose.waitUntil(5_000) { opened.get() != null }
        assertEquals(StarterDraftRequest(draft, ""), opened.get())
        coVerify(exactly = 1) { conversations.selectAssistantRequest(selection, id, true) }
    }
}
