package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.InjectionPosition
import net.weero.measix.pilot.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionTransformerTest {

    // region Helper functions
    private fun createAssistant(
        modeInjectionIds: Set<Uuid> = emptySet(),
        allowConversationPromptInjection: Boolean = false
    ) = Assistant(
        modeInjectionIds = modeInjectionIds,
        allowConversationPromptInjection = allowConversationPromptInjection
    )

    private fun createModeInjection(
        id: Uuid = Uuid.random(),
        name: String = "Test Injection",
        enabled: Boolean = true,
        priority: Int = 0,
        position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        content: String = "Injected content",
        injectDepth: Int = 4,
        role: MessageRole = MessageRole.USER
    ) = PromptInjection.ModeInjection(
        id = id,
        name = name,
        enabled = enabled,
        priority = priority,
        position = position,
        content = content,
        injectDepth = injectDepth,
        role = role
    )

    private fun getMessageText(message: UIMessage): String {
        return message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
    }

    private fun createAssistantWithUnexecutedTool(toolCallId: String, toolName: String): UIMessage {
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = "{}",
                    output = emptyList()
                )
            )
        )
    }

    private fun createAssistantWithExecutedTool(toolCallId: String, toolName: String): UIMessage {
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    input = "{}",
                    output = listOf(UIMessagePart.Text("result"))
                )
            )
        )
    }
    // endregion

    // region No injection tests
    @Test
    fun `no injections should return original messages`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(),
            modeInjections = emptyList()
        )

        assertEquals(messages, result)
    }

    @Test
    fun `disabled mode injection should not be applied`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            enabled = false
        )
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection)
        )

        assertEquals(messages, result)
    }

    @Test
    fun `unlinked mode injection should not be applied`() {
        val injection = createModeInjection()
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(), // No linked injections
            modeInjections = listOf(injection)
        )

        assertEquals(messages, result)
    }

    @Test
    fun `conversation mode injection should apply only when assistant allows it`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            content = "Conversation content"
        )
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val disabledResult = transformMessages(
            messages = messages,
            assistant = createAssistant(allowConversationPromptInjection = false),
            modeInjections = listOf(injection),
            conversationModeInjectionIds = setOf(injectionId)
        )
        val enabledResult = transformMessages(
            messages = messages,
            assistant = createAssistant(allowConversationPromptInjection = true),
            modeInjections = listOf(injection),
            conversationModeInjectionIds = setOf(injectionId)
        )

        assertEquals(messages, disabledResult)
        assertTrue(getMessageText(enabledResult.first()).contains("Conversation content"))
    }

    @Test
    fun `conversation mode injection takes precedence when both exist`() {
        val assistantInjectionId = Uuid.random()
        val conversationInjectionId = Uuid.random()
        val assistantInjection = createModeInjection(
            id = assistantInjectionId,
            content = "Assistant content"
        )
        val conversationInjection = createModeInjection(
            id = conversationInjectionId,
            content = "Conversation content"
        )
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(
                modeInjectionIds = setOf(assistantInjectionId),
                allowConversationPromptInjection = true
            ),
            modeInjections = listOf(assistantInjection, conversationInjection),
            conversationModeInjectionIds = setOf(conversationInjectionId)
        )
        val systemText = getMessageText(result.first())

        assertFalse(systemText.contains("Assistant content"))
        assertTrue(systemText.contains("Conversation content"))
    }
    // endregion

    // region AFTER_SYSTEM_PROMPT tests
    @Test
    fun `mode injection with AFTER_SYSTEM_PROMPT should append to system message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "Appended content"
        )

        val messages = listOf(
            UIMessage.system("Original system prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("Original system prompt"))
        assertTrue(systemText.endsWith("Appended content"))
    }
    // endregion

    // region BEFORE_SYSTEM_PROMPT tests
    @Test
    fun `mode injection with BEFORE_SYSTEM_PROMPT should prepend to system message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
            content = "Prepended content"
        )

        val messages = listOf(
            UIMessage.system("Original system prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("Prepended content"))
        assertTrue(systemText.endsWith("Original system prompt"))
    }

    @Test
    fun `BEFORE_SYSTEM_PROMPT injection should create new system message when none exists`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
            content = "New system content"
        )

        val messages = listOf(
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(2, result.size)
        assertEquals("New system content", getMessageText(result[0]))
    }
    // endregion

    // region TOP_OF_CHAT tests
    @Test
    fun `mode injection with TOP_OF_CHAT should insert before first user message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.TOP_OF_CHAT,
            content = "Top content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi there")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(4, result.size)
        assertEquals(MessageRole.USER, result[1].role)
        assertTrue(getMessageText(result[1]).contains("Top content"))
        assertEquals(MessageRole.USER, result[2].role)
    }
    // endregion

    // region BOTTOM_OF_CHAT tests
    @Test
    fun `mode injection with BOTTOM_OF_CHAT should insert before last message`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.BOTTOM_OF_CHAT,
            content = "Bottom content"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello"),
            UIMessage.assistant("How are you?")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(4, result.size)
        assertEquals("How are you?", getMessageText(result[4 - 1]))
    }
    // endregion

    // region AT_DEPTH tests
    @Test
    fun `mode injection with AT_DEPTH should insert at specified depth from end`() {
        val injectionId = Uuid.random()
        val injection = createModeInjection(
            id = injectionId,
            position = InjectionPosition.AT_DEPTH,
            content = "Depth content",
            injectDepth = 2
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Message 1"),
            UIMessage.assistant("Message 2"),
            UIMessage.user("Message 3"),
            UIMessage.assistant("Message 4")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(injectionId)),
            modeInjections = listOf(injection),
        )

        assertEquals(6, result.size)
    }
    // endregion

    // region Priority tests
    @Test
    fun `higher priority injection should appear first in merged content`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()
        val injection1 = createModeInjection(
            id = id1,
            priority = 1,
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "Lower priority"
        )
        val injection2 = createModeInjection(
            id = id2,
            priority = 10,
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            content = "Higher priority"
        )

        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = transformMessages(
            messages = messages,
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2)),
            modeInjections = listOf(injection1, injection2),
        )

        val systemText = getMessageText(result[0])
        assertTrue(systemText.indexOf("Higher priority") < systemText.indexOf("Lower priority"))
    }
    // endregion

    // region collectInjections tests
    @Test
    fun `collectInjections should return empty for no matching conditions`() {
        val result = collectInjections(
            messages = listOf(UIMessage.user("Hello")),
            assistant = createAssistant(),
            modeInjections = listOf(createModeInjection()),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `collectInjections should collect linked and enabled mode injections`() {
        val id1 = Uuid.random()
        val id2 = Uuid.random()

        val injections = listOf(
            createModeInjection(id = id1, enabled = true),
            createModeInjection(id = id2, enabled = false)
        )

        val result = collectInjections(
            messages = listOf(UIMessage.user("Hello")),
            assistant = createAssistant(modeInjectionIds = setOf(id1, id2)),
            modeInjections = injections,
        )

        assertEquals(1, result.size)
        assertEquals(id1, result[0].id)
    }
    // endregion

    // region applyInjections tests
    @Test
    fun `applyInjections with empty injections should return original messages`() {
        val messages = listOf(
            UIMessage.system("System prompt"),
            UIMessage.user("Hello")
        )

        val result = applyInjections(
            messages = messages,
            byPosition = emptyMap()
        )

        assertEquals(messages, result)
    }

    @Test
    fun `applyInjections with BEFORE_SYSTEM_PROMPT should modify system message`() {
        val messages = listOf(
            UIMessage.system("Original"),
            UIMessage.user("Hello")
        )
        val injection = createModeInjection(
            position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
            content = "Before content"
        )

        val result = applyInjections(
            messages = messages,
            byPosition = mapOf(InjectionPosition.BEFORE_SYSTEM_PROMPT to listOf(injection))
        )

        assertEquals(2, result.size)
        val systemText = getMessageText(result[0])
        assertTrue(systemText.startsWith("Before content"))
        assertTrue(systemText.contains("Original"))
    }
    // endregion

    // region findSafeInsertIndex tests
    @Test
    fun `findSafeInsertIndex should not insert between USER and ASSISTANT with tools`() {
        val messages = listOf(
            UIMessage.user("Hello"),
            createAssistantWithUnexecutedTool("call1", "tool1")
        )

        val index = findSafeInsertIndex(messages, 1)
        assertEquals(0, index)
    }

    @Test
    fun `findSafeInsertIndex should return target index when safe`() {
        val messages = listOf(
            UIMessage.user("Hello"),
            UIMessage.assistant("Hi"),
            UIMessage.user("How are you?")
        )

        val index = findSafeInsertIndex(messages, 2)
        assertEquals(2, index)
    }

    @Test
    fun `findSafeInsertIndex should handle ASSISTANT with executed tools`() {
        val messages = listOf(
            UIMessage.user("Hello"),
            createAssistantWithExecutedTool("call1", "tool1")
        )

        val index = findSafeInsertIndex(messages, 1)
        assertEquals(0, index)
    }
    // endregion
}
