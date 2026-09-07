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
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
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
internal fun ComposeTurnWorkload() {
    val initial = remember { UIMessage(role = MessageRole.ASSISTANT, parts = listOf(TurnTransition.openStep(0))) }
    val durable = remember {
        Conversation.ofId(Uuid.random()).copy(messageNodes = (1..999).map {
            UIMessage.user("Historical question $it").toMessageNode()
        } + initial.toMessageNode()).toSnapshot()
    }
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
        ConversationRuntimeSnapshot(durable, TurnStreamProjection(1, turnId, initial.id, active)).toPresentationSnapshot()
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
                Text("done:compose_100")
            } else LazyColumn(
                state = listState,
            ) {
                items(presentation.nodes, key = { it.id }) { node ->
                    val renderedUpdate = if (node.currentMessage.id == initial.id) update else null
                    if (renderedUpdate != null) Trace.beginSection("turn_compose_active")
                    ChatMessage(
                        node = node, loading = node.currentMessage.id == initial.id, readOnly = true,
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
