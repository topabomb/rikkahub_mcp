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
import net.weero.measix.pilot.data.files.ArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TurnPipelineFactory 装配等价性测试。
 * 锁定 Master/Target 输入与共享输出的装配顺序。
 */
class TurnPipelineFactoryTest {

    private fun factory(toolArtifactReplay: Boolean = true): TurnPipelineFactory = TurnPipelineFactory(
        templateTransformer = mockk<TemplateTransformer>(relaxed = true),
        workspaceReminderTransformer = mockk<WorkspaceReminderTransformer>(relaxed = true),
        toolArtifactReplayTransformer = if (toolArtifactReplay) mockk<ToolArtifactReplayTransformer>(relaxed = true) else null,
        attachmentProjectionTransformer = AttachmentProjectionTransformer(mockk<ArtifactStore>(relaxed = true)),
        base64ImageToLocalFileTransformer = Base64ImageToLocalFileTransformer(mockk<ArtifactStore>(relaxed = true)),
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
    fun `shared output matches documented order`() {
        val f = factory()
        assertEquals(
            listOf(
                ThinkTagTransformer::class,
                Base64ImageToLocalFileTransformer::class,
                RegexOutputTransformer::class,
            ),
            f.masterOutput().map { it::class },
        )
    }

    @Test
    fun `masterInput appends template workspace and replay then projection`() {
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
    fun `masterInput omits replay when null`() {
        val f = factory(toolArtifactReplay = false)
        val classes = f.masterInput().map { it::class }
        assertTrue(ToolArtifactReplayTransformer::class !in classes)
        assertEquals(7, classes.size)
    }

    @Test
    fun `targetInput matches target policy order`() {
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
    fun `targetOutput equals masterOutput`() {
        val f = factory()
        assertEquals(f.masterOutput().map { it::class }, f.targetOutput().map { it::class })
    }
}
