package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.core.ReasoningLevel
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantRegex
import net.weero.measix.pilot.data.model.Avatar
import org.junit.Assert.*
import org.junit.Test

class ConfigurationFeedbackTest {
    @Test fun `request changes include model defaults and prompt transport while visuals remain immediate`() {
        val original = Assistant()
        val reference = ConfigurationReference.random()
        listOf(AssistantPreferenceChange.Model(reference), AssistantPreferenceChange.Model(null),
            AssistantPreferenceChange.InheritModel, AssistantPreferenceChange.Reasoning(ReasoningLevel.AUTO),
            AssistantPreferenceChange.EditUsage(original, original.copy(temperature = 0.5f)),
            AssistantPreferenceChange.EditUsage(original, original.copy(messageTemplate = "{{ message }}!")),
            AssistantPreferenceChange.EditUsage(original, original.copy(enableMemory = false)))
            .forEach { assertTrue(it.toString(), it.affectsNextSend()) }
        listOf(AssistantPreferenceChange.Avatar(Avatar.Emoji("A")), AssistantPreferenceChange.Background("image.png"),
            AssistantPreferenceChange.Tags(emptyList(), emptyList()), AssistantPreferenceChange.QuickMessage(reference, true),
            AssistantPreferenceChange.EditUsage(original, original),
            AssistantPreferenceChange.EditUsage(original, original.copy(avatar = Avatar.Emoji("B"),
                background = "new.png", backgroundOpacity = 0.5f, useGradientBackground = true, useAssistantAvatar = true)),
            AssistantPreferenceChange.EditUsage(original, original.copy(regexes = listOf(AssistantRegex(reference, "Visual", findRegex = "a", replaceString = "b", visualOnly = true)))))
            .forEach { assertFalse(it.toString(), it.affectsNextSend()) }
        assertTrue(AssistantPreferenceChange.EditUsage(original, original.copy(regexes = listOf(
            AssistantRegex(reference, "Request", findRegex = "a", replaceString = "b", visualOnly = false)))).affectsNextSend())
    }
}
