package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.StreamingMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.runtime.ToolExecutionUpdatedCheckpoint
import net.weero.measix.pilot.service.runtime.ToolResultCheckpoint
import net.weero.measix.pilot.test.testPromptInputs
import net.weero.measix.pilot.test.turnRunInputsFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未发布资源 lease 的根化与精确回滚：checkpoint 成功才 publish，失败/取消 discard，
 * 且不得用未根化资源覆盖已闭合 usage。
 */
class ToolResourceCommitTest {
    @Test
    fun `tool output resource is discarded when completed checkpoint fails`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns
            mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        val handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val discarded = AtomicBoolean(false)
        val tool = Tool(
            name = "resource_tool",
            description = "Creates an output resource.",
            execute = { error("context required") },
            contextualExecute = {
                registerUnpublishedResource(
                    ToolResourceLease(publish = {}, discard = { discarded.set(true) })
                )
                listOf(UIMessagePart.Text("resource result"))
            },
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = tool.name, input = "{}")),
        )

        val outcome = handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(message),
                assistant = assistant,
                promptInputs = testPromptInputs(),
                tools = listOf(tool),
                maxSteps = 1,
                onCheckpoint = { checkpoint ->
                    if (checkpoint is ToolResultCheckpoint) {
                        error("durable checkpoint failed")
                    }
                },
            )
        )

        assertTrue((outcome as TurnOutcome.Failed).error.message?.contains("durable checkpoint failed") == true)
        assertTrue(discarded.get())
    }

    @Test
    fun `terminal transformer resource is discarded when step checkpoint fails`() = runTest {
        val harness = createProviderHarness()
        val discarded = AtomicBoolean(false)
        val transformer = terminalResourceTransformer(discarded)

        val thrown = runCatching {
            harness.handler.run(
                turnRunInputsFixture(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = harness.settings,
                    model = harness.model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("create image")),
                    outputTransformers = listOf(transformer),
                    assistant = harness.assistant,
                    promptInputs = testPromptInputs(),
                    maxSteps = 1,
                    onResult = { result ->
                        if (result is TurnOutcome.Completed) error("step checkpoint failed")
                    },
                )
            )
        }.exceptionOrNull()

        assertTrue(thrown?.message?.contains("step checkpoint failed") == true)
        assertTrue(discarded.get())
    }

    @Test
    fun `terminal transformer resource is discarded when checkpoint is cancelled`() = runTest {
        val harness = createProviderHarness()
        val discarded = AtomicBoolean(false)
        val transformer = terminalResourceTransformer(discarded)

        val failure = runCatching {
            harness.handler.run(
                turnRunInputsFixture(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = harness.settings,
                    model = harness.model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("create image")),
                    outputTransformers = listOf(transformer),
                    assistant = harness.assistant,
                    promptInputs = testPromptInputs(),
                    maxSteps = 1,
                    onResult = { result ->
                        if (result is TurnOutcome.Completed) {
                            throw kotlinx.coroutines.CancellationException("collector cancelled")
                        }
                    },
                )
            )
        }.exceptionOrNull()

        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(discarded.get())
    }

    @Test
    fun `cancelled output transformer cannot erase closed usage and discards its unpublished lease`() = runTest {
        val harness = createUsageStreamHarness()
        val entered = CompletableDeferred<Unit>()
        val discarded = AtomicBoolean(false)
        val published = AtomicBoolean(false)
        val registered = AtomicBoolean(false)
        val transformer = object : OutputMessageTransformer {
            override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
                if (messages.last().usage?.latestRequestContextTokens != null) {
                    if (registered.compareAndSet(false, true)) {
                        ctx.registerUnpublishedResource(ToolResourceLease(
                            publish = { published.set(true) },
                            discard = { discarded.set(true) },
                        ))
                    }
                    entered.complete(Unit)
                    awaitCancellation()
                }
                return messages
            }
        }
        var observed: UIMessage? = null
        val collector = launch {
            harness.handler.run(turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings, model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")), assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                outputTransformers = listOf(transformer),
                onAssistantObserved = { observed = it },
            ))
        }
        entered.await()
        collector.cancelAndJoin()

        assertTrue(collector.isCancelled)
        assertTrue(discarded.get())
        assertFalse(published.get())
        assertEquals(100L, observed!!.usage!!.latestRequestContextTokens)
        assertEquals(1, observed!!.usage!!.observedProviderRequestCount)
    }

    @Test
    fun `cancellation before output checkpoint never observes the discarded local payload`() = runTest {
        checkOutputResourceHandoff(OutputHandoffInterruption.BEFORE_COMMIT)
    }

    @Test
    fun `cancellation during output checkpoint completes root lease and owner handoff`() = runTest {
        checkOutputResourceHandoff(OutputHandoffInterruption.DURING_COMMIT)
    }

    @Test
    fun `failed output checkpoint discards its lease without observing the local payload`() = runTest {
        checkOutputResourceHandoff(OutputHandoffInterruption.COMMIT_FAILED)
    }

    @Test
    fun `lease publication failure retains the committed local payload in the owner slot`() = runTest {
        checkOutputResourceHandoff(OutputHandoffInterruption.PUBLISH_FAILED)
    }

    private enum class OutputHandoffInterruption { BEFORE_COMMIT, DURING_COMMIT, COMMIT_FAILED, PUBLISH_FAILED }

    private suspend fun kotlinx.coroutines.test.TestScope.checkOutputResourceHandoff(
        interruption: OutputHandoffInterruption,
    ) {
        val rawUrl = "data:image/png;base64,uncommitted"
        val localUrl = "file:///upload/owned-result.png"
        val harness = createProviderHarness(UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Image(rawUrl)),
        ))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val discarded = AtomicBoolean(false)
        val published = AtomicBoolean(false)
        val observations = mutableListOf<UIMessage>()
        var committedAssistant: UIMessage? = null
        var failure: Throwable? = null
        val transformer = object : OutputMessageTransformer, StreamingMessageTransformer {
            override suspend fun onStreamingFinish(
                ctx: TransformerContext,
                message: UIMessage,
                previousProjection: UIMessage?,
            ): UIMessage {
                ctx.registerUnpublishedResource(ToolResourceLease(
                    publish = {
                        if (interruption == OutputHandoffInterruption.PUBLISH_FAILED) error("publish failed")
                        published.set(true)
                    },
                    discard = {
                        check(committedAssistant == null) { "durable root prevents discard" }
                        discarded.set(true)
                    },
                ))
                if (interruption == OutputHandoffInterruption.BEFORE_COMMIT) {
                    // Model a completed owned IO operation returning just after cancellation.
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                return message.copy(parts = listOf(UIMessagePart.Image(localUrl)))
            }
        }
        val collector = launch {
            try {
                val outcome = harness.handler.run(turnRunInputsFixture(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = harness.settings, model = harness.model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("image")), assistant = harness.assistant,
                    promptInputs = testPromptInputs(),
                    outputTransformers = listOf(transformer),
                    onAssistantObserved = { observations += it },
                    onResult = { result ->
                        // 无 Tool Final step 的唯一 durable rooting 是终态 FinalizeTurn（经 onResult）。
                        if (result is TurnOutcome.Completed) {
                            if (interruption == OutputHandoffInterruption.COMMIT_FAILED) error("commit failed")
                            committedAssistant = result.assistantMessage
                            if (interruption == OutputHandoffInterruption.DURING_COMMIT) {
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                    },
                ))
                if (outcome is TurnOutcome.Failed) failure = outcome.error
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            }
        }
        if (interruption in setOf(OutputHandoffInterruption.BEFORE_COMMIT, OutputHandoffInterruption.DURING_COMMIT)) {
            entered.await()
            collector.cancel()
            release.complete(Unit)
        }
        collector.join()

        fun UIMessage.hasLocalImage() = parts.filterIsInstance<UIMessagePart.Image>()
            .any { it.url == localUrl }
        if (interruption in setOf(OutputHandoffInterruption.DURING_COMMIT, OutputHandoffInterruption.PUBLISH_FAILED)) {
            assertTrue(committedAssistant!!.hasLocalImage())
            assertFalse(discarded.get())
            assertTrue(observations.last().hasLocalImage())
            if (interruption == OutputHandoffInterruption.DURING_COMMIT) {
                assertTrue(collector.isCancelled)
                assertTrue(published.get())
            } else {
                assertFalse(published.get())
                assertTrue(failure?.message?.contains("publish failed") == true)
            }
        } else {
            assertTrue(discarded.get())
            assertFalse(published.get())
            assertTrue(observations.none { it.hasLocalImage() })
            assertTrue(committedAssistant == null)
            if (interruption == OutputHandoffInterruption.COMMIT_FAILED) {
                assertTrue(failure?.message?.contains("commit failed") == true)
            } else {
                assertTrue(collector.isCancelled)
            }
        }
    }

    @Test
    fun `deferred tool metadata never escapes a failed result checkpoint`() = runTest {
        checkToolMetadataHandoff(ToolMetadataInterruption.COMMIT_FAILED)
    }

    @Test
    fun `real cancellation of deferred tool metadata discards its unobserved resource`() = runTest {
        checkToolMetadataHandoff(ToolMetadataInterruption.CANCELLED)
    }

    @Test
    fun `progress checkpoints leave resources unpublished until the result is rooted`() = runTest {
        checkToolMetadataHandoff(ToolMetadataInterruption.NONE)
    }

    @Test
    fun `tool result assembly failure keeps its unrooted lease available for compensation`() = runTest {
        checkToolMetadataHandoff(ToolMetadataInterruption.ASSEMBLY_FAILED)
    }

    private enum class ToolMetadataInterruption { NONE, COMMIT_FAILED, CANCELLED, ASSEMBLY_FAILED }

    private suspend fun kotlinx.coroutines.test.TestScope.checkToolMetadataHandoff(
        interruption: ToolMetadataInterruption,
    ) {
        val harness = createProviderHarness()
        val entered = CompletableDeferred<Unit>()
        val observations = mutableListOf<UIMessage>()
        var committedTool: UIMessagePart.Tool? = null
        var published = false
        var discarded = false
        var failure: Throwable? = null
        fun UIMessagePart.Tool.hasArtifact() = metadata?.containsKey("artifact") == true
        val tool = Tool(
            name = "metadata_tool", description = "test", execute = { emptyList() },
            contextualExecute = {
                registerUnpublishedResource(ToolResourceLease(
                    publish = {
                        check(committedTool?.hasArtifact() == true) { "resource has no durable root" }
                        published = true
                    },
                    discard = {
                        check(committedTool?.hasArtifact() != true) { "durable root prevents discard" }
                        discarded = true
                    },
                ))
                reportMetadata(buildJsonObject { put("phase", "setting_background") }, ToolMetadataDelivery.CHECKPOINT)
                assertFalse(published)
                if (interruption == ToolMetadataInterruption.ASSEMBLY_FAILED) error("output assembly failed")
                reportMetadata(buildJsonObject {
                    put("phase", "completed")
                    put("artifact", "file:///upload/result.png")
                }, ToolMetadataDelivery.DEFERRED)
                assertTrue(observations.none { it.getTools().single().hasArtifact() })
                if (interruption == ToolMetadataInterruption.CANCELLED) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                listOf(UIMessagePart.Text("done"))
            },
        )
        val collector = launch {
            try {
                val outcome = harness.handler.run(turnRunInputsFixture(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = harness.settings, model = harness.model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                        UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = tool.name, input = "{}"),
                    ))),
                    assistant = harness.assistant, tools = listOf(tool), maxSteps = 1,
                    promptInputs = testPromptInputs(),
                    onAssistantObserved = { observations += it },
                    onCheckpoint = { checkpoint ->
                        if (checkpoint is ToolExecutionUpdatedCheckpoint) {
                            assertFalse(published)
                            assertFalse(checkpoint.assistantMessage.getTools().single().hasArtifact())
                        }
                        if (checkpoint is ToolResultCheckpoint) {
                            if (interruption == ToolMetadataInterruption.COMMIT_FAILED) error("result commit failed")
                            committedTool = checkpoint.assistantMessage.getTools().single()
                        }
                    },
                ))
                if (outcome is TurnOutcome.Failed) failure = outcome.error
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            }
        }
        if (interruption == ToolMetadataInterruption.CANCELLED) {
            entered.await()
            collector.cancelAndJoin()
        } else {
            collector.join()
        }
        if (interruption == ToolMetadataInterruption.NONE) {
            assertTrue(failure == null)
            assertTrue(published)
            assertFalse(discarded)
            assertTrue(observations.last().getTools().single().hasArtifact())
        } else {
            assertFalse(published)
            assertTrue(discarded)
            assertTrue(observations.none { it.getTools().single().hasArtifact() })
            when (interruption) {
                ToolMetadataInterruption.CANCELLED -> assertTrue(collector.isCancelled)
                ToolMetadataInterruption.COMMIT_FAILED -> assertTrue(failure?.message?.contains("result commit failed") == true)
                ToolMetadataInterruption.ASSEMBLY_FAILED -> assertTrue(failure?.message?.contains("resource has no durable root") == true)
                ToolMetadataInterruption.NONE -> error("unreachable")
            }
        }
    }
}
