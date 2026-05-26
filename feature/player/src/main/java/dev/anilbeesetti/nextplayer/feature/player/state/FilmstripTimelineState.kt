package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Shared filmstrip timeline mapping used by [dev.anilbeesetti.nextplayer.feature.player.ui.FilmstripSeekbar]
 * and video-area horizontal seek gestures when thumbnail preview is enabled.
 */
@Stable
class FilmstripTimelineState {
    var zoomLevel by mutableFloatStateOf(1f)
        private set

    var viewportWidthPx by mutableFloatStateOf(0f)

    fun setZoomLevel(value: Float, durationMs: Float) {
        zoomLevel = value.coerceIn(MIN_ZOOM, maxZoom(durationMs))
    }

    fun resetZoomForDuration(durationMs: Float) {
        zoomLevel = initialZoom(durationMs)
    }

    /** Pixels per millisecond of timeline (matches filmstrip drag math). */
    fun timeToPixelRatio(durationMs: Float): Float {
        if (durationMs <= 0f) return 1f
        val width = viewportWidthPx
        if (width <= 0f) return 1f
        val visibleDuration = durationMs / zoomLevel.coerceAtLeast(MIN_ZOOM)
        return if (visibleDuration > 0f) width / visibleDuration else 1f
    }

    /** Converts horizontal drag delta (pixels) to timeline delta (ms). Same sign as filmstrip. */
    fun deltaXToTimeDeltaMs(deltaX: Float, durationMs: Float): Float {
        val ratio = timeToPixelRatio(durationMs)
        return if (ratio > 0f) -(deltaX / ratio) else 0f
    }

    companion object {
        private const val MIN_ZOOM = 1f
        private const val MIN_VISIBLE_DURATION_AT_MAX_ZOOM_MS = 3000f
        private const val TARGET_VISIBLE_DURATION_MS = 30_000f

        fun maxZoom(durationMs: Float): Float =
            (durationMs / MIN_VISIBLE_DURATION_AT_MAX_ZOOM_MS).coerceIn(MIN_ZOOM, 2000f)

        fun initialZoom(durationMs: Float): Float {
            if (durationMs <= TARGET_VISIBLE_DURATION_MS) return MIN_ZOOM
            return (durationMs / TARGET_VISIBLE_DURATION_MS).coerceIn(MIN_ZOOM, maxZoom(durationMs))
        }
    }
}

@Composable
fun rememberFilmstripTimelineState(
    durationMs: Long,
    enabled: Boolean,
): FilmstripTimelineState {
    val state = remember { FilmstripTimelineState() }
    androidx.compose.runtime.LaunchedEffect(durationMs, enabled) {
        if (enabled && durationMs > 0) {
            state.resetZoomForDuration(durationMs.toFloat())
        }
    }
    return state
}
