package net.weero.measix.pilot.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalArtifactRefTest {
    @Test
    fun `tool path preserves exact short suffixed and historical names`() {
        listOf("g7ka2b.png", "g7ka2b-2.png", "OldFile.PNG", "809278de-6677-4bc1-9249-d94c85b0930c.png").forEach { name ->
            assertEquals("/upload/$name", LocalArtifactRef(relativePath = "upload/$name", mimeType = "image/png").toolPath())
        }
    }

    @Test
    fun `tool path never flattens directories or repairs malformed authority`() {
        listOf("images/a.png", "upload/nested/a.png", "upload/../a.png", "upload/a%2f.png", "upload/a\\b.png", "upload/a.png ").forEach { path ->
            assertNull(LocalArtifactRef(relativePath = path, mimeType = "image/png").toolPath())
        }
        assertNull(LocalArtifactRef(version = 2, relativePath = "upload/a.png", mimeType = "image/png").toolPath())
    }
}
