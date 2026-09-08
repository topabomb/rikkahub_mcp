package net.weero.measix.pilot.data.files

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilePayloadReadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `byte limit applies to the actual read even when the length check is stale`() = runTest {
        val file = temporary.newFile()
        val limit = net.weero.measix.pilot.data.imggen.GeneratedMediaStore.MAX_IMAGE_BYTES
        val bytes = ByteArray(limit) { (it % 251).toByte() }
        file.writeBytes(bytes)
        assertArrayEquals(bytes, FileUtils.readBoundedBytes(file, limit.toLong()))
        file.appendBytes(byteArrayOf(1))
        assertTrue(runCatching { FileUtils.readBoundedBytes(file, limit.toLong()) }.exceptionOrNull() is FilePayloadTooLargeException)
        val staleLength = object : File(file.absolutePath) { override fun length(): Long = 0 }
        assertTrue(runCatching { FileUtils.readBoundedBytes(staleLength, limit.toLong()) }.exceptionOrNull() is FilePayloadTooLargeException)
    }
}
