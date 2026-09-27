package net.weero.measix.pilot.benchmark

import android.os.Trace
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.channels.Channel
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.ConversationViewLease
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.service.turn.StepOutputAccumulator
import net.weero.measix.pilot.ui.components.message.ChatMessage
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.theme.MeasixTheme
import net.weero.measix.pilot.ui.theme.ColorMode
import net.weero.measix.pilot.ui.context.Navigator
import kotlin.uuid.Uuid

/** Real selected-branch projection and message renderer; the frame handshake avoids coalescing updates. */
@Composable
internal fun ComposeTurnWorkload(withContext: Boolean = false) {
    val workload = if (withContext) "compose_context_100" else "compose_100"
    val initial = remember { UIMessage(role = MessageRole.ASSISTANT, parts = listOf(TurnTransition.openStep(0))) }
    val durable = remember {
        if (withContext) contextFixture(initial) else Conversation.ofId(Uuid.random()).copy(messageNodes = (1..999).map {
            UIMessage.user("Historical question $it").toMessageNode()
        } + initial.toMessageNode()).toSnapshot()
    }
    val projector = remember { ConversationPresentationProjector() }
    val detailSource = remember { if (withContext) ConversationViewLease(durable.conversationId, RealmAccess.Personal, 0L) {} else null }
    DisposableEffect(detailSource) { onDispose { detailSource?.close() } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = durable.nodes.lastIndex)
    val accumulator = remember { StepOutputAccumulator() }
    val chunk = remember { MessageChunk(id = "chunk", model = "benchmark", choices = listOf(UIMessageChoice(
        index = 0, delta = UIMessage.assistant("Visible streaming text. "), message = null, finishReason = null,
    ))) }
    val turnId = remember { Uuid.random() }
    var active by remember { mutableStateOf(initial) }
    var update by remember { mutableIntStateOf(0) }
    var complete by remember { mutableStateOf(false) }
    val rendered = remember { Channel<Int>(Channel.CONFLATED) }
    val compositions = remember { intArrayOf(0) }
    val presentation = remember(active) {
        val snapshot = ConversationRuntimeSnapshot(durable, TurnStreamProjection(1, turnId, initial.id, active))
        if (withContext) projector.project(snapshot) else snapshot.toPresentationSnapshot()
    }
    val navigator = remember { Navigator(mutableListOf()) }
    val toaster = rememberToasterState()
    CompositionLocalProvider(
        LocalSettings provides remember { Settings() },
        LocalNavController provides navigator,
        LocalToaster provides toaster,
    ) {
        MeasixTheme(colorMode = ColorMode.LIGHT) {
            Toaster(state = toaster, darkTheme = false, richColors = true, showCloseButton = true)
            if (complete) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("done:$workload")
            } else LazyColumn(
                state = listState,
            ) {
                items(presentation.nodes, key = { it.id }) { node ->
                    val renderedUpdate = if (node.currentMessage.id == initial.id) update else null
                    if (renderedUpdate != null) Trace.beginSection("turn_compose_active")
                    ChatMessage(
                        node = node, loading = node.currentMessage.id == initial.id, readOnly = true,
                        detailSource = detailSource, contextSummary = presentation.context.messages[node.currentMessage.id],
                        onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {},
                    )
                    if (renderedUpdate != null) {
                        Trace.endSection()
                        SideEffect {
                            compositions[0]++
                            Trace.setCounter("turn_active_compositions", compositions[0].toLong())
                            rendered.trySend(renderedUpdate)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        listState.requestScrollToItem(durable.nodes.lastIndex)
        rendered.receive()
        val allocationBefore = allocatedJavaBytes()
        repeat(100) { index ->
            withFrameNanos { }
            active = accumulator.accumulate(active, chunk, null)
            update = index + 1
            // Match the forward chat list's follow-output scroll request before awaiting rendering.
            listState.requestScrollToItem(durable.nodes.lastIndex)
            while (rendered.receive() < index + 1) { /* Await the matching committed composition. */ }
        }
        withFrameNanos { }
        recordJavaAllocation("turn_compose_updates", allocationBefore)
        check(active.parts.filterIsInstance<UIMessagePart.Text>().single().text.length == 100 * "Visible streaming text. ".length)
        complete = true
    }
}

/** Valid causal owners and admissions exercise discovery; context bodies remain collapsed. */
private fun contextFixture(initial: UIMessage): ConversationAggregateSnapshot {
    val history = (0 until 999).map { index ->
        if (index % 2 == 0) UIMessage.user("Historical question $index").toMessageNode()
        else UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            TurnTransition.openStep(0), UIMessagePart.Text("Historical answer $index"))).toMessageNode()
    }
    val nodes = history + initial.toMessageNode()
    val base = Conversation.ofId(Uuid.random()).copy(messageNodes = nodes).toSnapshot()
    val namespace = DisclosureNamespace("__global__", base.header.assistantId)
    val entries = mutableListOf<ConversationModelContextEntry>()
    val admissions = mutableListOf<ConversationContextAdmission>()
    nodes.forEachIndexed { index, node ->
        if (index != nodes.lastIndex && index % 40 != 1) return@forEachIndexed
        val anchor = nodes[index - 1]
        val step = node.currentMessage.parts.filterIsInstance<UIMessagePart.Step>().single()
        val owner = ContextMessageLocator(node.id, node.currentMessage.id)
        val input = ContextMessageLocator(anchor.id, anchor.currentMessage.id)
        val system = ConversationModelContextEntry(node.id, owner.messageId, anchor.id, input.messageId,
            ConversationContextPayload(source = ConversationContextSource.System(emptyList()),
                body = ConversationContextBody.Inline("Stable benchmark system")), occurrence = 0, stepId = step.stepId)
        val disclosure = ConversationModelContextEntry(node.id, owner.messageId, anchor.id, input.messageId,
            ConversationContextPayload(source = ConversationContextSource.Disclosure(namespace, mapOf(DisclosureSection.MEMORY to
                if (index == nodes.lastIndex) ContextAdmissionReason.EXTERNAL_STATE else ContextAdmissionReason.INITIAL)),
                body = ConversationContextBody.Inline(ConversationDisclosureSnapshotService.renderSections(mapOf(
                    DisclosureSection.MEMORY to Json.parseToJsonElement(
                        """{"enabled":true,"scope":"global","header":["id","content"],"rows":[[1,"Benchmark memory $index"]]}"""
                    ).jsonObject)))), occurrence = 1, stepId = step.stepId)
        entries += listOf(system, disclosure)
        admissions += ConversationContextAdmission(owner, step.stepId, input,
            TurnContextSelection(systemEntryId = system.id, ruleEntryIds = emptyList(), timeReminderEnabled = false,
                timeZoneId = "UTC", disclosureNamespace = namespace), listOf(
                ConversationContextUse(system.id, MessageRole.SYSTEM, ContextPlacement.System),
                ConversationContextUse(disclosure.id, MessageRole.USER, ContextPlacement.BeforeStep(step.stepId))))
    }
    return base.copy(modelContextEntries = entries, contextAdmissions = admissions).also { snapshot ->
        val summary = ConversationPresentationProjector().project(ConversationRuntimeSnapshot(snapshot, null)).context
        check(summary.messages.values.count { it.updates.isNotEmpty() } == 1)
        check(summary.messages.getValue(initial.id).updates.isNotEmpty())
    }
}
