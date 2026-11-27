package top.mcly99.dl.engine.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import top.mcly99.dl.utils.AppConstants
import top.mcly99.dl.utils.AppLogger
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** libvlc 音量上限（与 [DEFAULT_VOLUME] 一致） */
private const val MAX_VOLUME = 100

/** 没拿到窗口尺寸时的转换上限：再大的源也按 1080p 转，避免 4K 每帧 32 MB */
private const val MAX_FRAME_WIDTH = 1920
private const val MAX_FRAME_HEIGHT = 1080

/**
 * ViewModel 依赖的播放器能力。
 *
 * 抽成接口是为了给状态机单测留注入点：[VideoPlayer] 需要 native libvlc，
 * 无头测试里只能用假实现。
 */
interface PlaybackEngine {
    fun play(request: PlaybackRequest, cachingMs: Int = 1_000)

    /**
     * 上报播放区域的像素尺寸，用于按实际显示大小降采样。
     *
     * 默认空实现：假引擎（测试）与不关心画质的实现不需要跟着改。
     */
    fun setViewport(width: Int, height: Int) = Unit

    fun togglePause()

    fun seekTo(positionMs: Long)

    fun setVolume(volume: Int)

    fun toggleMute()

    fun stop()

    fun release()
}

/**
 * 用 libvlc 解码、把画面交回 Compose 画的播放器。
 *
 * 为什么不用 `EmbeddedMediaPlayerComponent`：那是个 AWT 重层组件，压在 Compose 上面会
 * 出现 z-order 问题（弹窗/菜单被画面盖住）。这里走 **帧回调**：libvlc 解码到内存缓冲，
 * 我们取一帧转成 [ImageBitmap]，由 Compose 当普通图片画 —— 没有任何重层。
 *
 * 代价是 CPU 拷贝（1080p30 约 240 MB/s 的 int→byte 搬运），所以有帧率上限
 * [AppConstants.PLAYER_MAX_FPS]：超出的帧直接丢弃，不转换。
 *
 * 本类**不持有 Compose 状态**：状态与画面都通过回调推给 ViewModel。
 * 回调来自 libvlc 的解码/渲染线程，写入 Compose 状态是允许的（快照系统线程安全）。
 *
 * @param factory 由 [VlcRuntime] 提供；一个进程只建一个
 * @param onState 状态变化（播放/暂停/进度/时长/出错）
 * @param onFrame 新画面；null 表示清空
 */
class VideoPlayer(
    private val factory: uk.co.caprica.vlcj.factory.MediaPlayerFactory,
    private val onState: (PlaybackState) -> Unit,
    private val onFrame: (ImageBitmap?) -> Unit,
) : PlaybackEngine {

    /**
     * P2-02：libvlc 回调线程与 UI 线程共享的状态改用原子容器，避免 read-copy-write 丢字段。
     */
    private val stateHolder = PlaybackStateHolder()

    val state: PlaybackState get() = stateHolder.value

    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    /** 最近一帧的尺寸；由 [BufferFormatCallback] 更新，帧回调里用 */
    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    /** 上一帧的时间戳（纳秒），用于限帧 */
    @Volatile
    private var lastFrameAtNs = 0L

    /** 只保留最后一次请求的地址，用于「重播」；libvlc 的 error 回调线程也会读 */
    @Volatile
    private var lastRequest: PlaybackRequest? = null

    @Volatile
    private var lastCachingMs = 0

    /** 音量与静音在播放器实例上保留：切档 / 重播都不该把用户调好的音量打回去 */
    @Volatile
    private var volume = DEFAULT_VOLUME

    @Volatile
    private var muted = false

    /** 已分配的帧缓冲像素数；0 表示还没分配 */
    @Volatile
    private var bufferPixels = 0

    /** UI 上报的播放区域尺寸（像素）；0 表示还没上报，按 1080p 封顶 */
    @Volatile
    private var viewportWidth = 0

    @Volatile
    private var viewportHeight = 0

    private val renderCallback = object : RenderCallbackAdapter() {
        override fun onDisplay(mediaPlayer: MediaPlayer, pixels: IntArray) {
            val width = frameWidth
            val height = frameHeight
            if (width <= 0 || height <= 0) return
            if (pixels.size < width * height) return

            // 限帧：转换是 CPU 拷贝，解码出来的帧比屏幕刷新快时没必要全转
            val now = System.nanoTime()
            val minInterval = 1_000_000_000L / AppConstants.PLAYER_MAX_FPS
            if (now - lastFrameAtNs < minInterval) return
            lastFrameAtNs = now

            runCatching { onFrame(toImageBitmap(pixels, width, height)) }
                .onFailure { AppLogger.warn("VideoPlayer", "帧转换失败: ${it.message}", it) }
        }
    }

    private val bufferFormatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(width: Int, height: Int): BufferFormat {
            prepareFrameBuffer(width, height)
            return RV32BufferFormat(width, height)
        }

        override fun newFormatSize(width: Int, height: Int, sarNum: Int, sarDen: Int) {
            prepareFrameBuffer(width, height)
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    }

    /**
     * 给 [RenderCallbackAdapter] 准备目标数组。
     *
     * 这一步**不能省**：vlcj 的适配器自己不会分配 `int[]`，`display()` 里直接
     * `IntBuffer.get(dst)`，dst 为 null 时会抛 NPE（JNA 只打一条警告，画面永远不来）。
     * 分辨率变化（切码率）时要按新尺寸重新分配。
     */
    private fun prepareFrameBuffer(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        frameWidth = width
        frameHeight = height
        val needed = width * height
        if (needed == bufferPixels) return
        bufferPixels = needed
        renderCallback.setBuffer(IntArray(needed))
    }

    private val eventListener = object : MediaPlayerEventAdapter() {
        override fun opening(mediaPlayer: MediaPlayer) {
            update { it.copy(phase = PlaybackPhase.Opening, buffering = true) }
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            // length() 要等首个关键帧解出来才可靠；直播流返回 0
            val length = runCatching { mediaPlayer.status().length() }.getOrDefault(0L)
            update {
                it.copy(
                    phase = PlaybackPhase.Playing,
                    buffering = false,
                    lengthMs = if (length > 0) length else it.lengthMs,
                )
            }
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            update { it.copy(phase = PlaybackPhase.Paused, buffering = false) }
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            // 主动 stop() 与播放结束都会走到这里；已结束/失败的状态不要被覆盖
            update {
                if (it.phase == PlaybackPhase.Ended || it.phase == PlaybackPhase.Failed) it
                else it.copy(phase = PlaybackPhase.Idle, buffering = false)
            }
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            update { it.copy(phase = PlaybackPhase.Ended, buffering = false, timeMs = it.lengthMs) }
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            update { it.copy(timeMs = newTime) }
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            update { it.copy(lengthMs = newLength) }
        }

        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            update { it.copy(buffering = newCache < 100f) }
        }

        override fun error(mediaPlayer: MediaPlayer) {
            // 带 Cookie 的地址大概率是被源站按会话拦下来的：libvlc 3 注入不了 Cookie，
            // 重试多少次都一样。文案要指向「下载后看」，而不是让用户对着黑屏反复点重试
            val error = playbackErrorFor(lastRequest?.cookie)
            update { it.copy(phase = PlaybackPhase.Failed, buffering = false, error = error) }
        }
    }

    init {
        val surface = factory.videoSurfaces().newVideoSurface(bufferFormatCallback, renderCallback, true)
        player.videoSurface().set(surface)
        player.events().addMediaPlayerEventListener(eventListener)
    }

    /**
     * 起播。
     *
     * @param cachingMs 网络缓冲毫秒数；m3u8 给大一点
     */
    override fun play(request: PlaybackRequest, cachingMs: Int) {
        lastRequest = request
        lastCachingMs = cachingMs
        onFrame(null)
        lastFrameAtNs = 0L
        emit(
            PlaybackState(
                phase = PlaybackPhase.Opening,
                url = request.url,
                title = request.title,
                buffering = true,
                volume = volume,
                muted = muted,
            ),
        )
        runCatching {
            val options = VlcOptions.mediaOptions(request, cachingMs)
            player.media().play(request.url, *options.toTypedArray())
            // 音量是播放器级设置，新 media 起播后再压一次，保证切档/重播后仍然生效
            applyAudio()
        }.onFailure { e ->
            AppLogger.warn("VideoPlayer", "起播失败: ${request.url}", e)
            update {
                it.copy(phase = PlaybackPhase.Failed, buffering = false, error = PlaybackError.Unknown(e.message))
            }
        }
    }

    /**
     * 记录播放区域尺寸；下一次帧回调就按这个尺寸降采样。
     *
     * 只存不重算：尺寸变化不需要重新分配缓冲，下一帧自然会走新的抽取倍数。
     */
    override fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportWidth = width
        viewportHeight = height
    }

    /** 播放/暂停切换；已结束或失败时重新起播 */
    override fun togglePause() {
        when (state.phase) {
            PlaybackPhase.Playing -> runCatching { player.controls().setPause(true) }
            PlaybackPhase.Paused -> runCatching { player.controls().setPause(false) }
            PlaybackPhase.Ended, PlaybackPhase.Failed -> replay()
            else -> Unit
        }
    }

    /** 重播：用同一个地址与参数再来一次 */
    fun replay() {
        val request = lastRequest ?: return
        // startMs 是「切换清晰度时接着看」用的，重播必须回到 0，否则会卡在同一个位置
        play(request.copy(startMs = 0L), lastCachingMs)
    }

    /** 拖动进度（毫秒）；直播下无效 */
    override fun seekTo(positionMs: Long) {
        if (!state.canSeek) return
        val target = positionMs.coerceIn(0L, state.lengthMs.coerceAtLeast(0L))
        runCatching { player.controls().setTime(target) }
            .onSuccess { update { it.copy(timeMs = target) } }
    }

    /** 设置音量 0~100；拖到有声位置时顺手解除静音 */
    override fun setVolume(volume: Int) {
        val value = volume.coerceIn(0, MAX_VOLUME)
        this.volume = value
        if (value > 0) muted = false
        applyAudio()
        update { it.copy(volume = value, muted = muted) }
    }

    override fun toggleMute() {
        muted = !muted
        applyAudio()
        update { it.copy(muted = muted) }
    }

    /** 停止并清空画面（关闭播放页时调用） */
    override fun stop() {
        runCatching { player.controls().stop() }
        onFrame(null)
        lastFrameAtNs = 0L
        emit(PlaybackState(volume = volume, muted = muted))
    }

    /** 释放播放器；应用退出时调用，避免 libvlc 线程残留 */
    override fun release() {
        runCatching { player.events().removeMediaPlayerEventListener(eventListener) }
        runCatching { player.release() }
            .onFailure { AppLogger.warn("VideoPlayer", "释放播放器失败: ${it.message}", it) }
        onFrame(null)
    }

    /** 音量是 libvlc 的播放器级设置：换 media 后要重新压一次 */
    private fun applyAudio() {
        runCatching {
            player.audio().setVolume(volume)
            player.audio().setMute(muted)
        }.onFailure { AppLogger.warn("VideoPlayer", "设置音量失败: ${it.message}") }
    }

    /**
     * 统一的状态更新入口：P2-02 走原子 CAS，基于最新值重算，再把结果推给 ViewModel。
     */
    private fun update(block: (PlaybackState) -> PlaybackState) {
        onState(stateHolder.update(block))
    }

    private fun emit(next: PlaybackState) {
        onState(stateHolder.emit(next))
    }

    /**
     * RV32 帧 → [ImageBitmap]。
     *
     * RV32 的内存字节序是 B,G,R,A（int 值读出来是 0xAARRGGBB），对应 Skia 的
     * `BGRA_8888`；用 `asIntBuffer().put()` 批量搬运比逐像素拆字节快一个数量级。
     *
     * **按窗口尺寸整数倍抽取（最近邻）**：Compose 用 `ContentScale.Fit` 放大，
     * 视觉上几乎无损，但分配量按倍数平方下降 —— 1080p 源在 700px 高的窗口里
     * 从每帧 8.3 MB 降到约 2 MB。没拿到窗口尺寸时按 1080p 封顶。
     *
     * `Image.makeRaster` 不复制像素数据，所以每帧都必须是一块新的 ByteArray ——
     * 复用缓冲区会让还在显示的那一帧被下一帧覆盖（画面撕裂）。
     */
    private fun toImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap {
        val factor = downsampleFactor(width, height)
        val outWidth = (width / factor).coerceAtLeast(1)
        val outHeight = (height / factor).coerceAtLeast(1)
        val bytes = ByteArray(outWidth * outHeight * 4)
        val target = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        if (factor == 1) {
            target.put(pixels, 0, width * height)
        } else {
            // 最近邻：直接取每个块的左上角像素，不做插值
            for (y in 0 until outHeight) {
                val row = y * factor * width
                for (x in 0 until outWidth) {
                    target.put(pixels[row + x * factor])
                }
            }
        }
        val info = ImageInfo(outWidth, outHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        return Image.makeRaster(info, bytes, outWidth * 4).asImageBitmap()
    }

    /**
     * 抽取倍数：取宽、高两个方向里更「需要缩」的那个，向上取整。
     *
     * 向上取整是为了让 1080p 在 700px 高的区域里真的降到 540p（4 倍像素），
     * 而不是因为整数除法得到 1 倍、白白保持原尺寸。
     */
    private fun downsampleFactor(width: Int, height: Int): Int {
        val maxWidth = viewportWidth.takeIf { it > 0 }?.coerceAtMost(MAX_FRAME_WIDTH) ?: MAX_FRAME_WIDTH
        val maxHeight = viewportHeight.takeIf { it > 0 }?.coerceAtMost(MAX_FRAME_HEIGHT) ?: MAX_FRAME_HEIGHT
        val byWidth = (width + maxWidth - 1) / maxWidth
        val byHeight = (height + maxHeight - 1) / maxHeight
        return maxOf(1, byWidth, byHeight)
    }
}
