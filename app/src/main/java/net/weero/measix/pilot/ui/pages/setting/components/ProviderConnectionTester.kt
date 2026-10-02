package net.weero.measix.pilot.ui.pages.setting.components

import me.rerere.common.configuration.ConfigurationReference
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import net.weero.measix.pilot.utils.redactDiagnosticSecrets
import net.weero.measix.pilot.ui.components.ui.DiagnosticDisclosure

@Composable
fun ProviderConnectionTester(
    internalProvider: ProviderSetting,
    state: ProviderSettingsUiState,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSelectModel: (ConfigurationReference?) -> Unit,
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
                    catalog = net.weero.measix.pilot.service.userDefinitionModelCatalog(listOf(internalProvider)),
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
            is UiState.Error -> Column(Modifier.weight(1f)) {
                val diagnostic = remember(state.error) { redactDiagnosticSecrets(state.error.stackTraceToString()) }
                Text(
                    text = redactDiagnosticSecrets(state.error.message ?: stringResource(R.string.error_title_operation)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                DiagnosticDisclosure(diagnostic, title = label)
            }
        }
    }

}
