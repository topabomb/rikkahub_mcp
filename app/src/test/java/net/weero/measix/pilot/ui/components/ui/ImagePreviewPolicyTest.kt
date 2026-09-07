package net.weero.measix.pilot.ui.components.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Image preview surface policy: action labels and background-failure string mapping, the drag /
 * scrim / overlay progress math and save-error and delete-index decisions, and image-source
 * classification (inline / network / generated / upload / local, including the sibling-prefix and
 * uppercase-scheme guards). Pure decision functions aggregated by component owner.
 */
class ImagePreviewPolicyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val height = 1000f

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
    fun `progress is zero without drag or without container`() {
        assertEquals(0f, dragProgress(0f, height))
        assertEquals(0f, dragProgress(500f, 0f))
        assertEquals(0f, dragProgress(500f, -1f))
    }

    @Test
    fun `progress is direction agnostic and not upper clamped`() {
        assertEquals(0.25f, dragProgress(250f, height))
        assertEquals(0.25f, dragProgress(-250f, height))
        assertEquals(1.2f, dragProgress(height * 1.2f, height))
    }

    @Test
    fun `scrim alpha stays within bounds and never clamps above one`() {
        assertEquals(1f, scrimAlpha(0f, height))
        assertEquals(0.25f, scrimAlpha(height, height))
        // 退出动画终点(1.2 倍高)仍有余量下限 0.1(浮点容差)
        assertEquals(0.1f, scrimAlpha(height * 1.2f, height), 1e-6f)
    }

    @Test
    fun `overlay alpha hides at half progress and clamps to zero beyond`() {
        assertEquals(1f, overlayAlpha(0f, height))
        assertEquals(0f, overlayAlpha(height * 0.5f, height))
        assertEquals(0f, overlayAlpha(height, height))
    }

    @Test
    fun `save error maps permission separately from generic failures`() {
        assertEquals(
            "grant",
            imageSaveErrorMessage(
                message = net.weero.measix.pilot.service.IMAGE_SAVE_PERMISSION_REQUIRED,
                permissionText = "grant",
                failedFormat = "failed: %s",
            ),
        )
        assertEquals(
            "failed: HTTP 404",
            imageSaveErrorMessage(
                message = "HTTP 404",
                permissionText = "grant",
                failedFormat = "failed: %s",
            ),
        )
    }

    @Test
    fun `deleting a middle image keeps the same index for its successor`() {
        assertEquals(1, nextImageIndexAfterDelete(itemCount = 4, deletedIndex = 1))
    }

    @Test
    fun `deleting the last image selects the new last image`() {
        assertEquals(2, nextImageIndexAfterDelete(itemCount = 4, deletedIndex = 3))
    }

    @Test
    fun `deleting the only image closes the viewer`() {
        assertEquals(null, nextImageIndexAfterDelete(itemCount = 1, deletedIndex = 0))
    }

    @Test
    fun `classifies inline and network by scheme`() {
        assertEquals(ImageInfoSource.Inline, classifyImageSource("data:image/png;base64,QUJD"))
        assertEquals(ImageInfoSource.Network, classifyImageSource("https://example.com/a.png"))
        assertEquals(ImageInfoSource.Network, classifyImageSource("http://example.com/a.png"))
    }

    @Test
    fun `classifies app directories for file urls and bare paths`() {
        val filesDir = temp.newFolder()
        val generated = File(filesDir, "images/x.png").absolutePath
        val upload = File(filesDir, "upload/y.png").absolutePath
        val uploadUrl = "file://$upload"
        val isGenerated: (File) -> Boolean = { it.absolutePath == generated }

        assertEquals(
            ImageInfoSource.Generated,
            classifyImageSource("file://$generated", isManagedGeneratedFile = isGenerated),
        )
        assertEquals(
            ImageInfoSource.Upload,
            classifyImageSource(uploadUrl, isManagedUploadUrl = { it == uploadUrl }),
        )
        assertEquals(
            ImageInfoSource.Generated,
            classifyImageSource(generated, isManagedGeneratedFile = isGenerated),
        )
        assertEquals(ImageInfoSource.Local, classifyImageSource("file:///sdcard/Pictures/z.png"))
        assertEquals(ImageInfoSource.Local, classifyImageSource("/sdcard/Pictures/z.png"))
    }

    @Test
    fun `sibling directory with shared prefix is not mistaken for app directory`() {
        val filesDir = temp.newFolder()
        val sibling = File(filesDir, "images2/x.png").absolutePath

        val generatedDir = File(filesDir, "images").absoluteFile
        assertEquals(
            ImageInfoSource.Local,
            classifyImageSource("file://$sibling", isManagedGeneratedFile = { file ->
                file.absolutePath.startsWith(generatedDir.path + File.separator)
            }),
        )
    }

    @Test
    fun `uppercase file scheme uses the same local path`() {
        val filesDir = temp.newFolder()
        val generated = File(filesDir, "images/x.png").absoluteFile
        val uppercase = "FILE://" + generated.absolutePath.replace('\\', '/')

        assertEquals(
            ImageInfoSource.Generated,
            classifyImageSource(uppercase, isManagedGeneratedFile = { it.absoluteFile == generated }),
        )
    }
}
