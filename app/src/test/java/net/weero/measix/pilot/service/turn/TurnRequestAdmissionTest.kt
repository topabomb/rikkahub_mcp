package net.weero.measix.pilot.service.turn

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.RequestContextPlanner
import net.weero.measix.pilot.data.ai.request.resolveUsesAt
import net.weero.measix.pilot.data.ai.transformers.RequestMessageOriginTracker
import net.weero.measix.pilot.data.ai.transformers.RequestPartSource
import net.weero.measix.pilot.data.ai.transformers.TimeReminderTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.test.testTurnContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class TurnRequestAdmissionTest {
    @Test fun `predecessor deletion recalculates gap but retains the message first admitted zone`() = runTest {
        val predecessor = UIMessage.user("earlier").copy(createdAt = LocalDateTime.parse("2026-09-27T10:00:00"))
        val fixture = Fixture(predecessor = predecessor)
        fixture.context = fixture.context.copy(promptInputs = fixture.context.promptInputs.copy(
            enableTimeReminder = true, zoneId = "Asia/Shanghai"))
        fixture.admit()
        val original = fixture.snapshot.modelContextEntries.single {
            (it.payload.source as? ConversationContextSource.MessageTime)?.message?.messageId == fixture.user.id
        }
        assertEquals("<time_reminder>Message time: 2026-09-27T12:00:00+08:00; gap: 2 h</time_reminder>",
            (original.payload.body as ConversationContextBody.Inline).text)

        val before = fixture.snapshot
        fixture.snapshot = ConversationContextTransition.prune(
            before.copy(nodes = before.nodes.filterNot { it.currentMessage.id == predecessor.id }), before)
        fixture.startNextTurn()
        fixture.context = fixture.context.copy(promptInputs = fixture.context.promptInputs.copy(zoneId = "America/New_York"))
        fixture.admit()
        val updated = fixture.snapshot.modelContextEntries.last {
            (it.payload.source as? ConversationContextSource.MessageTime)?.message?.messageId == fixture.user.id
        }
        val time = updated.payload.source as ConversationContextSource.MessageTime
        assertNull(time.previous)
        assertEquals("Asia/Shanghai", time.zoneId)
        assertEquals("<time_reminder>Message time: 2026-09-27T12:00:00+08:00</time_reminder>",
            (updated.payload.body as ConversationContextBody.Inline).text)
        assertTrue(fixture.output.any { it.toText() == (updated.payload.body as ConversationContextBody.Inline).text })
        assertEquals(original, fixture.snapshot.modelContextEntries.single { it.id == original.id })
        val committed = fixture.snapshot
        fixture.admit()
        assertEquals(committed, fixture.snapshot)
    }

    @Test fun `attachment source and actual part index survive a disclosure prefix`() = runTest {
        val fixture = Fixture()
        fixture.admit(includeDocument = true)
        val snapshot = fixture.snapshot
        val entry = snapshot.modelContextEntries.single { it.payload.source is ConversationContextSource.Attachment }
        assertEquals(1, (entry.payload.source as ConversationContextSource.Attachment).partIndex)
        val use = snapshot.contextAdmissions.single().uses.single { it.entryId == entry.id }
        assertEquals(2, (use.placement as ContextPlacement.MessagePart).partIndex)
        assertEquals("derived document", fixture.output.single { it.id == fixture.user.id }.parts[2].let { (it as UIMessagePart.Text).text })
    }

    @Test fun `closed contribution stays absent through a zero change request and old detail survives`() = runTest {
        val fixture = Fixture()
        fixture.admit(includeDocument = true)
        val entry = fixture.snapshot.modelContextEntries.single { it.payload.source is ConversationContextSource.Attachment }
        val first = fixture.snapshot.contextAdmissions.single()
        fixture.nextStep()
        fixture.admit(includeDocument = false)
        assertEquals(ContextPlacement.Omitted,
            fixture.snapshot.contextAdmissions.last().uses.single { it.entryId == entry.id }.placement)
        fixture.nextStep()
        fixture.admit(includeDocument = false)
        assertTrue(fixture.snapshot.contextAdmissions.last().uses.isEmpty())
        val steps = fixture.snapshot.nodes.last().currentMessage.parts.filterIsInstance<UIMessagePart.Step>()
        assertTrue(resolveUsesAt(fixture.snapshot.contextAdmissions, first.owner, first.stepId, steps).uses.any { it.entryId == entry.id })
        assertTrue(resolveUsesAt(fixture.snapshot.contextAdmissions, first.owner, steps.last().stepId, steps).uses.none { it.entryId == entry.id })
    }

    @Test fun `a sealed empty difference is replayed without resampling current facts`() = runTest {
        val fixture = Fixture()
        fixture.admit()
        val initial = fixture.snapshot
        val output = fixture.output.map { it.toText() }
        fixture.admit()
        assertEquals(1, fixture.samples)
        assertEquals(initial, fixture.snapshot)
        assertEquals(output, fixture.output.map { it.toText() })
    }

    @Test fun `assembly rejection leaves no seal or application entries`() {
        val fixture = Fixture()
        val original = fixture.snapshot
        val failure = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.test.runTest { fixture.admit(failAssembly = true) }
        }
        assertEquals("request_rejected", failure.message)
        assertEquals(original, fixture.snapshot)
    }

    @Test fun `sealed request rejects removal of an admitted document`() = runTest {
        val fixture = Fixture()
        fixture.admit(includeDocument = true)
        val sealed = fixture.snapshot
        val failure = try {
            fixture.admit(includeDocument = false)
            null
        } catch (error: IllegalStateException) { error }
        assertEquals("admitted_request_placements_changed", failure?.message)
        assertEquals(sealed, fixture.snapshot)
        assertEquals(1, fixture.samples)
    }

    @Test fun `only producer identity grants a built in change contract`() = runTest {
        val fake = Tool("memory_tool", "remote tool", execute = { emptyList() })
        val untrusted = Fixture(listOf(fake))
        untrusted.admit()
        assertTrue(untrusted.snapshot.contextAdmissions.single().selection!!.builtinTools.isEmpty())
        val builtin = Fixture(listOf(fake.copy(executionIdentity = DisclosureBuiltinTool.MEMORY.executionIdentity)))
        builtin.admit()
        assertEquals(mapOf("memory_tool" to DisclosureBuiltinTool.MEMORY),
            builtin.snapshot.contextAdmissions.single().selection!!.builtinTools)
    }

    @Test fun `tool result attachment records original and projected nested locations`() = runTest {
        val fixture = Fixture(nestedAttachment = true)
        fixture.admit()
        val entry = fixture.snapshot.modelContextEntries.single { it.payload.source is ConversationContextSource.Attachment }
        val source = entry.payload.source as ConversationContextSource.Attachment
        assertEquals(1, source.partIndex)
        assertEquals(listOf(0), source.toolOutputPath)
        val placement = fixture.snapshot.contextAdmissions.single().uses.single { it.entryId == entry.id }.placement as ContextPlacement.MessagePart
        assertEquals(1, placement.partIndex)
        assertEquals(listOf(0), placement.toolOutputPath)
        assertEquals(AttachmentContextInput.REFERENCE_ONLY, source.input)
        fixture.admit()
        assertEquals(1, fixture.snapshot.contextAdmissions.size)
    }

    private class Fixture(tools: List<Tool> = emptyList(), val nestedAttachment: Boolean = false,
        predecessor: UIMessage? = null) {
        val model = Model(modelId = "test")
        val assistant = Assistant()
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val document = UIMessagePart.Document(url = "file:///managed/document.txt", fileName = "document.txt", mime = "text/plain")
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("question"), document),
            createdAt = LocalDateTime.parse("2026-09-27T12:00:00"))
        val image = UIMessagePart.Image("https://example.com/image.png")
        var response = UIMessage(role = MessageRole.ASSISTANT, parts = if (!nestedAttachment) listOf(TurnTransition.openStep(0)) else {
            val step = TurnTransition.openStep(0)
            listOf(step.copy(modelResult = me.rerere.ai.ui.StepModelResult(
                finishReason = "tool_calls", usage = me.rerere.ai.ui.StepUsage(), providerRequestCount = 1,
                timeToFirstOutputMillis = null, requestDurationMillis = null,
                usageCompleteness = me.rerere.ai.core.UsageCompleteness.NONE, providerMetadata = null,
            ), outcome = me.rerere.ai.ui.StepOutcome.Continue, finishedAt = step.startedAt),
                UIMessagePart.Tool(Uuid.random(), step.stepId, "media", "image_tool", "{}",
                    output = listOf(image), resultStatus = me.rerere.ai.ui.ToolResultStatus.COMPLETED),
                TurnTransition.openStep(1))
        })
        var snapshot = Conversation.ofId(Uuid.random(), assistant.id)
            .copy(messageNodes = listOfNotNull(predecessor?.let { MessageNode.of(it) }) +
                listOf(MessageNode.of(user), MessageNode.of(response))).toSnapshot()
        var handle = TurnHandle(snapshot.conversationId, 1, Uuid.random(), response.id)
        val artifacts = mockk<ArtifactStore>()
        var samples = 0
        var output = emptyList<UIMessage>()
        val configuration = ConfigurationResolver.resolve(
            UserSettingsDocument.empty().withPersonalSettings(settings.copy(assistants = listOf(assistant))),
            ConfigurationScope.Personal, EnterpriseState.Loading)
        var context = testTurnContext(settings, model, assistant, tools).copy(
            disclosure = TurnDisclosureSource.capture(configuration, assistant, DisclosureNamespace(null, assistant.id),
                readConfiguration = { configuration }, readMemory = { samples++; emptyList() }))
        val access = object : TurnRequestContextAccess {
            override suspend fun read() = TurnRequestHistory(snapshot, emptyMap())
            override suspend fun admit(entries: List<ConversationModelContextEntry>, admission: ConversationContextAdmission) {
                snapshot = ConversationTransition.apply(snapshot, AdmitRequestContext(handle, entries, admission))
            }
        }

        suspend fun admit(includeDocument: Boolean = false, failAssembly: Boolean = false) {
            val step = snapshot.nodes.last().currentMessage.parts.filterIsInstance<UIMessagePart.Step>().last()
            val admission = TurnRequestAdmission(context, access.read(), access, handle, step.stepId,
                RequestContextPlanner(), artifacts)
            val plan = admission.plan(snapshot.currentMessages())
            val origins = RequestMessageOriginTracker()
            plan.originsByMessageId.forEach { (id, source) ->
                origins.markDurable(id, (source as net.weero.measix.pilot.data.ai.request.RequestMessageOrigin.Durable).locator)
            }
            admission.markOrigins(origins)
            val transformed = plan.messages.map { message ->
                if (message.id == response.id && nestedAttachment) message.copy(parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Tool) part else {
                        val marker = UIMessagePart.Text("[Attachment type=image input=reference_only]")
                        origins.markPart(marker, RequestPartSource.AttachmentInput(image, "REFERENCE_ONLY"))
                        part.copy(output = listOf(marker))
                    }
                }) else if (message.id != user.id || !includeDocument) message else {
                    val derived = UIMessagePart.Text("derived document")
                    origins.markPart(derived, RequestPartSource.DocumentInput(document))
                    message.copy(parts = listOf(message.parts.first(), derived, document))
                }
            }
            val timed = TimeReminderTransformer.transform(TransformerContext(context.realmAccess, mockk(), model,
                context.assistant, context.promptInputs, origins, registerUnpublishedResource = {}), transformed)
            output = admission.admit(plan, timed, origins) {
                check(!failAssembly) { "request_rejected" }
                it
            }
        }

        fun startNextTurn() {
            val old = snapshot.nodes.last()
            val completed = old.copy(messages = listOf(old.currentMessage.copy(parts = old.currentMessage.parts.map { part ->
                if (part is UIMessagePart.Step) part.copy(outcome = me.rerere.ai.ui.StepOutcome.Final, finishedAt = part.startedAt)
                else part
            })))
            val nextUser = UIMessage.user("next turn").copy(createdAt = LocalDateTime.parse("2026-09-27T16:00:00"))
            response = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(TurnTransition.openStep(0)))
            snapshot = snapshot.copy(nodes = snapshot.nodes.dropLast(1) + completed +
                listOf(MessageNode.of(nextUser), MessageNode.of(response)))
            handle = TurnHandle(snapshot.conversationId, 1, Uuid.random(), response.id)
        }

        fun nextStep() {
            val node = snapshot.nodes.last()
            val current = node.currentMessage
            val parts = current.parts.map { part ->
                if (part is UIMessagePart.Step && part.outcome == null) part.copy(
                    outcome = me.rerere.ai.ui.StepOutcome.Continue,
                    finishedAt = part.startedAt,
                ) else part
            } + TurnTransition.openStep(current.parts.filterIsInstance<UIMessagePart.Step>().size)
            snapshot = snapshot.copy(nodes = snapshot.nodes.dropLast(1) + node.copy(messages = listOf(current.copy(parts = parts))))
        }
    }
}
