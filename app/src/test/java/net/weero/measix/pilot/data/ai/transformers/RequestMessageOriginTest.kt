package net.weero.measix.pilot.data.ai.transformers

import net.weero.measix.pilot.service.runtime.resolveAssistantRequest

import net.weero.measix.pilot.test.testPromptInputs

import io.mockk.coEvery
import io.mockk.mockk
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.data.ai.DurableMessageLocator
import net.weero.measix.pilot.data.ai.RequestMessageOrigin
import net.weero.measix.pilot.data.ai.SyntheticMessageKind
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.InjectionPosition
import net.weero.measix.pilot.data.model.PromptInjection
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader
import kotlin.uuid.Uuid

/**
 * 请求级消息来源跟踪：管线为本次请求合成的内容不能被用户的 messageTemplate 二次包裹。
 *
 * 这些测试同时锁定该事实**不进入** [UIMessage]：durable / Provider / UI 共用模型不能携带
 * 只属于单次请求的临时标记。
 */
class RequestMessageOriginTest {

    private val wrapTemplate = "PREFIX[{{ message }}]SUFFIX"

    private fun engine() = PebbleEngine.Builder()
        .autoEscaping(false)
        .build()

    private fun templateTransformer() = TemplateTransformer(engine = engine())

    private fun assistant(
        enableTimeReminder: Boolean = false,
        modeInjectionIds: Set<Uuid> = emptySet(),
        workspaceId: Uuid? = null,
    ) = Assistant(
        enableTimeReminder = enableTimeReminder,
        modeInjectionIds = modeInjectionIds,
        workspaceId = workspaceId,
    )

    private fun context(
        tracker: RequestMessageOriginTracker,
        assistant: Assistant = assistant(),
        promptInputs: net.weero.measix.pilot.service.runtime.FrozenTurnPromptInputs = testPromptInputs(
            messageTemplate = wrapTemplate,
        ),
    ) = mockk<android.content.Context>(relaxed = true).let { androidContext ->
        TransformerContext(
            context = androidContext,
            model = Model(modelId = "test", displayName = "Test"),
            assistant = resolveAssistantRequest(assistant),
            promptInputs = promptInputs,
            requestOrigins = tracker,
            registerUnpublishedResource = { error("no resources expected in this pipeline") },
        )
    }

    private fun text(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun List<UIMessage>.texts(): List<String> = map(::text)

    @Test
    fun `system message built for this request is not wrapped by the template`() = runTest {
        val tracker = RequestMessageOriginTracker()
        val system = UIMessage.system("You are a helpful assistant")
        tracker.markSynthetic(system, SyntheticMessageKind.SYSTEM_PROMPT)
        val messages = listOf(system, UIMessage.user("hello"))

        val result = templateTransformer().transform(context(tracker), messages)

        assertEquals("You are a helpful assistant", text(result[0]))
        assertEquals("PREFIX[hello]SUFFIX", text(result[1]))
    }

    @Test
    fun `time reminder injection is not wrapped by the template`() = runTest {
        val tracker = RequestMessageOriginTracker()
        val ctx = context(
            tracker,
            assistant = assistant(enableTimeReminder = true),
            promptInputs = testPromptInputs(messageTemplate = wrapTemplate, enableTimeReminder = true),
        )
        val messages = listOf(UIMessage.user("hello"))

        val afterReminder = TimeReminderTransformer.transform(ctx, messages)
        val reminder = afterReminder.first { it !== messages.single() }
        assertTrue(tracker.isSynthetic(reminder))

        val result = templateTransformer().transform(ctx, afterReminder)
        val reminderText = result.first { it.id == reminder.id }
        assertEquals(text(reminder), text(reminderText))
        assertFalse(text(reminderText).contains("PREFIX["))
        assertTrue(result.any { text(it) == "PREFIX[hello]SUFFIX" })
    }

    @Test
    fun `prompt injections at every position are not wrapped by the template`() = runTest {
        InjectionPosition.entries.forEach { position ->
            val tracker = RequestMessageOriginTracker()
            val injectionId = Uuid.random()
            val injection = PromptInjection.ModeInjection(
                id = injectionId,
                name = "inj",
                enabled = true,
                priority = 0,
                position = position,
                content = "INJECTED",
                injectDepth = 1,
                role = MessageRole.USER,
            )
            val ctx = context(
                tracker,
                assistant = assistant(modeInjectionIds = setOf(injectionId)),
                promptInputs = testPromptInputs(
                    messageTemplate = wrapTemplate,
                    promptInjections = listOf(
                        net.weero.measix.pilot.service.runtime.ResolvedPromptInjection(
                            id = injection.id,
                            priority = injection.priority,
                            position = injection.position,
                            content = injection.content,
                            injectDepth = injection.injectDepth,
                            role = injection.role,
                        ),
                    ),
                ),
            )
            val messages = listOf(UIMessage.system("system"), UIMessage.user("hello"))

            val afterInjection = PromptInjectionTransformer.transform(ctx, messages)
            val synthesized = afterInjection.filter { ctx.requestOrigins.isSynthetic(it) }
            assertTrue("position $position must mark synthesized messages", synthesized.isNotEmpty())

            val result = templateTransformer().transform(ctx, afterInjection)
            synthesized.forEach { message ->
                val rendered = result.first { it.id == message.id }
                assertFalse(
                    "position $position must not wrap synthesized content: ${text(rendered)}",
                    text(rendered).contains("PREFIX["),
                )
            }
        }
    }

    @Test
    fun `workspace reminder follows the production template ordering`() = runTest {
        val tracker = RequestMessageOriginTracker()
        val workspaceId = Uuid.random()
        val transformer = WorkspaceReminderTransformer()
        val ctx = context(
            tracker,
            assistant = assistant(workspaceId = workspaceId),
            promptInputs = testPromptInputs(
                messageTemplate = wrapTemplate,
                workspaceReminder = "<workspace>frozen</workspace>",
            ),
        )
        val messages = listOf(UIMessage.system("system"), UIMessage.user("hello"))

        // TurnPipelineFactory 的真实顺序是 Template → WorkspaceReminder：durable System 先按
        // 用户模板渲染，随后只追加本次请求的 Workspace 内容，不能反向把整条 durable 消息免模板。
        val afterTemplate = templateTransformer().transform(ctx, messages)
        val templatedSystem = afterTemplate.first { it.role == MessageRole.SYSTEM }
        val afterReminder = transformer.transform(ctx, afterTemplate)
        val systemMessage = afterReminder.first { it.role == MessageRole.SYSTEM }
        assertTrue("rewritten system must be marked synthetic", tracker.isSynthetic(systemMessage))
        assertTrue(text(systemMessage).startsWith(text(templatedSystem) + "\n\n"))
        assertTrue(text(systemMessage).startsWith("PREFIX[system]SUFFIX"))
        assertEquals(1, Regex("PREFIX\\[").findAll(text(systemMessage)).count())
    }

    @Test
    fun `ordinary durable messages still use the template`() = runTest {
        val tracker = RequestMessageOriginTracker()
        val messages = listOf(
            UIMessage.system("durable preset system"),
            UIMessage.user("hello"),
            UIMessage.assistant("hi"),
        )

        val result = templateTransformer().transform(context(tracker), messages)

        assertEquals(
            listOf(
                "PREFIX[durable preset system]SUFFIX",
                "PREFIX[hello]SUFFIX",
                "PREFIX[hi]SUFFIX",
            ),
            result.texts(),
        )
    }

    @Test
    fun `copied messages keep their synthetic identity`() = runTest {
        val tracker = RequestMessageOriginTracker()
        val system = UIMessage.system("system")
        tracker.markSynthetic(system, SyntheticMessageKind.SYSTEM_PROMPT)
        val copied = system.copy(parts = system.parts + UIMessagePart.Text(" more"))

        assertTrue(tracker.isSynthetic(copied))
    }

    @Test
    fun `durable registration is explicit and frozen origins carry both halves`() = runTest {
        val tracker = RequestMessageOriginTracker()
        val user = UIMessage.user("hello")
        val system = UIMessage.system("system")
        val locator = DurableMessageLocator(nodeId = Uuid.random(), messageId = user.id)
        tracker.markDurable(user.id, locator)
        tracker.markSynthetic(system, SyntheticMessageKind.SYSTEM_PROMPT)

        assertFalse(tracker.isSynthetic(user))
        assertEquals(
            mapOf(
                user.id to RequestMessageOrigin.Durable(locator),
                system.id to RequestMessageOrigin.Synthetic(SyntheticMessageKind.SYSTEM_PROMPT),
            ),
            tracker.frozenOrigins(),
        )
    }

    @Test
    fun `trackers do not leak between requests`() = runTest {
        val first = RequestMessageOriginTracker()
        val systemOfFirst = UIMessage.system("system")
        first.markSynthetic(systemOfFirst, SyntheticMessageKind.SYSTEM_PROMPT)

        val second = RequestMessageOriginTracker()
        assertFalse(second.isSynthetic(systemOfFirst))
        assertFalse(first.isSynthetic(UIMessage.system("other")))
    }

    @Test
    fun `UIMessage serialization carries no synthetic marker`() {
        val message = UIMessage.system("system")
        val encoded = Json { encodeDefaults = true }.encodeToString(message)

        assertFalse(encoded.contains("synthetic", ignoreCase = true))
        assertTrue(encoded.contains("\"role\""))
        assertTrue(encoded.contains("\"parts\""))
        assertTrue(encoded.contains("\"id\""))
    }

    private class FixedTemplateLoader(private val template: String) : Loader<String> {
        override fun getReader(cacheKey: String?): Reader? = StringReader(template)
        override fun setCharset(charset: String?) = Unit
        override fun setPrefix(prefix: String?) = Unit
        override fun setSuffix(suffix: String?) = Unit
        override fun resolveRelativePath(relativePath: String?, anchorPath: String?): String? = relativePath
        override fun createCacheKey(templateName: String?): String? = templateName
        override fun resourceExists(templateName: String?): Boolean = templateName != null
    }
}
