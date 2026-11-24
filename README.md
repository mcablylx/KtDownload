# KtDownload 使用与开发说明

> 当前版本：**1.0.0**  
> 工程名称：`KtDownload`  
> 应用名称：KtDownload  
> 开源协议：**GNU Affero General Public License v3.0（AGPL-3.0）**  
> 版本唯一来源：`gradle.properties` 中的 `appVersion`

---

## 目录

- [项目简介](#项目简介)
- [鸣谢与上游代码关系](#鸣谢与上游代码关系)
- [功能总览](#功能总览)
- [快速开始](#快速开始)
  - [离线打包（无需下载 FFmpeg / libvlc）](#离线打包无需下载-ffmpeg--libvlc)
- [使用说明](#使用说明)
- [配置项](#配置项)
- [网络协议与端口](#网络协议与端口)
- [技术栈与架构](#技术栈与架构)
- [目录结构](#目录结构)
- [已知限制](#已知限制)
- [许可证与第三方组件](#许可证与第三方组件)

---

## 项目简介

KtDownload 是一款基于 Kotlin Multiplatform 与 Compose Multiplatform（Desktop）的跨平台桌面工具，围绕“下载”这一核心场景提供四组能力：

| 模块 | 说明 |
| --- | --- |
| 下载引擎 | HTTP/HTTPS 多线程分块下载、M3U8/HLS 流媒体下载、断点续传、并发队列、限速与代理 |
| 抓网页 | 从网页中解析 m3u8 / mp4 等媒体地址，支持静态解析与基于本机浏览器的深度抓取 |
| 在线观看 | 应用内直接播放媒体地址，支持 HLS 清晰度切换与直播/点播分流 |
| 我的网盘 | 统一页面管理 10 个主流网盘平台的文件、账号、直链下载与转存 |
| 局域网传输 | UDP 设备发现、TCP 点对点发送文件、内置 HTTP 网页上传服务 |
| 桌面集成 | 系统托盘、通知、剪贴板监听、单实例保护、中英双语、深浅主题 |

---

## 鸣谢与上游代码关系

本项目在实现“我的网盘”能力时借鉴并移植了开源项目 **YunX-Desktop** 的部分源码：

- 项目地址：<https://github.com/tidain/YunX-Desktop>
- 移植快照：`main` 分支 `00a030f7`，tag **v1.3.4**（2026-11-13）
- 移植内容：网盘协议解析、直链获取、登录流程等共 56 个源文件，约 13,670 行代码；部分文件按本项目包结构与日志体系做了改写
- 来源清单：见 [`third-party/yunx-desktop/SOURCE.txt`](third-party/yunx-desktop/SOURCE.txt)
- 许可全文：见 [`third-party/yunx-desktop/LICENSE.txt`](third-party/yunx-desktop/LICENSE.txt)

YunX-Desktop 使用 **AGPL-3.0** 协议。由于 AGPL-3.0 的代码以源码形式编译进同一个程序，整体分发必须采用与 AGPL-3.0 兼容的许可，单纯的 MIT 声明无法覆盖，二者存在 copyleft 冲突。

本新工程已按以下方式解决冲突：

1. 将**整个新工程**统一按 **AGPL-3.0** 开源，根目录 [`LICENSE`](LICENSE) 使用 AGPL-3.0 全文；
2. 保留所有移植文件的上游来源头、修改记录与上游许可全文；
3. 在应用“设置 → 开源许可与署名”及 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 中明确署名上游项目；
4. 完整对应源代码随本仓库提供，可通过本仓库地址获取。

感谢 tidain 及 YunX-Desktop 贡献者的开源工作。

---

## 功能总览

### 下载引擎

- **多线程分块下载**：单文件可配置 1 / 2 / 4 / 8 / 16 / 32 / 64 线程；按字节均分为多个块，通过 `Range: bytes=start-end` 并发写入 `*.partN`，完成后原子合并。
- **智能策略**：先用 HEAD 探测大小，再用 `Range: bytes=0-0` 判断是否支持续传；小文件或不支持 Range 时自动退回单线程。
- **并发队列**：同时下载任务数可配置（默认 3），超出上限的任务进入排队状态，任务结束后自动拉起下一个。
- **完整状态机**：`QUEUED → DOWNLOADING → MERGING → COMPLETED`，并区分 `PAUSED`、`FAILED`、`CANCELLED`；取消不会被误标为失败。
- **断点续传**：单线程按本地文件长度续传；多线程用 `*.partN.offset` 记录分片偏移。暂停与失败保留分片，取消与删除任务才清理。
- **内容指纹**：记录 ETag / Last-Modified 并配合 `If-Range` 校验，避免 CDN 错误页或远端内容替换导致错误续传。
- **完整性校验**：每个分片校验落盘长度，合并先写中间文件再原子替换，避免半成品文件。
- **任务控制**：添加、开始、暂停、继续、取消、删除、打开所在目录；支持批量开始、全部暂停和清理已完成。
- **进度与速度**：线程安全追踪器汇总各分片进度，速度使用滑动窗口采样，显示百分比、已下载量与预计剩余时间。
- **任务持久化**：任务列表写入 `~/.mclydownload/tasks.properties`，2 秒防抖并原子替换；重启后恢复任务，下载中任务以暂停状态恢复，避免偷偷占用带宽。
- **全局限速**：设置页可实时调整总带宽上限，令牌桶按所有连接共享，0 表示不限速。
- **HTTP 代理**：支持主机 + 端口形式的 HTTP 代理，新连接立即生效。
- **默认目录**：Windows 优先 `D:\download`，否则 `~/Downloads`；macOS / Linux 使用 `~/Downloads`。重名文件自动追加 `(1)`、`(2)`。
- **搜索与筛选**：按文件名、URL 或保存路径模糊匹配；侧边栏“进行中”统一包含下载中、暂停和排队任务。

### M3U8 / HLS 流媒体下载

- 解析 master / media 播放列表，自动选择最高带宽档位。
- 支持 AES-128 解密、`#EXT-X-MAP`（fMP4）、TS 分片、省略 offset 的 `#EXT-X-BYTERANGE` 与独立音轨。
- 拒绝非 `identity` 的 DRM `KEYFORMAT`、SAMPLE-AES 与加密的初始化段。
- 分片并发下载，逐片记录身份签名（URI、时长、区间、密钥、落盘长度），升级或内容变化后只重下失效分片。
- 有 FFmpeg 时用 `-c copy` 转封装为 MP4；fMP4 播放列表可直拼，不依赖 FFmpeg。
- 合成前检查可用空间，峰值约为成品的 2 倍；任务卡片可单独清理分片目录。
- 失败重试仅覆盖超时、5xx、408、429；429 优先遵循 `Retry-After`。
- 播放列表、密钥、初始化段和分片均有大小与数量上限保护。

### 抓网页

- 主动请求页面并解析 HTML，不依赖浏览器插件或网络拦截。
- 三条提取通道：标签属性、内联脚本绝对地址、引号内相对路径。
- 统一还原 `\/`、`\u0026`、`\x26`、`&amp;` 等转义写法后再扫描，兼容常见国内站点。
- 过滤非媒体地址与 `.ts` / `.m4s` 分片，按完整 URL 去重，结果按 m3u8 > mp4 > 其它排序，最多 200 条。
- iframe 最多跟随一层、最多 3 个；主页面有可播结果时不再跟 iframe。
- 每条结果记住来源页面地址，下载时作为 `Referer` 贯穿探测、分块与 HLS 分片请求。
- 可选“探测文件大小”：按 4 并发 HEAD / Range 获取体积；可选“深度抓取”：用本机 Chrome / Edge 经 CDP 监听真实网络请求，拿到运行时签名地址与当次会话 Cookie。
- 深度抓取的会话 Cookie 不写磁盘；任务重启后提示重新抓取，而不是发起注定失败的请求。
- 身份头按来源区分：抓网页任务使用浏览器 UA，手动添加任务自报 `KtDownload/1.0.0`；跳转不同 host 时不携带 Cookie。

### 在线观看

- 使用 VLCJ 4 与随包 libvlc 3，不要求用户另外安装播放器。
- libvlc 解码到内存后由 Compose 渲染，无 AWT 重层，弹窗与菜单不会被画面遮挡。
- 直播 / 点播自动分流：直播隐藏进度条并显示“直播”标记，点播可拖动进度。
- m3u8 主播放列表多档位时提供清晰度下拉，切档从当前位置续播并保留独立音轨。
- 播放页支持音量调节与静音，切换清晰度或重播后保持。
- 播放内核懒加载，不点播放不加载；找不到内核时只提示，下载功能不受影响。
- DRM（Widevine / FairPlay）流无法播放；普通 AES-128 HLS 不受影响。

### 我的网盘

- 统一页面覆盖 10 个网盘平台：夸克、UC、迅雷、百度网盘、中国移动 139、123 云盘、115、光鸭、蓝奏优享、蓝奏云；GitHub 作为代码仓库入口单独处理。
- 支持登录状态展示、加密多账号池、主账号切换与按文件轮换账号取链。
- 应用内嵌 JCEF 浏览器完成平台登录，不调用系统浏览器；登录完成后左侧列表与右侧内容同步刷新。
- 文件管理支持：浏览目录、新建文件夹、重命名、移动、删除、创建分享、按平台有效期档位分享、转存他人分享链接、下载文件与查看容量。
- 每个平台按能力位显示按钮：不支持的操作直接隐藏，而不是点击后再报错。
- 网盘文件优先使用文件列表中的真实大小，避免取链接口占位值把任务总大小压成几 KB；容量在删除、转存、新建文件夹后自动刷新。
- 网盘直链下载携带逐平台请求头（UA、Referer、Origin、Sec-Ch-Ua），Cookie 不写入任务文件。
- 支持口令加密的账号备份与恢复、一键诊断包与崩溃日志归档。

### 局域网传输

- UDP 广播发现设备：向每块活跃网卡的定向广播地址发送 `DISCOVERY|v1|设备ID|设备名|Web端口`，并补发 `255.255.255.255`；10 秒标记离线、30 秒移除。
- TCP 点对点发送文件：连接 `ip:9877`，先发元数据行再发原始字节流，512 KB 缓冲区，约每 200ms 回报进度与速度；支持发送整个文件夹。
- TCP 接收服务：`9877` 端口常驻，文件名过滤非法字符与 Windows 保留设备名，落盘前用 canonical path 二次校验，拒绝目录穿越与超长文件。
- HTTP 网页上传：内置 `com.sun.net.httpserver`，默认 `8080` 端口；提供深色主题上传页，支持多文件、拖拽、逐文件进度与实时速度。
- 上传访问令牌：地址形如 `http://<IP>:<端口>/?token=xxxxxxxx`，缺失或不匹配返回 403，界面可一键复制。
- 上传防护：请求体 4 GB、单文件 2 GB、单次最多 64 个文件、并发 4 个；超限返回 413 / 429。
- 传输记录统一记录方向、文件、大小、对端、进度、速度与状态；目前仅保存在内存中。

### 桌面集成

- 系统托盘：显示窗口 / 退出；关闭窗口可最小化到托盘，托盘不可用时降级为正常退出。
- 单实例保护：通过 `~/.mclydownload/app.lock` 文件锁避免重复启动导致任务互相覆盖与端口争抢。
- 系统通知：下载完成与失败时通过托盘气泡提醒。
- 剪贴板监听：每 2 秒检查一次，发现 HTTP/HTTPS 直链或 m3u8 时自动填入添加任务对话框，可关闭。
- 开机后自动开始：启动时恢复未完成任务。
- 全局异常兜底：未捕获异常写入日志并弹出错误对话框，避免无控制台时静默崩溃。
- 运行日志：按天写入 `~/.mclydownload/logs/app-YYYY-MM-DD.log`，可用 `KTDL_DEBUG=1` 开启调试日志。
- 原生文件对话框：macOS 使用 AWT FileDialog，Windows / Linux 使用 Swing JFileChooser。
- 界面：Material Design 3，深色 / 浅色 / 跟随系统三种主题，`zh-CN` 与 `en-US` 双语即时切换。

---

## 快速开始

### 环境要求

| 项目 | 要求 |
| --- | --- |
| JDK | 21 或更高（构建插件要求 21） |
| Gradle | 使用仓库内的 Gradle Wrapper（8.14.5） |
| 操作系统 | Windows 10/11 x64、macOS 10.15+、Linux 桌面 |
| 内存 | 建议 4 GB 以上，构建 JVM 参数 `-Xmx3072M` |
| 网络 | 首次构建需访问 Google Maven / Maven Central；打包还需下载 libvlc、UPX 与 FFmpeg（均可预先离线预置，见下） |

> 若 IDE 报 Gradle JVM 版本不兼容，请在 IDE 的 Gradle 设置中选择 JDK 21。命令行构建使用仓库固定的 21 守护进程配置。

### 常用命令

```bash
# 运行开发版本
./gradlew :composeApp:run

# 编译
./gradlew :composeApp:compileKotlinJvm

# 运行全部 JVM 测试
./gradlew :composeApp:jvmTest

# 热重载开发（Compose Hot Reload）
./gradlew :composeApp:hotRunJvm

# 当前系统 release 包
./gradlew :composeApp:packageReleaseDistributionForCurrentOS
```

### Windows 打包与 FFmpeg（含离线构建）

Windows 打包 EXE / MSI 需安装 WiX Toolset 3。随包分发的 FFmpeg 二进制因体积（134 MB）超过 GitHub 单文件 100 MB 限制，**不入 Git 仓库**，由 `scripts/fetch-ffmpeg.ps1` 自动获取并做 SHA-256 校验。运行 `package-installer.bat` 会自动执行该脚本，也可单独运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-ffmpeg.ps1
```

获取优先级（命中即校验，全部失败才报错）：

1. 已存在且校验通过的 `resources/windows/ffmpeg/ffmpeg.exe` —— 完全离线
2. 环境变量 `KTDOWNLOAD_FFMPEG_ZIP` 指向的本地 zip —— 完全离线
3. `%GRADLE_USER_HOME%\vlcSetup\` 下的缓存 zip（与 libvlc/UPX 离线包同目录）—— 完全离线
4. 本项目 GitHub Release（tag `ffmpeg`）下载的 zip
5. 上游 FFmpeg-Builds（兜底，上游会删旧 tag，不保证长期可用）

**完全离线打包**：把以下 3 个压缩包预置到 `%GRADLE_USER_HOME%\vlcSetup\`（默认 `C:\Users\<用户>\.gradle\vlcSetup\`），即可在断网环境构建 Windows 包：

| 文件 | 用途 |
| --- | --- |
| `ffmpeg-n8.1.3-24-g92611900bb-win64-lgpl-8.1.zip` | FFmpeg（解压出 ffmpeg.exe） |
| `vlc-3.0.21.zip` | libvlc 播放内核 |
| `upx-4.2.4.zip` | UPX 压缩工具 |

单个包也可通过 `KTDOWNLOAD_FFMPEG_ZIP` 环境变量单独指定。若需更新 FFmpeg 版本，必须同时更新脚本里的 `$AssetName`、`$ExpectedSha256` 以及 `third-party/ffmpeg/SOURCE.txt`。

macOS 打包需在本机用 `scripts/fetch-ffmpeg-macos.sh` 编译 FFmpeg 并完成 ad-hoc 签名。

### 离线打包（无需下载 FFmpeg / libvlc）

仓库的 `third-party/binaries/` 随附了**校验过 SHA-256 的预置二进制**（macOS 版 FFmpeg 为 universal，同时支持 Intel 与 Apple Silicon；libvlc 为 universal 3.0.21）。clone 后直接打包即可，全程不联网、不现场编译：

```bash
git clone <repo-url>
cd KtDownload
bash package-macos.sh
```

打包脚本会自动完成以下还原（都在校验 SHA-256 后进行）：

- FFmpeg：从 `third-party/binaries/ffmpeg-macos-universal` 还原到 `resources/macos/ffmpeg/ffmpeg`
- libvlc：从 `third-party/binaries/vlc-3.0.21.dmg` 还原到 `~/.gradle/vlcSetup/`，供 vlcSetup 插件解压使用

预置文件缺失或损坏时，脚本会自动回退到原来的联网下载/源码编译路径（`--force` 可强制走源码编译）。因此网络良好的环境行为与之前完全一致。

> Windows / Linux 目前仍需联网获取 FFmpeg 与 libvlc（版本已锁定并带 SHA 校验），仅 macOS 提供离线预置包。升级任一二进制版本时，必须同步更新 `third-party/binaries/SOURCE.txt` 中记录的版本与 SHA，以及脚本/构建文件中的对应常量。

---

## 使用说明

1. **添加下载任务**：在顶部输入框粘贴 HTTP/HTTPS 直链或 m3u8 地址，也可从剪贴板自动填入；设置文件名、保存目录和线程数后确认。
2. **管理下载**：侧边栏按全部 / 进行中 / 已完成筛选；列表中可暂停、继续、取消、删除、打开所在目录，批量操作在工具栏中。
3. **抓网页**：进入“工具 → 抓网页”，粘贴页面地址。默认静态解析；若站点地址由 JavaScript 生成，在设置中打开“深度抓取”。
4. **在线播放**：抓到 m3u8 / mp4 后点击播放；多档位时可在播放页切换清晰度，`Esc` 返回列表。
5. **我的网盘**：左侧选择平台，未登录时点击“登录该平台”在应用内浏览器完成登录；登录后浏览文件、勾选后进行下载、删除、移动、分享或转存。
6. **局域网发送**：在设备列表中选择目标设备，选择文件或文件夹发送；设备发现开关在设置页即时生效。
7. **网页上传**：在“局域网传输”页复制带令牌的上传地址，用手机或其他电脑浏览器打开即可上传；端口与开关在设置页配置。

---

## 配置项

配置文件位于 `~/.mclydownload/config.properties`，使用 Java Properties 格式原子写入。

| 键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `downloadPath` | String | 见下 | 默认下载目录，同时作为局域网接收目录与网页上传落盘目录 |
| `maxConcurrent` | Int | `3` | 同时下载的最大任务数 |
| `defaultThreads` | Int | `8` | 新任务默认线程数，可选 1/2/4/8/16/32/64 |
| `autoStart` | Boolean | `true` | 启动时自动恢复未完成任务 |
| `clipboardWatch` | Boolean | `true` | 监听剪贴板并自动填入添加框 |
| `showNotifications` | Boolean | `true` | 下载完成 / 失败托盘通知 |
| `minimizeToTray` | Boolean | `true` | 关闭窗口时最小化到托盘 |
| `language` | String | `zh-CN` | `zh-CN` 或 `en-US` |
| `theme` | String | `dark` | `dark`、`light` 或 `system` |
| `lanServerEnabled` | Boolean | `true` | 是否开启网页上传服务 |
| `lanServerPort` | Int | `8080` | 网页上传端口，修改后约 3 秒防抖重启 |
| `speedLimit` | Long | `0` | 全局限速，字节/秒，0 表示不限速 |
| `proxyEnabled` / `proxyHost` / `proxyPort` | Boolean / String / Int | `false` / `""` / `1080` | 下载 HTTP 代理 |
| `lanDiscoveryEnabled` | Boolean | `true` | UDP 设备发现开关 |
| `keepHlsSegments` | Boolean | `false` | 合成成功后保留 HLS 分片目录 |
| `probeMediaSize` | Boolean | `false` | 抓网页后并发探测每条结果大小 |
| `deepCatchEnabled` | Boolean | `false` | 深度抓取，需本机 Chrome / Edge，无内核时自动降级 |

默认下载目录：Windows 优先 `D:\download`，否则 `%USERPROFILE%\Downloads`；macOS / Linux 为 `~/Downloads`。

---

## 网络协议与端口

| 端口 | 协议 | 用途 | 说明 |
| --- | --- | --- | --- |
| `9876` | UDP | 设备发现 | 广播间隔 2 秒；10 秒标记离线，30 秒移除 |
| `9877` | TCP | 点对点文件传输 | 元数据行 `FILE|文件名|字节数` + 原始字节流 |
| `8080`（可配置） | HTTP | 网页上传服务 | `GET /` 上传页，`POST /` multipart 上传 |

跨设备使用需在防火墙中放行上述端口。

---

## 技术栈与架构

| 分类 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.3.20 |
| UI | Compose Multiplatform 1.10.3，Material 3 |
| 架构 | MVVM + 单一状态源，Compose `mutableStateOf` / `StateFlow` |
| 协程 | KotlinX Coroutines 1.10.2 |
| 生命周期 | JetBrains Compose Lifecycle 2.10.0 |
| 网络 | JDK HttpURLConnection、DatagramSocket / ServerSocket、com.sun.net.httpserver |
| 媒体处理 | FFmpeg 8.1.3，外部进程调用，仅做 TS → MP4 转封装 |
| 浏览器内核 | JCEF / Chromium，用于网盘应用内登录 |
| 播放内核 | VLCJ 4 + libvlc 3，动态链接且可替换 |
| 构建 | Gradle 8.14.5、Kotlin Multiplatform、Compose Hot Reload |
| 打包 | Compose Desktop jpackage，输出 EXE / MSI / DMG / PKG |
| 目标平台 | JVM 桌面平台 |

代码按 `data`（配置与网络）、`engine`（下载引擎、网盘、传输、HLS）、`model`、`viewmodel`、`ui`、`theme`、`utils` 分层；下载状态机、任务存储、内容指纹与分片身份均为独立模块，便于回归验证。

---

## 目录结构

```text
KtDownload/
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradle/                          # Wrapper 与守护进程配置
├── composeApp/
│   ├── build.gradle.kts             # 依赖、打包参数、版本文件生成
│   └── src/
│       ├── jvmMain/kotlin/top/mcly99/dl/
│       │   ├── data/                # 设置、网络 API、仓库
│       │   ├── engine/              # 下载引擎、HLS、网盘、传输
│       │   ├── model/               # 任务模型与状态机
│       │   ├── viewmodel/           # 页面状态与交互
│       │   ├── ui/                  # Compose 页面与组件
│       │   ├── theme/               # 主题、字符串、许可文案
│       │   └── utils/               # 日志、文件、格式化工具
│       └── jvmTest/                 # JVM 单元测试与集成测试
├── resources/                       # 各平台 FFmpeg / libvlc 资源与许可
├── third-party/
│   ├── binaries/                    # 离线打包用预置二进制（universal FFmpeg + libvlc，含 SOURCE.txt）
│   ├── ffmpeg/                      # FFmpeg 版本与来源说明
│   └── yunx-desktop/                # 上游 AGPL 许可与移植来源清单
├── scripts/                         # FFmpeg 获取与打包辅助脚本
├── .github/workflows/               # 三平台 CI
├── README.md                        # 本文档
├── THIRD-PARTY-NOTICES.md           # 第三方组件与许可声明
├── LICENSE                          # AGPL-3.0 全文
└── NOTICE                           # 版权与上游鸣谢
```

---

## 已知限制

1. 不支持 BT：`magnet:` 与 `.torrent` 会在 UI 与引擎两层被拒绝。
2. HLS 不支持直播流、SAMPLE-AES、DRM `KEYFORMAT`、加密的 `#EXT-X-MAP` 与多个不同初始化段；分片、播放列表大小与数量均有上限。
3. macOS 内置 FFmpeg 目前主要覆盖 Intel x86_64；Apple 芯片可经 Rosetta 或自行指定 FFmpeg 路径。
4. 抓网页默认不执行 JavaScript；深度抓取依赖本机 Chrome / Edge，且 `blob:`（MSE）地址、登录态播放内核的 Cookie 仍有边界。
5. 在线观看不支持 DRM；高码率 4K 播放会吃力；播放器随包增加约 46 MB 体积。
6. 单线程续传依赖本地文件长度，服务器忽略 Range 时会从头写入。
7. 代理仅支持 HTTP，不支持 SOCKS 与代理认证；libvlc 未接入代理设置。
8. 局域网传输记录只存在内存中，不跨重启持久化。
9. 搜索为内存包含匹配，不支持正则。
10. 内置二维码编码器仅支持字节模式、纠错等级 M、版本 1~10，约 270 字节以内。
11. Windows 原生 toast 进度通知未启用，当前使用托盘气泡。

---

## 许可证与第三方组件

本项目整体使用 **AGPL-3.0**，完整许可文本见 [`LICENSE`](LICENSE)。版权与上游鸣谢见 [`NOTICE`](NOTICE)。

主要第三方组件：

| 组件 | 协议 | 集成方式 |
| --- | --- | --- |
| FFmpeg | LGPL-3.0-or-later | 外部子进程调用，未链接，属于单纯聚合 |
| VLC / libvlc | LGPL-2.1 | 经 JNA 动态链接的可替换共享库 |
| JCEF / Chromium | Apache-2.0 / BSD-style | 运行时加载原生二进制，用于应用内网页登录 |
| YunX-Desktop 网盘解析器 | AGPL-3.0 | 源码编译进本程序，完整来源见 `third-party/yunx-desktop/SOURCE.txt` |

更详细的版本、许可、来源与合规说明见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。应用内也可在“设置 → 开源许可与署名”查看相同信息。
