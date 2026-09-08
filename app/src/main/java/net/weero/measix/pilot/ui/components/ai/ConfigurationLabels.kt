package net.weero.measix.pilot.ui.components.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason

@Composable
internal fun configurationUnavailableText(reason: ConfigurationUnavailableReason): String = stringResource(when (reason) {
    ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED -> R.string.configuration_reason_policy
    ConfigurationUnavailableReason.ENTERPRISE_CONFIGURATION_NOT_READY -> R.string.configuration_reason_not_ready
    ConfigurationUnavailableReason.DIFFERENT_ENTERPRISE,
    ConfigurationUnavailableReason.ENTERPRISE_RESOURCE_IN_PERSONAL_SCOPE -> R.string.configuration_reason_other_space
    ConfigurationUnavailableReason.RESOURCE_DISABLED -> R.string.configuration_reason_disabled
    ConfigurationUnavailableReason.RESOURCE_CAPABILITY_MISMATCH -> R.string.configuration_reason_capability
    ConfigurationUnavailableReason.REFERENCE_MISSING -> R.string.configuration_reason_missing
    ConfigurationUnavailableReason.REFERENCE_AMBIGUOUS -> R.string.configuration_reason_ambiguous
})
