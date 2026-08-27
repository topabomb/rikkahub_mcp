package net.weero.measix.pilot.data.ai.tools

import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolsTest {
    @Test
    fun `workspace image output carries a stable attachment handle`() {
        val image = workspaceImagePart("file:///data/data/net.weero.measix.pilot/files/upload/image.png")

        val ref = AttachmentRefs.getStableRef(image)
        assertNotNull(ref)
        assertTrue(ref!!.startsWith(AttachmentRefs.PREFIX))
    }
}
