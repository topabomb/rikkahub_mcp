package net.weero.measix.pilot.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import net.weero.measix.pilot.ui.context.LocalTTSState
import net.weero.measix.pilot.ui.hooks.CustomTtsState
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import net.weero.measix.pilot.R
import androidx.compose.ui.res.stringResource
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Avatar
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun TTSController() {
    val context = LocalContext.current
    val ttsState = LocalTTSState.current

    val isSpeaking by ttsState.isSpeaking.collectAsState()
    val activeSource by ttsState.activeSource.collectAsState()
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isSpeaking) {
        if (isSpeaking) {
            // 如果开启，显示悬浮窗
            isVisible = true
        }
    }

    FloatingWindow(
        tag = "tts_controller",
        visibility = isVisible
    ) {
        val playbackState by ttsState.playbackState.collectAsState()
        var expand by remember { mutableStateOf(false) }
        val stopDesc = stringResource(R.string.tts_controller_stop)
        val expandDesc = stringResource(R.string.tts_controller_expand)
        val fastForwardDesc = stringResource(R.string.tts_controller_fast_forward)
        val playDesc = stringResource(R.string.tts_controller_play)
        val pauseDesc = stringResource(R.string.tts_controller_pause)
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.padding(8.dp),
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 设计文档 §7.5：Target 播放时显示 Target 小头像
                // 头像按 Assistant ID 实时解析，Target 已删除或解析失败时显示默认头像
                if (activeSource?.type == net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource.SourceType.SUB_ASSISTANT) {
                    SubAssistantAvatarBadge(
                        assistantId = activeSource?.assistantId,
                        assistantName = activeSource?.assistantName ?: "",
                    )
                }

                PlayPauseButton(playbackState = playbackState, ttsState = ttsState, playDesc = playDesc, pauseDesc = pauseDesc)

                IconButton(
                    onClick = {
                        ttsState.stop()
                        isVisible = false
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stopDesc,
                    )
                }

                AnimatedVisibility(expand) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpeedButton(playbackState, ttsState)

                        FastForwardButton(ttsState = ttsState, contentDescription = fastForwardDesc)
                    }
                }

                IconButton(
                    onClick = {
                        expand = !expand
                    }
                ) {
                    Icon(
                        imageVector = if (expand) HugeIcons.ArrowLeft01 else HugeIcons.ArrowRight01,
                        contentDescription = expandDesc,
                    )
                }
            }
        }
    }
}

/**
 * 子助手播放时显示的小头像徽章。
 * 设计文档 §7.5：头像按 Assistant ID 实时解析，Target 已删除或解析失败时显示默认头像。
 */
@Composable
private fun SubAssistantAvatarBadge(
    assistantId: Uuid?,
    assistantName: String,
) {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsState()
    // 按 ID 实时解析 Assistant 的 avatar，Assistant 已删除时 fallback 到 Dummy
    val avatar = remember(assistantId, settings.assistants) {
        assistantId?.let { id ->
            settings.assistants.find { it.id == id }?.avatar
        } ?: Avatar.Dummy
    }
    val displayName = remember(assistantId, settings.assistants, assistantName) {
        // 优先用 name snapshot，若 Assistant 仍存在则用最新 name
        assistantId?.let { id ->
            settings.assistants.find { it.id == id }?.name
        } ?: assistantName
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.padding(2.dp),
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 用一个小尺寸 TextAvatar 显示首字符作为 fallback，
            // 如果 Assistant 有图片头像，这里仍保持简洁首字符展示
            // （控制条空间有限，不加载大图）
            Text(
                text = displayName.take(1).ifEmpty { "?" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun FastForwardButton(ttsState: CustomTtsState, contentDescription: String) {
    IconButton(
        onClick = {
            ttsState.fastForward(5000)
        }
    ) {
        Icon(
            imageVector = HugeIcons.Forward02,
            contentDescription = contentDescription,
        )
    }
}

@Composable
private fun PlayPauseButton(
    playbackState: PlaybackState,
    ttsState: CustomTtsState,
    playDesc: String,
    pauseDesc: String,
) {
    FilledTonalIconButton(
        onClick = {
            when (playbackState.status) {
                PlaybackStatus.Playing -> {
                    ttsState.pause()
                }

                else -> {
                    ttsState.resume()
                }
            }
        },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Icon(
            imageVector = if (playbackState.status == PlaybackStatus.Playing) HugeIcons.Pause else HugeIcons.Play,
            contentDescription = if (playbackState.status == PlaybackStatus.Playing) pauseDesc else playDesc,
        )
        if (playbackState.status == PlaybackStatus.Playing || playbackState.status == PlaybackStatus.Buffering || playbackState.status == PlaybackStatus.Paused) {
            CircularProgressIndicator(
                progress = {
                    if (playbackState.status == PlaybackStatus.Playing) {
                        playbackState.positionMs.toFloat() / playbackState.durationMs
                    } else {
                        0f
                    }
                },
                color = MaterialTheme.colorScheme.tertiary,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
            CircularProgressIndicator(
                progress = {
                    if (playbackState.status == PlaybackStatus.Playing) {
                        playbackState.currentChunkIndex.toFloat() / playbackState.totalChunks
                    } else {
                        0f
                    }
                },
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(2.dp),
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
        }
    }
}

@Composable
private fun SpeedButton(
    playbackState: PlaybackState,
    ttsState: CustomTtsState
) {
    TextButton(
        onClick = {
            when (playbackState.speed) {
                0.8f -> {
                    ttsState.setSpeed(1.0f)
                }

                1.0f -> {
                    ttsState.setSpeed(1.2f)
                }

                1.2f -> {
                    ttsState.setSpeed(1.5f)
                }

                1.5f -> {
                    ttsState.setSpeed(0.8f)
                }

                else -> {
                    ttsState.setSpeed(1.0f)
                }
            }
        }
    ) {
        Text(text = "x${"%.1f".format(playbackState.speed)}")
    }
}
