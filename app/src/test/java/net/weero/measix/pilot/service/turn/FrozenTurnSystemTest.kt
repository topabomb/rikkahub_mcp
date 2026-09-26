package net.weero.measix.pilot.service.turn

import me.rerere.ai.core.FrozenToolDefinition
import me.rerere.ai.core.MessageRole
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.ai.transformers.renderPromptPlaceholders
import net.weero.measix.pilot.data.enterprise.EnterpriseStarter
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterOpeningSnapshot
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.test.testPromptInputs
import org.junit.Assert.*
import org.junit.Test

class FrozenTurnSystemTest {
    private val assistant = resolveTurnAssistantSnapshot(Assistant(
        id = ConfigurationReference.parse("managed~dep_example~asd_example"), systemPrompt = "assistant {{user}}"))
    private val opening = ConversationOpening(assistant = assistant.id as ConfigurationReference.Enterprise,
        releaseId = "release", generation = 1, snapshotHash = "hash",
        definition = EnterpriseStarter("starter", "asd_example", "Title", "Prompt", openingSnapshot = EnterpriseStarterOpeningSnapshot(1, "opening {{user}}", emptyList())))
    private val prompt = testPromptInputs().copy(placeholderValues = mapOf("user" to "{{model_name}}", "model_name" to "M", "unused" to "secret-free-unused"))

    @Test fun `domain selection preserves empty opening and respects assistant identity`() {
        fun domain(system: FrozenTurnSystem) = system.contributions.single { it.kind == SystemContextKind.DOMAIN }
        assertEquals("conversation_override", domain(freezeTurnSystem(assistant, prompt.copy(conversationSystemPrompt = "override"), emptyList(), opening)).name)
        assertEquals("enterprise_opening", domain(freezeTurnSystem(assistant, prompt, emptyList(), opening)).name)
        val empty = opening.copy(definition = opening.definition.copy(openingSnapshot = opening.definition.openingSnapshot!!.copy(systemPrompt = "")))
        assertEquals("", domain(freezeTurnSystem(assistant, prompt, emptyList(), empty)).template)
        val other = assistant.copy(id = ConfigurationReference.random())
        assertEquals("assistant", domain(freezeTurnSystem(other, prompt, emptyList(), opening)).name)
    }

    @Test fun `templates render once while tool and workspace braces stay literal and sources keep only used variables`() {
        val tools = listOf(FrozenToolDefinition("tool", "", null, "tool literal {{user}}"))
        val system = freezeTurnSystem(assistant, prompt.copy(workspaceReminder = "workspace literal {{model_name}}"), tools, opening)
        assertTrue(system.text.contains("opening {{model_name}}"))
        assertTrue(system.text.contains("tool literal {{user}}"))
        assertTrue(system.text.contains("workspace literal {{model_name}}"))
        assertEquals(mapOf("user" to "{{model_name}}"), system.contributions.single { it.kind == SystemContextKind.DOMAIN }.variables)
        assertTrue(system.contributions.filter { it.kind != SystemContextKind.DOMAIN }.all { it.variables.isEmpty() })
        assertEquals(1, Regex(Regex.escape(ConversationDisclosureSnapshotService.MODEL_RULES)).findAll(system.text).count())
        assertEquals(1, Regex(Regex.escape(APPLICATION_CONTEXT_RULES)).findAll(system.text).count())
        assertEquals(listOf("application_context", "disclosure_interpretation"),
            system.contributions.filter { it.kind == SystemContextKind.APPLICATION }.map { it.name })
        assertTrue(freezeTurnSystem(assistant, prompt, emptyList()).text.contains(APPLICATION_CONTEXT_RULES))
        assertTrue(freezeTurnSystem(assistant, prompt, emptyList()).text.contains(ConversationDisclosureSnapshotService.MODEL_RULES))
    }

    @Test fun `system rules freeze priority order full definitions and their already rendered values`() {
        fun rule(name: String, priority: Int, position: InjectionPosition) = ResolvedPromptInjection(ConfigurationReference.random(), priority,
            position, renderPromptPlaceholders("$name {{user}}", prompt.placeholderValues), 3, MessageRole.ASSISTANT, name, "$name {{user}}")
        val beforeLow = rule("low", 1, InjectionPosition.BEFORE_SYSTEM_PROMPT)
        val beforeHigh = rule("high", 9, InjectionPosition.BEFORE_SYSTEM_PROMPT)
        val after = rule("after", 100, InjectionPosition.AFTER_SYSTEM_PROMPT)
        val history = rule("history", 1000, InjectionPosition.TOP_OF_CHAT)
        val system = freezeTurnSystem(assistant, prompt.copy(promptInjections = listOf(beforeLow, history, after, beforeHigh)), emptyList())
        assertTrue(system.text.startsWith("high {{model_name}}\n\nlow {{model_name}}"))
        assertTrue(system.text.endsWith("after {{model_name}}"))
        assertFalse(system.text.contains(history.content))
        val sources = system.contributions.filter { it.kind == SystemContextKind.PROMPT_RULE }
        assertEquals(listOf(beforeHigh.id, beforeLow.id, after.id), sources.map { it.reference })
        assertEquals(listOf(9, 1, 100), sources.map { it.promptRule!!.priority })
        assertTrue(sources.all { it.promptRule!!.role == MessageRole.ASSISTANT && it.promptRule.injectDepth == 3 })
    }
}
