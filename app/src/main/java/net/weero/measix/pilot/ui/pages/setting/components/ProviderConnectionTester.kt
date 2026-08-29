package net.weero.measix.pilot.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ProviderToolProbeResult
import net.weero.measix.pilot.ui.components.ai.ModelListSheet
import net.weero.measix.pilot.ui.components.ai.ModelSelectorButton
import net.weero.measix.pilot.ui.components.ai.rememberModelListState
import net.weero.measix.pilot.ui.pages.setting.ProviderSettingsUiState
import net.weero.measix.pilot.ui.theme.extendColors
import net.weero.measix.pilot.utils.UiState
import kotlin.uuid.Uuid

@Composable
fun ProviderConnectionTester(
    internalProvider: ProviderSetting,
    state: ProviderSettingsUiState,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSelectModel: (Uuid?) -> Unit,
    onRun: () -> Unit,
) {
    IconButton(onClick = onOpen) {
        Icon(HugeIcons.Connect, null)
    }

    if (state.showConnectionTest) {
        val selectedModel = internalProvider.models.firstOrNull { it.id == state.selectedConnectionModelId }
        val testState = state.connectionTest
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(stringResource(R.string.setting_provider_page_test_connection))
            },
            text = {
                val connectionModelState = rememberModelListState(
                    modelId = selectedModel?.id,
                    providers = listOf(internalProvider),
                    type = ModelType.CHAT,
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModelSelectorButton(
                        state = connectionModelState,
                        modifier = Modifier.fillMaxWidth()
                    )

                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_non_streaming),
                        state = testState.nonStreaming,
                        resultText = (testState.nonStreaming as? UiState.Success)?.data.orEmpty(),
                    )

                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_streaming),
                        state = testState.streaming,
                        resultText = testState.streamingText,
                    )

                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_tool_call),
                        state = testState.toolCall,
                        resultText = when (val result = (testState.toolCall as? UiState.Success)?.data) {
                            is ProviderToolProbeResult.Called -> stringResource(
                                R.string.setting_provider_page_test_tool_called,
                                result.toolName,
                                result.input,
                            )
                            is ProviderToolProbeResult.NotCalled -> stringResource(
                                R.string.setting_provider_page_test_no_tool,
                                result.responseText,
                            )
                            null -> ""
                        },
                    )
                }
                ModelListSheet(
                    state = connectionModelState,
                    onSelect = { onSelectModel(it.id) },
                )
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onRun,
                    enabled = selectedModel != null,
                ) {
                    Text(stringResource(R.string.setting_provider_page_test))
                }
            }
        )
    }
}

@Composable
private fun TestResultItem(
    label: String,
    state: UiState<*>,
    resultText: String
) {
    var showErrorSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(64.dp)
        )
        when (state) {
            is UiState.Idle -> Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is UiState.Loading -> LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
            is UiState.Success -> Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.green6
                )
                if (resultText.isNotBlank()) {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            is UiState.Error -> Text(
                text = state.error.message ?: "Error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendColors.red6,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showErrorSheet = true }
            )
        }
    }

    if (showErrorSheet && state is UiState.Error) {
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        val stackTrace = remember(state.error) {
            state.error.stackTraceToString()
        }
        AdaptiveModal(
            onDismissRequest = { showErrorSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = state.error.message ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.red6
                )
                Text(
                    text = stackTrace,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
