package net.weero.measix.pilot.data.files

import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.configuration.UsageValue
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.collectFileReferenceTokens

/** Settings 域 artifact roots 的唯一语义定义。 */
object ArtifactReferencePolicy {
    fun roots(settings: Settings): Set<String> = buildSet {
        settings.assistants.forEach { assistant ->
            assistant.background?.let(::add)
            (assistant.avatar as? Avatar.Image)?.url?.let(::add)
            addAll(assistant.presetMessages.collectFileReferenceTokens())
        }
        (settings.displaySetting.userAvatar as? Avatar.Image)?.url?.let(::add)
    }

    internal fun roots(document: UserSettingsDocument): Set<String> = scopedRoots(document).values.flatten().toSet()

    /** Read durable definitions and overrides without resolving or dropping inactive principals. */
    internal fun scopedRoots(document: UserSettingsDocument): Map<ConfigurationScope, Set<String>> = buildMap {
        put(ConfigurationScope.Personal, roots(document.personalSettings()))
        document.preferences.scopes.forEach { scoped ->
            put(scoped.scope, buildSet {
                addAll(get(scoped.scope).orEmpty())
                scoped.assistantUsage.forEach { usage ->
                    usage.background?.value?.let(::add)
                    (usage.avatar?.value as? Avatar.Image)?.url?.let(::add)
                    addAll(usage.presetMessages?.value.orEmpty().collectFileReferenceTokens())
                }
            })
        }
    }

    internal fun detach(document: UserSettingsDocument, tokens: Set<String>): UserSettingsDocument {
        if (tokens.isEmpty()) return document
        val personal = detach(document.personalSettings(), tokens)
        return document.copy(
            configuration = document.configuration.copy(
                assistants = personal.assistants,
                profile = document.configuration.profile.copy(avatar = personal.displaySetting.userAvatar),
            ),
            preferences = document.preferences.copy(scopes = document.preferences.scopes.map { scoped ->
                scoped.copy(assistantUsage = scoped.assistantUsage.map { usage ->
                    usage.copy(
                        avatar = usage.avatar?.let { UsageValue(detachAvatar(it.value, tokens)) },
                        background = usage.background?.let { UsageValue(it.value?.takeUnless(tokens::contains)) },
                        presetMessages = usage.presetMessages?.let { UsageValue(detachMessages(it.value, tokens)) },
                    )
                })
            }),
        )
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
        val presetMessages = detachMessages(assistant.presetMessages, fileUris)
        return if (background == assistant.background && avatar == assistant.avatar && presetMessages == assistant.presetMessages) {
            assistant
        } else {
            assistant.copy(background = background, avatar = avatar, presetMessages = presetMessages)
        }
    }

    private fun detachAvatar(avatar: Avatar, tokens: Set<String>): Avatar =
        if (avatar is Avatar.Image && avatar.url in tokens) Avatar.Dummy else avatar

    /** A tool part is indivisible: do not leave its archive handle or delivery metadata dangling. */
    private fun detachMessages(messages: List<UIMessage>, tokens: Set<String>): List<UIMessage> =
        messages.mapNotNull { message ->
            val parts = message.parts.filterNot { part ->
                listOf(message.copy(parts = listOf(part))).collectFileReferenceTokens().any(tokens::contains)
            }
            if (parts == message.parts) message else parts.takeIf { it.isNotEmpty() }?.let { message.copy(parts = it) }
        }
}
