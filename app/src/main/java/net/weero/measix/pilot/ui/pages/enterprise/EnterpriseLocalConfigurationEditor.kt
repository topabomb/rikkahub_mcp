package net.weero.measix.pilot.ui.pages.enterprise

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.GatewayEnablementPolicy
import net.weero.measix.pilot.service.LocalEnterpriseConfigurationChange
import net.weero.measix.pilot.service.LocalEnterpriseConfigurationUiModel

/** Edits the installed local source; every action publishes through the normal source and sync owners. */
@Composable
internal fun EnterpriseLocalConfigurationEditor(
    original: LocalEnterpriseConfigurationUiModel,
    busy: Boolean,
    error: Int?,
    notice: Int?,
    onChange: (LocalEnterpriseConfigurationChange) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var modelId by remember { mutableStateOf<String?>(null) }
    var modelName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    // A new source version invalidates edits opened against its predecessor.
    LaunchedEffect(original.revision) { naming = false; deleting = null }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enterprise_local_configuration)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.enterprise_local_configuration_notice))
            Text(stringResource(R.string.enterprise_source_generation, original.generation))
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(stringResource(it)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.enterprise_personal_resources), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.enterprise_personal_resources_notice), style = MaterialTheme.typography.bodySmall)
            val policy = original.policy
            ConfigurationToggle(stringResource(R.string.enterprise_allow_user_models), policy.allowLocalProviders, !busy) {
                onChange(LocalEnterpriseConfigurationChange.Policy(policy.copy(allowLocalProviders = it)))
            }
            ConfigurationToggle(stringResource(R.string.enterprise_allow_user_tts), policy.allowLocalTts, !busy) {
                onChange(LocalEnterpriseConfigurationChange.Policy(policy.copy(allowLocalTts = it)))
            }
            ConfigurationToggle(stringResource(R.string.enterprise_allow_user_asr), policy.allowLocalAsr, !busy) {
                onChange(LocalEnterpriseConfigurationChange.Policy(policy.copy(allowLocalAsr = it)))
            }
            ConfigurationToggle(stringResource(R.string.enterprise_allow_user_mcp), policy.allowLocalMcp, !busy) {
                onChange(LocalEnterpriseConfigurationChange.Policy(policy.copy(allowLocalMcp = it)))
            }
            ConfigurationToggle(stringResource(R.string.enterprise_allow_user_assistants), policy.allowLocalAssistants, !busy) {
                onChange(LocalEnterpriseConfigurationChange.Policy(policy.copy(allowLocalAssistants = it)))
            }
            original.gateways.forEach { gateway ->
                HorizontalDivider()
                Text(gateway.name)
                Text(stringResource(R.string.enterprise_gateway_policy_notice), style = MaterialTheme.typography.bodySmall)
                ConfigurationToggle(stringResource(R.string.enterprise_gateway_required), gateway.enablement == GatewayEnablementPolicy.REQUIRED, !busy) {
                    onChange(LocalEnterpriseConfigurationChange.Gateway(gateway.id, if (it) GatewayEnablementPolicy.REQUIRED else GatewayEnablementPolicy.USER_CONTROLLABLE_DEFAULT_ON))
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.enterprise_source_models), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.enterprise_source_models_notice), style = MaterialTheme.typography.bodySmall)
            original.models.forEach { model ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model.name, style = MaterialTheme.typography.titleSmall)
                        Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ConfigurationToggle(stringResource(R.string.enterprise_model_enabled), model.enabled, !busy) {
                            onChange(LocalEnterpriseConfigurationChange.ModelEnabled(model.id, it))
                        }
                        Row {
                            TextButton(enabled = !busy, onClick = { modelId = model.id; modelName = model.name; naming = true }) {
                                Text(stringResource(R.string.enterprise_model_rename))
                            }
                            TextButton(enabled = !busy, onClick = { deleting = model.id }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
            OutlinedButton(enabled = !busy, onClick = { modelId = null; modelName = ""; naming = true }) {
                Text(stringResource(R.string.enterprise_add_example_model))
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_card_close)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onRefresh) { Text(stringResource(R.string.enterprise_reload_source)) } },
    )
    if (naming) AlertDialog(onDismissRequest = { naming = false },
        title = { Text(stringResource(if (modelId == null) R.string.enterprise_add_example_model else R.string.enterprise_model_rename)) },
        text = { OutlinedTextField(modelName, { modelName = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = !busy && modelName.isNotBlank(), onClick = {
            naming = false
            onChange(modelId?.let { LocalEnterpriseConfigurationChange.RenameModel(it, modelName) }
                ?: LocalEnterpriseConfigurationChange.AddExampleModel(modelName))
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } },
    )
    original.models.firstOrNull { it.id == deleting }?.let { model -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(stringResource(R.string.confirm_delete)) },
        text = { Text(model.name) },
        confirmButton = { TextButton(enabled = !busy, onClick = { deleting = null; onChange(LocalEnterpriseConfigurationChange.DeleteModel(model.id)) }) {
            Text(stringResource(R.string.delete))
        } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
    ) }
}

@Composable
private fun ConfigurationToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
