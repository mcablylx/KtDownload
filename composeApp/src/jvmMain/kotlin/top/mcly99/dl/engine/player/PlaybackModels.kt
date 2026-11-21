package top.mcly99.dl.engine.player

import top.mcly99.dl.engine.web.MediaCandidate
import top.mcly99.dl.engine.web.MediaKind
import top.mcly99.dl.utils.AppConstants
import java.util.Locale

/**
 * 一次播放请求。
 *
 * @property url 媒体地址（m3u8 或 mp4）
 * @property referer 发现它的页面地址，作为防盗链 `Referer`；手动播放时可为空
 * @property cookie 会话 `Cookie`（只有深度抓取会带）。签名地址常把会话绑进去，
 *   下载侧早就带上了，播放侧原来没有 —— 于是「能下不能播」（v7-P1-1）
 * @property title 界面上显示的名称，空串表示由 UI 兜底
 * @property startMs 起播位置（毫秒）。切换清晰度时用它「接着看」；0 表示从头开始
 * @property audioUrl 独立音轨地址（主列表 `#EXT-X-MEDIA` 的 URI）；切档后子列表里没有它，
 *   只能单独交给 libvlc
 */
data class PlaybackRequest(
    val url: String,
    val referer: String? = null,
    val cookie: String? = null,
    val title: String = "",
    val startMs: Long = 0L,
    val audioUrl: String? = null,
) {
    companion object {
        fun of(candidate: MediaCandidate, displayName: String = candidate.name) = PlaybackRequest(
            url = candidate.url,
            referer = candidate.referer,
            // 候选上的 Cookie 必须跟着走：少了它，深度抓到的地址一点播放就 403
            cookie = candidate.cookie,
            title = displayName,
        )
    }
}

/** 播放器所处阶段；UI 只按这个枚举分支，不看 libvlc 的内部状态 */
enum class PlaybackPhase {
    /** 还没开始播 */
    Idle,

    /** 已发起 play，等首个关键帧（m3u8 要拉播放列表 + 首个分片，这一步明显比 mp4 慢） */
    Opening,
    Playing,
    Paused,

    /** 正常播完（点播才有） */
    Ended,

    /** 起播失败或中途出错 */
    Failed,
    ;

    val isActive: Boolean get() = this == Opening || this == Playing || this == Paused
}

/**
 * 播放失败的原因。
 *
 * 用类型而不是字符串：引擎层不该知道界面语言，文案由 UI 用 `UiStrings` 映射
 * （否则英文界面会显示「Cannot play: 播放器报错」这种中英混排）。
 */
sealed interface PlaybackError {
    /** 播放内核不可用：没装 VLC / libvlc 加载失败 */
    data object EngineUnavailable : PlaybackError

    /** libvlc 报错（解码失败、网络中断等） */
    data object MediaError : PlaybackError

    /**
     * 起播失败且这条请求**带着会话 Cookie** —— 大概率是源站要求登录态。
     *
     * 为什么单独一类：libvlc 3 的 http access 没有「注入自定义 Cookie」的参数
     * （2026-10-28 实测：`:http-cookie=` 在分片请求上一条都没带，而同一次运行里
     * `:http-referrer` 正常生效）。也就是说这类地址**在播放侧无解**，只能下载后看。
     * 不单列这一类，用户看到的就是「播放器报错」，会一直重试。
     */
    data object SessionRequired : PlaybackError

    /** 其他失败，[detail] 是原始异常信息，仅用于兜底展示 */
    data class Unknown(val detail: String? = null) : PlaybackError
}

/**
 * libvlc 报「error」时该归成哪一类。
 *
 * 抽成纯函数只为一件事：这条判定错了，用户看到的就是「播放器报错 + 重试」，
 * 而实际上重试永远不会成功（libvlc 3 注入不了 Cookie）。真机才发现代价太高。
 */
internal fun playbackErrorFor(cookie: String?): PlaybackError =
    if (cookie != null) PlaybackError.SessionRequired else PlaybackError.MediaError

/** 默认音量：libvlc 的音量范围是 0~100，100 即满音量 */
const val DEFAULT_VOLUME = 100

/**
 * 播放器对外的全部状态。
 *
 * @property timeMs 当前播放位置
 * @property lengthMs 总时长；**0 表示直播**（libvlc 对无 ENDLIST 的流返回 0）
 * @property buffering 是否在缓冲（弱网下会频繁翻转，UI 只做轻提示）
 * @property error 失败原因，仅 [PlaybackPhase.Failed] 时有值
 * @property volume 音量 0~100（0 不等于静音：静音是独立开关）
 * @property muted 是否静音
 */
data class PlaybackState(
    val phase: PlaybackPhase = PlaybackPhase.Idle,
    val url: String = "",
    val title: String = "",
    val timeMs: Long = 0L,
    val lengthMs: Long = 0L,
    val buffering: Boolean = false,
    val error: PlaybackError? = null,
    val volume: Int = DEFAULT_VOLUME,
    val muted: Boolean = false,
) {
    /**
     * 是否直播。
     *
     * 只在**已经开始播**之后判断：起播瞬间（[PlaybackPhase.Opening]）`length()` 还没解析出来，
     * 同样是 0，这时候判成直播会让点播也闪一下「直播」标签。开播前 UI 显示不确定态进度条即可。
     */
    val isLive: Boolean
        get() = (phase == PlaybackPhase.Playing || phase == PlaybackPhase.Paused) && lengthMs <= 0L

    /** 是否可拖动进度条：直播、未开播、已结束都不可拖 */
    val canSeek: Boolean get() = !isLive && phase.isActive

    /** 进度 0f~1f；不可拖动时返回 0 */
    val progress: Float
        get() = if (!canSeek || lengthMs <= 0L) 0f else (timeMs.toFloat() / lengthMs).coerceIn(0f, 1f)
}

/**
 * libvlc 启动参数与媒体级参数。
 *
 * 抽成纯函数是为了能单测：真正起播必须人工验证，但「参数拼错了」这类问题不该靠肉眼看。
 */
object VlcOptions {

    /**
     * `MediaPlayerFactory` 级参数（一个进程一份，作用于所有媒体）。
     *
     * UA 放在这一层是刻意的：它是常量，而且 HLS 的分片请求由 libvlc 内部另建 access，
     * 只有工厂级参数能保证一定带上。`Referer` 反过来是**逐条不同**的，放媒体级。
     */
    fun factoryArgs(): List<String> = listOf(
        "--no-video-title-show",
        "--http-user-agent=${AppConstants.CATCH_WEB_USER_AGENT}",
    )

    /**
     * 媒体级参数（`:xxx` 前缀 = 只作用于这个 media）。
     *
     * @param cachingMs 网络缓冲毫秒数；m3u8 起播本来就慢，缓冲给大一点能少卡顿
     */
    fun mediaOptions(request: PlaybackRequest, cachingMs: Int = DEFAULT_CACHING_MS): List<String> =
        buildList {
            request.referer?.takeIf { it.isNotBlank() }?.let { add(":http-referrer=$it") }
            add(":http-user-agent=${AppConstants.CATCH_WEB_USER_AGENT}")
            // 会话 Cookie（v7-P1-1）。**2026-10-28 实测：libvlc 3.0.21 不支持这个选项。**
            // 证据：`VlcPlaybackSmokeTest.hls_segments_carry_injected_cookie` 用本地 HLS 服务器
            // 记录请求头，同一次运行里 `:http-referrer` 在分片请求上正常生效、
            // `:http-cookie` 一条都没带 —— 说明「媒体级选项 → 子请求」链路是通的，只是选项名不存在。
            // 这里仍然传：对不认识的选项 libvlc 只打一条 unknown option 警告并忽略，
            // 不会崩；将来换 libvlc 4 若支持了就自动生效（重跑那条冒烟用例即可确认）。
            // 真正需要登录态的地址由 `VideoPlayer` 的 `SessionRequired` 兜底文案引导去下载。
            request.cookie?.takeIf { it.isNotBlank() }?.let { add(":http-cookie=$it") }
            add(":network-caching=$cachingMs")
            // 子播放列表里没有 #EXT-X-MEDIA，独立音轨只能这样单独交给 libvlc，否则切档后没声音
            request.audioUrl?.takeIf { it.isNotBlank() }?.let { add(":input-slave=$it") }
            // 切换清晰度时从原位置接着播：HLS 的主列表地址与子列表地址时长一致，
            // 直接 :start-time 定位比「播起来再 seek」稳（后者会先闪一下第 0 秒）
            if (request.startMs > 0L) add(":start-time=${formatStartSeconds(request.startMs)}")
        }

    /**
     * 毫秒 → `12.345`（秒，三位小数）。
     *
     * 必须用 `Locale.ROOT`：默认 Locale 下某些地区会把小数点写成逗号，
     * 于是参数变成 `:start-time=12,345` —— libvlc 会当成非法值静默从头播。
     */
    internal fun formatStartSeconds(millis: Long): String =
        String.format(Locale.ROOT, "%.3f", millis / 1000.0)

    /** m3u8 的缓冲比 mp4 大：分片要串行拉，缓冲太小会一直「缓冲中」 */
    fun cachingFor(kind: MediaKind): Int = when (kind) {
        MediaKind.M3u8 -> 3_000
        else -> DEFAULT_CACHING_MS
    }

    private const val DEFAULT_CACHING_MS = 1_000
}
