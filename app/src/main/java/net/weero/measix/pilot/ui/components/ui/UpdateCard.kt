package net.weero.measix.pilot.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import net.weero.measix.pilot.BuildConfig
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import net.weero.measix.pilot.ui.components.richtext.MarkdownBlock
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.hooks.useThrottle
import net.weero.measix.pilot.ui.pages.chat.ChatVM
import net.weero.measix.pilot.utils.UpdateDownload
import net.weero.measix.pilot.utils.Version
import net.weero.measix.pilot.utils.onError
import net.weero.measix.pilot.utils.onSuccess
import net.weero.measix.pilot.utils.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

@OptIn(ExperimentalTime::class)
@Composable
fun UpdateCard(vm: ChatVM) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    state.onError {
        val errorDismissed by remember { derivedStateOf { vm.updateChecker.errorDismissed.value } }
        if (!errorDismissed) {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.update_card_check_failed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = it.message ?: stringResource(R.string.update_card_unknown_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { vm.updateChecker.dismissError() }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.update_card_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    state.onSuccess { info ->
        var showDetail by remember { mutableStateOf(false) }
        val current = remember { Version(BuildConfig.VERSION_NAME) }
        val latest = remember(info) { Version(info.version) }
        // 持久化关闭：用户关掉的版本不再弹，新版本发布自动恢复提示
        val dismissedByVersion = settings.ignoredUpdateVersion == info.version
        if (latest > current && !dismissedByVersion) {
            Card(
                onClick = { showDetail = true },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Download01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.update_card_new_version_found, info.version),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(ignoredUpdateVersion = info.version)
                            )
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.update_card_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (showDetail) {
            val downloadingText = stringResource(R.string.update_card_downloading)
            val downloadHandler = useThrottle<UpdateDownload>(500) { item ->
                vm.updateChecker.downloadUpdate(context, item)
                showDetail = false
                toaster.show(downloadingText, type = ToastType.Info)
            }
            AdaptiveModal(
                onDismissRequest = { showDetail = false },
                sheetState = rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = info.version,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = Instant.parse(info.publishedAt).toJavaInstant().toLocalDateTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    MarkdownBlock(
                        content = info.changelog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    info.downloads.fastForEach { downloadItem ->
                        OutlinedCard(
                            onClick = {
                                downloadHandler(downloadItem)
                            },
                        ) {
                            ListItem(
                                supportingContent = {
                                    Text(
                                        text = downloadItem.size
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = HugeIcons.Download01,
                                        contentDescription = null
                                    )
                                }
                            ) {
                                Text(
                                    text = downloadItem.name,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
