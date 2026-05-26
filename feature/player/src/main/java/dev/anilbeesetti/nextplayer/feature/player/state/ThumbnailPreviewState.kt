package dev.anilbeesetti.nextplayer.feature.player.state

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import dev.anilbeesetti.nextplayer.feature.player.utils.ThumbnailProvider
import android.util.Log

/**
 * 缩略图预览状态管理
 * 用于在进度条拖动时显示视频缩略图预览
 */
@Composable
fun rememberThumbnailPreviewState(
    player: Player,
): ThumbnailPreviewState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 使用 remember(player) 确保 player 变化时重新创建 state
    val state = remember(player) {
        ThumbnailPreviewState(
            thumbnailProvider = ThumbnailProvider(context.applicationContext, scope),
        )
    }
    
    // 监听播放器状态，初始化缩略图
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val uri = player.currentMediaItem?.localConfiguration?.uri
                    val duration = player.duration
                    if (uri != null && duration > 0) {
                        state.initializeVideo(uri, duration)
                    }
                }
            }
            
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                // 切换视频时重置状态（不调用 cleanup，让 initializeVideo 处理）
                Log.d("ThumbnailPreviewState", "ThumbnailPreviewState: Media item transition, resetting for new video")
                state.resetForNewVideo()
            }
        }
        
        player.addListener(listener)
        
        // 如果播放器已经准备好，立即初始化
        if (player.playbackState == Player.STATE_READY) {
            val uri = player.currentMediaItem?.localConfiguration?.uri
            val duration = player.duration
            if (uri != null && duration > 0) {
                state.initializeVideo(uri, duration)
            }
        }
        
        onDispose {
            Log.d("ThumbnailPreviewState", "ThumbnailPreviewState: Disposing, removing listener and cleaning up")
            player.removeListener(listener)
            state.cleanup()
        }
    }
    
    return state
}

@Stable
class ThumbnailPreviewState(
    private val thumbnailProvider: ThumbnailProvider,
) {
    companion object {
        private const val TAG = "ThumbnailPreviewState"
    }

    var currentThumbnail: Bitmap? by mutableStateOf(null)
        private set
    
    var isInitialized: Boolean by mutableStateOf(false)
        private set
    
    var videoAspectRatio: Float by mutableStateOf(16f / 9f)
        private set
    
    // 标记是否已清理
    @Volatile
    private var isCleanedUp = false
    
    fun initializeVideo(uri: Uri, durationMs: Long) {
        // 重置清理标志（允许重新初始化）
        isCleanedUp = false
        
        Log.d(TAG, "ThumbnailPreviewState: Initializing video $uri, duration=$durationMs")
        thumbnailProvider.initializeVideo(uri, durationMs)
        videoAspectRatio = thumbnailProvider.getVideoAspectRatio()
        isInitialized = true
    }
    
    /**
     * 为新视频重置状态（切换视频时调用，不完全清理）
     */
    fun resetForNewVideo() {
        currentThumbnail = null
        isInitialized = false
        // 不设置 isCleanedUp，让下一个 initializeVideo 正常工作
    }
    
    /**
     * 获取指定时间点的缩略图（并更新 currentThumbnail）
     */
    fun getThumbnailAt(timeMs: Long) {
        if (isCleanedUp) return
        
        val thumbnail = thumbnailProvider.getThumbnail(timeMs)
        if (thumbnail != null) {
            currentThumbnail = thumbnail
        } else {
            // 异步加载
            thumbnailProvider.getThumbnailAsync(timeMs) { bitmap ->
                if (!isCleanedUp) {
                    currentThumbnail = bitmap
                }
            }
        }
    }
    
    /**
     * 直接获取指定时间点的缩略图（用于胶片带绘制）
     * 如果缓存中没有，返回 null
     */
    fun getThumbnailSync(timeMs: Long): Bitmap? {
        if (isCleanedUp) return null
        return thumbnailProvider.getThumbnail(timeMs)
    }
    
    /**
     * 异步加载缩略图（用于胶片带缓存预加载）
     */
    fun loadThumbnailAsync(timeMs: Long, callback: (Bitmap?) -> Unit) {
        if (isCleanedUp) {
            callback(null)
            return
        }
        thumbnailProvider.getThumbnailAsync(timeMs) { bitmap ->
            if (!isCleanedUp) {
                callback(bitmap)
            }
        }
    }
    
    /**
     * 清除当前缩略图
     */
    fun clearThumbnail() {
        currentThumbnail = null
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        if (isCleanedUp) {
            Log.d(TAG, "ThumbnailPreviewState: Already cleaned up")
            return
        }
        isCleanedUp = true
        Log.d(TAG, "ThumbnailPreviewState: Cleaning up")
        thumbnailProvider.cleanup()
        currentThumbnail = null
        isInitialized = false
    }
}

