package net.weero.measix.pilot.ui.components.ai

import me.rerere.common.configuration.ConfigurationReference
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.produceState
import me.rerere.ai.provider.ProviderSetting
import androidx.compose.runtime.remember
import org.koin.compose.koinInject
import net.weero.measix.pilot.service.ProviderBalanceUiState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoneyBag02
import net.weero.measix.pilot.service.ProviderSettingsApplicationService
import net.weero.measix.pilot.R
import net.weero.measix.pilot.utils.toDp

@Composable
fun ProviderBalanceText(
    providerId: ConfigurationReference.User,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    val service: ProviderSettingsApplicationService = koinInject()
    val balance by remember(service, providerId) { service.observeBalance(providerId) }
        .collectAsStateWithLifecycle(initialValue = ProviderBalanceUiState.Hidden)
    ProviderBalanceValue(balance, modifier, style, color)
}

@Composable
fun ProviderBalancePreview(
    draft: ProviderSetting,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val service: ProviderSettingsApplicationService = koinInject()
    val balance by produceState<ProviderBalanceUiState>(ProviderBalanceUiState.Hidden, draft) {
        value = ProviderBalanceUiState.Loading
        value = service.previewBalance(draft)
    }
    ProviderBalanceValue(balance, modifier, style, color)
}

@Composable
private fun ProviderBalanceValue(
    balance: ProviderBalanceUiState,
    modifier: Modifier,
    style: TextStyle,
    color: Color,
) {
    val value = when (val state = balance) {
        ProviderBalanceUiState.Hidden -> return
        is ProviderBalanceUiState.Available -> state.value
        ProviderBalanceUiState.Unavailable -> androidx.compose.ui.res.stringResource(R.string.provider_balance_unavailable)
        ProviderBalanceUiState.Loading -> "~"
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = HugeIcons.MoneyBag02,
            contentDescription = null,
            modifier = Modifier.size(style.fontSize.toDp()),
            tint = color.takeOrElse { LocalContentColor.current }
        )
        Text(
            text = value,
            style = style,
            maxLines = 1,
            color = color
        )
    }
}
