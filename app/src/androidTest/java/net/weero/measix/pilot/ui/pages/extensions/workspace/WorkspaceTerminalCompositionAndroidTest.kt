package net.weero.measix.pilot.ui.pages.extensions.workspace

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.view.TerminalView
import net.weero.measix.pilot.service.workspace.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies Compose disposal/reuse; native PTY ownership has a separate platform test. */
@RunWith(AndroidJUnit4::class)
class WorkspaceTerminalCompositionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun switchingTabsRetiresOldViewsAndExtraKeysUseTheDisplayedTab() {
        val bound = mutableListOf<Pair<String, TerminalView>>()
        val sent = mutableListOf<Pair<String, String>>()
        var state by mutableStateOf(WorkspaceTerminalScreenUiModel(
            workspaceId = "workspace",
            shellReady = true,
            terminal = WorkspaceTerminalWorkspaceState(
                tabs = listOf(
                    WorkspaceTerminalTabUiModel("a", 1, "First", WorkspaceTerminalReadiness.READY),
                    WorkspaceTerminalTabUiModel("b", 2, "Second", WorkspaceTerminalReadiness.READY),
                ),
                selectedTabId = "a",
            ),
        ))
        compose.setContent {
            MaterialTheme {
                WorkspaceTerminalContent(
                    state = state,
                    contentPadding = PaddingValues(top = 48.dp, bottom = 24.dp),
                    bindViewport = { tab, viewport -> bound += tab to viewport.view; true },
                    unbindViewport = { _, viewport -> viewport.view.retire() },
                    writeTerminal = { tab, text -> sent += tab to text },
                    onCreate = {},
                    onSelect = { state = state.copy(terminal = state.terminal.copy(selectedTabId = it)) },
                    onClose = {},
                    onRename = { _, _ -> },
                    onReorder = {},
                )
            }
        }
        compose.runOnIdle { assertEquals(listOf("a"), bound.map { it.first }) }
        compose.onNodeWithText("Second").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(listOf("a", "b"), bound.map { it.first }) }
        compose.onNodeWithText("-").performClick()
        compose.runOnIdle {
            assertTrue(bound[0].second.isRetired)
            assertFalse(bound[1].second.isRetired)
            assertEquals(listOf("b" to "-"), sent)
        }
        compose.onNodeWithText("First").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("Displayed tab, bound=${bound.map { it.first }}", "a", state.terminal.selectedTabId) }
        compose.runOnIdle {
            assertEquals(listOf("a", "b", "a"), bound.map { it.first })
            assertTrue(bound[1].second.isRetired)
            assertFalse(bound[2].second.isRetired)
            assertNotSame(bound[0].second, bound[2].second)
            state = state.copy(terminal = WorkspaceTerminalWorkspaceState())
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(bound.all { it.second.isRetired }) }
    }
}
