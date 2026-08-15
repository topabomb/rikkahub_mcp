package net.weero.measix.pilot.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class BackupArchivePathTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `nested backup entry remains under its destination root`() {
        val root = temporaryFolder.newFolder("upload")

        val target = resolveBackupEntry(root, "nested/file.txt")

        assertEquals(root.resolve("nested/file.txt").canonicalFile, target)
    }

    @Test
    fun `backup entry cannot escape its destination root`() {
        val root = temporaryFolder.newFolder("upload")

        assertNull(resolveBackupEntry(root, "../outside.txt"))
        assertNull(resolveBackupEntry(root, "nested/../../outside.txt"))
    }
}
