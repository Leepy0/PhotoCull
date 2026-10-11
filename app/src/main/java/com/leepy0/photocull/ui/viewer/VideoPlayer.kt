package com.leepy0.photocull.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.leepy0.photocull.BuildConfig
import com.leepy0.photocull.R
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.ui.theme.ViewerColors
import com.leepy0.photocull.util.Format
import kotlinx.coroutines.delay
import java.io.File

/** 영상 1개의 재생 상태. VideoView를 감싼다(앱 안에서 바로 재생). */
@Stable
class VideoPlaybackState internal constructor() {
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        internal set
    var durationMs by mutableLongStateOf(0L)
        internal set
    var isPrepared by mutableStateOf(false)
        internal set
    var hasError by mutableStateOf(false)
        internal set

    /** 한 번이라도 재생을 시작했는지(그 전에는 첫 프레임 썸네일을 덮어 보여준다) */
    var started by mutableStateOf(false)
        private set

    internal var view: VideoView? = null

    fun play() {
        val v = view ?: return
        if (hasError) return
        if (durationMs > 0 && positionMs >= durationMs - END_TOLERANCE_MS) v.seekTo(0)
        v.start()
        isPlaying = true
        started = true
    }

    fun pause() {
        view?.takeIf { it.isPlaying }?.pause()
        isPlaying = false
    }

    fun toggle() = if (isPlaying) pause() else play()

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        view?.seekTo(target.toInt())
        positionMs = target
    }

    internal fun onCompleted() {
        isPlaying = false
        positionMs = durationMs
    }

    internal fun onReleased() {
        view = null
        isPlaying = false
    }

    private companion object {
        const val END_TOLERANCE_MS = 100L
    }
}

@Composable
fun rememberVideoPlaybackState(key: Any?): VideoPlaybackState = remember(key) { VideoPlaybackState() }

/**
 * 영상 재생 화면(VideoView). 탭하면 재생/일시정지. [isCurrent]가 아니면(옆 페이지로 넘기면) 멈춘다.
 * 화면이 꺼지거나 앱이 가려져도 멈춘다.
 */
@Composable
fun VideoPlayer(
    card: MediaCard,
    state: VideoPlaybackState,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current

    LaunchedEffect(isCurrent) { if (!isCurrent) state.pause() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) state.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            state.view?.let { state.positionMs = it.currentPosition.toLong() }
            delay(POSITION_POLL_MS)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !state.hasError,
                onClick = { state.toggle() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!inspection) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnPreparedListener { mp ->
                            state.durationMs = mp.duration.toLong().coerceAtLeast(0L)
                            state.isPrepared = true
                            // 첫 프레임을 그려 둔다(재생 전 검은 화면 방지)
                            if (!state.started) seekTo(1)
                        }
                        setOnCompletionListener { state.onCompleted() }
                        setOnErrorListener { _, _, _ ->
                            state.hasError = true
                            state.pause()
                            true
                        }
                        setVideoPath(card.path)
                        state.view = this
                    }
                },
                onRelease = { view ->
                    view.stopPlayback()
                    state.onReleased()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!state.started || state.hasError || inspection) {
            AsyncImage(
                model = File(card.path),
                contentDescription = card.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (state.hasError) {
            ViewerMessage(
                title = stringResource(R.string.viewer_video_error_title),
                body = stringResource(R.string.viewer_video_error_body),
                icon = Icons.Outlined.VideocamOff,
                action = {
                    ViewerOutlinedButton(
                        text = stringResource(R.string.viewer_open_external),
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        onClick = { openExternally(context, card) },
                    )
                },
            )
        } else if (!state.isPlaying) {
            val playLabel = stringResource(R.string.viewer_play)
            FilledIconButton(
                onClick = { state.play() },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ViewerColors.scrim,
                    contentColor = ViewerColors.onScrim,
                ),
                modifier = Modifier
                    .size(Spacing.touchTarget + Spacing.l)
                    .semantics { contentDescription = playLabel },
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(Spacing.iconLarge))
            }
        }
    }
}

/** 재생/일시정지 · 재생 위치 · 시간 */
@Composable
fun VideoControls(state: VideoPlaybackState, modifier: Modifier = Modifier, fallbackDurationMs: Long? = null) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    // 준비 전에는 분석 때 잰 길이를 보여준다(0:00 / 0:00 대신)
    val shownDuration = state.durationMs.takeIf { it > 0 } ?: fallbackDurationMs ?: 0L
    val duration = state.durationMs.coerceAtLeast(0L)
    val position = if (dragging) (dragValue * duration).toLong() else state.positionMs
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        IconButton(
            onClick = { state.toggle() },
            enabled = state.isPrepared && !state.hasError,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = ViewerColors.onScrim,
                disabledContentColor = ViewerColors.onScrimMuted,
            ),
        ) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (state.isPlaying) R.string.viewer_pause else R.string.viewer_play),
            )
        }
        val seekLabel = stringResource(R.string.viewer_video_seek)
        Slider(
            value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                state.seekTo((dragValue * duration).toLong())
                dragging = false
            },
            enabled = duration > 0 && !state.hasError,
            colors = SliderDefaults.colors(
                thumbColor = ViewerColors.onScrim,
                activeTrackColor = ViewerColors.onScrim,
                inactiveTrackColor = ViewerColors.onScrimMuted,
            ),
            modifier = Modifier.weight(1f).semantics { contentDescription = seekLabel },
        )
        Text(
            stringResource(R.string.viewer_video_time, Format.duration(position), Format.duration(shownDuration)),
            style = MaterialTheme.typography.labelMedium,
            color = ViewerColors.onScrim,
        )
    }
}

/** 다른 앱(갤러리·동영상 플레이어)으로 열기. FileProvider로 읽기 권한만 넘긴다. */
fun openExternally(context: Context, card: MediaCard): Boolean {
    val opened = runCatching {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", File(card.path))
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, card.mimeType.ifBlank { "video/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(view, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
        true
    }.recover { e ->
        if (e is ActivityNotFoundException || e is IllegalArgumentException || e is SecurityException) false else throw e
    }.getOrDefault(false)
    if (!opened) {
        Toast.makeText(context, context.getString(R.string.viewer_open_external_failed), Toast.LENGTH_SHORT).show()
    }
    return opened
}

private const val POSITION_POLL_MS = 200L
