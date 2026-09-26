package net.weero.measix.pilot.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import net.weero.measix.pilot.utils.toLocalDateTime
import java.time.Instant
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun ContextMessageEntry(externalUpdate: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (externalUpdate) R.string.context_updated else R.string.context_title)
    val description = stringResource(if (externalUpdate) R.string.context_updated_accessibility else R.string.context_title)
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Text("$label ›", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun contextCategoryText(category: ConversationContextCategory): String = stringResource(when (category) {
    ConversationContextCategory.OPENING -> R.string.context_opening
    ConversationContextCategory.SYSTEM -> R.string.context_system
    ConversationContextCategory.MEMORY -> R.string.context_memory
    ConversationContextCategory.ASSISTANTS -> R.string.context_assistants
    ConversationContextCategory.ENTERPRISE_BACKGROUND -> R.string.context_enterprise_background
    ConversationContextCategory.PROMPT_RULE -> R.string.context_prompt_rule
    ConversationContextCategory.TIME -> R.string.context_time
    ConversationContextCategory.RESTORE -> R.string.context_restore
    ConversationContextCategory.ATTACHMENT -> R.string.context_attachment
    ConversationContextCategory.HISTORY_SUMMARY -> R.string.context_history_summary
    ConversationContextCategory.PRESET -> R.string.context_preset
    ConversationContextCategory.HISTORICAL -> R.string.context_historical
})

/** The modal owns its reads: dismissal cancels IO and a revoked page closes the whole detail. */
@Composable
internal fun ConversationContextDetails(
    lease: ConversationViewLease,
    conversationId: Uuid,
    messageId: Uuid,
    onDismiss: () -> Unit,
    query: ConversationQueryService = koinInject(),
) {
    var detail by remember(lease, conversationId, messageId) { mutableStateOf<ConversationContextDetailsUiModel?>(null) }
    var failure by remember(lease, conversationId, messageId) { mutableStateOf<String?>(null) }
    val dismiss by rememberUpdatedState(onDismiss)
    val bodies = remember(lease, conversationId, messageId) { mutableMapOf<Uuid?, ConversationContextContentUiModel>() }
    val bodyReads = remember(lease, conversationId, messageId) { Mutex() }
    LaunchedEffect(lease) { query.observeViewAccess(lease).collect { if (!it) dismiss() } }
    LaunchedEffect(lease, conversationId, messageId) {
        try { query.observeContextDetails(lease, conversationId, messageId).collect { detail = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { detail = null; bodies.clear(); failure = contextDiagnostic(error) }
    }
    AdaptiveModal(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(stringResource(R.string.context_title), Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, stringResource(R.string.update_card_close)) }
        }
        if (detail == null && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            failure?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
            detail?.let { value ->
                ContextRequestList(value) { request, item ->
                    bodyReads.withLock {
                        bodies[item.entryId] ?: query.contextContent(lease, conversationId, messageId, request.id, item.key)
                            .also { bodies[item.entryId] = it }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ContextRequestList(
    detail: ConversationContextDetailsUiModel,
    load: suspend (ConversationContextRequestUiModel, ConversationContextItemUiModel) -> ConversationContextContentUiModel,
) {
    val expanded = remember { mutableStateMapOf<Uuid?, Boolean>() }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(detail.requests) {
        if (!initialized && detail.requests.isNotEmpty()) {
            expanded[detail.requests.first().id] = true
            initialized = true
        }
    }
    detail.requests.forEach { request -> key(request.id) {
        TextButton(onClick = { expanded[request.id] = expanded[request.id] != true }) {
            Text(if (request.ordinal == null) stringResource(if (request.state == ConversationContextRequestState.SAVED_CONTENT)
                R.string.context_title else R.string.context_historical)
                else stringResource(R.string.context_request, request.ordinal + 1))
        }
        if (expanded[request.id] == true) ContextRequest(request) { load(request, it) }
    } }
}

@Composable
private fun ContextRequest(request: ConversationContextRequestUiModel, load: suspend (ConversationContextItemUiModel) -> ConversationContextContentUiModel) {
    val status = when (request.state) {
        ConversationContextRequestState.ADDED -> R.string.context_added
        ConversationContextRequestState.INCOMPLETE -> R.string.context_incomplete
        ConversationContextRequestState.DELIVERY_UNKNOWN -> R.string.context_delivery_unknown
        ConversationContextRequestState.HISTORICAL -> R.string.context_unrecorded_request
        ConversationContextRequestState.SAVED_CONTENT -> null
    }
    if (status != null) Text(stringResource(status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    request.time?.let { Text(Instant.parse(it).toLocalDateTime(), style = MaterialTheme.typography.bodySmall) }
    request.items.forEach { item -> key(item.key) {
        ContextContentItem(item) { load(item) }
    } }
}

@Composable
internal fun ContextContentItem(item: ConversationContextItemUiModel, load: suspend () -> ConversationContextContentUiModel) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    var sourceExpanded by remember(item.key) { mutableStateOf(false) }
    var content by remember(item.entryId, item.key) { mutableStateOf<ConversationContextContentUiModel?>(null) }
    var failure by remember(item.key) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(expanded, item.key) {
        if (expanded && content == null) try { content = load(); failure = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = contextDiagnostic(error) }
    }
    val title = item.categories.map { contextCategoryText(it) }.joinToString(" / ")
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(item.name?.let { "$title · $it" } ?: title, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (expanded) {
        if (content == null && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
        content?.let { value ->
            value.emptySections.forEach { section ->
                Text(stringResource(if (section == ConversationContextCategory.MEMORY) R.string.context_empty_memory else R.string.context_empty_assistants),
                    style = MaterialTheme.typography.labelMedium)
            }
            SelectionContainer { Text(value.text, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(title, value.text))) } }) {
                Text(stringResource(R.string.context_copy_original))
            }
            val location = listOfNotNull(item.role, item.location).joinToString(" · ")
            if (value.source != null || location.isNotEmpty()) {
                TextButton(onClick = { sourceExpanded = !sourceExpanded }) { Text(stringResource(R.string.context_source)) }
                if (sourceExpanded) SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (location.isNotEmpty()) Text(location, style = MaterialTheme.typography.bodySmall)
                        value.source?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

private fun contextDiagnostic(error: Exception): String {
    android.util.Log.e("ConversationContext", "Context detail read failed", error)
    return error.userVisibleDiagnostic()
}
