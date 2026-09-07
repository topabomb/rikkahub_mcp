package net.weero.measix.pilot.service.runtime
import net.weero.measix.pilot.service.turn.TurnPipelineFactory

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
 * 锁定单一 owner 下按 TurnKind 给出的输入顺序与共享输出顺序。
 */
class TurnPipelineFactoryTest {

    private fun factory(): TurnPipelineFactory = TurnPipelineFactory(
        templateTransformer = mockk<TemplateTransformer>(relaxed = true),
        workspaceReminderTransformer = mockk<WorkspaceReminderTransformer>(relaxed = true),
        toolArtifactReplayTransformer = mockk<ToolArtifactReplayTransformer>(relaxed = true),
        attachmentProjectionTransformer = AttachmentProjectionTransformer(mockk<ArtifactStore>(relaxed = true)),
        base64ImageToLocalFileTransformer = Base64ImageToLocalFileTransformer(mockk<ArtifactStore>(relaxed = true)),
        documentAsPromptTransformer = DocumentAsPromptTransformer(mockk<ArtifactStore>(relaxed = true)),
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
            factory().baseInput().map { it::class },
        )
    }

    @Test
    fun `output matches documented order`() {
        assertEquals(
            listOf(
                ThinkTagTransformer::class,
                Base64ImageToLocalFileTransformer::class,
                RegexOutputTransformer::class,
            ),
            factory().output().map { it::class },
        )
    }

    @Test
    fun `USER input appends template workspace replay then projection`() {
        val classes = factory().input(TurnKind.USER).map { it::class }
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
    fun `SUB_ASSISTANT input omits artifact replay`() {
        val classes = factory().input(TurnKind.SUB_ASSISTANT).map { it::class }
        assertTrue(ToolArtifactReplayTransformer::class !in classes)
        assertEquals(
            listOf(
                TimeReminderTransformer::class,
                PromptInjectionTransformer::class,
                PlaceholderTransformer::class,
                DocumentAsPromptTransformer::class,
                TemplateTransformer::class,
                WorkspaceReminderTransformer::class,
                AttachmentProjectionTransformer::class,
            ),
            classes,
        )
    }
}
