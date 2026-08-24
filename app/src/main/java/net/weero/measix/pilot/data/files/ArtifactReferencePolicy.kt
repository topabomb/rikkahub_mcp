package net.weero.measix.pilot.data.files

import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar

/** Settings 域 artifact roots 的唯一语义定义。 */
object ArtifactReferencePolicy {
    fun roots(settings: Settings): Set<String> = buildSet {
        settings.assistants.forEach { assistant ->
            assistant.background?.let(::add)
            (assistant.avatar as? Avatar.Image)?.url?.let(::add)
        }
        (settings.displaySetting.userAvatar as? Avatar.Image)?.url?.let(::add)
    }

    fun detach(settings: Settings, fileUris: Set<String>): Settings {
        if (fileUris.isEmpty()) return settings
        val assistants = settings.assistants.map { assistant -> detach(assistant, fileUris) }
        val avatar = settings.displaySetting.userAvatar
        val display = if (avatar is Avatar.Image && avatar.url in fileUris) {
            settings.displaySetting.copy(userAvatar = Avatar.Dummy)
        } else {
            settings.displaySetting
        }
        return settings.copy(assistants = assistants, displaySetting = display)
    }

    private fun detach(assistant: Assistant, fileUris: Set<String>): Assistant {
        val background = assistant.background?.takeUnless(fileUris::contains)
        val avatar = assistant.avatar.let { value ->
            if (value is Avatar.Image && value.url in fileUris) Avatar.Dummy else value
        }
        return if (background == assistant.background && avatar == assistant.avatar) {
            assistant
        } else {
            assistant.copy(background = background, avatar = avatar)
        }
    }
}
