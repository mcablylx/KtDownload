package top.mcly99.dl.theme

/**
 * 「设置 → 开源许可」弹窗的文案与第三方组件清单。
 *
 * 存在理由：`THIRD-PARTY-NOTICES.md` 的合规清单里有一条「应用内上游署名」长期未勾。
 * 仓库根目录的声明文件只有拿到源码的人看得到，最终用户看不到；AGPL-3.0 §5(a)/§13
 * 要求的显著署名因此并不成立。这里把清单作为 [UiStrings] 的一个**分组字段**挂载
 * （`UiStrings` 已接近 JVM 方法参数上限 255，不能再平铺字段），由设置页弹窗直接渲染。
 *
 * 清单是代码里的数据，而不是运行时读取 `THIRD-PARTY-NOTICES.md`：打包后的应用不保证
 * 携带仓库根目录的 md 文件，许可全文则是随 `resources/common/` 分发的。两者需人工同步，
 * `LicenseStringsTest` 锁住关键字段（AGPL 条目、上游仓库、快照版本、许可全文路径）。
 *
 * 本文件是 KtDownload 自有实现，不含任何 YunX-Desktop 源码。
 */

/** 单个第三方组件的署名信息，字段与 `THIRD-PARTY-NOTICES.md` 的表格逐项对应。 */
data class LicenseEntry(
    val name: String,
    val license: String,
    val integration: String,
    val sourceUrl: String,
    val notes: String,
)

data class LicenseStrings(
    val title: String,
    val subtitle: String,
    /** AGPL 覆盖整个组合作品的警示，必须在清单之前显示。 */
    val warning: String,
    val licenseLabel: String,
    val integrationLabel: String,
    val openUpstream: String,
    val openFolder: String,
    val close: String,
    val components: List<LicenseEntry>,
)

internal val zhCNLicenses = LicenseStrings(
    title = "开源许可与第三方署名",
    subtitle = "本程序使用了下列第三方组件。许可全文随安装包分发，可点击下方按钮打开所在目录。",
    warning = "本项目整体以 GNU AGPL-3.0 开源，完整对应源代码按 AGPL-3.0 §13 随本仓库提供。" +
        "YunX-Desktop 网盘解析器以 AGPL-3.0 源码编译进程序，根 LICENSE 已统一为 AGPL-3.0；" +
        "上游署名与修改记录见 SOURCE.txt。",
    licenseLabel = "许可证",
    integrationLabel = "集成方式",
    openUpstream = "打开上游源码",
    openFolder = "打开许可目录",
    close = "关闭",
    components = listOf(
        LicenseEntry(
            name = "FFmpeg",
            license = "LGPL-3.0-or-later",
            integration = "外部子进程调用（未链接，属于单纯聚合）",
            sourceUrl = "https://ffmpeg.org/legal.html",
            notes = "用于 HLS(m3u8) 合轨。许可全文：resources/common/ffmpeg/LICENSE.txt；" +
                "ffmpeg 是独立可执行文件，用户可自行替换。",
        ),
        LicenseEntry(
            name = "VLC / libvlc",
            license = "LGPL-2.1",
            integration = "经 JNA 动态链接的可替换共享库",
            sourceUrl = "https://www.videolan.org/vlc/",
            notes = "播放器内核。许可全文：resources/common/vlc/COPYING.LIB.txt；" +
                "动态库与插件位于 resources/<platform>/vlc，可替换。",
        ),
        LicenseEntry(
            name = "JCEF / Chromium (jcefmaven)",
            license = "Apache-2.0 / BSD-style",
            integration = "运行时加载原生二进制，提供应用内网页登录",
            sourceUrl = "https://github.com/jcefmaven/jcefmaven",
            notes = "用于网盘网页登录。内核随包分发（Windows amd64 / macOS amd64 / macOS arm64 / " +
                "Linux amd64），运行时零下载；首次使用只在本地解包到 ~/.mclydownload/jcef。" +
                "注意：登录流程代码改编自 YunX-Desktop，因此该部分同样受下方 AGPL-3.0 覆盖。",
        ),
        LicenseEntry(
            name = "YunX-Desktop cloud-drive resolver",
            license = "GNU AGPL-3.0",
            integration = "源码编译进本程序：56 个上游文件 / 13,670 行 + 4 个兼容 shim",
            sourceUrl = "https://github.com/tidain/YunX-Desktop",
            notes = "快照 v1.3.4（tag）@ commit 00a030f7，含 19 处下游改写。移植文件清单、" +
                "逐项改动与快照 SHA-256 见 third-party/yunx-desktop/SOURCE.txt；许可全文见 " +
                "resources/common/yunx-desktop/LICENSE.txt。本条署名为 AGPL-3.0 §5(a)/§13 要求。",
        ),
    ),
)

internal val enUSLicenses = LicenseStrings(
    title = "Open Source Licenses & Attribution",
    subtitle = "This application uses the third-party components below. Full license texts ship " +
        "with the package; use the button below to open their folder.",
    warning = "This project as a whole is licensed under GNU AGPL-3.0; the complete Corresponding " +
        "Source is provided in this repository under AGPL-3.0 §13. The YunX-Desktop resolver " +
        "is AGPL-3.0 source compiled into the program, the root LICENSE is AGPL-3.0, and " +
        "upstream attribution is recorded in SOURCE.txt.",
    licenseLabel = "License",
    integrationLabel = "Integration",
    openUpstream = "Open upstream source",
    openFolder = "Open license folder",
    close = "Close",
    components = listOf(
        LicenseEntry(
            name = "FFmpeg",
            license = "LGPL-3.0-or-later",
            integration = "External subprocess (not linked; mere aggregation)",
            sourceUrl = "https://ffmpeg.org/legal.html",
            notes = "Used for HLS (m3u8) remux. Full text: resources/common/ffmpeg/LICENSE.txt. " +
                "ffmpeg is a standalone executable and can be replaced by the user.",
        ),
        LicenseEntry(
            name = "VLC / libvlc",
            license = "LGPL-2.1",
            integration = "Dynamically linked via JNA, replaceable shared library",
            sourceUrl = "https://www.videolan.org/vlc/",
            notes = "Playback engine. Full text: resources/common/vlc/COPYING.LIB.txt. " +
                "Libraries and plugins live in resources/<platform>/vlc and are replaceable.",
        ),
        LicenseEntry(
            name = "JCEF / Chromium (jcefmaven)",
            license = "Apache-2.0 / BSD-style",
            integration = "Native binaries loaded at runtime for in-app web login",
            sourceUrl = "https://github.com/jcefmaven/jcefmaven",
            notes = "Powers the netdisk web login. The kernel ships with the package (Windows amd64 / " +
                "macOS amd64 / macOS arm64 / Linux amd64) and is unpacked locally to ~/.mclydownload/jcef, " +
                "so nothing is downloaded at runtime. " +
                "The login flow itself is adapted from YunX-Desktop and is therefore covered by the " +
                "AGPL-3.0 entry below.",
        ),
        LicenseEntry(
            name = "YunX-Desktop cloud-drive resolver",
            license = "GNU AGPL-3.0",
            integration = "Source compiled into this program: 56 upstream files / 13,670 lines + 4 shims",
            sourceUrl = "https://github.com/tidain/YunX-Desktop",
            notes = "Snapshot v1.3.4 (tag) @ commit 00a030f7, with 19 downstream-modified files. " +
                "The file list, all modifications and the snapshot SHA-256 are recorded in " +
                "third-party/yunx-desktop/SOURCE.txt; full license text: " +
                "resources/common/yunx-desktop/LICENSE.txt. This attribution is required by " +
                "AGPL-3.0 §5(a)/§13.",
        ),
    ),
)
