package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.InjectionPosition
import net.weero.measix.pilot.service.runtime.ResolvedPromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionTransformerTest {
    private fun injection(
        content: String,
        position: InjectionPosition,
        priority: Int = 0,
        depth: Int = 1,
        role: MessageRole = MessageRole.USER,
    ) = ResolvedPromptInjection(
        id = Uuid.random(),
        priority = priority,
        position = position,
        content = content,
        injectDepth = depth,
        role = role,
    )

    @Test
    fun `empty frozen injections preserve message identity`() {
        val messages = listOf(UIMessage.user("question"))
        assertSame(messages, transformMessages(messages, emptyList()))
    }

    @Test
    fun `system injections are ordered by priority around original system`() {
        val result = transformMessages(
            messages = listOf(UIMessage.system("SYSTEM"), UIMessage.user("question")),
            injections = listOf(
                injection("after", InjectionPosition.AFTER_SYSTEM_PROMPT),
                injection("low", InjectionPosition.BEFORE_SYSTEM_PROMPT, priority = 1),
                injection("high", InjectionPosition.BEFORE_SYSTEM_PROMPT, priority = 10),
            ),
        )
        assertEquals("high\nlow\nSYSTEM\nafter", result.first().toText())
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
        assertEquals(listOf("system", "top", "u1", "a1", "depth", "bottom", "u2"), result.map { it.toText() })
        assertEquals(MessageRole.ASSISTANT, result.first { it.toText() == "depth" }.role)
    }

    @Test
    fun `safe insertion never splits user from following assistant tool call`() {
        val messages = listOf(
            UIMessage.user("question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Tool("call", "lookup", "{}")),
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
}
