package net.weero.measix.pilot.service.turn

import me.rerere.common.configuration.ConfigurationReference


import me.rerere.ai.provider.Model
import net.weero.measix.pilot.data.ai.transformers.DefaultPlaceholderProvider
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * START 冻结契约：`freezeTurnPromptSnapshot` 是占位符值、模式注入资格与
 * 会话 System 覆盖的唯一求值点。芯片可插入的每个占位符都必须有冻结值（契约测试锁定两侧
 * 不漂移）；注入资格过滤从 Transformer 挪到这里后，本测试是它的 JVM 归属。
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class TurnContextFactoryTest {
    private val instant: Instant = Instant.parse("2026-09-03T10:15:30Z")
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `skill metadata changes are captured only by a new turn materialization`() {
        val metadata = mutableListOf(net.weero.measix.pilot.data.files.SkillMetadata("a&b", "before <change>"))
        fun plan() = TurnLaunchPlan(
            realmAccess = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
            assistant = Assistant(),
            model = io.mockk.mockk(),
            mediaCapabilities = me.rerere.ai.provider.RequestMediaCapabilities.NONE,
            promptInputs = net.weero.measix.pilot.test.testPromptInputs(),
            tools = net.weero.measix.pilot.data.ai.tools.createSkillTools(setOf("a&b"), metadata, io.mockk.mockk()),
            disclosure = io.mockk.mockk { io.mockk.every { withInstalledTools(any()) } returns this },
        )
        val factory = TurnContextFactory(io.mockk.mockk())
        val first = factory.materialize(plan())
        val original = first.system
        metadata[0] = metadata.single().copy(description = "after <change>")
        val next = factory.materialize(plan())
        assertTrue(first.system.text.contains("before &lt;change&gt;"))
        assertTrue(!first.system.text.contains("after &lt;change&gt;"))
        assertTrue(next.system.text.contains("after &lt;change&gt;"))
        assertEquals(original, first.system)
        assertTrue(first.system.text.contains("<name>a&amp;b</name>"))
    }

    @Test
    fun `materialize freezes final system and removes system producers from step inputs`() {
        val assistant = Assistant(systemPrompt = "domain {{user}}")
        val systemRule = net.weero.measix.pilot.service.turn.ResolvedPromptInjection(
            ConfigurationReference.random(), 0,
            net.weero.measix.pilot.data.model.InjectionPosition.AFTER_SYSTEM_PROMPT,
            "rule captured", 1, me.rerere.ai.core.MessageRole.USER,
            "rule", "rule {{user}}",
        )
        val historyRule = systemRule.copy(id = ConfigurationReference.random(),
            position = net.weero.measix.pilot.data.model.InjectionPosition.TOP_OF_CHAT)
        val inputs = net.weero.measix.pilot.test.testPromptInputs().copy(
            placeholderValues = mapOf("user" to "captured"),
            workspaceReminder = "workspace {{user}}",
            promptInjections = listOf(systemRule, historyRule),
        )
        var schemaReads = 0
        val tool = me.rerere.ai.core.Tool("tool", "description", parameters = { schemaReads++; null },
            systemPromptContribution = "tool {{user}}", execute = { emptyList() })
        val plan = TurnLaunchPlan(
            realmAccess = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
            assistant = assistant,
            model = io.mockk.mockk(),
            mediaCapabilities = me.rerere.ai.provider.RequestMediaCapabilities.NONE,
            promptInputs = inputs,
            tools = listOf(tool),
            disclosure = io.mockk.mockk {
                io.mockk.every { withInstalledTools(any()) } returns this
            },
        )
        val context = TurnContextFactory(io.mockk.mockk()).materialize(plan)
        assertEquals(1, schemaReads)
        assertEquals(listOf(historyRule), context.promptInputs.promptInjections)
        assertEquals(null, context.promptInputs.workspaceReminder)
        assertTrue(context.system.text.contains("domain captured"))
        assertTrue(context.system.text.contains("workspace {{user}}"))
        assertTrue(context.system.text.contains("tool {{user}}"))
        assertTrue(context.system.text.endsWith("rule captured"))
        val payload = net.weero.measix.pilot.data.model.ConversationContextPayload(
            source = net.weero.measix.pilot.data.model.ConversationContextSource.System(context.system.contributions),
            body = net.weero.measix.pilot.data.model.ConversationContextBody.Inline(context.system.text),
        )
        assertEquals(payload, net.weero.measix.pilot.data.model.ConversationContextCodec.decode(
            net.weero.measix.pilot.data.model.ConversationContextCodec.encode(payload)))
    }

    private fun freeze(
        settings: Settings = Settings(),
        assistant: Assistant = Assistant(name = "Tester", description = "desc"),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<ConfigurationReference> = emptySet(),
    ): TurnPromptSnapshot = freezeTurnPromptSnapshot(
        settings = settings,
        assistant = assistant,
        model = Model(modelId = "m-1", displayName = "Model One"),
        conversationSystemPrompt = conversationSystemPrompt,
        conversationModeInjectionIds = conversationModeInjectionIds,
        workspaceReminder = null,
        instant = instant,
        locale = Locale.US,
        zoneId = zoneId,
    )

    @Test
    fun `every UI placeholder chip has a frozen request value`() {
        val frozen = freeze()
        val chipKeys = DefaultPlaceholderProvider.placeholders.keys
        assertTrue(chipKeys.isNotEmpty())
        assertEquals(
            "UI chips and frozen placeholder values must not drift apart",
            chipKeys,
            chipKeys.intersect(frozen.placeholderValues.keys),
        )
    }

    @Test
    fun `frozen placeholder values resolve fallbacks like the prompt page promises`() {
        val frozen = freeze(
            assistant = Assistant(name = "", description = "Android / Kotlin specialist"),
            settings = Settings().copy(
                displaySetting = Settings().displaySetting.copy(userNickname = ""),
            ),
        )
        val values = frozen.placeholderValues
        assertEquals("assistant", values.getValue("char"))
        assertEquals("Android / Kotlin specialist", values.getValue("description"))
        assertEquals("user", values.getValue("user"))
        assertEquals("user", values.getValue("nickname"))
    }

    @Test
    fun `frozen values ignore wall clock and locale drift inside the turn`() {
        val first = freeze()
        val second = freezeTurnPromptSnapshot(
            settings = Settings(),
            assistant = Assistant(name = "Tester", description = "desc"),
            model = Model(modelId = "m-1", displayName = "Model One"),
            conversationSystemPrompt = null,
            conversationModeInjectionIds = emptySet(),
            workspaceReminder = null,
            instant = Instant.parse("2027-01-01T00:00:00Z"),
            locale = Locale.CHINA,
            zoneId = ZoneId.of("UTC"),
        )
        assertTrue(first.placeholderValues.keys == second.placeholderValues.keys)
        // 不同冻结输入必须得到不同 bytes：这里只锁定 cur_date/locale/timezone 是冻结求值产物。
        assertTrue(first.placeholderValues["cur_date"] != second.placeholderValues["cur_date"])
        assertTrue(first.placeholderValues["locale"] != second.placeholderValues["locale"])
    }

    @Test
    fun `only enabled injections linked to the effective mode set are frozen`() {
        val linked = ConfigurationReference.random()
        val unlinked = ConfigurationReference.random()
        val disabled = ConfigurationReference.random()
        val assistant = Assistant(
            modeInjectionIds = setOf(linked, disabled),
            allowConversationPromptInjection = false,
        )
        val settings = Settings().copy(
            modeInjections = listOf(
                PromptInjection.ModeInjection(
                    id = linked,
                    content = "linked",
                    priority = 1,
                ),
                PromptInjection.ModeInjection(id = unlinked, content = "unlinked"),
                PromptInjection.ModeInjection(id = disabled, content = "disabled", enabled = false),
            ),
        )
        val frozen = freeze(settings = settings, assistant = assistant)
        assertEquals(listOf("linked"), frozen.promptInjections.map { it.content })
    }

    @Test
    fun `conversation mode binding wins when the assistant allows it`() {
        val assistantInjectionId = ConfigurationReference.random()
        val conversationInjectionId = ConfigurationReference.random()
        val assistant = Assistant(
            modeInjectionIds = setOf(assistantInjectionId),
            allowConversationPromptInjection = true,
        )
        val settings = Settings().copy(
            modeInjections = listOf(
                PromptInjection.ModeInjection(id = assistantInjectionId, content = "assistant"),
                PromptInjection.ModeInjection(id = conversationInjectionId, content = "conversation"),
            ),
        )
        val frozen = freeze(
            settings = settings,
            assistant = assistant,
            conversationModeInjectionIds = setOf(conversationInjectionId),
        )
        assertEquals(listOf("conversation"), frozen.promptInjections.map { it.content })
    }

    @Test
    fun `conversation system prompt applies only when allowed and non blank`() {
        val allowed = freeze(
            assistant = Assistant(name = "Tester", allowConversationSystemPrompt = true),
            conversationSystemPrompt = "custom",
        )
        assertEquals("custom", allowed.conversationSystemPrompt)

        val blank = freeze(
            assistant = Assistant(name = "Tester", allowConversationSystemPrompt = true),
            conversationSystemPrompt = "   ",
        )
        assertEquals(null, blank.conversationSystemPrompt)

        val disallowed = freeze(
            assistant = Assistant(name = "Tester", allowConversationSystemPrompt = false),
            conversationSystemPrompt = "custom",
        )
        assertEquals(null, disallowed.conversationSystemPrompt)
    }
}
