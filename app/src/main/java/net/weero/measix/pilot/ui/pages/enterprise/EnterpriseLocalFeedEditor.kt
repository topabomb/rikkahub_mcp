package net.weero.measix.pilot.ui.pages.enterprise

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.enterprise.*

/** Native local source authoring; the Portal only consumes published updates. */
@Composable
internal fun EnterpriseLocalFeedEditor(
    original: LocalEnterpriseFeedSnapshot,
    busy: Boolean,
    error: Int?,
    onChange: (EnterpriseFeedCommand) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember(original.revision) { mutableStateOf(false) }
    var id by remember(original.revision) { mutableStateOf<String?>(null) }
    var content by remember(original.revision) { mutableStateOf(EnterpriseUpdateContent(
        "", "", EnterpriseUpdateFormat.PLAIN, EnterpriseUpdateCategory.NOTICE, EnterpriseUpdateSeverity.INFO,
    )) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.enterprise_feed_editor), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.enterprise_feed_notice), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.enterprise_feed_revision, original.document.publicRevision,
                    original.document.enterpriseTimezone), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (editing) {
                    OutlinedTextField(content.title, { content = content.copy(title = it) },
                        label = { Text(stringResource(R.string.enterprise_feed_title)) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(content.content, { content = content.copy(content = it) },
                        label = { Text(stringResource(R.string.enterprise_feed_content)) }, enabled = !busy,
                        minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnterpriseUpdateFormat.entries.forEach { value ->
                            FilterChip(selected = content.contentFormat == value, onClick = { content = content.copy(contentFormat = value) },
                                enabled = !busy, label = { Text(stringResource(if (value == EnterpriseUpdateFormat.PLAIN)
                                    R.string.enterprise_feed_plain else R.string.enterprise_feed_markdown)) })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnterpriseUpdateCategory.entries.forEach { value ->
                            FilterChip(selected = content.category == value, onClick = { content = content.copy(category = value) },
                                enabled = !busy, label = { Text(stringResource(when (value) {
                                    EnterpriseUpdateCategory.NOTICE -> R.string.enterprise_feed_notice_category
                                    EnterpriseUpdateCategory.ANNOUNCEMENT -> R.string.enterprise_feed_announcement
                                    EnterpriseUpdateCategory.MAINTENANCE -> R.string.enterprise_feed_maintenance
                                })) })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnterpriseUpdateSeverity.entries.forEach { value ->
                            FilterChip(selected = content.severity == value, onClick = { content = content.copy(severity = value) },
                                enabled = !busy, label = { Text(stringResource(when (value) {
                                    EnterpriseUpdateSeverity.INFO -> R.string.enterprise_feed_info
                                    EnterpriseUpdateSeverity.WARNING -> R.string.enterprise_feed_warning
                                    EnterpriseUpdateSeverity.CRITICAL -> R.string.enterprise_feed_critical
                                })) })
                        }
                    }
                    Row {
                        Button(onClick = { onChange(id?.let { EnterpriseFeedCommand.UpdateDraft(it, content) }
                            ?: EnterpriseFeedCommand.CreateDraft(content)) }, enabled = !busy && content.title.isNotBlank() && content.content.isNotBlank()) {
                            Text(stringResource(R.string.enterprise_feed_save_draft))
                        }
                        TextButton(onClick = { editing = false }, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                    }
                } else {
                    OutlinedButton(onClick = {
                        id = null
                        content = EnterpriseUpdateContent("", "", EnterpriseUpdateFormat.PLAIN,
                            EnterpriseUpdateCategory.NOTICE, EnterpriseUpdateSeverity.INFO)
                        editing = true
                    }, enabled = !busy) { Text(stringResource(R.string.enterprise_feed_create)) }
                    original.document.items.asReversed().forEach { entry ->
                        HorizontalDivider()
                        Text(entry.content.title, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when (entry.status) {
                            EnterpriseUpdateStatus.DRAFT -> R.string.enterprise_feed_draft
                            EnterpriseUpdateStatus.PUBLISHED -> R.string.enterprise_feed_published
                            EnterpriseUpdateStatus.WITHDRAWN -> R.string.enterprise_feed_withdrawn
                        }))
                        entry.publishedAt?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(entry.content.content, maxLines = 4)
                        Row {
                            if (entry.status == EnterpriseUpdateStatus.DRAFT) {
                                TextButton(onClick = { id = entry.enterpriseUpdateId; content = entry.content; editing = true }, enabled = !busy) {
                                    Text(stringResource(R.string.edit))
                                }
                                TextButton(onClick = { onChange(EnterpriseFeedCommand.Publish(entry.enterpriseUpdateId)) }, enabled = !busy) {
                                    Text(stringResource(R.string.enterprise_feed_publish))
                                }
                            } else if (entry.status == EnterpriseUpdateStatus.PUBLISHED) {
                                TextButton(onClick = { onChange(EnterpriseFeedCommand.Withdraw(entry.enterpriseUpdateId)) }, enabled = !busy) {
                                    Text(stringResource(R.string.enterprise_feed_withdraw))
                                }
                            }
                        }
                    }
                }
                Row {
                    TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.enterprise_reload_source)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_card_close)) }
                }
            }
        }
    }
}
