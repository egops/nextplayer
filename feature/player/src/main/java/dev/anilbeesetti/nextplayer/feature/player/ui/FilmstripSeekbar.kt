package dev.anilbeesetti.nextplayer.feature.player.ui

import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anilbeesetti.nextplayer.feature.player.LocalControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.FilmstripTimelineState
import dev.anilbeesetti.nextplayer.feature.player.state.ThumbnailPreviewState
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 胶片带式时间线进度条
 * 类似专业视频剪辑软件（Premiere/Final Cut）
 * 
 * 特点：
 * - 红色播放头固定在屏幕左侧位置
 * - 胶片带（缩略图）在播放头下方滚动
 * - 上方显示时间刻度
 * - 支持双指缩放
 * - 滑动胶片带调整播放进度
 */
@Composable
fun FilmstripSeekbar(
    position: Float,
    duration: Float,
    thumbnailPreviewState: ThumbnailPreviewState,
    filmstripTimelineState: FilmstripTimelineState,
    onSeek: (Float) -> Unit,
    onSeekStart: () -> Unit,
    onSeekFinished: () -> Unit,
    /** True when scrubbing from video surface (semi-transparent filmstrip, same as internal drag). */
    overlayScrubActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 如果 duration 无效，不渲染组件
    if (duration <= 0) {
        return
    }
    
    val density = LocalDensity.current
    
    // 获取控件可见性状态，用于在操作时重置自动隐藏计时器
    val controlsVisibilityState = LocalControlsVisibilityState.current
    
    // 布局配置
    val timelineHeight = 80.dp // 总高度
    val filmstripHeight = 48.dp // 胶片带高度
    val timeScaleHeight = 24.dp // 时间刻度高度
    val playheadPositionRatio = 0.25f // 播放头位置（距左侧25%）
    
    val minZoom = 1f
    val maxZoom = FilmstripTimelineState.maxZoom(duration)
    val zoomLevel = filmstripTimelineState.zoomLevel
    
    // 状态
    var isDragging by remember { mutableStateOf(false) }
    var isZooming by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 拖动时的临时位置
    var dragPosition by remember { mutableFloatStateOf(position) }
    // 标记是否在拖动冷却期（拖动结束后短暂使用 dragPosition）
    var isInDragCooldown by remember { mutableStateOf(false) }
    
    // 手势状态
    var pointerCount by remember { mutableIntStateOf(0) }
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastDistance by remember { mutableFloatStateOf(0f) }
    var gestureMode by remember { mutableStateOf(GestureMode.NONE) }
    
    // 使用动画平滑过渡播放位置（500ms 匹配 MediaPresentationState 的更新间隔）
    val animatedPosition by animateFloatAsState(
        targetValue = position,
        animationSpec = tween(
            durationMillis = 450, // 略小于 500ms，避免累积延迟
            easing = LinearEasing
        ),
        label = "position"
    )
    
    // 冷却期结束后切换回动画位置
    LaunchedEffect(isInDragCooldown) {
        if (isInDragCooldown) {
            kotlinx.coroutines.delay(600)
            isInDragCooldown = false
        }
    }
    
    // 实际显示位置：
    // - 拖动中或缩放中：使用 dragPosition
    // - 拖动刚结束（冷却期内）：使用 dragPosition（避免跳回旧位置）
    // - 其他：使用动画平滑后的位置
    val currentSeekPosition = when {
        isDragging || isZooming -> dragPosition
        isInDragCooldown -> dragPosition
        else -> animatedPosition
    }
    
    // 开始拖动时，同步 dragPosition
    LaunchedEffect(isDragging) {
        if (isDragging) {
            dragPosition = animatedPosition
            isInDragCooldown = false
        }
    }
    
    // 视频宽高比
    val videoAspectRatio = thumbnailPreviewState.videoAspectRatio
    
    // 胶片带缩略图缓存 - 使用 Map 避免闪烁
    val thumbnailCache = remember { mutableStateMapOf<Long, Bitmap?>() }
    
    // 计算播放头的像素位置
    val playheadX = canvasSize.width * playheadPositionRatio
    
    // 计算胶片带参数
    val filmstripHeightPx = with(density) { filmstripHeight.toPx() }
    val thumbnailHeight = filmstripHeightPx - 8f
    val thumbnailWidth = thumbnailHeight * videoAspectRatio
    
    val visibleDuration = if (zoomLevel > 0) duration / zoomLevel else duration
    val timeToPixelRatio = filmstripTimelineState.timeToPixelRatio(duration).takeIf { it > 0f }
        ?: if (visibleDuration > 0 && canvasSize.width > 0) {
            canvasSize.width.toFloat() / visibleDuration
        } else {
            1f
        }
    
    // 计算当前视口的时间范围（用于按需加载缩略图）
    val viewportStartTime = (currentSeekPosition - playheadPositionRatio * visibleDuration).coerceAtLeast(0f)
    val viewportEndTime = (viewportStartTime + visibleDuration).coerceAtMost(duration)
    
    // 预加载缓冲区：可见范围前后各 30 秒
    val preloadBuffer = 30000f
    val preloadStartTime = (viewportStartTime - preloadBuffer).coerceAtLeast(0f)
    val preloadEndTime = (viewportEndTime + preloadBuffer).coerceAtMost(duration)
    
    // 计算需要预加载的时间点（只加载可见范围附近的缩略图）
    val preloadTimes = remember(preloadStartTime.toLong() / 2000, preloadEndTime.toLong() / 2000, duration) {
        if (duration > 0) {
            // 预加载间隔：每2秒一帧
            val interval = 2000L
            val startIndex = (preloadStartTime / interval).toLong()
            val endIndex = (preloadEndTime / interval).toLong() + 1
            
            val times = (startIndex..endIndex).map { i -> 
                (i * interval).coerceIn(0L, duration.toLong()) 
            }.toMutableList()
            
            // 确保包含视口边界的帧
            times.add(preloadStartTime.toLong().coerceAtLeast(0L))
            times.add(preloadEndTime.toLong().coerceAtMost(duration.toLong()))
            
            // 如果接近视频末尾，确保包含最后一帧
            if (preloadEndTime >= duration - interval) {
                times.add(duration.toLong())
            }
            
            times.distinct().sorted()
        } else {
            emptyList()
        }
    }
    
    // 异步预加载缩略图
    LaunchedEffect(preloadTimes, thumbnailPreviewState.isInitialized) {
        if (thumbnailPreviewState.isInitialized) {
            preloadTimes.forEach { timeMs ->
                if (!thumbnailCache.containsKey(timeMs)) {
                    val thumbnail = thumbnailPreviewState.getThumbnailSync(timeMs)
                    if (thumbnail != null) {
                        thumbnailCache[timeMs] = thumbnail
                    } else {
                        thumbnailPreviewState.loadThumbnailAsync(timeMs) { bitmap ->
                            if (bitmap != null) {
                                thumbnailCache[timeMs] = bitmap
                            }
                        }
                    }
                }
            }
        }
    }
    
    // 预先计算所有缓存的时间点（用于快速查找最近帧）
    val cachedTimes = remember(thumbnailCache.size) {
        thumbnailCache.keys.toList().sorted()
    }
    
    // 查找最近的缓存缩略图
    fun findNearestCachedThumbnail(targetTime: Long): Bitmap? {
        if (cachedTimes.isEmpty()) return null
        
        // 二分查找最近的时间点
        var left = 0
        var right = cachedTimes.size - 1
        
        while (left < right) {
            val mid = (left + right) / 2
            if (cachedTimes[mid] < targetTime) {
                left = mid + 1
            } else {
                right = mid
            }
        }
        
        // 比较左右两边，找最近的
        val nearestTime = when {
            left == 0 -> cachedTimes[0]
            left >= cachedTimes.size -> cachedTimes.last()
            else -> {
                val leftDist = kotlin.math.abs(cachedTimes[left - 1] - targetTime)
                val rightDist = kotlin.math.abs(cachedTimes[left] - targetTime)
                if (leftDist < rightDist) cachedTimes[left - 1] else cachedTimes[left]
            }
        }
        
        return thumbnailCache[nearestTime]
    }
    
    // 拖动时降低透明度，减少对视频画面的遮挡（含在画面上滑动 seek 时）
    val filmstripAlpha = if (isDragging || isZooming || overlayScrubActive) 0.3f else 1f
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = filmstripAlpha }
            // 不设置背景，让视频透出来
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(timelineHeight)
        ) {
            // 时间线画布
            @OptIn(ExperimentalComposeUiApi::class)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(timelineHeight)
                    .onSizeChanged {
                        canvasSize = it
                        filmstripTimelineState.viewportWidthPx = it.width.toFloat()
                    }
                    .pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                pointerCount = 1
                                lastX = event.x
                                gestureMode = GestureMode.NONE
                                true
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> {
                                pointerCount = event.pointerCount
                                if (pointerCount >= 2) {
                                    // 切换到缩放模式
                                    gestureMode = GestureMode.ZOOM
                                    isZooming = true
                                    isDragging = false
                                    // 重置自动隐藏计时器
                                    controlsVisibilityState?.showControls()
                                    // 计算初始两指距离
                                    lastDistance = calculateDistance(event)
                                }
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                // 在拖动或缩放过程中持续重置自动隐藏计时器
                                if (isDragging || isZooming) {
                                    controlsVisibilityState?.showControls()
                                }
                                when {
                                    // 多指 - 只处理缩放
                                    event.pointerCount >= 2 && gestureMode == GestureMode.ZOOM -> {
                                        val newDistance = calculateDistance(event)
                                        if (lastDistance > 0f) {
                                            val scale = newDistance / lastDistance
                                            val newZoom = (zoomLevel * scale).coerceIn(minZoom, maxZoom)
                                            filmstripTimelineState.setZoomLevel(newZoom, duration)
                                        }
                                        lastDistance = newDistance
                                    }
                                    // 单指且不在缩放模式 - 处理拖动
                                    event.pointerCount == 1 && gestureMode != GestureMode.ZOOM -> {
                                        if (gestureMode == GestureMode.NONE) {
                                            gestureMode = GestureMode.DRAG
                                            isDragging = true
                                            onSeekStart() // 通知开始拖动
                                            // 重置自动隐藏计时器
                                            controlsVisibilityState?.showControls()
                                        }
                                        if (duration > 0 && gestureMode == GestureMode.DRAG) {
                                            val deltaX = event.x - lastX
                                            val timeDelta = -(deltaX / timeToPixelRatio)
                                            val newPosition = (dragPosition + timeDelta).coerceIn(0f, duration)
                                            dragPosition = newPosition
                                            onSeek(newPosition)
                                        }
                                        lastX = event.x
                                    }
                                }
                                true
                            }
                            MotionEvent.ACTION_POINTER_UP -> {
                                pointerCount = event.pointerCount - 1
                                // 如果还有手指按着，重新计算距离
                                if (pointerCount >= 2) {
                                    // 需要等下一个 MOVE 事件重新计算
                                    lastDistance = 0f
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                // 所有手指抬起
                                if (isDragging) {
                                    onSeekFinished()
                                    // 进入冷却期，避免立即跳回旧位置
                                    isInDragCooldown = true
                                }
                                isDragging = false
                                isZooming = false
                                pointerCount = 0
                                gestureMode = GestureMode.NONE
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                val timeScaleHeightPx = timeScaleHeight.toPx()
                val filmstripTop = timeScaleHeightPx + 8f
                
                // 计算视口的时间范围
                val viewportStartTime = (currentSeekPosition - playheadPositionRatio * visibleDuration).coerceAtLeast(0f)
                
                // 1. 绘制时间刻度背景（高透明度黑色）
                drawRect(
                    color = Color(0x33000000), // ~20% 不透明
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, timeScaleHeightPx)
                )
                
                // 2. 绘制胶片带背景
                drawRect(
                    color = Color(0xFF1A1A1A),
                    topLeft = Offset(0f, filmstripTop),
                    size = Size(size.width, filmstripHeightPx)
                )
                
                // 3. 绘制缩略图 - 连续平铺
                if (thumbnailPreviewState.isInitialized && duration > 0) {
                    // 计算视频开始和结束位置对应的 X 坐标
                    val videoStartX = playheadX + (0f - currentSeekPosition) * timeToPixelRatio
                    val videoEndX = playheadX + (duration - currentSeekPosition) * timeToPixelRatio
                    
                    // 裁剪范围：左边界不小于视频开始位置，右边界不大于视频结束位置
                    val clipLeft = videoStartX.coerceAtLeast(0f)
                    val clipRight = videoEndX.coerceAtMost(size.width)
                    
                    val destTop = filmstripTop + 4f
                    val thumbGap = 2f // 缩略图之间的间隙
                    val effectiveThumbWidth = thumbnailWidth + thumbGap
                    
                    clipRect(clipLeft, filmstripTop, clipRight, filmstripTop + filmstripHeightPx) {
                        // 计算需要绘制的缩略图数量和起始位置
                        // 从视频开始位置开始连续平铺
                        val startX = videoStartX
                        val thumbCount = ((videoEndX - videoStartX) / effectiveThumbWidth).toInt() + 2
                        
                        for (i in 0 until thumbCount) {
                            val thumbX = startX + i * effectiveThumbWidth
                            
                            // 只绘制可见范围内的缩略图
                            if (thumbX > -thumbnailWidth && thumbX < size.width + thumbnailWidth) {
                                // 计算这个位置对应的时间点
                                val thumbTime = ((thumbX - playheadX) / timeToPixelRatio + currentSeekPosition)
                                    .toLong()
                                    .coerceIn(0L, duration.toLong())
                                
                                // 绘制占位背景
                                drawRect(
                                    color = Color(0xFF0D0D0D),
                                    topLeft = Offset(thumbX, destTop),
                                    size = Size(thumbnailWidth, thumbnailHeight)
                                )
                                
                                // 获取缩略图：优先精确匹配，否则用最近的缓存帧
                                val bitmap = thumbnailCache[thumbTime] ?: findNearestCachedThumbnail(thumbTime)
                                if (bitmap != null) {
                                    // 计算保持原始比例的绘制尺寸
                                    val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                                    val targetAspect = thumbnailWidth / thumbnailHeight
                                    
                                    val (drawWidth, drawHeight, offsetX, offsetY) = if (bitmapAspect > targetAspect) {
                                        // 图片更宽，以高度为准，裁剪两边
                                        val h = thumbnailHeight
                                        val w = h * bitmapAspect
                                        val ox = (thumbnailWidth - w) / 2
                                        arrayOf(w, h, ox, 0f)
                                    } else {
                                        // 图片更高，以宽度为准，裁剪上下
                                        val w = thumbnailWidth
                                        val h = w / bitmapAspect
                                        val oy = (thumbnailHeight - h) / 2
                                        arrayOf(w, h, 0f, oy)
                                    }
                                    
                                    // 在缩略图区域内裁剪，防止溢出
                                    clipRect(thumbX, destTop, thumbX + thumbnailWidth, destTop + thumbnailHeight) {
                                        drawImage(
                                            image = bitmap.asImageBitmap(),
                                            dstOffset = IntOffset((thumbX + offsetX).toInt(), (destTop + offsetY).toInt()),
                                            dstSize = IntSize(drawWidth.toInt(), drawHeight.toInt())
                                        )
                                    }
                                }
                                
                                // 绘制边框
                                drawRect(
                                    color = Color(0xFF333333),
                                    topLeft = Offset(thumbX, destTop),
                                    size = Size(thumbnailWidth, thumbnailHeight),
                                    style = Stroke(width = 1f)
                                )
                            }
                        }
                    }
                }
                
                // 4. 绘制时间刻度（限制在视频时长范围内）
                drawTimeScale(
                    viewportStartTime = viewportStartTime,
                    visibleDuration = visibleDuration,
                    totalDuration = duration,
                    timeToPixelRatio = timeToPixelRatio,
                    playheadX = playheadX,
                    currentTime = currentSeekPosition,
                    height = timeScaleHeightPx
                )
                
                // 5. 绘制播放头（红色竖线 + 三角形）
                drawPlayhead(
                    x = playheadX,
                    topY = 0f,
                    bottomY = size.height,
                    timeScaleHeight = timeScaleHeightPx
                )
                
                // 6. 绘制胶片带边框
                drawRect(
                    color = Color(0xFF606060),
                    topLeft = Offset(0f, filmstripTop),
                    size = Size(size.width, filmstripHeightPx),
                    style = Stroke(width = 1f)
                )
            }
            
            // 播放头上方的时间显示（更小的字体，高透明度背景）
            // 使用 Box + layout modifier 实现精确居中对齐播放头
            Box(
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            // 将整个 Box（包括背景）中心对齐到播放头位置
                            placeable.placeRelative(
                                x = (playheadX - placeable.width / 2f).roundToInt(),
                                y = 0
                            )
                        }
                    }
            ) {
                Text(
                    text = formatTimeWithDecimal(currentSeekPosition.toLong()),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/**
 * 绘制时间刻度
 */
private fun DrawScope.drawTimeScale(
    viewportStartTime: Float,
    visibleDuration: Float,
    totalDuration: Float,
    timeToPixelRatio: Float,
    playheadX: Float,
    currentTime: Float,
    height: Float
) {
    val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(200, 255, 255, 255)
        textSize = 20f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    
    // 根据可视时长确定刻度间隔（更细粒度）
    val majorInterval = when {
        visibleDuration < 8000 -> 500L       // <8秒时，每0.5秒
        visibleDuration < 20000 -> 1000L     // <20秒时，每1秒
        visibleDuration < 40000 -> 2000L     // <40秒时，每2秒
        visibleDuration < 90000 -> 5000L     // <1.5分钟时，每5秒
        visibleDuration < 180000 -> 10000L   // <3分钟时，每10秒
        visibleDuration < 360000 -> 30000L   // <6分钟时，每30秒
        visibleDuration < 900000 -> 60000L   // <15分钟时，每1分钟
        visibleDuration < 1800000 -> 120000L // <30分钟时，每2分钟
        else -> 300000L                       // 其他，每5分钟
    }
    
    // 计算第一个刻度的时间（从0开始对齐）
    val firstTickTime = ((viewportStartTime / majorInterval).toLong() * majorInterval).coerceAtLeast(0L)
    
    // 播放头附近的隐藏区域（避免与播放头时间冲突）
    // 增大范围以避免干扰播放头上方的进度时间显示
    val hideZoneStart = playheadX - 100f
    val hideZoneEnd = playheadX + 100f
    
    // 计算视频结束位置的 X 坐标
    val videoEndX = playheadX + (totalDuration - currentTime) * timeToPixelRatio
    
    var tickTime = firstTickTime
    while (tickTime <= viewportStartTime + visibleDuration + majorInterval) {
        // 限制刻度不超过视频总时长
        if (tickTime > totalDuration) break
        
        val tickX = playheadX + (tickTime - currentTime) * timeToPixelRatio
        
        // 检查是否在可见范围内，且不在播放头隐藏区域
        if (tickX >= 0 && tickX <= size.width && tickX <= videoEndX) {
            val inHideZone = tickX > hideZoneStart && tickX < hideZoneEnd
            
            if (!inHideZone) {
                // 绘制刻度线
                drawLine(
                    color = Color(0xFF808080),
                    start = Offset(tickX, height - 6f),
                    end = Offset(tickX, height),
                    strokeWidth = 1f
                )
                
                // 绘制时间文本（根据间隔选择格式）
                val timeText = if (majorInterval < 1000) {
                    formatTimeWithMs(tickTime)
                } else {
                    formatTimeShort(tickTime)
                }
                drawContext.canvas.nativeCanvas.drawText(
                    timeText,
                    tickX,
                    height - 10f,
                    textPaint
                )
            }
        }
        
        tickTime += majorInterval
    }
    
    // 绘制视频结束标记（如果在可见范围内）
    if (videoEndX >= 0 && videoEndX <= size.width) {
        // 绘制结束竖线
        drawLine(
            color = Color(0xFF4CAF50), // 绿色结束标记
            start = Offset(videoEndX, 0f),
            end = Offset(videoEndX, height),
            strokeWidth = 2f
        )
    }
}

/**
 * 绘制播放头
 */
private fun DrawScope.drawPlayhead(
    x: Float,
    topY: Float,
    bottomY: Float,
    timeScaleHeight: Float
) {
    // 三角形顶部
    val triangleSize = 8f
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(x, topY + timeScaleHeight)
        lineTo(x - triangleSize, topY + timeScaleHeight - triangleSize)
        lineTo(x + triangleSize, topY + timeScaleHeight - triangleSize)
        close()
    }
    
    // 绘制三角形
    drawPath(
        path = path,
        color = Color.Red
    )
    
    // 绘制竖线
    drawLine(
        color = Color.Red,
        start = Offset(x, topY + timeScaleHeight),
        end = Offset(x, bottomY),
        strokeWidth = 2f
    )
}

/**
 * 格式化时间（带小数点后一位）
 */
private fun formatTimeWithDecimal(timeMs: Long): String {
    val totalSeconds = timeMs / 1000.0
    val hours = (totalSeconds / 3600).toInt()
    val minutes = ((totalSeconds % 3600) / 60).toInt()
    val seconds = totalSeconds % 60
    
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%04.1f", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%04.1f", minutes, seconds)
    }
}

/**
 * 格式化时间（短格式，用于刻度）
 */
private fun formatTimeShort(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * 格式化时间（带毫秒，用于高精度刻度）
 */
private fun formatTimeWithMs(timeMs: Long): String {
    val totalSeconds = timeMs / 1000.0
    val minutes = (totalSeconds / 60).toInt()
    val seconds = totalSeconds % 60
    
    return String.format(Locale.US, "%d:%04.1f", minutes, seconds)
}

/**
 * 计算两指之间的距离
 */
private fun calculateDistance(event: MotionEvent): Float {
    if (event.pointerCount < 2) return 0f
    val dx = event.getX(0) - event.getX(1)
    val dy = event.getY(0) - event.getY(1)
    return sqrt(dx * dx + dy * dy)
}

/**
 * 手势模式枚举
 */
private enum class GestureMode {
    NONE,   // 未确定
    DRAG,   // 单指拖动
    ZOOM    // 双指缩放
}
