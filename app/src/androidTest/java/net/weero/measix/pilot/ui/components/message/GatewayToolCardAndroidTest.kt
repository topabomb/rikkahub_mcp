package net.weero.measix.pilot.ui.components.message

import android.content.Context
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.ui.context.LocalSettings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolOutputArchiveRef
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.ui.components.ui.ChainOfThought
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class GatewayToolCardAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun archivedCardOpensCommittedBusinessIdentityWithoutReadingArchivedPayloadOrArguments() {
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "gateway-call",
            toolName = "mcp__enterprise_example__invoke_tool", input = "{\"name\":\"untrusted_argument_name\"}",
            output = listOf(UIMessagePart.Text("[Archived tool result: ref=7; status=completed; lines=3; chars=90]")),
            resultStatus = ToolResultStatus.COMPLETED,
            runtimeState = ToolRuntimeState(ToolOutputPolicy.ARCHIVABLE_TEXT,
                ToolOutputArchive(7, ToolOutputArchiveRef("not-installed.txt", "text/plain"), 90, 3)),
            metadata = buildJsonObject { put("com.measix/resolvedTool", buildJsonObject {
                put("gatewayToolId", "gtl_updates"); put("name", "Enterprise feed")
                put("status", "completed"); put("requestId", "req_archive")
                put("credential", "secret_should_not_render")
            }) },
        )
        val locator = ToolCallLocator(Uuid.random(), tool.stepId, tool.localCallId)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(), LocalSettings provides Settings.dummy()) {
                    ChainOfThought(steps = listOf(tool)) { ChatMessageToolStep(it, locator, ToolLivePhase.COMPLETED) }
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val title = context.getString(R.string.chat_message_tool_call_generic, "Enterprise feed")
        compose.onNodeWithText(title).assertIsDisplayed().performClick()
        compose.onAllNodesWithText("req_archive", substring = true).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("gtl_updates", substring = true).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("secret_should_not_render", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("untrusted_argument_name", substring = true).assertCountEquals(0)
    }
}
