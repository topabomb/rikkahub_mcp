package net.weero.measix.pilot.service.runtime

import io.mockk.mockk
import net.weero.measix.pilot.data.ai.transformers.AttachmentProjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.Base64ImageToLocalFileTransformer
import net.weero.measix.pilot.data.ai.transformers.DocumentAsPromptTransformer
import net.weero.measix.pilot.data.ai.transformers.PlaceholderTransformer
import net.weero.measix.pilot.data.ai.transformers.PromptInjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.RegexOutputTransformer
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.TimeReminderTransformer
import net.weero.measix.pilot.data.ai.transformers.ToolArtifactReplayTransformer
import net.weero.measix.pilot.data.ai.transformers.WorkspaceReminderTransformer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TurnPipelineFactory 装配等价性测试。
 * 锁定 masterInput/targetInput 与 ChatService/Coordinator 原装配段逐项一致，
 * 防止后续装配顺序回归。
 */
class TurnPipelineFactoryTest {

    private fun factory(toolArtifactReplay: Boolean = true): TurnPipelineFactory = TurnPipelineFactory(
        templateTransformer = mockk<TemplateTransformer>(relaxed = true),
        workspaceReminderTransformer = mockk<WorkspaceReminderTransformer>(relaxed = true),
        toolArtifactReplayTransformer = if (toolArtifactReplay) mockk<ToolArtifactReplayTransformer>(relaxed = true) else null,
    )

    @Test
    fun `BASE_INPUT matches documented base order`() {
        assertEquals(
            listOf(
                TimeReminderTransformer::class,
                PromptInjectionTransformer::class,
                PlaceholderTransformer::class,
                DocumentAsPromptTransformer::class,
            ),
            TurnPipelineFactory.BASE_INPUT.map { it::class },
        )
    }

    @Test
    fun `P3 BASE_OUTPUT matches documented base order`() {
        assertEquals(
            listOf(
                ThinkTagTransformer::class,
                Base64ImageToLocalFileTransformer::class,
                RegexOutputTransformer::class,
            ),
            TurnPipelineFactory.BASE_OUTPUT.map { it::class },
        )
    }

    @Test
    fun `P1 masterInput appends template workspace and replay then projection`() {
        val f = factory(toolArtifactReplay = true)
        val classes = f.masterInput().map { it::class }
        assertEquals(
            listOf(
                TimeReminderTransformer::class,
                PromptInjectionTransformer::class,
                PlaceholderTransformer::class,
                DocumentAsPromptTransformer::class,
                TemplateTransformer::class,
                WorkspaceReminderTransformer::class,
                ToolArtifactReplayTransformer::class,
                AttachmentProjectionTransformer::class,
            ),
            classes,
        )
    }

    @Test
    fun `P1 masterInput omits replay when null`() {
        val f = factory(toolArtifactReplay = false)
        val classes = f.masterInput().map { it::class }
        assertTrue(ToolArtifactReplayTransformer::class !in classes)
        assertEquals(7, classes.size)
    }

    @Test
    fun `P2 targetInput matches coordinator hardcoded list`() {
        val f = factory(toolArtifactReplay = false)
        val classes = f.targetInput().map { it::class }
        assertEquals(
            listOf(
                TimeReminderTransformer::class,
                PromptInjectionTransformer::class,
                PlaceholderTransformer::class,
                DocumentAsPromptTransformer::class,
                AttachmentProjectionTransformer::class,
                TemplateTransformer::class,
                WorkspaceReminderTransformer::class,
            ),
            classes,
        )
    }

    @Test
    fun `targetOutput equals BASE_OUTPUT`() {
        val f = factory()
        assertEquals(TurnPipelineFactory.BASE_OUTPUT.map { it::class }, f.targetOutput().map { it::class })
    }
}
