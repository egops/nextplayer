package dev.anilbeesetti.nextplayer.feature.player.state

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.feature.player.extensions.formatted
import dev.anilbeesetti.nextplayer.feature.player.extensions.setIsScrubbingModeEnabled
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import android.util.Log

@UnstableApi
@Composable
fun rememberSeekGestureState(
    player: Player,
    sensitivity: Float = 0.5f,
    enableSeekGesture: Boolean,
): SeekGestureState {
    val seekGestureState = remember {
        SeekGestureState(
            player = player,
            sensitivity = sensitivity,
            enableSeekGesture = enableSeekGesture,
        )
    }
    
    // 在 Composable 销毁时清理资源
    DisposableEffect(Unit) {
        onDispose {
            seekGestureState.dispose()
        }
    }
    
    return seekGestureState
}

@Stable
class SeekGestureState(
    private val player: Player,
    private val enableSeekGesture: Boolean = true,
    private val sensitivity: Float = 0.5f,
) {
    var isSeeking: Boolean by mutableStateOf(false)
        private set

    var seekStartPosition: Long? by mutableStateOf(null)
        private set

    var seekAmount: Long? by mutableStateOf(null)
        private set

    private var seekStartX = 0f
    
    // Warm-up decode 相关（解决暂停态大范围 seek 后卡顿问题）
    private var lastWarmUpPosition: Long = -1L
    private var isWarmingUp: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 保存 Runnable 引用，便于移除
    private var warmUpTimeoutRunnable: Runnable? = null
    
    private val warmUpListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            Log.d("SeekGestureState", "Warm-up: onRenderedFirstFrame")
            // 第一帧渲染完成，停止 warm-up
            player.playWhenReady = false
            player.volume = 1f
            isWarmingUp = false
            player.removeListener(this)
            // 移除超时回调
            warmUpTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            warmUpTimeoutRunnable = null
        }
    }
    
    companion object {
        // Warm-up decode 阈值（毫秒）- 解决暂停态大范围 seek 后卡顿问题
        private const val WARM_UP_SEEK_THRESHOLD_MS = 4000L // 4秒
    }

    fun onSeek(value: Long) {
        if (!isSeeking) {
            isSeeking = true
            seekStartPosition = player.currentPosition
            player.setIsScrubbingModeEnabled(true)
        }

        seekAmount = (value - seekStartPosition!!).coerceIn(
            minimumValue = 0 - seekStartPosition!!,
            maximumValue = player.duration - seekStartPosition!!,
        )
        
        // 检测是否需要 warm-up decode
        maybeWarmUpDecode(value)

        if (value > player.currentPosition) {
            player.seekTo(value.coerceAtMost(player.duration))
        } else {
            player.seekTo(value.coerceAtLeast(0L))
        }
    }

    fun onSeekEnd() {
        cleanupWarmUp()
        reset()
    }

    fun onDragStart(offset: Offset) {
        if (!enableSeekGesture) return
        if (player.currentPosition == C.TIME_UNSET) return
        if (player.duration == C.TIME_UNSET) return
        if (!player.isCurrentMediaItemSeekable) return

        isSeeking = true
        seekStartX = offset.x
        seekStartPosition = player.currentPosition

        player.setIsScrubbingModeEnabled(true)
    }

    @OptIn(UnstableApi::class)
    fun onDrag(change: PointerInputChange, dragAmount: Float) {
        if (seekStartPosition == null) return
        if (player.duration == C.TIME_UNSET) return
        if (!player.isCurrentMediaItemSeekable) return
        if (player.currentPosition <= 0L && dragAmount < 0) return
        if (player.currentPosition >= player.duration && dragAmount > 0) return
        if (change.isConsumed) return

        val newPosition = seekStartPosition!! + ((change.position.x - seekStartX) * (sensitivity * 100)).toInt()
        seekAmount = (newPosition - seekStartPosition!!).coerceIn(
            minimumValue = 0 - seekStartPosition!!,
            maximumValue = player.duration - seekStartPosition!!,
        )
        
        // 检测是否需要 warm-up decode
        maybeWarmUpDecode(newPosition)

        player.seekTo(newPosition.coerceIn(0L, player.duration))
    }

    fun onDragEnd() {
        cleanupWarmUp()
        reset()
    }

    private fun reset() {
        player.setIsScrubbingModeEnabled(false)
        isSeeking = false
        seekStartPosition = null
        seekAmount = null
        seekStartX = 0f
        lastWarmUpPosition = -1L
    }
    
    /**
     * 检测是否需要执行 warm-up decode
     * 条件：本地文件 + 暂停状态 + 大范围 seek + 拖动中
     */
    private fun maybeWarmUpDecode(targetPosition: Long) {
        // 如果正在 warm-up，跳过
        if (isWarmingUp) return
        
        // 条件1：播放器处于暂停状态
        if (player.isPlaying) return
        
        // 条件2：正在拖动中（preview seek）
        if (!isSeeking) return
        
        // 条件3：检测是否是本地文件
        val uri = player.currentMediaItem?.localConfiguration?.uri
        val isLocal = uri?.scheme == "file" || uri?.scheme == "content"
        if (!isLocal) return
        
        // 条件4：seek 距离超过阈值
        val currentPosition = player.currentPosition
        val seekDelta = abs(targetPosition - currentPosition)
        
        // 额外检查：与上次 warm-up 位置的距离也要超过阈值（避免频繁 warm-up）
        val deltaFromLastWarmUp = if (lastWarmUpPosition >= 0) {
            abs(targetPosition - lastWarmUpPosition)
        } else {
            seekDelta
        }
        
        if (seekDelta >= WARM_UP_SEEK_THRESHOLD_MS && deltaFromLastWarmUp >= WARM_UP_SEEK_THRESHOLD_MS) {
            performWarmUpDecode(targetPosition)
        }
    }
    
    /**
     * 执行 warm-up decode
     * 短暂播放以"点燃"解码管线，然后立即暂停
     */
    private fun performWarmUpDecode(targetPosition: Long) {
        isWarmingUp = true
        lastWarmUpPosition = targetPosition
        
        Log.d("SeekGestureState", "Warm-up: Starting warm-up decode at position $targetPosition")
        
        // 静音并开始播放
        player.volume = 0f
        player.addListener(warmUpListener)
        player.playWhenReady = true
        
        // 安全超时：如果 100ms 内没有收到 onRenderedFirstFrame，强制停止
        warmUpTimeoutRunnable = Runnable {
            if (isWarmingUp) {
                Log.d("SeekGestureState", "Warm-up: Timeout, stopping warm-up")
                player.playWhenReady = false
                player.volume = 1f
                isWarmingUp = false
                player.removeListener(warmUpListener)
            }
            warmUpTimeoutRunnable = null
        }
        mainHandler.postDelayed(warmUpTimeoutRunnable!!, 100)
    }
    
    /**
     * 清理 warm-up 状态
     */
    private fun cleanupWarmUp() {
        // 移除超时回调
        warmUpTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        warmUpTimeoutRunnable = null
        
        if (isWarmingUp) {
            Log.d("SeekGestureState", "Warm-up: Cleaning up warm-up state")
            player.playWhenReady = false
            player.volume = 1f
            player.removeListener(warmUpListener)
            isWarmingUp = false
        }
    }
    
    /**
     * 释放资源（在 Composable 销毁时调用）
     */
    fun dispose() {
        cleanupWarmUp()
        // 移除所有 Handler 回调
        mainHandler.removeCallbacksAndMessages(null)
        Log.d("SeekGestureState", "SeekGestureState disposed")
    }
}

val SeekGestureState.seekAmountFormatted: String
    get() {
        val seekAmount = seekAmount ?: return ""
        val sign = if (seekAmount < 0) "-" else "+"
        return sign + abs(seekAmount).milliseconds.formatted()
    }

val SeekGestureState.seekToPositionFormated: String
    get() {
        val position = seekStartPosition ?: return ""
        val seekAmount = seekAmount ?: return ""
        return (position + seekAmount).milliseconds.formatted()
    }
