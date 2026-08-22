package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantArtifactProjectionTest {
    private val ref = AttachmentRefs.format(Uuid.parse("11111111-1111-1111-1111-111111111111"))
    private val image = SubAssistantDeliverableArtifact(
        ref = ref,
        type = ARTIFACT_TYPE_IMAGE,
        mime = "image/png",
        fileUrl = "file:///tmp/a.png",
    )

    @Test
    fun `without extras no parts are projected`() = runTest {
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image),
            extras = emptySet(),
        )
        assertTrue(projection.extraParts.isEmpty())
    }

    @Test
    fun `extras append native image parts with stable ref`() = runTest {
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
        )
        assertEquals(1, projection.extraParts.size)
        val part = projection.extraParts.single() as UIMessagePart.Image
        assertEquals("file:///tmp/a.png", part.url)
        assertEquals(ref, AttachmentRefs.getRef(part))
    }

    @Test
    fun `multiple image artifacts keep order`() = runTest {
        val second = image.copy(
            ref = AttachmentRefs.format(Uuid.parse("22222222-2222-2222-2222-222222222222")),
            fileUrl = "file:///tmp/b.png",
        )
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image, second),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
        )
        assertEquals(2, projection.extraParts.size)
        val urls = projection.extraParts.map { (it as UIMessagePart.Image).url }
        assertEquals(listOf("file:///tmp/a.png", "file:///tmp/b.png"), urls)
    }

    @Test
    fun `non image artifacts are not projected as parts`() = runTest {
        val document = SubAssistantDeliverableArtifact(
            ref = AttachmentRefs.format(Uuid.parse("33333333-3333-3333-3333-333333333333")),
            type = ARTIFACT_TYPE_DOCUMENT,
            mime = "application/pdf",
            fileUrl = "file:///tmp/a.pdf",
        )
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image, document),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
        )
        // 只有 Image 交付物投影 native parts；document 的引用保留在 metadata artifacts。
        assertEquals(1, projection.extraParts.size)
        assertTrue(projection.extraParts.all { it is UIMessagePart.Image })
    }

    @Test
    fun `image artifact without file url is skipped`() = runTest {
        val dangling = image.copy(fileUrl = null)
        val projection = projectArtifactsForCaller(
            artifacts = listOf(dangling),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
        )
        assertTrue(projection.extraParts.isEmpty())
    }
}
