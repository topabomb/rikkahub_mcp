package net.weero.measix.pilot.data.model

import kotlinx.serialization.encodeToString
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpecResolution
import net.weero.measix.pilot.data.ai.subassistant.resolveSubAssistantRunSpec
import net.weero.measix.pilot.data.ai.tools.shouldUseExternalWebSearch
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.UsageValue
import net.weero.measix.pilot.data.configuration.resolveUserAssistantUsage
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class AssistantModelTest {
    @Test fun `assistants sharing a model resolve search independently and preserve other tools`() {
        val original = Model(tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext))
        val disabled = Assistant(chatModelId = original.id, builtInSearch = false)
        val inherited = Assistant(chatModelId = original.id)
        val local = disabled.copy(enableWebSearch = true)
        val settings = Settings(providers = listOf(ProviderSetting.Google(models = listOf(original))))
        assertEquals(setOf(BuiltInTools.UrlContext), settings.getChatModel(disabled)!!.tools)
        assertEquals(original.tools, settings.getChatModel(inherited)!!.tools)
        assertFalse(shouldUseExternalWebSearch(disabled, settings.getChatModel(disabled)))
        assertTrue(shouldUseExternalWebSearch(local, settings.getChatModel(local)))
        assertEquals(original, settings.providers.single().models.single())
    }

    @Test fun `child inherits model identity but resolves its own search preference from original definition`() {
        for (defaultSearch in listOf(false, true)) {
            val original = Model(tools = setOf(BuiltInTools.UrlContext) +
                if (defaultSearch) setOf(BuiltInTools.Search) else emptySet())
            val settings = Settings(providers = listOf(ProviderSetting.Google(models = listOf(original))))
            val caller = Assistant(chatModelId = original.id, builtInSearch = !defaultSearch)
            for (targetSearch in listOf(null, false, true)) {
                val target = Assistant(builtInSearch = targetSearch)
                val result = resolveSubAssistantRunSpec(settings::getChatModel, caller, target)
                    as SubAssistantRunSpecResolution.Ready
                assertEquals(original.id, result.spec.model.id)
                assertEquals(target.id, result.spec.assistant.id)
                assertEquals(targetSearch ?: defaultSearch, BuiltInTools.Search in result.spec.model.tools)
                assertTrue(BuiltInTools.UrlContext in result.spec.model.tools)
            }
        }
    }

    @Test fun `enterprise usage distinguishes definition inheritance from model inheritance and explicit choices`() {
        val definition = Assistant(builtInSearch = false)
        val original = Model(tools = setOf(BuiltInTools.Search))
        val choices: List<UsageValue<Boolean?>?> = listOf(null, UsageValue(null), UsageValue(false), UsageValue(true))
        val expected = listOf(false, true, false, true)
        choices.zip(expected).forEach { (choice, enabled) ->
            val usage = AssistantUsagePreferences(definition.id, builtInSearch = choice)
            val restored = JsonInstant.decodeFromString<AssistantUsagePreferences>(JsonInstant.encodeToString(usage))
            assertEquals(usage, restored)
            assertEquals(enabled, BuiltInTools.Search in original.withAssistantSearch(resolveUserAssistantUsage(definition, restored)).tools)
        }
        assertEquals(false, definition.builtInSearch)
    }
}
