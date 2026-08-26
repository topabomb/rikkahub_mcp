package net.weero.measix.pilot.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoneyBag02
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.weero.measix.pilot.service.ProviderSettingsApplicationService
import net.weero.measix.pilot.service.balanceRequestFingerprint
import net.weero.measix.pilot.R
import net.weero.measix.pilot.utils.toDp
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun ProviderBalanceText(
    providerSetting: ProviderSetting,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    if (!providerSetting.balanceOption.enabled || providerSetting !is ProviderSetting.OpenAI) {
        // Balance option is disabled or provider is not OpenAI type
        return
    }

    val balanceVM = koinViewModel<ProviderBalanceVM>()
    val balances by balanceVM.balances.collectAsStateWithLifecycle()
    LaunchedEffect(providerSetting) { balanceVM.request(providerSetting) }
    val value = when (val state = balances[providerSetting.id]) {
        is ProviderBalanceUiState.Available -> state.value
        ProviderBalanceUiState.Unavailable -> androidx.compose.ui.res.stringResource(R.string.provider_balance_unavailable)
        ProviderBalanceUiState.Loading,
        null,
        -> "~"
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

class ProviderBalanceVM(
    private val providerSettings: ProviderSettingsApplicationService,
) : ViewModel() {
    private val _balances = MutableStateFlow<Map<Uuid, ProviderBalanceUiState>>(emptyMap())
    val balances = _balances.asStateFlow()
    private val jobs = mutableMapOf<Uuid, Job>()
    private val revisions = mutableMapOf<Uuid, Long>()
    private val requestFingerprints = mutableMapOf<Uuid, String>()

    fun request(provider: ProviderSetting.OpenAI) {
        val providerId = provider.id
        val fingerprint = provider.balanceRequestFingerprint()
        if (requestFingerprints[providerId] == fingerprint) {
            when (_balances.value[providerId]) {
                ProviderBalanceUiState.Loading,
                is ProviderBalanceUiState.Available,
                -> return
                ProviderBalanceUiState.Unavailable,
                null,
                -> Unit
            }
        }
        val revision = revisions.getOrDefault(providerId, 0L) + 1L
        revisions[providerId] = revision
        requestFingerprints[providerId] = fingerprint
        jobs.remove(providerId)?.cancel()
        _balances.update { it + (providerId to ProviderBalanceUiState.Loading) }
        val job = viewModelScope.launch {
            val result = try {
                ProviderBalanceUiState.Available(providerSettings.getBalance(provider))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ProviderBalanceVM", "Failed to query provider balance", error)
                ProviderBalanceUiState.Unavailable
            }
            if (revisions[providerId] == revision) {
                _balances.update { it + (providerId to result) }
            }
        }
        jobs[providerId] = job
        job.invokeOnCompletion {
            if (revisions[providerId] == revision) jobs.remove(providerId)
        }
    }

}

sealed interface ProviderBalanceUiState {
    data object Loading : ProviderBalanceUiState
    data class Available(val value: String) : ProviderBalanceUiState
    data object Unavailable : ProviderBalanceUiState
}
