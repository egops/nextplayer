package dev.anilbeesetti.nextplayer.feature.player.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * 视频缩略图提取和缓存管理器
 * 针对高性能设备优化，大量使用内存缓存以提高响应速度
 */
@UnstableApi
class ThumbnailProvider(
    private val context: Context,
    private val parentScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ThumbnailProvider"
    }

    // 使用独立的 CoroutineScope，便于取消和重建
    private var scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    
    // 标记是否已清理
    private val isCleanedUp = AtomicBoolean(false)
    
    // 降低缓存容量到 50MB，避免 OOM
    private val thumbnailCache = object : LruCache<Long, Bitmap>(50 * 1024 * 1024) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }
    }

    private var currentVideoUri: Uri? = null
    private var videoDurationMs: Long = 0
    @Volatile
    private var retriever: MediaMetadataRetriever? = null
    private var prefetchJob: Job? = null
    
    // 视频宽高比（默认 16:9）
    private var videoAspectRatio: Float = 16f / 9f
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    // 缩略图配置 - 高质量
    private val thumbnailWidth = 320
    private val thumbnailHeight = 180
    
    // 预取策略：每隔N毫秒预取一帧
    private val prefetchIntervalMs = 2000L // 每2秒一帧

    /**
     * 初始化视频源
     */
    fun initializeVideo(uri: Uri, durationMs: Long) {
        if (currentVideoUri == uri && videoDurationMs == durationMs && !isCleanedUp.get()) {
            return // 已经初始化过相同视频
        }

        // 如果之前已清理，重置标志并重建 scope
        if (isCleanedUp.getAndSet(false)) {
            scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
            Log.d(TAG, "ThumbnailProvider: Recreated scope after cleanup")
        }
        
        // 取消之前的预取任务
        prefetchJob?.cancel()
        prefetchJob = null
        
        // 清理旧的 retriever
        val oldRetriever = retriever
        retriever = null
        oldRetriever?.let { 
            Thread {
                try {
                    it.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing old MediaMetadataRetriever", e)
                }
            }.start()
        }
        
        // 清空旧缓存
        thumbnailCache.evictAll()

        currentVideoUri = uri
        videoDurationMs = durationMs

        // 初始化 MediaMetadataRetriever
        try {
            retriever = MediaMetadataRetriever().apply {
                setDataSource(context, uri)
            }
            
            // 获取视频尺寸和宽高比
            retriever?.let { r ->
                val widthStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val heightStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                
                videoWidth = widthStr?.toIntOrNull() ?: 0
                videoHeight = heightStr?.toIntOrNull() ?: 0
                
                if (videoWidth > 0 && videoHeight > 0) {
                    videoAspectRatio = videoWidth.toFloat() / videoHeight.toFloat()
                    Log.d(TAG, "Video dimensions: ${videoWidth}x${videoHeight}, aspect ratio: $videoAspectRatio")
                }
            }
            
            // 开始预取缩略图
            startPrefetchingThumbnails()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaMetadataRetriever", e)
        }
    }
    
    /**
     * 获取视频的宽高比
     */
    fun getVideoAspectRatio(): Float {
        return videoAspectRatio
    }

    /**
     * 获取指定时间点的缩略图（同步方法，优先从缓存获取）
     * 直接使用传入的时间戳，不做对齐（由调用方决定对齐策略）
     */
    fun getThumbnail(timeMs: Long): Bitmap? {
        if (isCleanedUp.get()) return null
        if (videoDurationMs <= 0) return null
        
        val clampedTime = timeMs.coerceIn(0, videoDurationMs)
        
        // 首先尝试从缓存获取
        thumbnailCache.get(clampedTime)?.let { return it }

        // 缓存未命中，尝试从附近的缓存获取（快速响应）
        val nearestCached = findNearestCachedThumbnail(clampedTime, 2000)
        if (nearestCached != null) {
            return nearestCached
        }

        // 都没有，返回null，由异步加载处理
        return null
    }

    /**
     * 异步获取缩略图
     * 直接使用传入的时间戳，不做对齐（由调用方决定对齐策略）
     */
    fun getThumbnailAsync(timeMs: Long, callback: (Bitmap?) -> Unit) {
        if (isCleanedUp.get() || videoDurationMs <= 0) {
            callback(null)
            return
        }
        
        val clampedTime = timeMs.coerceIn(0, videoDurationMs)
        
        scope.launch {
            val bitmap = loadThumbnail(clampedTime)
            if (!isCleanedUp.get()) {
                withContext(Dispatchers.Main) {
                    callback(bitmap)
                }
            }
        }
    }

    /**
     * 预加载一系列缩略图（用于进度条拖动预测）
     */
    fun prefetchThumbnails(timesMs: List<Long>) {
        if (isCleanedUp.get()) return
        
        scope.launch(Dispatchers.IO) {
            timesMs.forEach { timeMs ->
                if (isCleanedUp.get()) return@launch
                if (thumbnailCache.get(timeMs) == null) {
                    loadThumbnail(timeMs)
                }
            }
        }
    }

    /**
     * 实际加载缩略图的方法
     */
    private suspend fun loadThumbnail(timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        // 检查是否已清理或 duration 无效
        if (isCleanedUp.get() || videoDurationMs <= 0) return@withContext null
        
        val clampedTime = timeMs.coerceIn(0, videoDurationMs)
        
        // 再次检查缓存（可能在等待期间被其他协程加载了）
        thumbnailCache.get(clampedTime)?.let { return@withContext it }
        
        // 检查是否已清理
        if (isCleanedUp.get()) return@withContext null
        
        // 获取 retriever 的本地引用，避免并发问题
        val localRetriever = retriever ?: return@withContext null

        // 从视频提取帧
        try {
            // getFrameAtTime 是阻塞操作，无法取消
            // 但我们在调用前后都检查状态
            val bitmap = localRetriever.getFrameAtTime(
                clampedTime * 1000, // 转换为微秒
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            
            // 提取完成后立即检查是否需要丢弃结果
            if (isCleanedUp.get()) {
                bitmap?.recycle()
                return@withContext null
            }

            bitmap?.let {
                // 缩放到目标尺寸以节省内存和提高渲染速度
                val scaled = Bitmap.createScaledBitmap(it, thumbnailWidth, thumbnailHeight, true)
                if (scaled != it) {
                    it.recycle()
                }
                
                // 存入缓存（如果还没清理）
                if (!isCleanedUp.get()) {
                    thumbnailCache.put(clampedTime, scaled)
                    // 减少日志输出，避免刷屏
                    if (clampedTime % 10000 == 0L) {
                        Log.d(TAG, "Loaded thumbnail at $clampedTime ms")
                    }
                    scaled
                } else {
                    scaled.recycle()
                    null
                }
            }
        } catch (e: Exception) {
            if (!isCleanedUp.get()) {
                Log.e(TAG, "Failed to extract frame at $timeMs ms", e)
            }
            null
        }
    }

    /**
     * 查找最近的已缓存缩略图
     */
    private fun findNearestCachedThumbnail(timeMs: Long, maxDistanceMs: Long): Bitmap? {
        var nearestBitmap: Bitmap? = null
        var minDistance = Long.MAX_VALUE

        val snapshot = thumbnailCache.snapshot()
        for ((cachedTime, bitmap) in snapshot) {
            val distance = kotlin.math.abs(cachedTime - timeMs)
            if (distance < minDistance && distance <= maxDistanceMs) {
                minDistance = distance
                nearestBitmap = bitmap
            }
        }

        return nearestBitmap
    }

    /**
     * 后台预取缩略图，只加载开头的几帧作为初始显示
     * 其余缩略图由 FilmstripSeekbar 按需加载
     */
    private fun startPrefetchingThumbnails() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                // 只预加载前 15 秒（约 8 帧），其余按需加载
                val initialPrefetchDuration = 15000L
                val maxPrefetchFrames = 8
                val prefetchDuration = minOf(initialPrefetchDuration, videoDurationMs)
                val frameCount = max(1, (prefetchDuration / prefetchIntervalMs).toInt())
                    .coerceAtMost(maxPrefetchFrames)
                
                Log.d(TAG, "Initial prefetch: $frameCount thumbnails for first ${prefetchDuration}ms")
                
                for (i in 0 until frameCount) {
                    // 每帧前都检查是否需要取消
                    if (!isActive || isCleanedUp.get()) {
                        Log.d(TAG, "Prefetching cancelled at frame $i")
                        return@launch
                    }
                    
                    val timeMs = i * prefetchIntervalMs
                    if (thumbnailCache.get(timeMs) == null) {
                        // 再次检查（因为 loadThumbnail 可能耗时较长）
                        if (isCleanedUp.get()) return@launch
                        loadThumbnail(timeMs)
                    }
                    
                    // 每帧后都让出执行权，便于响应取消
                    kotlinx.coroutines.yield()
                }
                
                if (!isCleanedUp.get()) {
                    Log.d(TAG, "Finished initial prefetch of $frameCount thumbnails")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Prefetching cancelled by coroutine cancellation")
            } catch (e: Exception) {
                if (!isCleanedUp.get()) {
                    Log.e(TAG, "Error during thumbnail prefetching", e)
                }
            }
        }
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            size = thumbnailCache.size(),
            maxSize = thumbnailCache.maxSize(),
            hitCount = thumbnailCache.hitCount(),
            missCount = thumbnailCache.missCount()
        )
    }

    /**
     * 清理资源（异步执行耗时操作，避免主线程卡顿）
     */
    fun cleanup() {
        // 设置清理标志，防止新的操作
        isCleanedUp.set(true)
        
        // 取消所有协程
        prefetchJob?.cancel()
        prefetchJob = null
        scope.cancel()
        
        // 保存引用，然后立即清空，避免阻塞主线程
        val localRetriever = retriever
        val cacheSnapshot = thumbnailCache.snapshot()
        
        retriever = null
        thumbnailCache.evictAll()
        currentVideoUri = null
        videoDurationMs = 0
        
        // 在后台线程释放资源
        Thread {
            try {
                // 释放 MediaMetadataRetriever
                localRetriever?.release()
                Log.d(TAG, "MediaMetadataRetriever released successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e)
            }
            
            // 回收 Bitmap（可选，LruCache.evictAll 已经会触发回收）
            // 这里显式回收可以更快释放内存
            try {
                cacheSnapshot.values.forEach { bitmap ->
                    bitmap?.recycle()
                }
                Log.d(TAG, "Recycled ${cacheSnapshot.size} cached bitmaps")
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling bitmaps", e)
            }
        }.start()
        
        Log.d(TAG, "ThumbnailProvider cleanup initiated")
    }

    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hitCount: Int,
        val missCount: Int
    ) {
        val hitRate: Float
            get() = if (hitCount + missCount > 0) {
                hitCount.toFloat() / (hitCount + missCount)
            } else 0f
    }
}

