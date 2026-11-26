package top.mcly99.dl.data.network

import top.mcly99.dl.data.AppContext
import top.mcly99.dl.data.ShimPrefs
import java.util.concurrent.atomic.AtomicReference

/**
 * 迅雷网页 captcha 签名参数（client_version + 9 条盐）的**运行时配置**（P1-02）。
 *
 * 背景：`pan.xunlei.com` 网页 bundle 会轮换 client_version 与 9 条盐（约 20 天一次）。
 * 1.0.3 把它们硬编码在 [XunleiWebCredential] 里，一旦服务端拒绝旧签名，游客与网页登录态
 * 会同时被阻断，且**只能发版修**——这就是走查报告说的「无自愈的时效炸弹」。
 *
 * 这里把参数从编译期常量改成单一数据源：
 * - [DEFAULT] 是当前抓包得到的内置快照，保持与 1.0.3 完全一致（签名基准值不变）；
 * - [XunleiWebSignConfig.apply] 允许运行时下发新参数（校验后立即生效并持久化），
 *   将来接远程配置只需调用一次，无需重新打包；
 * - 本地也可直接编辑 `~/.mclydownload/yunx-misc.properties` 的
 *   `xunlei.web.clientVersion` / `xunlei.web.salts`（`|` 分隔）后重启或调 [load]。
 *
 * 盐内容属于签名密钥，[XunleiWebSignConfig.diagnostics] 只暴露版本与盐数量，绝不落日志。
 */
data class WebSignConfig(
    val clientVersion: String,
    val salts: List<String>,
) {
    /** 完整有效才允许替换内置默认：版本非空、恰好 9 条盐、每条盐非空。 */
    val isValid: Boolean
        get() = clientVersion.isNotBlank() &&
            salts.size == SALTS_NEEDED &&
            salts.all { it.isNotEmpty() }

    companion object {
        const val SALTS_NEEDED = 9

        /** 内置快照：与 1.0.3 硬编码值逐字一致，`XunleiGuestCaptchaTest` 的基准签名依赖它。 */
        const val DEFAULT_CLIENT_VERSION = "1.93.10"

        /** 9 条盐，顺序敏感（来自网页版 public-key 链）。轮换后通过 [XunleiWebSignConfig.apply] 整组替换。 */
        val DEFAULT_SALTS: List<String> = listOf(
            "DVM8BRQfJWDsIE0JbH5GCUM0z0jU1yGymBTj4Eyloazv1F+w0G7ig",
            "T",
            "a1fUxfjOO1/dW++DpGupRgw9P93L",
            "aktKbp+/lQc3MvlMHIupt65LjAgJOL6ttMFvTKK+7+WKubSf9rT",
            "SB/ne9FQUnQmWz4Wuv9KqKfGOBOCUubpHpECxOHfD4hNoMclYA2Yq",
            "sOZFBQ8IMFy3OFjAIf/eA65iqFvS9TIKzLyrhiT6",
            "ZhJdLgL7MGh2GanboB0yk",
            "Osf",
            "v",
        )

        val DEFAULT: WebSignConfig = WebSignConfig(DEFAULT_CLIENT_VERSION, DEFAULT_SALTS)
    }
}

/**
 * 当前生效的网页签名参数。
 *
 * 用 [AtomicReference] 保证「读参数 → 算签名 → 发请求」期间配置不会被下发线程改到一半；
 * [load] 在 object 初始化时自动执行一次，把本地覆盖值载入内存。
 */
object XunleiWebSignConfig {

    private const val KEY_VERSION = "xunlei.web.clientVersion"
    private const val KEY_SALTS = "xunlei.web.salts"
    private const val SEP = "|"

    private val current = AtomicReference(WebSignConfig.DEFAULT)

    /** 可替换以便测试使用临时目录，不污染 `~/.mclydownload/`。 */
    internal var prefsProvider: () -> ShimPrefs = { AppContext.miscPrefs }

    init {
        load()
    }

    /** 当前生效配置（每次签名现取，下发后下一个请求立即用新参数）。 */
    fun current(): WebSignConfig = current.get()

    /** 是否使用了非内置参数，供诊断与 UI 提示。 */
    fun isOverridden(): Boolean = current.get() != WebSignConfig.DEFAULT

    /**
     * 从本地配置载入覆盖值。版本或盐缺失时用内置默认补齐；参数不完整（盐不是 9 条等）
     * 一律忽略并保留当前值，绝不让一份坏配置把签名打瘫。
     */
    fun load() {
        val prefs = runCatching { prefsProvider() }.getOrNull() ?: return
        val version = prefs.get(KEY_VERSION, null)?.trim().orEmpty()
        val saltsRaw = prefs.get(KEY_SALTS, null)?.trim().orEmpty()
        if (version.isEmpty() && saltsRaw.isEmpty()) {
            current.set(WebSignConfig.DEFAULT)
            return
        }
        val candidate = WebSignConfig(
            clientVersion = version.ifEmpty { WebSignConfig.DEFAULT_CLIENT_VERSION },
            salts = if (saltsRaw.isEmpty()) WebSignConfig.DEFAULT_SALTS else saltsRaw.split(SEP),
        )
        if (candidate.isValid) current.set(candidate)
    }

    /**
     * 运行时下发一组新参数：校验通过立即生效并持久化，供后续远程配置接入。
     *
     * @return false 表示参数不完整（盐不是 9 条 / 版本为空），已忽略且当前配置不变。
     */
    fun apply(version: String, salts: List<String>): Boolean {
        val candidate = WebSignConfig(version.trim(), salts.map { it.trim() })
        if (!candidate.isValid) return false
        current.set(candidate)
        runCatching {
            val prefs = prefsProvider()
            prefs.put(KEY_VERSION, candidate.clientVersion)
            prefs.put(KEY_SALTS, candidate.salts.joinToString(SEP))
        }
        return true
    }

    /** 恢复内置默认并清掉本地覆盖（ShimPrefs 无 remove，写空串即视为无覆盖）。 */
    fun reset() {
        current.set(WebSignConfig.DEFAULT)
        runCatching {
            val prefs = prefsProvider()
            prefs.put(KEY_VERSION, "")
            prefs.put(KEY_SALTS, "")
        }
    }

    /** 诊断用摘要：只给版本与盐数量，绝不输出盐本身。 */
    fun diagnostics(): String {
        val c = current.get()
        return "clientVersion=${c.clientVersion}, salts=${c.salts.size}, overridden=${isOverridden()}"
    }
}
