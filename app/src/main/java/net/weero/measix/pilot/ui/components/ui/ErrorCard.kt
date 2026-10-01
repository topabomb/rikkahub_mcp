package net.weero.measix.pilot.ui.components.ui

import android.content.ClipData
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.InformationCircle
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.ChatError
import net.weero.measix.pilot.service.ChatErrorRetention
import net.weero.measix.pilot.service.ChatErrorSolution
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.ui.context.LocalNavController
import kotlin.uuid.Uuid

@Composable
fun ErrorCardsDisplay(
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryReads: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = errors.isNotEmpty(),
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        BoxWithConstraints {
            // Leave conversation content visible even with multiple long diagnostics or an open keyboard.
            Column(
                Modifier.heightIn(max = maxHeight / 2).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (errors.size > 1) {
                    TextButton(onClick = onClearAllErrors) {
                        Icon(HugeIcons.Delete01, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_page_clear_all_errors))
                    }
                }
                errors.asReversed().forEach { error -> key(error.id) {
                    ErrorCard(
                        error = error,
                        onDismiss = { onDismissError(error.id) },
                        onRetry = if (error.solution == ChatErrorSolution.RetryConversationReads && onRetryReads != null) {
                            { onDismissError(error.id); onRetryReads() }
                        } else null,
                    )
                } }
            }
        }
    }
}

/** The compact summary and the full diagnostic use the same error; copying never dismisses it. */
@Composable
fun ErrorCard(
    error: ChatError,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showDetails by remember(error.id) { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val copy: () -> Unit = {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Error", error.detail))) }
    }
    LaunchedEffect(error.id, error.retention, showDetails) {
        if (!showDetails && error.retention == ChatErrorRetention.TRANSIENT && dismiss != null) {
            val remainingMillis = (TRANSIENT_ERROR_DURATION_MILLIS -
                (System.currentTimeMillis() - error.timestamp)).coerceAtLeast(0L)
            delay(remainingMillis)
            dismiss?.invoke()
        }
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                error.title?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(error.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
                FlowRow {
                    IconButton(onClick = { showDetails = true }) {
                        Icon(HugeIcons.InformationCircle, stringResource(R.string.chat_conversation_diagnostics),
                            tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.application_recovery_retry)) }
                    ErrorSolution(error.solution)
                }
            }
            IconButton(onClick = copy) {
                Icon(HugeIcons.Copy01, stringResource(R.string.chat_page_copy_error), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
            if (onDismiss != null) IconButton(onClick = onDismiss) {
                Icon(HugeIcons.Cancel01, stringResource(R.string.chat_page_dismiss_error), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
    if (showDetails) ErrorDetails(error, onDismiss = { showDetails = false })
}

@Composable
fun ErrorDetails(error: ChatError, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AdaptiveModal(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error.title ?: stringResource(R.string.chat_conversation_diagnostics), Modifier.weight(1f).padding(8.dp),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Error", error.detail))) }
            }) { Icon(HugeIcons.Copy01, stringResource(R.string.chat_page_copy_error)) }
            IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, stringResource(R.string.update_card_close)) }
        }
        SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(error.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ErrorSolution(solution: ChatErrorSolution?) {
    val destination = when (solution) {
        ChatErrorSolution.CheckTitleModelSettings -> Screen.SettingModels
        ChatErrorSolution.CheckProviderSettings -> Screen.SettingProvider
        ChatErrorSolution.ViewEnterpriseUsage -> Screen.EnterpriseUsage
        else -> return
    }
    val label = when (solution) {
        ChatErrorSolution.CheckTitleModelSettings -> R.string.chat_page_check_title_model_settings
        ChatErrorSolution.CheckProviderSettings -> R.string.chat_page_check_provider_settings
        else -> R.string.enterprise_budget_details
    }
    val navigation = LocalNavController.current
    TextButton(onClick = { navigation.navigate(destination) { launchSingleTop = true } }) { Text(stringResource(label)) }
}

private const val TRANSIENT_ERROR_DURATION_MILLIS = 5_000L
