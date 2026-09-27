package net.weero.measix.pilot.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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
internal fun ContextMessageEntry(
    externalUpdate: Boolean,
    categories: List<ConversationContextCategory> = emptyList(),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val names = categories.distinct().map { contextCategoryText(it) }
    val label = when {
        !externalUpdate -> stringResource(R.string.context_title)
        names.isEmpty() -> stringResource(R.string.context_updated)
        names.size <= 2 -> stringResource(R.string.context_updated_categories, names.joinToString(" · "))
        else -> stringResource(R.string.context_updated_more, names.first(), names.size)
    }
    val description = if (externalUpdate && names.isEmpty()) stringResource(R.string.context_updated_accessibility)
        else stringResource(R.string.context_details_accessibility, label)
    // A compact text row uses Compose's expanded minimum touch target without button content insets.
    Box(modifier.heightIn(min = 32.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.CenterStart) {
        Text("$label ›", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
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
            val initial = detail.requests.firstOrNull { request -> request.items.any { it.isCurrentUpdate } }
                ?: detail.requests.first()
            expanded[initial.id] = true
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
    val updates = request.items.filter { it.isCurrentUpdate }
    val other = request.items.filterNot { it.isCurrentUpdate }
    if (updates.isNotEmpty()) {
        Text(stringResource(R.string.context_changes_heading), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.context_changes_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        updates.forEach { item -> key(item.key) { ContextContentItem(item, initiallyExpanded = true) { load(item) } } }
    }
    var showOther by remember(request.id) { mutableStateOf(false) }
    if (other.isNotEmpty()) {
        if (updates.isNotEmpty()) TextButton(onClick = { showOther = !showOther }) {
            Text(stringResource(R.string.context_other_inputs))
        } else Text(stringResource(R.string.context_inputs_heading), style = MaterialTheme.typography.titleSmall)
        if (updates.isEmpty() || showOther) other.forEach { item -> key(item.key) {
            ContextContentItem(item) { load(item) }
        } }
    }
}

@Composable
internal fun ContextContentItem(item: ConversationContextItemUiModel, initiallyExpanded: Boolean = false, load: suspend () -> ConversationContextContentUiModel) {
    var expanded by remember(item.key) { mutableStateOf(initiallyExpanded) }
    var sourceExpanded by remember(item.key) { mutableStateOf(false) }
    var originalExpanded by remember(item.key) { mutableStateOf(false) }
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
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(item.name?.let { "$title · $it" } ?: title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(if (expanded) "⌄" else "›")
            }
            if (expanded) {
                if (content == null && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                failure?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
                content?.let { value ->
                    if (value.presentation.sections.isNotEmpty()) {
                        value.presentation.sections.forEach { section -> ContextSection(section) }
                        TextButton(onClick = { originalExpanded = !originalExpanded }) { Text(stringResource(R.string.context_raw_input)) }
                        if (originalExpanded) ContextLiteral(value.text)
                    } else ContextLiteral(value.text)
                    if (originalExpanded || value.presentation.sections.isEmpty()) TextButton(onClick = {
                        scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(title, value.text))) }
                    }) {
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
    }
}

@Composable
private fun ContextSection(section: ConversationContextSectionUiModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val ownerTitle = section.systemOwner?.let { owner -> stringResource(when (owner) {
            ConversationContextSystemOwner.DOMAIN -> R.string.context_owner_assistant
            ConversationContextSystemOwner.CONVERSATION -> R.string.context_owner_conversation
            ConversationContextSystemOwner.ENTERPRISE_OPENING -> R.string.context_owner_opening
            ConversationContextSystemOwner.CONTEXT_SYNC -> R.string.context_owner_sync
            ConversationContextSystemOwner.APPLICATION -> R.string.context_owner_application
            ConversationContextSystemOwner.TOOL -> R.string.context_owner_tool
            ConversationContextSystemOwner.WORKSPACE -> R.string.context_owner_workspace
            ConversationContextSystemOwner.PROMPT_RULE -> R.string.context_prompt_rule
        }) }
        val title = when (section.systemOwner) {
            null -> section.title ?: contextCategoryText(section.category)
            ConversationContextSystemOwner.TOOL, ConversationContextSystemOwner.PROMPT_RULE ->
                listOfNotNull(ownerTitle, section.title).joinToString(" · ")
            else -> requireNotNull(ownerTitle)
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        val qualifiers = listOfNotNull(
            section.scope?.let { stringResource(when (it) {
                ConversationContextScope.SHARED -> R.string.context_scope_shared
                ConversationContextScope.ASSISTANT -> if (section.category == ConversationContextCategory.ASSISTANTS)
                    R.string.context_scope_available else R.string.context_scope_assistant
                ConversationContextScope.READ_ONLY -> R.string.context_scope_enterprise
                ConversationContextScope.DISABLED -> R.string.context_scope_disabled
                ConversationContextScope.UNKNOWN -> R.string.context_scope_unknown
            }) },
            section.reason?.let { stringResource(when (it) {
                ConversationContextReason.INITIAL -> R.string.context_reason_initial
                ConversationContextReason.EXTERNAL -> R.string.context_reason_external
                ConversationContextReason.RESTORE -> R.string.context_reason_restore
                ConversationContextReason.HISTORICAL -> R.string.context_historical
            }) },
        )
        if (qualifiers.isNotEmpty()) Text(qualifiers.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (section.reason == ConversationContextReason.EXTERNAL && !section.exactChanges) {
            Text(stringResource(R.string.context_legacy_changes), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        section.attributes.forEach { attribute ->
            val name = stringResource(when (attribute.name) {
                "enabled" -> R.string.context_attribute_enabled
                "scope" -> R.string.context_attribute_scope
                else -> R.string.context_attribute_capability
            })
            val after = contextAttributeValue(attribute.after)
            Text(if (attribute.before == null) "$name: $after" else "$name: ${contextAttributeValue(attribute.before)} → $after",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (section.orderBefore.isNotEmpty()) {
            var showOrder by remember(section) { mutableStateOf(false) }
            TextButton(onClick = { showOrder = !showOrder }) { Text(stringResource(R.string.context_order_changed)) }
            if (showOrder) {
                Text(stringResource(R.string.context_order_before), style = MaterialTheme.typography.labelLarge)
                section.orderBefore.forEachIndexed { index, row -> ContextLiteral("${index + 1}. ${row.after.orEmpty()}") }
                Text(stringResource(R.string.context_order_after), style = MaterialTheme.typography.labelLarge)
                section.orderAfter.forEachIndexed { index, row -> ContextLiteral("${index + 1}. ${row.after.orEmpty()}") }
            }
        }
        section.text?.let { ContextLiteral(it) }
        section.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider()
            val action = row.change?.let { stringResource(when (it) {
                ConversationContextChangeKind.ADDED -> R.string.context_change_added
                ConversationContextChangeKind.MODIFIED -> R.string.context_change_modified
                ConversationContextChangeKind.REMOVED -> R.string.context_change_removed
            }) }
            val name = row.title ?: if (section.category == ConversationContextCategory.MEMORY && row.id != null)
                stringResource(R.string.context_memory_item, row.id) else stringResource(R.string.context_numbered_item, index + 1)
            Text(listOfNotNull(action, name).joinToString(" · "), style = MaterialTheme.typography.labelLarge)
            if (row.change == ConversationContextChangeKind.REMOVED) row.before?.let { ContextLiteral(it) }
            else {
                row.after?.let { ContextLiteral(it) }
                row.before?.let { previous ->
                    var showPrevious by remember(row) { mutableStateOf(false) }
                    TextButton(onClick = { showPrevious = !showPrevious }) { Text(stringResource(R.string.context_before_change)) }
                    if (showPrevious) ContextLiteral(previous)
                }
            }
        }
        if (section.rows.isEmpty() && section.text == null && !section.exactChanges) {
            Text(stringResource(R.string.context_no_entries), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun contextAttributeValue(value: String): String = when (value) {
    "true" -> stringResource(R.string.context_enabled)
    "false", "disabled" -> stringResource(R.string.context_scope_disabled)
    "global" -> stringResource(R.string.context_scope_shared)
    "local" -> stringResource(R.string.context_scope_assistant)
    "management_only" -> stringResource(R.string.context_manage_only)
    "delegation_only" -> stringResource(R.string.context_delegate_only)
    "both" -> stringResource(R.string.context_manage_delegate)
    else -> value
}

@Composable
private fun ContextLiteral(text: String) {
    var full by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = if (full) Int.MAX_VALUE else 8,
            overflow = TextOverflow.Ellipsis, onTextLayout = { if (!full) overflows = it.hasVisualOverflow })
    }
    if (overflows || full) TextButton(onClick = { full = !full }) {
        Text(stringResource(if (full) R.string.context_collapse_text else R.string.context_expand_text))
    }
}

private fun contextDiagnostic(error: Exception): String {
    android.util.Log.e("ConversationContext", "Context detail read failed", error)
    return error.userVisibleDiagnostic()
}
