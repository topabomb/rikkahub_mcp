package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.transformers.ImageAdaptCapability
import net.weero.measix.pilot.data.ai.transformers.ImageInputAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `without extras no parts and observe is not called`() = runTest {
        var observed = 0
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image),
            extras = emptySet(),
            capability = ImageAdaptCapability.DERIVED,
            observe = {
                observed++
                "should not run"
            },
        )
        assertTrue(projection.extraParts.isEmpty())
        assertNull(projection.artifactDelivery)
        assertEquals(0, observed)
    }

    @Test
    fun `native extras append image parts and omit delivery`() = runTest {
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
            capability = ImageAdaptCapability.NATIVE,
            observe = { error("native must not observe") },
        )
        assertEquals(1, projection.extraParts.size)
        val part = projection.extraParts.single() as UIMessagePart.Image
        assertEquals("file:///tmp/a.png", part.url)
        assertEquals(ref, AttachmentRefs.getRef(part))
        assertNull(projection.artifactDelivery)
    }

    @Test
    fun `derived extras append observation text and never the original image`() = runTest {
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
            capability = ImageAdaptCapability.DERIVED,
            observe = { "a red square" },
        )
        assertEquals(1, projection.extraParts.size)
        val text = projection.extraParts.single() as UIMessagePart.Text
        assertTrue(text.text.contains("<attachment_observation ref=\"$ref\">"))
        assertTrue(text.text.contains("a red square"))
        assertTrue(projection.extraParts.none { it is UIMessagePart.Image })
        assertEquals(ARTIFACT_DELIVERY_DERIVED, projection.artifactDelivery)
    }

    @Test
    fun `derived observe failure still completes with stable error sentence`() = runTest {
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
            capability = ImageAdaptCapability.DERIVED,
            observe = { error("ocr down") },
        )
        val text = projection.extraParts.single() as UIMessagePart.Text
        assertTrue(text.text.contains(ImageInputAdapter.OBSERVATION_FAILED))
        assertEquals(ARTIFACT_DELIVERY_UNAVAILABLE, projection.artifactDelivery)
    }

    @Test
    fun `derived partial observe failure reports partial delivery`() = runTest {
        val second = image.copy(
            ref = AttachmentRefs.format(Uuid.parse("22222222-2222-2222-2222-222222222222")),
            fileUrl = "file:///tmp/b.png",
        )
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image, second),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
            capability = ImageAdaptCapability.DERIVED,
            observe = { img ->
                if ((img as UIMessagePart.Image).url.endsWith("a.png")) "a red square" else error("ocr down")
            },
        )
        assertEquals(2, projection.extraParts.size)
        assertEquals(ARTIFACT_DELIVERY_PARTIAL, projection.artifactDelivery)
    }

    @Test
    fun `derived all success but unsupported type present reports partial`() = runTest {
        val document = SubAssistantDeliverableArtifact(
            ref = AttachmentRefs.format(Uuid.parse("33333333-3333-3333-3333-333333333333")),
            type = ARTIFACT_TYPE_DOCUMENT,
            mime = "application/pdf",
            fileUrl = "file:///tmp/a.pdf",
        )
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image, document),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
            capability = ImageAdaptCapability.DERIVED,
            observe = { "a red square" },
        )
        assertEquals(1, projection.extraParts.size)
        assertEquals(ARTIFACT_DELIVERY_PARTIAL, projection.artifactDelivery)
    }

    @Test
    fun `native extras with unsupported type reports partial delivery`() = runTest {
        val document = SubAssistantDeliverableArtifact(
            ref = AttachmentRefs.format(Uuid.parse("33333333-3333-3333-3333-333333333333")),
            type = ARTIFACT_TYPE_DOCUMENT,
            mime = "application/pdf",
            fileUrl = "file:///tmp/a.pdf",
        )
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image, document),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
            capability = ImageAdaptCapability.NATIVE,
            observe = { error("native must not observe") },
        )
        assertEquals(1, projection.extraParts.size)
        assertEquals(ARTIFACT_DELIVERY_PARTIAL, projection.artifactDelivery)
    }

    @Test
    fun `unavailable extras keep completed delivery flag and no image parts`() = runTest {
        val projection = projectArtifactsForCaller(
            artifacts = listOf(image),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
            capability = ImageAdaptCapability.UNAVAILABLE,
            observe = { error("unavailable must not observe") },
        )
        assertTrue(projection.extraParts.isEmpty())
        assertEquals(ARTIFACT_DELIVERY_UNAVAILABLE, projection.artifactDelivery)
    }

    @Test
    fun `named extras with only unsupported types are unavailable`() = runTest {
        val projection = projectArtifactsForCaller(
            artifacts = listOf(
                SubAssistantDeliverableArtifact(
                    ref = ref,
                    type = ARTIFACT_TYPE_DOCUMENT,
                    mime = "application/pdf",
                    fileUrl = "file:///tmp/a.pdf",
                ),
            ),
            extras = setOf(ASSISTANT_CALL_EXTRA_ARTIFACTS),
            capability = ImageAdaptCapability.NATIVE,
            observe = { error("no image to observe") },
        )
        assertTrue(projection.extraParts.isEmpty())
        assertEquals(ARTIFACT_DELIVERY_UNAVAILABLE, projection.artifactDelivery)
    }
}
