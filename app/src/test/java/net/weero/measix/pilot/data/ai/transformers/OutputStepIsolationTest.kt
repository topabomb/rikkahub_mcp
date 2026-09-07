package net.weero.measix.pilot.data.ai.transformers

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.PersistedMessageArtifacts
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.runtime.TurnTransition
import net.weero.measix.pilot.service.turn.resolveTurnAssistantSnapshot
import net.weero.measix.pilot.test.testPromptInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OutputStepIsolationTest {
    @Test
    fun `image materialization owns only open Step payload and never rewrites closed history`() = runTest {
        val closed = TurnTransition.openStep(0).copy(outcome = StepOutcome.Continue)
        val oldImage = UIMessagePart.Image("data:image/png;base64,old")
        val open = TurnTransition.openStep(1)
        val newImage = UIMessagePart.Image("data:image/png;base64,new")
        val local = UIMessagePart.Image("file:///upload/new.png")
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(closed, oldImage, open, newImage))
        val store = mockk<ArtifactStore>()
        val owned = mockk<OwnedArtifact>()
        val lease = mockk<ToolResourceLease>()
        val projected = slot<UIMessage>()
        coEvery { store.persistBase64Images(capture(projected)) } coAnswers {
            PersistedMessageArtifacts(firstArg<UIMessage>().copy(parts = listOf(local)), listOf(owned))
        }
        every { store.unpublishedBatchLease(listOf(owned)) } returns lease
        val registered = mutableListOf<ToolResourceLease>()
        val context = TransformerContext(
            context = mockk(relaxed = true), model = Model(modelId = "test"),
            assistant = resolveTurnAssistantSnapshot(Assistant()), promptInputs = testPromptInputs(),
            requestOrigins = RequestMessageOriginTracker(), registerUnpublishedResource = { registered += it },
        )
        val transformer = Base64ImageToLocalFileTransformer(store)

        val result = transformer.onStreamingFinish(context, message, null)

        assertEquals(listOf(newImage), projected.captured.parts)
        assertEquals(listOf(closed, oldImage, open, local), result.parts)
        assertSame(oldImage, result.parts[1])
        assertEquals(listOf(lease), registered)
        val terminal = message.copy(parts = listOf(closed, oldImage))
        assertSame(terminal, transformer.onStreamingFinish(context, terminal, null))
        coVerify(exactly = 1) { store.persistBase64Images(any()) }
    }
}
