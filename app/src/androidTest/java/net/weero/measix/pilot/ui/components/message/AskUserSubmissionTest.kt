package net.weero.measix.pilot.ui.components.message

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.ui.components.ui.ChainOfThought
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AskUserSubmissionTest {
    @get:Rule val compose = createComposeRule()
    private val submitText get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.chat_message_tool_submit)

    @Test fun waitsForAcceptanceAndAllowsRetryAfterRejection() {
        var attempts = 0
        var result = CompletableDeferred<Boolean>()
        val tool = question()
        compose.setContent {
            MaterialTheme {
                ChainOfThought(steps = listOf(tool)) {
                    AskUserToolStep(it, ToolLivePhase.AWAITING_INPUT) { payload ->
                        assertTrue(payload.contains("blue"))
                        attempts++
                        result.await()
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("blue")
        compose.onNodeWithText(submitText).performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
        compose.onNodeWithText(submitText).assertIsNotEnabled()
        compose.runOnIdle { result.complete(false) }
        compose.onNodeWithText(submitText).assertIsEnabled()
        compose.runOnIdle { result = CompletableDeferred() }
        compose.onNodeWithText(submitText).performClick()
        compose.runOnIdle { assertEquals(2, attempts); result.complete(true) }
        compose.onNodeWithText(submitText).assertIsNotEnabled()
    }

    @Test fun removingTheQuestionCancelsItsPendingSubmission() {
        var shown by mutableStateOf(true)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Boolean>()
        val tool = question()
        compose.setContent {
            MaterialTheme {
                if (shown) ChainOfThought(steps = listOf(tool)) {
                    AskUserToolStep(it, ToolLivePhase.AWAITING_INPUT) {
                        entered.complete(Unit)
                        try { result.await() }
                        finally { cancelled.complete(Unit) }
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("blue")
        compose.onNodeWithText(submitText).performClick()
        compose.runOnIdle { assertTrue(entered.isCompleted); shown = false }
        compose.waitUntil(timeoutMillis = 5000) { cancelled.isCompleted }
        compose.onNodeWithText(submitText).assertDoesNotExist()
        assertFalse(result.isCompleted)
    }

    @Test fun replacingTheInteractionCancelsOldSubmissionAndStartsWithEmptyAnswers() {
        var tool by mutableStateOf(question())
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Boolean>()
        compose.setContent {
            MaterialTheme {
                ChainOfThought(steps = listOf(tool)) {
                    AskUserToolStep(it, ToolLivePhase.AWAITING_INPUT) {
                        entered.complete(Unit)
                        try { result.await() }
                        finally { cancelled.complete(Unit) }
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("old answer")
        compose.onNodeWithText(submitText).performClick()
        compose.runOnIdle {
            assertTrue(entered.isCompleted)
            tool = tool.copy(providerCallId = "next-interaction")
        }
        compose.waitUntil(timeoutMillis = 5000) { cancelled.isCompleted }
        compose.onNode(hasSetTextAction()).assertTextEquals("")
        compose.onNodeWithText(submitText).assertIsNotEnabled()
        assertFalse(result.isCompleted)
    }

    private fun question() = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "interaction",
        toolName = "ask_user",
        input = """{"questions":[{"id":"color","question":"Choose a color","selection_type":"text"}]}""",
    )
}
