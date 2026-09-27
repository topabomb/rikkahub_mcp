package net.weero.measix.pilot.ui.components.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.StarterOpeningDetailUiModel
import net.weero.measix.pilot.service.StarterOpeningIssue
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.utils.userVisibleDiagnostic

private fun openingFailure(error: Exception): String {
    android.util.Log.e("StarterOpening", "Opening detail operation failed", error)
    return error.userVisibleDiagnostic()
}

@Composable
internal fun starterOpeningIssueText(issue: StarterOpeningIssue): String = stringResource(when (issue) {
    StarterOpeningIssue.UPDATED -> R.string.opening_updated
    StarterOpeningIssue.NOT_SUPPLIED -> R.string.opening_missing
    StarterOpeningIssue.UNAVAILABLE -> R.string.opening_unavailable
    StarterOpeningIssue.ASSISTANT_CHANGED -> R.string.opening_not_applicable
})

/** The full enterprise text is queried only after the user expands this group. */
@Composable
internal fun StarterOpeningContext(key: Any, load: suspend () -> StarterOpeningDetailUiModel) {
    var expanded by remember(key) { mutableStateOf(false) }
    var detail by remember(key) { mutableStateOf<StarterOpeningDetailUiModel?>(null) }
    var failure by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, expanded) {
        if (expanded && detail == null) try { failure = null; detail = load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = openingFailure(error) }
    }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.opening_context)) }
    if (expanded) {
        failure?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (detail != null) OpeningBody(requireNotNull(detail))
        else if (failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun OpeningBody(detail: StarterOpeningDetailUiModel) {
    detail.issue?.let { Text(starterOpeningIssueText(it), color = MaterialTheme.colorScheme.error) }
    if (!detail.systemApplicable) Text(stringResource(R.string.opening_not_applicable), style = MaterialTheme.typography.bodySmall)
    detail.systemPrompt?.let {
        Text(stringResource(R.string.opening_system), style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(it) }
    }
    if (detail.contexts.isNotEmpty()) Text(stringResource(R.string.opening_background), style = MaterialTheme.typography.labelLarge)
    detail.contexts.forEach { block ->
        Text(block.title, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(block.content) }
    }
}

@Composable
internal fun StarterOpeningDetails(
    key: Any,
    load: suspend () -> StarterOpeningDetailUiModel,
    refresh: suspend () -> Unit,
    clear: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var detail by remember(key) { mutableStateOf<StarterOpeningDetailUiModel?>(null) }
    var failure by remember(key) { mutableStateOf<String?>(null) }
    var busy by remember(key) { mutableStateOf(false) }
    var expanded by remember(key) { mutableStateOf(false) }
    suspend fun read() {
        try { detail = load(); failure = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = openingFailure(error) }
    }
    LaunchedEffect(key) { read() }
    AdaptiveModal(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(stringResource(R.string.opening_details), Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, stringResource(R.string.update_card_close)) }
            }
            if (detail == null && failure == null || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                failure?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
                detail?.let { value ->
                    Text(value.title, style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(value.prompt) }
                    value.issue?.let { Text(starterOpeningIssueText(it), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.opening_context)) }
                    if (expanded) OpeningBody(value.copy(issue = null))
                    if (value.canRefresh) TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try { refresh(); read() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { failure = openingFailure(error) }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.opening_update)) }
                    if (value.isDraft) TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try { clear(); onDismiss() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { failure = openingFailure(error) }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.opening_remove)) }
                }
            }
        }
    }
}
