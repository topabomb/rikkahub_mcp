package net.weero.measix.pilot.ui.pages.enterprise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.EnterpriseStarterReadState
import net.weero.measix.pilot.service.StarterDraftRequest
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.koin.compose.koinInject

/** Selects enterprise-provided draft starters without creating another assistant-detail surface. */
@Composable
internal fun EnterpriseStarterPicker(
    selection: RealmSelection,
    onOpenDraft: (StarterDraftRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val queries: ConfigurationQueryService = koinInject()
    val conversations: ConversationApplicationService = koinInject()
    val state by remember(queries, selection) { queries.observeEnterpriseStarters(selection) }
        .collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<ConfigurationReference.Enterprise?>(null) }
    var opening by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    AdaptiveModal(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (selected != null) {
                    IconButton(onClick = { selected = null; failure = null }, enabled = !opening) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                }
                Text(
                    stringResource(R.string.enterprise_start_conversation),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.update_card_close))
                }
            }
            val available = (state as? EnterpriseStarterReadState.Available)?.value
            if (available == null) {
                if (state == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else SelectionContainer {
                    Text(
                        when (val unavailable = state) {
                            EnterpriseStarterReadState.SourceChanged -> stringResource(R.string.enterprise_source_changed)
                            is EnterpriseStarterReadState.Failed -> unavailable.detail
                            else -> return@SelectionContainer
                        },
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@Column
            }
            Text(
                available.enterpriseName,
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            failure?.let { detail -> SelectionContainer {
                Text(detail, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            } }
            if (opening) LinearProgressIndicator(Modifier.fillMaxWidth())
            val starter = available.starters.singleOrNull { it.target.reference == selected }
            if (starter == null) {
                Text(
                    stringResource(R.string.enterprise_starters_description),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(
                    Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (available.starters.isEmpty()) item { Text(stringResource(R.string.enterprise_starters_empty)) }
                    items(available.starters, key = { it.target.reference.toString() }) { entry ->
                        OutlinedCard(onClick = { selected = entry.target.reference; failure = null }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                Text(entry.assistantName, style = MaterialTheme.typography.labelMedium)
                                entry.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(starter.title, style = MaterialTheme.typography.titleLarge)
                    Text(starter.assistantName, style = MaterialTheme.typography.labelLarge)
                    starter.description?.let { Text(it) }
                    Text(stringResource(R.string.enterprise_starter_preview), style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(starter.prompt) }
                    Text(stringResource(R.string.enterprise_starters_description), style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        if (!opening) {
                            opening = true
                            failure = null
                            scope.launch {
                                try {
                                    val request = conversations.newStarterDraftRequest(starter.target)
                                    queries.requireSelection(selection)
                                    onOpenDraft(request)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    android.util.Log.e("EnterpriseStarter", "Opening starter failed", error)
                                    failure = error.userVisibleDiagnostic()
                                } finally {
                                    opening = false
                                }
                            }
                        }
                    },
                    enabled = !opening,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(stringResource(R.string.enterprise_starter_open))
                }
            }
        }
    }
}
