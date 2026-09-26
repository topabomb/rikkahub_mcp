package net.weero.measix.pilot.data.ai.transformers

import me.rerere.common.configuration.ConfigurationReference

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.InjectionPosition
import net.weero.measix.pilot.service.turn.ResolvedPromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionTransformerTest {
    @Test fun `an empty active step does not move depth one past the user`() {
        val user = UIMessage.user("question")
        val active = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(net.weero.measix.pilot.service.runtime.TurnTransition.openStep(0)))
        val result = transformMessages(listOf(user, active), listOf(injection("rule", InjectionPosition.AT_DEPTH)))
        assertEquals(listOf("rule", "question", ""), result.map { it.toText() })
    }
    private fun injection(
        content: String,
        position: InjectionPosition,
        priority: Int = 0,
        depth: Int = 1,
        role: MessageRole = MessageRole.USER,
    ) = ResolvedPromptInjection(
        id = ConfigurationReference.random(),
        priority = priority,
        position = position,
        content = content,
        injectDepth = depth,
        role = role,
    )

    @Test
    fun `empty frozen injections preserve content and order`() {
        val messages = listOf(UIMessage.user("question"))
        assertEquals(messages, transformMessages(messages, emptyList()))
    }

    @Test
    fun `top bottom and depth injections keep their frozen roles`() {
        val messages = listOf(
            UIMessage.system("system"),
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
            UIMessage.user("u2"),
        )
        val result = transformMessages(
            messages = messages,
            injections = listOf(
                injection("top", InjectionPosition.TOP_OF_CHAT),
                injection("depth", InjectionPosition.AT_DEPTH, depth = 2, role = MessageRole.ASSISTANT),
                injection("bottom", InjectionPosition.BOTTOM_OF_CHAT),
            ),
        )
        assertEquals(listOf("system", "top", "u1", "depth", "a1", "bottom", "u2"), result.map { it.toText() })
        assertEquals(MessageRole.ASSISTANT, result.first { it.toText() == "depth" }.role)
    }

    @Test
    fun `safe insertion never splits user from following assistant tool call`() {
        val messages = listOf(
            UIMessage.user("question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "lookup", input = "{}")),
            ),
            UIMessage.user("next"),
        )
        val result = transformMessages(
            messages = messages,
            injections = listOf(injection("injected", InjectionPosition.AT_DEPTH, depth = 2)),
        )
        assertEquals("injected", result.first().toText())
        assertTrue(result[1] === messages[0] && result[2] === messages[1])
    }
    @Test
    fun `mixed roles retain priority order and equal priorities retain directory order`() {
        val result = transformMessages(listOf(UIMessage.user("question")), listOf(
            injection("user-high", InjectionPosition.TOP_OF_CHAT, 3),
            injection("assistant", InjectionPosition.TOP_OF_CHAT, 2, role = MessageRole.ASSISTANT),
            injection("user-low", InjectionPosition.TOP_OF_CHAT, 1),
            injection("user-tie", InjectionPosition.TOP_OF_CHAT, 1),
        ))
        assertEquals(listOf("user-high", "assistant", "user-low\nuser-tie", "question"), result.map { it.toText() })
    }
    @Test
    fun `depth ignores projections and normalizes nonpositive values without changing history`() {
        val reminder = UIMessage.user("reminder")
        val history = listOf(UIMessage.system("s"), UIMessage.user("u"), reminder, UIMessage.assistant("a"))
        for (depth in listOf(-5, 0, 1)) {
            val result = transformMessages(history, listOf(injection("rule", InjectionPosition.AT_DEPTH, depth = depth)),
                isHistory = { it.id != reminder.id })
            assertEquals(listOf("s", "u", "rule", "reminder", "a"), result.map { it.toText() })
        }
        val result = transformMessages(history, listOf(injection("rule", InjectionPosition.AT_DEPTH, depth = 99)),
            isHistory = { it.id != reminder.id })
        assertEquals(listOf("s", "rule", "u", "reminder", "a"), result.map { it.toText() })
    }

    @Test
    fun `merged rules retain each source and global priority at the same resolved boundary`() {
        val low = injection("low", InjectionPosition.TOP_OF_CHAT, priority = 1)
        val high = injection("high", InjectionPosition.AT_DEPTH, priority = 3, depth = 1)
        val sources = mutableListOf<ResolvedPromptInjection>()
        val result = transformMessages(listOf(UIMessage.user("question")), listOf(low, high),
            onInjection = { message, rules ->
                assertEquals(rules.map { it.content }, message.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
                sources += rules
            })
        assertEquals(listOf(high, low), sources)
        assertEquals(listOf("high\nlow", "question"), result.map { it.toText() })
    }
}
