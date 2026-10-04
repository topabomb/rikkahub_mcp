package net.weero.measix.pilot.ui.components.files

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import me.rerere.tts.model.PlaybackStatus
import net.weero.measix.pilot.service.SpeechPlayback
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.MoreVertical
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.files.MediaPreviewSource
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import net.weero.measix.pilot.ui.components.ui.ErrorDetails
import net.weero.measix.pilot.service.ChatError

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MediaPreview(source: MediaPreviewSource, onClose: () -> Unit, onExternal: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null, actionsEnabled: Boolean = true,
    speechPlayback: SpeechPlayback? = null, status: @Composable () -> Unit = {}) {
    val currentSpeech by rememberUpdatedState(speechPlayback)
    val context = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val activity = remember(context) { context.activity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var position by rememberSaveable(source.name, source.length) { mutableLongStateOf(0L) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var playing by remember(source) { mutableStateOf(false) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    val player = remember(source, lifecycle) {
        ExoPlayer.Builder(context).setLoadControl(DefaultLoadControl.Builder()
            .setTargetBufferBytes(24 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
    }
    fun prepare() {
        player.setMediaSource(ProgressiveMediaSource.Factory(DataSource.Factory { MediaPreviewDataSource(source) })
            .createMediaSource(MediaItem.fromUri(Uri.parse("workspace-media:/content"))))
        player.seekTo(position)
        player.prepare()
    }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) currentSpeech?.pause()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                position = newPosition.positionMs.coerceAtLeast(0)
            }
            override fun onPlayerError(failure: PlaybackException) { error = failure.userVisibleDiagnostic() }
        }
        player.addListener(listener)
        prepare()
        // Opening a file prepares it; starting playback is an explicit user action.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                position = player.currentPosition.coerceAtLeast(0)
                player.pause()
                player.stop()
            } else if (event == Lifecycle.Event.ON_START && player.playbackState == Player.STATE_IDLE && error == null) prepare()
        }
        lifecycle.addObserver(observer)
        onDispose {
            position = player.currentPosition.coerceAtLeast(0)
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, speechPlayback) {
        // Coordinate existing application commands without changing background TTS audio policy.
        speechPlayback?.playbackState?.map { it.status }?.distinctUntilChanged()?.collect { status ->
            if (status == PlaybackStatus.Playing) player.pause()
        }
    }
    LaunchedEffect(player, playing) {
        while (playing) { position = player.currentPosition.coerceAtLeast(0); delay(500) }
    }
    LaunchedEffect(player) {
        source.accessChanges.collect {
            try { source.verifyAccess() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                player.stop(); player.clearMediaItems()
                error = failure.userVisibleDiagnostic()
                return@collect
            }
            position = player.currentPosition.coerceAtLeast(0)
        }
    }
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(activity) {
        val original = activity?.requestedOrientation
        onDispose { if (original != null) activity.requestedOrientation = original }
    }
    fun rotate() {
        activity?.requestedOrientation = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    BackHandler { if (fullscreen) fullscreen = false else onClose() }
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        if (!fullscreen) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClose) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back), tint = Color.White) }
            Text(source.name, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(::rotate) { Text(stringResource(R.string.file_media_rotate), color = Color.White) }
            if (onExternal != null || onDownload != null) Box {
                IconButton({ menu = true }, enabled = actionsEnabled) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options), tint = Color.White) }
                DropdownMenu(menu, { menu = false }) {
                    onExternal?.let { DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_open_external)) }, onClick = { menu = false; it() }) }
                    onDownload?.let { DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_download)) }, onClick = { menu = false; it() }) }
                }
            }
        }
        status()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { PlayerView(it).apply {
                this.player = player
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setShowNextButton(false); setShowPreviousButton(false)
                setFullscreenButtonClickListener { fullscreen = it }
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == android.view.View.VISIBLE })
                controllerAutoShow = true
                keepScreenOn = true
            } }, update = { it.player = player; it.setFullscreenButtonState(fullscreen); it.keepScreenOn = playing },
                onRelease = { it.player = null; it.keepScreenOn = false }, modifier = Modifier.fillMaxSize())
            if (fullscreen && controlsVisible) TextButton(::rotate, Modifier.align(androidx.compose.ui.Alignment.TopEnd)) {
                Text(stringResource(R.string.file_media_rotate), color = Color.White)
            }
        }
    }
    error?.let { detail -> ErrorDetails(remember(detail) { ChatError(detail = detail) }, onDismiss = { error = null }) }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
