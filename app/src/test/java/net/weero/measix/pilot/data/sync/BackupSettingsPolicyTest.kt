package net.weero.measix.pilot.data.sync

import kotlin.uuid.Uuid
import net.weero.measix.pilot.data.datastore.ChatFontFamily
import net.weero.measix.pilot.data.datastore.PendingAssistantDeletion
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSettingsPolicyTest {
    @Test
    fun `settings-only backup removes every identity owned by local durable domains`() {
        val local = Assistant(
            avatar = Avatar.Image("file:///files/upload/avatar.png"),
            background = "content://local/background",
        )
        val remote = Assistant(
            avatar = Avatar.Image("https://example.com/avatar.png"),
            background = "data:image/png;base64,AA==",
        )
        val settings = Settings(
            assistants = listOf(local, remote),
            displaySetting = Settings().displaySetting.copy(
                userAvatar = Avatar.Image("/upload/user.png"),
                chatFontFamily = ChatFontFamily.CUSTOM,
                chatCustomFontPath = "fonts/custom.ttf",
                chatCustomFontName = "custom.ttf",
            ),
            pendingAssistantDeletions = listOf(
                PendingAssistantDeletion(Uuid.random(), avatarUri = "file:///files/upload/deleted.png")
            ),
        )

        val portable = BackupSettingsPolicy.withoutLocalPayloadReferences(settings)

        assertEquals(Avatar.Dummy, portable.assistants[0].avatar)
        assertNull(portable.assistants[0].background)
        assertEquals(remote.avatar, portable.assistants[1].avatar)
        assertEquals(remote.background, portable.assistants[1].background)
        assertEquals(Avatar.Dummy, portable.displaySetting.userAvatar)
        assertEquals(ChatFontFamily.DEFAULT, portable.displaySetting.chatFontFamily)
        assertEquals("", portable.displaySetting.chatCustomFontPath)
        assertEquals("", portable.displaySetting.chatCustomFontName)
        assertTrue(portable.pendingAssistantDeletions.isEmpty())
    }
}
