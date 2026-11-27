package com.anigooli.player

import android.app.PictureInPictureParams
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.PlayerView
import com.anigooli.streaming.AudioTrack
import com.anigooli.streaming.StreamSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private const val SEEK_DRAG_WINDOW_MS = 90_000f
private const val DOUBLE_TAP_SEEK_MS = 10_000L
private const val CONTROLS_AUTO_HIDE_MS = 3_500L
private const val POSITION_POLL_MS = 100L
private const val SAVE_PROGRESS_EVERY_TICKS = 50
private const val PIP_RATIO_NUM = 16
private const val PIP_RATIO_DEN = 9
private const val MIN_RAM_MS = 15_000
private const val REBUFFER_MIN_MS = 1_500
private const val REBUFFER_AFTER_SEEK_MS = 3_000
private const val FAST_FORWARD_HOLD_SPEED = 2.0f
private const val HALF_SCREEN_RATIO = 0.5f
private const val MAX_PINCH_ZOOM = 2.5f
private const val MIN_PINCH_ZOOM = 1.0f
private const val PERCENT_MAX = 100
private const val MAX_SYSTEM_BRIGHTNESS = 255f
private const val DEFAULT_SYSTEM_BRIGHTNESS = 128
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private enum class GestureHudType { SEEK, VOLUME, BRIGHTNESS, SPEED }

/**
 * Fullscreen landscape Media3 gesture player supporting touch-drag seek/volume/brightness,
 * double-tap ±10s, long-press 2x speed, pinch-zoom, quality selection, per-anime JP/Dub + Subtitles,
 * live single-slot pre-buffer progress, pre-buffer-reusing Download, PiP, and External MPV launch.
 */
@AndroidEntryPoint(ComponentActivity::class)
class PlayerActivity : Hilt_PlayerActivity() {

    private val viewModel: PlayerViewModel by viewModels()
    private var initialBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var isInPipModeState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        initialBrightness = window.attributes.screenBrightness

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                PlayerScreen(
                    state = state,
                    isInPipMode = isInPipModeState.value,
                    viewModel = viewModel,
                    onAdjustBrightness = ::setWindowBrightness,
                    onEnterPip = ::enterPip,
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState.value = isInPictureInPictureMode
    }

    override fun onDestroy() {
        setWindowBrightness(initialBrightness)
        super.onDestroy()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setWindowBrightness(brightness: Float) {
        val lp = window.attributes
        lp.screenBrightness = brightness
        window.attributes = lp
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(PIP_RATIO_NUM, PIP_RATIO_DEN))
                .build()
            runCatching { enterPictureInPictureMode(params) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "CognitiveComplexMethod")
private fun PlayerScreen(
    state: PlayerUiState,
    isInPipMode: Boolean,
    viewModel: PlayerViewModel,
    onAdjustBrightness: (Float) -> Unit,
    onEnterPip: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.ensurePreBufferRunning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val exoPlayer = remember {
        val ramMb = viewModel.preBufferManager.recommendedRamBufferMb()
        val ramMs = (ramMb * 1024).coerceIn(30_000, 120_000)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setTargetBufferBytes(ramMb * 1024 * 1024)
            .setBufferDurationsMs(MIN_RAM_MS, ramMs, REBUFFER_MIN_MS, REBUFFER_AFTER_SEEK_MS)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
    }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedPosMs by remember { mutableLongStateOf(0L) }
    var pendingActiveWatchMs by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var pinchScale by remember { mutableFloatStateOf(1f) }

    var showQualitySheet by remember { mutableStateOf(false) }
    var showAudioSubSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showSubtitleStylePanel by remember { mutableStateOf(false) }
    var showDownloadSheet by remember { mutableStateOf(false) }

    var activeHud by remember { mutableStateOf<GestureHudType?>(null) }
    var seekPreviewMs by remember { mutableLongStateOf(0L) }
    var seekDeltaMs by remember { mutableLongStateOf(0L) }
    var volumeLevel by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
    }
    var brightnessLevel by remember {
        val winBright = (context as? ComponentActivity)?.window?.attributes?.screenBrightness ?: -1f
        val initial = if (winBright in 0f..1f) {
            winBright
        } else {
            runCatching {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    DEFAULT_SYSTEM_BRIGHTNESS,
                ) / MAX_SYSTEM_BRIGHTNESS
            }.getOrDefault(HALF_SCREEN_RATIO).coerceIn(0.01f, 1f)
        }
        mutableFloatStateOf(initial)
    }
    var tapIndicatorText by remember { mutableStateOf<String?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize(1, 1)) }
    var playerErrorText by remember { mutableStateOf<String?>(null) }

    // Wire player lifecycle & listener
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    playerErrorText = null
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                playerErrorText = error.localizedMessage ?: "Playback failed"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            viewModel.savePlaybackProgress(
                positionMs = exoPlayer.currentPosition,
                durationMs = exoPlayer.duration.coerceAtLeast(0L),
                activeWatchDeltaMs = pendingActiveWatchMs,
            )
            pendingActiveWatchMs = 0L
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Prepare media source whenever stream source or local file changes
    LaunchedEffect(state.selectedSource, state.localVideoPath, state.isLocalFile) {
        playerErrorText = null
        if (state.isLocalFile) {
            val path = state.localVideoPath ?: return@LaunchedEffect
            val dataFactory = DefaultDataSource.Factory(context)
            val finalSource = ProgressiveMediaSource.Factory(dataFactory)
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(File(path))))
            exoPlayer.setMediaSource(finalSource)
            exoPlayer.prepare()
            if (state.targetSeekPositionMs > 0L) exoPlayer.seekTo(state.targetSeekPositionMs)
            exoPlayer.playWhenReady = true
        } else {
            val source = state.selectedSource ?: return@LaunchedEffect
            val dataFactory = viewModel.preBufferManager.createDataSourceFactory(
                userAgent = state.userAgent,
                referer = source.referer,
                origin = source.origin,
            )
            val builder = MediaItem.Builder().setUri(source.url.toUri())
            if (source.isHls) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            val videoSource = DefaultMediaSourceFactory(dataFactory).createMediaSource(builder.build())
            val finalSource = if (!source.audioUrl.isNullOrBlank()) {
                val audioUrl = source.audioUrl!!
                val audioItem = MediaItem.fromUri(audioUrl)
                val audioSource = if (audioUrl.substringBefore('?').endsWith(".m3u8", true) || source.isHls) {
                    HlsMediaSource.Factory(dataFactory).createMediaSource(audioItem)
                } else {
                    ProgressiveMediaSource.Factory(dataFactory).createMediaSource(audioItem)
                }
                MergingMediaSource(true, true, videoSource, audioSource)
            } else {
                videoSource
            }
            exoPlayer.setMediaSource(finalSource)
            exoPlayer.prepare()
            if (state.targetSeekPositionMs > 0L) exoPlayer.seekTo(state.targetSeekPositionMs)
            exoPlayer.playWhenReady = true
        }
    }

    // Apply per-anime subtitle toggle immediately without reloading video
    LaunchedEffect(state.subtitlesEnabled, state.selectedSource, state.localVideoPath) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !state.subtitlesEnabled)
            .setPreferredTextLanguage("en")
            .build()
    }

    // Apply playback speed
    LaunchedEffect(state.playbackSpeed) {
        exoPlayer.setPlaybackSpeed(state.playbackSpeed)
    }

    // Poll position and periodically save resume timestamp & active watch time
    LaunchedEffect(exoPlayer) {
        var tick = 0
        var lastPollMs = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val elapsed = (now - lastPollMs).coerceIn(0L, POSITION_POLL_MS * 3)
            lastPollMs = now

            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            bufferedPosMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            if (exoPlayer.isPlaying) {
                val speed = exoPlayer.playbackParameters.speed.coerceAtLeast(0.25f)
                pendingActiveWatchMs += (elapsed * speed).toLong()
            }
            tick++
            if (tick % SAVE_PROGRESS_EVERY_TICKS == 0 && exoPlayer.isPlaying) {
                viewModel.savePlaybackProgress(
                    positionMs = currentPosMs,
                    durationMs = durationMs,
                    activeWatchDeltaMs = pendingActiveWatchMs,
                )
                pendingActiveWatchMs = 0L
            }
            delay(POSITION_POLL_MS)
        }
    }

    // Auto-hide controls after inactivity
    LaunchedEffect(showControls, isPlaying, isLocked) {
        if (showControls && isPlaying && !isLocked) {
            delay(CONTROLS_AUTO_HIDE_MS)
            showControls = false
        }
    }

    // Auto-clear transient banners & double-tap pills
    LaunchedEffect(state.statusBanner) {
        if (state.statusBanner != null) {
            delay(2_200L)
            viewModel.clearStatusBanner()
        }
    }
    LaunchedEffect(tapIndicatorText) {
        if (tapIndicatorText != null) {
            delay(800L)
            tapIndicatorText = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewportSize = it },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    subtitleView?.setApplyEmbeddedStyles(true)
                    subtitleView?.visibility = View.VISIBLE
                    player = exoPlayer
                }
            },
            update = { view ->
                view.resizeMode = state.aspectMode.media3ResizeMode
                view.keepScreenOn = isPlaying
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pinchScale
                    scaleY = pinchScale
                },
        )

        // Touch gesture overlay (disabled when locked or in PiP)
        if (!isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isLocked) {
                        if (isLocked) {
                            detectTapGestures(onTap = { showControls = !showControls })
                        } else {
                            detectTapGestures(
                                onTap = { showControls = !showControls },
                                onDoubleTap = { offset ->
                                    val width = viewportSize.width.coerceAtLeast(1)
                                    val delta = if (offset.x < width / 2f) -DOUBLE_TAP_SEEK_MS else DOUBLE_TAP_SEEK_MS
                                    val nextPos = (exoPlayer.currentPosition + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
                                    exoPlayer.seekTo(nextPos)
                                    currentPosMs = nextPos
                                    tapIndicatorText = if (delta < 0) "-10s" else "+10s"
                                },
                                onLongPress = {
                                    activeHud = GestureHudType.SPEED
                                    exoPlayer.setPlaybackSpeed(FAST_FORWARD_HOLD_SPEED)
                                },
                                onPress = {
                                    tryAwaitRelease()
                                    if (activeHud == GestureHudType.SPEED) {
                                        activeHud = null
                                        exoPlayer.setPlaybackSpeed(state.playbackSpeed)
                                    }
                                },
                            )
                        }
                    }
                    .then(
                        if (!isLocked) {
                            Modifier
                                .pointerInput(durationMs) {
                                    var dragType: GestureHudType? = null
                                    var dragStartX = 0f
                                    var startPosMs = 0L
                                    var accumulatedX = 0f
                                    var accumulatedVol = volumeLevel
                                    var accumulatedBright = brightnessLevel

                                    detectDragGestures(
                                        onDragStart = { startOffset ->
                                            dragType = null
                                            dragStartX = startOffset.x
                                            accumulatedX = 0f
                                            startPosMs = exoPlayer.currentPosition
                                            accumulatedVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                                            accumulatedBright = brightnessLevel
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val width = viewportSize.width.coerceAtLeast(1).toFloat()
                                            val height = viewportSize.height.coerceAtLeast(1).toFloat()
                                            if (dragType == null) {
                                                dragType = if (kotlin.math.abs(dragAmount.x) >= kotlin.math.abs(dragAmount.y)) {
                                                    GestureHudType.SEEK
                                                } else if (dragStartX < width * HALF_SCREEN_RATIO) {
                                                    GestureHudType.BRIGHTNESS
                                                } else {
                                                    GestureHudType.VOLUME
                                                }
                                            }
                                            activeHud = dragType
                                            when (dragType) {
                                                GestureHudType.SEEK -> {
                                                    accumulatedX += dragAmount.x
                                                    val delta = ((accumulatedX / width) * SEEK_DRAG_WINDOW_MS).toLong()
                                                    seekDeltaMs = delta
                                                    seekPreviewMs = (startPosMs + delta).coerceIn(0L, durationMs.coerceAtLeast(1L))
                                                }
                                                GestureHudType.BRIGHTNESS -> {
                                                    accumulatedBright = (accumulatedBright - dragAmount.y / height).coerceIn(0.01f, 1f)
                                                    brightnessLevel = accumulatedBright
                                                    onAdjustBrightness(accumulatedBright)
                                                }
                                                GestureHudType.VOLUME -> {
                                                    accumulatedVol = (accumulatedVol - dragAmount.y / height).coerceIn(0f, 1f)
                                                    volumeLevel = accumulatedVol
                                                    val step = (accumulatedVol * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
                                                }
                                                else -> {}
                                            }
                                        },
                                        onDragEnd = {
                                            if (dragType == GestureHudType.SEEK) {
                                                exoPlayer.seekTo(seekPreviewMs)
                                                currentPosMs = seekPreviewMs
                                            }
                                            dragType = null
                                            activeHud = null
                                        },
                                        onDragCancel = {
                                            dragType = null
                                            activeHud = null
                                        },
                                    )
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        if (zoom != 1f) {
                                            pinchScale = (pinchScale * zoom).coerceIn(MIN_PINCH_ZOOM, MAX_PINCH_ZOOM)
                                        }
                                    }
                                }
                        } else Modifier
                    ),
            )
        }

        // Loading / Buffering spinner
        if (isBuffering || state.isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Center Gesture HUD feedback
        activeHud?.let { hud ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.78f),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    val label = when (hud) {
                        GestureHudType.SEEK -> {
                            val sign = if (seekDeltaMs >= 0) "+" else "-"
                            "${formatMs(seekPreviewMs)}  ($sign${formatMs(kotlin.math.abs(seekDeltaMs))})"
                        }
                        GestureHudType.BRIGHTNESS -> "Brightness ${(brightnessLevel * PERCENT_MAX).roundToInt()}%"
                        GestureHudType.VOLUME -> "Volume ${(volumeLevel * PERCENT_MAX).roundToInt()}%"
                        GestureHudType.SPEED -> "2.0x Speed >>"
                    }
                    Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (hud == GestureHudType.BRIGHTNESS || hud == GestureHudType.VOLUME) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { if (hud == GestureHudType.BRIGHTNESS) brightnessLevel else volumeLevel },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier
                                .width(140.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }

        // Double-tap seek indicator
        tapIndicatorText?.let { text ->
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(if (text.startsWith("-")) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 48.dp),
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }

        // Transient status banner (e.g. "Quality: 1080p", "Download started")
        state.statusBanner?.let { banner ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp),
            ) {
                Text(
                    text = banner,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // Error overlay
        (state.errorMessage ?: playerErrorText)?.let { err ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // Compose subtitle overlay (renders parsed VTT/SRT/ASS cues + live style preview)
        if (((state.subtitlesEnabled && state.subtitleCues.isNotEmpty()) || showSubtitleStylePanel) && !isInPipMode) {
            val activeLines = remember(currentPosMs, state.subtitleCues, state.subtitlesEnabled) {
                if (state.subtitlesEnabled) {
                    SubtitleParser.activeCueTexts(state.subtitleCues, currentPosMs)
                } else {
                    emptyList()
                }
            }
            SubtitleOverlay(
                lines = activeLines,
                style = state.subtitleStyle,
                showControls = showControls,
                isPreviewMode = showSubtitleStylePanel,
            )
        }

        // Controls Overlay
        AnimatedVisibility(
            visible = showControls && !isInPipMode && !showSubtitleStylePanel,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (isLocked) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .clip(CircleShape)
                            .clickable { isLocked = false },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = "Unlock", tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("Unlock", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            } else {
                PlayerControlsOverlay(
                    state = state,
                    isPlaying = isPlaying,
                    currentPosMs = currentPosMs,
                    durationMs = durationMs,
                    bufferedPosMs = bufferedPosMs,
                    onBack = onBack,
                    onPlayPause = {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    },
                    onSeekBy = { delta ->
                        val next = (exoPlayer.currentPosition + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
                        exoPlayer.seekTo(next)
                        currentPosMs = next
                    },
                    onSeekTo = { target ->
                        exoPlayer.seekTo(target)
                        currentPosMs = target
                    },
                    onPrevEpisode = {
                        val idx = state.allEpisodes.indexOf(state.episode)
                        if (idx > 0) {
                            viewModel.savePlaybackProgress(
                                positionMs = exoPlayer.currentPosition,
                                durationMs = durationMs,
                                activeWatchDeltaMs = pendingActiveWatchMs,
                            )
                            pendingActiveWatchMs = 0L
                            viewModel.navigateEpisode(state.allEpisodes[idx - 1])
                        }
                    },
                    onNextEpisode = {
                        val idx = state.allEpisodes.indexOf(state.episode)
                        if (idx in 0 until state.allEpisodes.lastIndex) {
                            viewModel.savePlaybackProgress(
                                positionMs = exoPlayer.currentPosition,
                                durationMs = durationMs,
                                activeWatchDeltaMs = pendingActiveWatchMs,
                            )
                            pendingActiveWatchMs = 0L
                            viewModel.navigateEpisode(state.allEpisodes[idx + 1])
                        }
                    },
                    onLock = { isLocked = true },
                    onPip = onEnterPip,
                    onDownload = { showDownloadSheet = true },
                    onOpenMpv = {
                        exoPlayer.pause()
                        if (!viewModel.openInMpv()) {
                            Toast.makeText(context, R.string.player_mpv_missing, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenQuality = { showQualitySheet = true },
                    onOpenAudioSubs = { showAudioSubSheet = true },
                    onOpenSubtitleStyle = { showSubtitleStylePanel = true },
                    onOpenSpeed = { showSpeedSheet = true },
                    onCycleAspect = {
                        pinchScale = 1f
                        viewModel.cycleAspectMode()
                    },
                )
            }
        }

        if (showSubtitleStylePanel && !isInPipMode) {
            SubtitleStylePanel(
                style = state.subtitleStyle,
                onFontFamilyChange = viewModel::setSubtitleFontFamily,
                onFontSizeChange = viewModel::setSubtitleFontSize,
                onVerticalPositionChange = viewModel::setSubtitleVerticalPosition,
                onWidthFractionChange = viewModel::setSubtitleWidthFraction,
                onBackgroundEnabledChange = viewModel::setSubtitleBackgroundEnabled,
                onBackgroundColorChange = viewModel::setSubtitleBackgroundColor,
                onBackgroundOpacityChange = viewModel::setSubtitleBackgroundOpacity,
                onReset = viewModel::resetSubtitleStyle,
                onDismiss = { showSubtitleStylePanel = false },
            )
        }
    }

    if (showDownloadSheet) {
        val hasSub = !state.selectedSource?.subtitleUrl.isNullOrBlank() || state.subtitleCues.isNotEmpty()
        DownloadOptionsSheet(
            hasSubtitle = hasSub,
            onDownloadBoth = {
                showDownloadSheet = false
                viewModel.downloadCurrentEpisode(includeSubtitle = true)
            },
            onDownloadVideoOnly = {
                showDownloadSheet = false
                viewModel.downloadCurrentEpisode(includeSubtitle = false)
            },
            onDownloadSubtitleOnly = {
                showDownloadSheet = false
                viewModel.downloadSubtitleOnly()
            },
            onDismiss = { showDownloadSheet = false },
        )
    }

    if (showQualitySheet) {
        QualityPickerSheet(
            sources = state.sources,
            selected = state.selectedSource,
            onSelect = { src ->
                showQualitySheet = false
                viewModel.selectQuality(src, exoPlayer.currentPosition)
            },
            onDismiss = { showQualitySheet = false },
        )
    }

    if (showAudioSubSheet) {
        AudioSubPickerSheet(
            audioTrack = state.audioTrack,
            subtitlesEnabled = state.subtitlesEnabled,
            isLocalFile = state.isLocalFile,
            onSelectAudio = { track ->
                showAudioSubSheet = false
                viewModel.switchAudioTrack(track, exoPlayer.currentPosition)
            },
            onToggleSubtitles = viewModel::setSubtitlesEnabled,
            onOpenSubtitleStyle = {
                showAudioSubSheet = false
                showSubtitleStylePanel = true
            },
            onDismiss = { showAudioSubSheet = false },
        )
    }

    if (showSpeedSheet) {
        SpeedPickerSheet(
            currentSpeed = state.playbackSpeed,
            onSelectSpeed = { spd ->
                viewModel.setPlaybackSpeed(spd)
                showSpeedSheet = false
            },
            onDismiss = { showSpeedSheet = false },
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun PlayerControlsOverlay(
    state: PlayerUiState,
    isPlaying: Boolean,
    currentPosMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onLock: () -> Unit,
    onPip: () -> Unit,
    onDownload: () -> Unit,
    onOpenMpv: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenAudioSubs: () -> Unit,
    onOpenSubtitleStyle: () -> Unit,
    onOpenSpeed: () -> Unit,
    onCycleAspect: () -> Unit,
) {
    val epIdx = state.allEpisodes.indexOf(state.episode)
    val hasPrev = epIdx > 0
    val hasNext = epIdx in 0 until state.allEpisodes.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.72f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.78f),
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        // Top bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${state.animeTitle} — Ep ${state.episode}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!state.isLocalFile && state.preBufferState.capBytes > 0L) {
                    val pct = if (state.preBufferState.isComplete) {
                        PERCENT_MAX
                    } else {
                        (state.preBufferState.progressFraction * PERCENT_MAX).roundToInt().coerceIn(0, PERCENT_MAX)
                    }
                    val suffix = if (state.preBufferState.isComplete) {
                        "Pre-buffered: 100%"
                    } else {
                        "Pre-buffer: $pct%"
                    }
                    Text(
                        text = suffix,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            if (!state.isLocalFile) {
                ControlPillButton(label = "Download", onClick = onDownload)
                Spacer(Modifier.width(6.dp))
            }
            ControlPillButton(label = "MPV", onClick = onOpenMpv)
            Spacer(Modifier.width(6.dp))
            ControlPillButton(label = "PiP", onClick = onPip)
            IconButton(onClick = onLock) {
                Icon(Icons.Rounded.Lock, contentDescription = "Lock screen", tint = Color.White)
            }
        }

        // Center playback buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.align(Alignment.Center),
        ) {
            if (hasPrev) {
                ControlPillButton(label = "Prev", onClick = onPrevEpisode)
            }
            ControlPillButton(label = "-10s", onClick = { onSeekBy(-DOUBLE_TAP_SEEK_MS) })
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.22f),
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPlayPause),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isPlaying) {
                        Text("II", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }
            }
            ControlPillButton(label = "+10s", onClick = { onSeekBy(DOUBLE_TAP_SEEK_MS) })
            if (hasNext) {
                ControlPillButton(label = "Next", onClick = onNextEpisode)
            }
        }

        // Bottom bar: timeline + quick selectors
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(formatMs(currentPosMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(10.dp))
                VideoSeekBar(
                    currentPosMs = currentPosMs,
                    durationMs = durationMs,
                    bufferedPosMs = bufferedPosMs,
                    preBufferFraction = state.preBufferState.progressFraction,
                    showPreBuffer = !state.isLocalFile && state.preBufferState.capBytes > 0L,
                    onSeekTo = onSeekTo,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(formatMs(durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!state.isLocalFile) {
                    ControlPillButton(
                        label = state.selectedSource?.qualityLabel ?: "Quality",
                        onClick = onOpenQuality,
                    )
                }
                val audioLabel = if (state.audioTrack == AudioTrack.ENGLISH_DUB) "EN Dub" else "Japanese"
                val subLabel = if (state.subtitlesEnabled) "Sub ON" else "Sub OFF"
                ControlPillButton(
                    label = "$audioLabel • $subLabel",
                    onClick = onOpenAudioSubs,
                )
                ControlPillButton(
                    label = "Sub Style",
                    onClick = onOpenSubtitleStyle,
                )
                ControlPillButton(
                    label = "${state.playbackSpeed}x",
                    onClick = onOpenSpeed,
                )
                ControlPillButton(
                    label = state.aspectMode.label,
                    onClick = onCycleAspect,
                )
            }
        }
    }
}

@Composable
private fun VideoSeekBar(
    currentPosMs: Long,
    durationMs: Long,
    bufferedPosMs: Long,
    preBufferFraction: Float,
    showPreBuffer: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackBgColor = Color.White.copy(alpha = 0.20f)
    val preBufferColor = Color.White.copy(alpha = 0.55f)

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val currentFraction = if (durationMs > 0L) {
        (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val displayFraction = if (isDragging) dragFraction else currentFraction
    val exoBufFraction = if (durationMs > 0L) {
        (bufferedPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val preBufEnd = if (showPreBuffer) preBufferFraction.coerceIn(0f, 1f) else 0f

    Canvas(
        modifier = modifier
            .height(28.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0L && size.width > 0) {
                        val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeekTo((frac * durationMs).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (durationMs > 0L && size.width > 0) {
                            isDragging = true
                            dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeekTo((dragFraction * durationMs).toLong())
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (durationMs > 0L && size.width > 0) {
                            dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeekTo((dragFraction * durationMs).toLong())
                        }
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                )
            },
    ) {
        val trackHeight = 3.dp.toPx()
        val centerY = size.height / 2f
        val trackTop = centerY - trackHeight / 2f
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        val width = size.width

        // Unbuffered track background
        drawRoundRect(
            color = trackBgColor,
            topLeft = Offset(0f, trackTop),
            size = Size(width, trackHeight),
            cornerRadius = radius,
        )

        // Pre-buffered sections (light gray): disk pre-buffer + ExoPlayer RAM buffer ahead
        if (preBufEnd > 0f) {
            drawRoundRect(
                color = preBufferColor,
                topLeft = Offset(0f, trackTop),
                size = Size(width * preBufEnd, trackHeight),
                cornerRadius = radius,
            )
        }
        if (exoBufFraction > currentFraction) {
            val startX = width * currentFraction
            val bufWidth = (width * (exoBufFraction - currentFraction)).coerceAtLeast(0f)
            drawRoundRect(
                color = preBufferColor,
                topLeft = Offset(startX, trackTop),
                size = Size(bufWidth, trackHeight),
                cornerRadius = radius,
            )
        }

        // Watched section (thin purple bar) connected directly to the circle thumb with no gap
        val watchedWidth = width * displayFraction
        if (watchedWidth > 0f) {
            drawRoundRect(
                color = primaryColor,
                topLeft = Offset(0f, trackTop),
                size = Size(watchedWidth, trackHeight),
                cornerRadius = radius,
            )
        }

        // Bold filled circle thumb (no vertical line, no gap)
        val thumbRadius = if (isDragging) 8.dp.toPx() else 6.5.dp.toPx()
        val thumbX = watchedWidth.coerceIn(thumbRadius, (width - thumbRadius).coerceAtLeast(thumbRadius))
        drawCircle(
            color = primaryColor,
            radius = thumbRadius,
            center = Offset(thumbX, centerY),
        )
    }
}

@Composable
private fun ControlPillButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.18f),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualityPickerSheet(
    sources: List<StreamSource>,
    selected: StreamSource?,
    onSelect: (StreamSource) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Video Quality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(sources) { src ->
                    val isSelected = src == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(src) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text("${src.qualityLabel} (${src.serverName})")
                        if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSubPickerSheet(
    audioTrack: AudioTrack,
    subtitlesEnabled: Boolean,
    isLocalFile: Boolean,
    onSelectAudio: (AudioTrack) -> Unit,
    onToggleSubtitles: (Boolean) -> Unit,
    onOpenSubtitleStyle: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp),
        ) {
            Text("Audio & Subtitles (Saved for this Anime)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (!isLocalFile) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioTrack.entries.forEach { track ->
                        val active = audioTrack == track
                        OutlinedButton(
                            onClick = { onSelectAudio(track) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (track == AudioTrack.JAPANESE) "Japanese" else "English Dub",
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (active) {
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("English Subtitles", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = subtitlesEnabled, onCheckedChange = onToggleSubtitles)
            }
            OutlinedButton(
                onClick = onOpenSubtitleStyle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Customize Subtitle Style")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedPickerSheet(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Playback Speed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            SPEED_OPTIONS.forEach { spd ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectSpeed(spd) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text("${spd}x")
                    if (spd == currentSpeed) Icon(Icons.Rounded.Check, contentDescription = null)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
    }
}

private fun detectSubtitleMimeType(urlOrPath: String): String {
    val clean = urlOrPath.substringBefore('?').lowercase(Locale.ROOT)
    return when {
        clean.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        clean.endsWith(".ass") || clean.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        clean.endsWith(".ttml") || clean.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
}
