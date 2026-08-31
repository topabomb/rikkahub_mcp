package net.weero.measix.pilot.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import net.weero.measix.pilot.ui.context.LocalTTSState
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.hooks.CustomTtsState
import net.weero.measix.pilot.ui.hooks.subAssistantActivityRing
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import net.weero.measix.pilot.R
import androidx.compose.ui.res.stringResource
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.model.Avatar
import coil3.compose.AsyncImage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState

@Composable
fun TTSController(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    windowInsets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
    hazeState: HazeState? = null,
    hasVisibleBackground: Boolean = false,
) {
    val ttsState = LocalTTSState.current

    val isSpeaking by ttsState.isSpeaking.collectAsState()
    val activeSource by ttsState.activeSource.collectAsState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding)
            .windowInsetsPadding(windowInsets)
            .padding(8.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        AnimatedVisibility(visible = isSpeaking, enter = fadeIn(), exit = fadeOut()) {
            val playbackState by ttsState.playbackState.collectAsState()
            var expand by remember { mutableStateOf(false) }
            val stopDesc = stringResource(R.string.tts_controller_stop)
            val expandDesc = stringResource(R.string.tts_controller_expand)
            val fastForwardDesc = stringResource(R.string.tts_controller_fast_forward)
            val playDesc = stringResource(R.string.tts_controller_play)
            val pauseDesc = stringResource(R.string.tts_controller_pause)
            ChatOverlaySurface(
                shape = CircleShape,
                enableBlurEffect = LocalSettings.current.displaySetting.enableBlurEffect,
                hasVisibleBackground = hasVisibleBackground,
                hazeState = hazeState,
                modifier = Modifier.testTag("tts_controller"),
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TtsSourceAvatar(activeSource)

                    PlayPauseButton(playbackState = playbackState, ttsState = ttsState, playDesc = playDesc, pauseDesc = pauseDesc)

                    IconButton(
                        onClick = {
                            ttsState.stop()
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
}

/**
 * 仅在子助手 TTS 且开启“使用助手头像”时显示来源头像。
 * 主助手播放不显示：控制条已经属于当前会话助手。
 */
@Composable
private fun TtsSourceAvatar(
    source: TtsPlaybackSource?,
) {
    if (source?.type != TtsPlaybackSource.SourceType.SUB_ASSISTANT) {
        return
    }

    val settings = LocalSettings.current
    val assistant = remember(source.assistantId, settings.assistants) {
        source.assistantId?.let { id ->
            settings.assistants.find { it.id == id }
        }
    }
    if (assistant?.useAssistantAvatar != true) return
    val resolvedAssistant = assistant // smart-cast to non-null
    val avatar = resolvedAssistant.avatar
    val displayName = resolvedAssistant.name

    val ringColor = MaterialTheme.colorScheme.primary
    val avatarDescription = displayName.ifBlank {
        stringResource(R.string.assistant_page_sub_assistant_tag)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .subAssistantActivityRing(color = ringColor)
            .semantics { contentDescription = avatarDescription },
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (avatar) {
                    is Avatar.Image -> {
                        AsyncImage(
                            model = avatar.url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    is Avatar.Emoji -> {
                        Text(
                            text = avatar.content,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 16.sp,
                                maxFontSize = 32.sp,
                            ),
                            lineHeight = 0.8.em,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                    is Avatar.Dummy -> {
                        val (fromColor, toColor) = remember(displayName) {
                            proceduralAvatarColors(displayName.ifBlank { "?" })
                        }
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(fromColor, toColor),
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, size.height),
                                ),
                            )
                        }
                    }
                }
            }
        }
        SubAssistantAvatarMark(modifier = Modifier.align(Alignment.BottomEnd))
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
            if (playbackState.status.shouldPauseFromToolbar()) {
                ttsState.pause()
            } else {
                ttsState.resume()
            }
        },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Icon(
            imageVector = if (playbackState.status.shouldPauseFromToolbar()) HugeIcons.Pause else HugeIcons.Play,
            contentDescription = if (playbackState.status.shouldPauseFromToolbar()) pauseDesc else playDesc,
        )
        if (playbackState.status == PlaybackStatus.Playing || playbackState.status == PlaybackStatus.Buffering || playbackState.status == PlaybackStatus.Paused) {
            CircularProgressIndicator(
                progress = {
                    playbackState.toolbarAudioProgress()
                },
                color = MaterialTheme.colorScheme.tertiary,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
            CircularProgressIndicator(
                progress = {
                    playbackState.toolbarChunkProgress()
                },
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(2.dp),
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
        }
    }
}

internal fun PlaybackStatus.shouldPauseFromToolbar(): Boolean =
    this == PlaybackStatus.Playing || this == PlaybackStatus.Buffering

internal fun PlaybackState.toolbarAudioProgress(): Float =
    if ((status == PlaybackStatus.Playing || status == PlaybackStatus.Paused) && durationMs > 0L) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

internal fun PlaybackState.toolbarChunkProgress(): Float =
    if ((status == PlaybackStatus.Playing || status == PlaybackStatus.Paused) && totalChunks > 0) {
        (currentChunkIndex.toFloat() / totalChunks).coerceIn(0f, 1f)
    } else {
        0f
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
