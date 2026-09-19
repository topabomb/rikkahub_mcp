package me.rerere.asr.providers

import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTranscriptionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `encoded file limit includes metadata and base64 or multipart overhead`() {
        FileTranscriptionProtocol.entries.forEach { protocol ->
            val file = temporary.newFile()
            val limit = maxFileTranscriptionAudioBytes(protocol, "model-with-utf8-\u4e2d\u6587", "zh", 2048)
            RandomAccessFile(file, "rw").use { it.setLength(limit) }
            assertTrue(fileTranscriptionBody(protocol, "model-with-utf8-\u4e2d\u6587", "zh", file, 2048).contentLength() <= 2048)
            RandomAccessFile(file, "rw").use { it.setLength(limit + 1) }
            assertThrows(IllegalArgumentException::class.java) {
                fileTranscriptionBody(protocol, "model-with-utf8-\u4e2d\u6587", "zh", file, 2048)
            }
        }
    }

    @Test fun `each protocol reads only its declared transcript shape`() {
        assertEquals("one", decodeFileTranscription(FileTranscriptionProtocol.OPENAI, """{"text":"one"}"""))
        assertEquals("two", decodeFileTranscription(FileTranscriptionProtocol.DASHSCOPE, """{"output":{"text":"two"}}"""))
        assertThrows(IllegalStateException::class.java) {
            decodeFileTranscription(FileTranscriptionProtocol.DASHSCOPE, """{"text":"wrong profile"}""" )
        }
        assertThrows(IllegalStateException::class.java) {
            decodeFileTranscription(FileTranscriptionProtocol.OPENAI, """{"text":123}""" )
        }
    }
}
