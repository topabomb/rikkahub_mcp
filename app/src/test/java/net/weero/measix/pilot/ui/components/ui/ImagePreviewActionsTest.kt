package net.weero.measix.pilot.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class ImagePreviewActionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `short generated label truncates without dumping the full prompt`() {
        assertEquals("无提示词", shortGeneratedLabel("   ", "无提示词"))
        assertEquals("hello", shortGeneratedLabel("hello", "无提示词"))
        val long = "这是一段非常非常长的文生图提示词，不该整段塞进删除确认"
        val shortened = shortGeneratedLabel(long, "无提示词", maxChars = 8)
        assertEquals(true, shortened.endsWith("…"))
        assertEquals(true, shortened.length < long.length)
    }

    @Test
    fun `generated delete label uses a short prompt like the card title`() {
        assertEquals("img_1.png", generatedDeleteLabel("img_1.png", "无提示词"))
        assertEquals("无提示词", generatedDeleteLabel("  ", "无提示词"))
        assertEquals("无提示词", generatedDeleteLabel(null, "无提示词"))
        val long = "这是一段非常非常长的文生图提示词，不该整段塞进删除确认"
        val shortened = generatedDeleteLabel(long, "无提示词")
        assertEquals(true, shortened.endsWith("…"))
        assertEquals(true, shortened.length < long.length)
    }

    @Test
    fun `assistant display name falls back when blank`() {
        assertEquals("默认助手", assistantDisplayName("  ", "默认助手"))
        assertEquals("默认助手", assistantDisplayName(null, "默认助手"))
        assertEquals("旅行规划", assistantDisplayName("旅行规划", "默认助手"))
    }

    @Test
    fun `local file urls resolve existing files`() {
        val file = temp.newFile("wall.png")
        assertEquals(file.absoluteFile, localFileFromImageUrl("file://${file.absolutePath}")?.absoluteFile)
        assertEquals(file.absoluteFile, localFileFromImageUrl(file.absolutePath)?.absoluteFile)
        assertNull(localFileFromImageUrl("https://example.com/a.png"))
        assertNull(localFileFromImageUrl("data:image/png;base64,QUJD"))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `data uri materializes into cache`() {
        val cache = temp.newFolder("cache")
        val payload = Base64.encode("not-a-real-png".encodeToByteArray())
        val result = writeDataUriToCache(cache, "data:image/png;base64,$payload")
        requireNotNull(result)
        assertEquals("image/png", result.second)
        assertEquals(true, result.first.exists())
        assertEquals("not-a-real-png", result.first.readText())
    }

    @Test
    fun `background failure codes map to dedicated strings`() {
        assertEquals(
            net.weero.measix.pilot.R.string.chat_message_tool_generate_image_background_assistant_missing,
            backgroundFailureMessageRes("assistant_not_found"),
        )
        assertEquals(
            net.weero.measix.pilot.R.string.chat_message_tool_generate_image_background_copy_failed,
            backgroundFailureMessageRes("background_copy_failed"),
        )
        assertEquals(
            net.weero.measix.pilot.R.string.chat_message_tool_generate_image_background_settings_failed,
            backgroundFailureMessageRes("settings_write_rejected"),
        )
        assertEquals(
            net.weero.measix.pilot.R.string.image_viewer_background_failed,
            backgroundFailureMessageRes("unknown"),
        )
    }

    @Test
    fun `missing local file does not materialize`() {
        assertNull(localFileFromImageUrl(File(temp.root, "missing.png").absolutePath))
    }

    @Test
    fun `mime extension mapping stays conservative`() {
        assertEquals("jpg", extensionForMime("image/jpeg"))
        assertEquals("webp", extensionForMime("image/webp"))
        assertEquals("png", extensionForMime("application/octet-stream"))
        val file = File(temp.root, "x.webp")
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals("image/webp", mimeFromFile(file))
    }
}
