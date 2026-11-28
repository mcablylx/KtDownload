import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.security.MessageDigest

// 版本号唯一来源：gradle.properties（可用 -PappVersion=... 临时覆盖）
val appVersion: String = providers.gradleProperty("appVersion")
    .orElse("0.0.0-dev")
    .get()

// Configure Gradle to use UTF-8 encoding for all files
gradle.projectsEvaluated {
    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    // 把 libvlc 的 dll/so/dylib 下载并放进 resources/<平台>/，供 jpackage 打进 app/resources
    alias(libs.plugins.vlcSetup)
}

// P3-04：依赖锁定基础设施。KMP + Compose 的可解析配置很多，先接入基础设施但不强制
// `--locked-mode`（强推 lockfile 的维护成本高于当前收益）；需要时用
// `./gradlew :composeApp:dependencies --write-locks` 生成锁定文件。
// PR 侧的漏洞 / 许可证门禁在 ci.yml 的 dependency-review job。
dependencyLocking {
    lockAllConfigurations()
}

// JCEF 的 CefBrowserWindowMac.getWindowHandle() 连续引用五个 JDK 内部类，分属三个包：
//   sun.awt.AWTAccessor、sun.lwawt.LWComponentPeer / PlatformWindow、
//   sun.lwawt.macosx.CFRetainedResource / CPlatformWindow。
// JDK 9+ 默认都不向 unnamed module 导出，点「网盘登录」创建内嵌浏览器时会抛
// IllegalAccessError 并弹窗崩溃。三个必须一次补齐 —— 只补 sun.awt 时，修好第一个
// 就会在下一行冒出 sun.lwawt，看起来像「改了没用」。
// 和 vlcj 的 java.nio 一样，jdeps 探测不到这种直接的内部 API 引用，只能显式声明。
val jcefInternalPackages = listOf(
    "sun.awt",
    "sun.lwawt",
    "sun.lwawt.macosx",
)
val jcefModuleArgs = jcefInternalPackages.flatMap { pkg ->
    listOf(
        "--add-exports=java.desktop/$pkg=ALL-UNNAMED",
        "--add-opens=java.desktop/$pkg=ALL-UNNAMED",
    )
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            // 统一在这里添加生成目录，确保 UI 代码能引用且不产生多模块冲突
            kotlin.srcDirs(layout.buildDirectory.dir("generated/version"))
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        val jvmMain by getting {
            // 此处不再添加 srcDirs，以避免编译错误
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutinesSwing)
                // 播放内核：vlcj（Java 绑定）+ 随包的 libvlc（native 由 vlcSetup 插件准备）
                implementation(libs.vlcj)
                // 云盘解析器（移植自 YunX-Desktop / AGPL-3.0）的 network 层依赖。
                // 详见 THIRD-PARTY-NOTICES.md 与 third-party/yunx-desktop/SOURCE.txt
                implementation(libs.okhttp)
                implementation(libs.org.json)
                // 内嵌 Chromium 网页登录（与 YunX-Desktop 相同做法）：JCEF 由 jcefmaven
                // 装载，natives 以随包 jar 形式提供，运行时不解外网下载。
                implementation(libs.jcefmaven)
                // Chromium natives **随包分发**，按构建机平台/架构选一个：
                //   Windows amd64 / macOS amd64(Intel) / macOS arm64(Apple Silicon) / Linux amd64。
                // 之所以不能再让 jcefmaven 运行时下载：那是一次 ~125MB 的 GitHub/Maven 拉取，
                // 国内网络下会卡到十几分钟还没有进度，用户看到的就是「初始化内核卡死」。
                val buildOs = System.getProperty("os.name").orEmpty().lowercase()
                val buildArch = System.getProperty("os.arch").orEmpty().lowercase()
                when {
                    buildOs.contains("win") -> implementation(libs.jcef.natives.windows.amd64)
                    buildOs.contains("mac") && (buildArch.contains("aarch64") || buildArch.contains("arm64")) ->
                        implementation(libs.jcef.natives.macosx.arm64)
                    buildOs.contains("mac") -> implementation(libs.jcef.natives.macosx.amd64)
                    buildOs.contains("linux") -> implementation(libs.jcef.natives.linux.amd64)
                }
                implementation(libs.commons.compress)
            }
        }

        val jvmTest by getting {
            dependencies {
                // ViewModel 状态机单测：可控调度器 + runTest
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// JDK 默认丢弃 `Origin` 等受限请求头（见 main.kt 的同名设置）。测试里要在
// HttpURLConnection 类初始化之前生效，只能靠 JVM 启动参数，不能靠代码里 setProperty。
// P2-29：把「需要真机 / 真实二进制」的冒烟用例从 jvmTest 里摘出去。
// 这些用例在环境变量缺失时提前 return 静默「通过」，却被计入 jvmTest 绿灯，
// 让 CDP / JCEF / VLC / HLS 真实转封装零覆盖仍显示 0 失败、0 跳过（走查 P2-29）。
// JUnit4 没有 @Tag，且混合类里含真单测，所以清单按「类名 + 方法名」显式列出；
// 两个任务共用同一份常量，避免以后只改一处造成漏跑或双跑。
val integrationTestPatterns = listOf(
    // 整个类都是冒烟：分别需要本机 Chrome/CDP、libvlc + 图形栈
    "*BrowserCaptureSmokeTest",
    "*VlcPlaybackSmokeTest",
    // 混合类：其余方法是不依赖外部进程的真单测，必须留在 jvmTest
    "*JcefHolderTest.starts_the_embedded_browser_on_this_machine",
    "*FfmpegMuxerTest.real_ffmpeg_muxes_ts_segments_to_playable_mp4",
    "*FfmpegMuxerTest.real_ffmpeg_muxes_video_with_separate_audio_track",
)

tasks.named<Test>("jvmTest") {
    systemProperty("sun.net.http.allowRestrictedHeaders", "true")
    // 单元测试 = 纯 JVM，无浏览器 / 图形栈 / 外部二进制依赖，任何平台都应全绿
    filter {
        integrationTestPatterns.forEach { pattern -> excludeTestsMatching(pattern) }
    }
}

// 与 jvmTest 共用同一套编译输出，仅换 filter。默认不执行、也不挂到 check：
//   KTDL_RUN_INTEGRATION=1 ./gradlew :composeApp:integrationTest --no-configuration-cache
// 用例本身还要求 KTDL_CDP_SMOKE / KTDL_VLC_SMOKE / MCLY_JCEF_SMOKE=1 与本机 ffmpeg；
// 没设这些变量的用例会自行跳过，因此这里的「通过」不代表对应链路已覆盖。
val jvmTestTask = tasks.named<Test>("jvmTest")
val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Opt-in smoke/integration tests (CDP browser, JCEF, VLC, real ffmpeg). Not part of check."
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    systemProperty("sun.net.http.allowRestrictedHeaders", "true")
    filter {
        integrationTestPatterns.forEach { pattern -> includeTestsMatching(pattern) }
    }
    val enabled = providers.environmentVariable("KTDL_RUN_INTEGRATION")
        .map { it == "1" }.getOrElse(false)
    onlyIf("需要显式设置 KTDL_RUN_INTEGRATION=1") { enabled }
    doFirst {
        logger.lifecycle(
            "integrationTest：冒烟用例需要真实 Chrome/CDP、JCEF natives、libvlc 与 ffmpeg；" +
                "未设置对应 *_SMOKE=1 的用例会自行跳过，通过不代表这些链路已覆盖。"
        )
    }
    shouldRunAfter(jvmTestTask)
}

// 生成版本号文件任务（使用 layout.buildDirectory，兼容 Gradle 9）
val generateVersionFile by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version/top/mcly99/dl")
    val version = appVersion
    inputs.property("appVersion", version)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("Version.kt").writeText(
            """
            package top.mcly99.dl
            const val APP_VERSION = "$version"
            """.trimIndent() + "\n",
        )
    }
}

// 编译前生成版本文件
tasks.matching { it.name == "compileKotlinJvm" || it.name == "compileKotlinCommonMain" }.configureEach {
    dependsOn(generateVersionFile)
}

compose.desktop {
    application {
        mainClass = "top.mcly99.dl.MainKt"

        // release 构建默认会跑 ProGuard 的 shrink（删「看起来没人引用」的类）。
        // 桌面端的第三方库大量依赖反射 / JNA 接口映射 / native 层按类名加载，
        // 静态分析看不到这些引用，删了以后开发环境（不裁剪）完全正常、只有安装包出事：
        //   - JCEF：org.cef.callback.CefCommandLine_N 等被删 → 登录页浏览器白屏
        //            （jcef-api 从 199 个类被裁到 73 个）；
        //   - vlcj/os: 411 → 121 个类，播放内核随时可能坏；
        //   - okio: 107 → 45 个类，出过 VerifyError。
        // 裁剪换来的体积收益约 55 MB（主要是 material-icons-extended 37 MB 和一批
        // 未用到的 compose/okhttp 类），占安装包 16%，但已经把两个功能打坏过，
        // 因此整体关闭；规则文件保留在 proguard-rules.pro，里面已写好重新启用所需的 keep 规则。
        buildTypes.release.proguard {
            isEnabled = false
            configurationFiles.from(file("proguard-rules.pro"))
        }
        // vlcj 通过 DirectByteBuffer 读 native 缓冲，JDK 17+ 需要显式开放 java.nio
        // jcefModuleArgs 供内嵌浏览器访问 JDK 内部包（见文件头注释）
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED") + jcefModuleArgs
        nativeDistributions {
            // 随安装包分发 ffmpeg：resources/common 放跨平台许可，resources/<平台>/ffmpeg 放二进制
            // （Compose 会把 common 与当前平台目录合并到同一个 app resources 根下）
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("resources"))
            jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED") + jcefModuleArgs
            targetFormats(
                TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Pkg,
                // Linux 用 deb：此前只配了 Windows/macOS 的四种格式，导致 Linux 上
                // packageReleaseDistributionForCurrentOS 没有任何可执行子任务、
                // 直接 UP-TO-DATE 且不产出 main-release/（Release 流水线的
                // "Collect installers" 因此 find 到空目录而失败）。
                TargetFormat.Deb,
            )
            packageName = "KtDownload"
            packageVersion = appVersion
            description = "A powerful download manager"
            copyright = "\u00A9 2026 KtDownload. All rights reserved."
            vendor = "KtDownload"

            // java.net.http 是独立模块，不会被 java.desktop / jdk.httpserver 顺带带进来。
            // 深度抓取（CDP）用的 HttpClient / WebSocket 全在这里，漏了会让打包版点「抓页面」
            // 时抛 NoClassDefFoundError: java/net/http/HttpClient（开发环境是完整 JDK，不会复现）。
            modules(
                "java.desktop", "java.xml", "java.logging", "java.management",
                "jdk.httpserver", "java.net.http",
                // JCEF / SwingPanel 互操作与 Chromium 原生库需要的 JDK 内部模块：
                // jdeps 探测不到反射使用，漏了打包版内嵌浏览器会起不来。
                "java.prefs", "jdk.unsupported", "jdk.unsupported.desktop",
            )

            windows {
                iconFile = file("src/jvmMain/composeResources/drawable/img_log.ico")
                menuGroup = "KtDownload"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                perUserInstall = false
                console = false
                dirChooser = true
                shortcut = true
            }

            macOS {
                iconFile = file("src/jvmMain/composeResources/drawable/icons.icns")
                bundleID = "top.mcly99.dl"
            }

            linux {
                // jpackage 打 deb 时图标必须是 png（.ico 只用于 Windows），
                // 且包名只能是小写字母数字 —— 直接用全局的 "KtDownload" 会被拒绝。
                iconFile = file("src/jvmMain/composeResources/drawable/img_logo.png")
                packageName = "ktdownload"
                // deb 的 Maintainer 字段是必填的，jpackage 不会替我们猜。
                debMaintainer = "mcablylx <308380504@qq.com>"
                appCategory = "Network"
                shortcut = true
            }
        }
    }
}

// Compose Hot Reload 的 `hotRunJvm` 是独立的 JavaExec 任务，不吃 compose.desktop.application.jvmArgs。
// IDE 里的「composeApp [jvm, hot] 🔥」运行配置走的就是它（见 .idea/workspace.xml 的 taskNames），
// 漏配的话开发时点「网盘登录」照样弹 IllegalAccessError。
tasks.withType<JavaExec>().configureEach {
    if (name.startsWith("hotRun")) jvmArgs(jcefModuleArgs)
}

// 把 libvlc 下载并解到 resources/<平台>/，与 ffmpeg 一样由 jpackage 合并进 app/resources。
//
// 目录约束：插件的 vlcSetup 任务只会删除目标目录里的 *.dll 后覆盖写入，
// 所以 resources/windows/ffmpeg 不会被误删；反过来，千万不要把自有文件放进
// resources/windows/ 根目录（会被 VLC 的同名文件覆盖）。
//
// shouldIncludeAllVlcFiles 必须为 true：默认的「精简插件集」不含 adaptive（HLS）与 http
// 访问插件，m3u8 会直接放不出来。压缩（UPX）对 dylib 无效，macOS 上是 no-op。
// 版本号提到变量里：下面的「离线兜底」要按同一个版本号拼压缩包文件名
//
// 版本锁定说明（P3-11）：
// - 3.0.21 是 3.0.x 线里带三平台预编译包、且 vlcj 4.x 直接支持的版本；
// - 选它是因为随包分发的 adaptive（HLS）与 http 访问插件齐全，HEVC / HLS 已人工验证过；
// - 上次评估：2026-10-27（随本轮走查复查）。升级前按 docs/CATCH_WEB_PLAN.md
//   「libvlc 升级检查清单」逐项跑一遍，不要只改版本号。
val vlcBundleVersion = "3.0.21"
val upxBundleVersion = "4.2.4"

// 平台判断提前到这里：下面的「离线兜底」要按平台拼下载文件名
val osName = System.getProperty("os.name").lowercase()

vlcSetup {
    vlcVersion = vlcBundleVersion
    upxVersion = upxBundleVersion
    // 保持 UPX 压缩：安装包少约 14 MB。历史上出过「压缩后无法加载 / 被杀软误报」的反馈，
    // 本轮评估（2026-10-27）决定维持现状；若误报集中出现，改成 false 即可（代价是包体积）。
    shouldCompressVlcFiles = true
    shouldIncludeAllVlcFiles = true
    pathToCopyVlcWindowsFilesTo = rootProject.layout.projectDirectory.dir("resources/windows").asFile
    pathToCopyVlcMacosFilesTo = rootProject.layout.projectDirectory.dir("resources/macos").asFile
    pathToCopyVlcLinuxFilesTo = rootProject.layout.projectDirectory.dir("resources/linux").asFile
}

// vlc-setup 0.1.0 只把 vlcSetup 挂到名为 `processResources` 的任务上；
// 本项目是 KMP，资源任务叫 `jvmProcessResources`，挂不上 —— 打包不会自动拉 libvlc，
// 而 `clean` 又会删掉 resources/<平台>/ 里的 dll/dylib。这里显式挂到 jpackage
// 取资源的入口，三平台一致（v9 修复，见 docs/IMPROVEMENTS.md）。
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn("vlcSetup")
}

// 离线兜底：插件的下载任务用的是 overwrite(false)，文件已存在时仍会去探一次远端时间戳，
// 于是在 GitHub / videolan 不可达的网络里（国内常见）打包会直接失败。
// 这里约定：把对应平台的归档预置到 ~/.gradle/vlcSetup/ 后，下载任务直接跳过，
// 其余解压/压缩/拷贝照常。
// 落地名按插件实现（三个平台不一样，见插件源码；注意 macOS 的本地文件名不带 -universal）：
//   Windows  vlc-3.0.21.zip            / upx-4.2.4.zip
//   macOS    vlc-3.0.21.dmg            （下载源是 -universal.dmg；UPX 随插件附带，没有 upxDownload 任务）
//   Linux    vlc-3.0.20-2.jar          / upx-4.2.4.tar.xz
//            ↑ 注意不是 3.0.21：vlc-setup 0.1.0 的 LinuxTasksConfigure.kt 把版本硬编成
//            "3.0.20-2"（作者自己留了 FIXME: vlcSetupExtension.vlcVersion），
//            所以 Linux 的归档名与上面的 vlcBundleVersion 无关。下面统一用
//            cachedVlcArchive() 解析，不把插件的内部版本号抄进本脚本。
val vlcArchiveName = when {
    osName.contains("win") -> "vlc-$vlcBundleVersion.zip"
    osName.contains("mac") -> "vlc-$vlcBundleVersion.dmg"
    else -> "vlc-$vlcBundleVersion.jar"
}
val upxArchiveName = when {
    osName.contains("win") -> "upx-$upxBundleVersion.zip"
    osName.contains("mac") -> null
    else -> "upx-$upxBundleVersion.tar.xz"
}
val vlcSetupCacheDir = gradle.gradleUserHomeDir.resolve("vlcSetup")

// 解析「vlc-setup 插件实际下载到缓存里的那个归档」。
// Windows / macOS 的文件名由 vlcArchiveName 决定（与插件固定一致），直接拼即可；
// Linux 的版本被插件硬编成 3.0.20-2（见上方说明），拼名字必然踩空，改成按前缀扫描，
// 插件以后修好 FIXME 也不必再改这里。
// 返回 null 表示缓存里还没有归档（尚未下载 / 已被清理）。
fun cachedVlcArchive(): File? {
    if (!osName.contains("win") && !osName.contains("mac")) {
        return vlcSetupCacheDir
            .listFiles { f -> f.isFile && f.name.startsWith("vlc-") && f.extension == "jar" }
            ?.maxByOrNull { it.lastModified() }
    }
    return vlcSetupCacheDir.resolve(vlcArchiveName).takeIf { it.isFile }
}

// 可选预置归档（third-party/binaries/）：归档齐全时 clone 后无需联网即可打包。
// 默认 sparse-checkout 会排除该目录，仓库里只有 SOURCE.txt 清单；缺归档时
// restoreVlcFromRepo 的 onlyIf 不满足，自动走原来的联网下载路径（见 README）。
// 在 vlcDownload 之前把它还原到 gradle 缓存，于是下面的 onlyIf 会判定「已存在」
// 而跳过联网下载。校验 SHA-256，防止归档损坏或被人替换。
val bundledVlcArchive = rootProject.layout.projectDirectory.file("third-party/binaries/$vlcArchiveName")
// 三平台上游归档的 SHA-256 一律钉死（与 third-party/binaries/SOURCE.txt 记录一致）。
// 取值来自上游官方校验文件，不是本机自己算的：
//   Windows  https://get.videolan.org/vlc/3.0.21/win64/vlc-3.0.21-win64.zip.sha256
//   macOS    videolan 3.0.21 universal dmg（与本仓库预置那份实测逐字节一致）
//   Linux    https://repo1.maven.org/maven2/ir/mahozad/vlc-plugins-linux/3.0.20-2/…jar.sha256
//            （Linux 实际版本是 3.0.20-2，见上面 vlcArchiveName 的说明）
// 之前只给 macOS 配值、Windows/Linux 留 null，那两个平台打包时只会告警「本次未校验」，
// 等于每次装包都不校验 libvlc；现在三者都是真校验。
val pinnedVlcSha256 = when {
    osName.contains("win") -> "a0b7ec02b50adf6417eed014fb8df50af39690505a4225b85b3dc2ed17d14843"
    osName.contains("mac") -> "50380e56f81d630e3531426a265abf8b3dee6c0822bb7c9e30b532c7d69a65c0"
    else -> "3de56c7136d0beda0f87dcafbbc075eb64a6b2e65c70ffc7371e4472d9149abe"
}

// P2-25：期望 SHA 的取值顺序是「命令行注入 > 内置钉死值」。
// -PvlcArchiveSha256=<sha256> 保留给「试新版本 / 上游重新打包」的场景；
// 显式传空值（-PvlcArchiveSha256=）会退回「未知期望」，此时校验任务只告警不失败。
val vlcExpectedSha256: String? = providers.gradleProperty("vlcArchiveSha256")
    .orElse(pinnedVlcSha256)
    .orNull
    ?.takeIf { it.isNotBlank() }
val restoreVlcFromRepo by tasks.registering {
    // doLast 里引用了脚本作用域的 logger，configuration cache 无法序列化；
    // 与下方 vlc-* 任务同样标记（打包链路本来就会因 vlc-setup 退化为不用缓存）。
    notCompatibleWithConfigurationCache("自定义任务在 doLast 中引用 Gradle script 对象")
    group = "compose desktop"
    description = "Restore the pinned libvlc archive from third-party/binaries/ into the gradle cache"
    val cacheFile = vlcSetupCacheDir.resolve(vlcArchiveName)
    val repoFile = bundledVlcArchive.asFile
    val expectedSha = vlcExpectedSha256
    onlyIf("缓存中尚无 $vlcArchiveName 且仓库含预置归档") {
        !cacheFile.isFile && repoFile.isFile
    }
    doLast {
        if (expectedSha != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            repoFile.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { b -> "%02x".format(b) }
            check(actual.equals(expectedSha, ignoreCase = true)) {
                "预置归档 $repoFile 的 SHA-256 不匹配：期望 $expectedSha，实际 $actual。请重新获取该文件。"
            }
        }
        vlcSetupCacheDir.mkdirs()
        repoFile.copyTo(cacheFile, overwrite = true)
        logger.lifecycle("Restored $vlcArchiveName from third-party/binaries/ (offline packaging)")
    }
}
tasks.matching { it.name == "vlcDownload" }.configureEach {
    dependsOn(restoreVlcFromRepo)
    // 用 cachedVlcArchive() 而不是固定文件名：Linux 上插件的落盘名与 vlcBundleVersion 无关，
    // 拿固定名判断会让 onlyIf 永远成立、离线预置永远失效。
    onlyIf("压缩包已预置在 $vlcSetupCacheDir") { cachedVlcArchive() == null }
}
upxArchiveName?.let { name ->
    tasks.matching { it.name == "upxDownload" }.configureEach {
        val archive = vlcSetupCacheDir.resolve(name)
        onlyIf("压缩包已预置在 $vlcSetupCacheDir") { !archive.isFile }
    }
}

// vlc-setup 0.1.0 在任务执行期调用 Task.project（project.copy），与 configuration cache 不兼容。
// 本项目的 org.gradle.configuration-cache=true 会因此直接失败；把这几个任务标成不兼容，
// Gradle 会退化为「本次构建不用缓存」，而不是报错。等插件修了再删掉这段。
tasks.matching { it.name.startsWith("vlc") || it.name == "upxDownload" || it.name == "upxExtract" }
    .configureEach {
        notCompatibleWithConfigurationCache("vlc-setup 插件在执行期访问 Task.project")
    }

// 只裁「与播放无关」的类别，codec / demux / access / video_* 一律不动（裁过头会放不出来）：
//   gui              Qt 与 skins2 界面插件 —— vlcj 没有界面，纯死重
//   visualization    音频可视化
//   stream_out       转码/推流输出
//   access_output    推流输出
//   services_discovery  媒体库扫描（upnp/podcast/驱动器）
//   axvlc/npvlc      浏览器 ActiveX/NPAPI 插件
// 合计约 14 MB，去掉后 libvlc 体积约 45 MB。
val bundledVlcPrunePatterns = listOf(
    "axvlc.dll", "npvlc.dll",
    "plugins/gui/**",
    "plugins/visualization/**",
    "plugins/stream_out/**",
    "plugins/access_output/**",
    "plugins/services_discovery/**",
)

val vlcPlatformDir = when {
    osName.contains("win") -> "windows"
    osName.contains("mac") -> "macos"
    else -> "linux"
}

// 裁剪对象是 **prepareAppResources 的暂存副本**，不是仓库里的 resources/：
// resources/windows 是 vlcSetup 的输出目录，动它会让 Gradle 判定 vlcSetup 过期，
// 于是每次构建都要重新解压+压缩一遍（约 5 分钟）。开发期（gradlew run）直接用
// resources/ 下的完整插件集，只是大一点，功能一致。
// P2-25：联网下载的 libvlc 归档原先完全没有 SHA 校验（vlc-setup 插件下载后直接解压）。
// 这里在「资源就绪 → 打包」之间对最终落到 gradle 缓存的归档做一次校验：
//   - 有期望 SHA（macOS 内置预置包 / -PvlcArchiveSha256=...）→ 不匹配直接让构建失败；
//   - 没有期望 SHA → 明确告警「本次未校验」，不再静默跳过。
// 挂在 prepareAppResources 之后、pruneBundledVlc 之前，保证下载/还原已经完成，
// 且损坏或被替换的归档不会进入安装包。
//
// 这里刻意**不**把归档声明成任务输入（inputs 文件）：Linux 上插件的落盘名与
// vlcBundleVersion 无关（见 cachedVlcArchive 的说明），一旦声明为输入，就会在
// 配置校验阶段直接失败（v1.0.4 Release 的 ubuntu job 就是这么挂的：
// Property '$1' specifies file 'vlc-3.0.21.jar' which doesn't exist，optional() 也拦不住）。
// 本任务没有 outputs，本来每次都会执行，用 inputs 换不来 up-to-date，只留 path 记录便于排查。
val verifyVlcArchive by tasks.registering {
    notCompatibleWithConfigurationCache("自定义任务在 doLast 中引用 Gradle script 对象")
    group = "compose desktop"
    description = "Verify the libvlc archive SHA-256 before it is bundled (P2-25)"
    dependsOn("prepareAppResources")
    val expected = vlcExpectedSha256
    inputs.property("expectedSha256", expected ?: "<none>")
    inputs.property("vlcArchiveName", vlcArchiveName)
    doLast {
        val archive = cachedVlcArchive()
        if (archive == null) {
            logger.warn(
                "P2-25: 在 $vlcSetupCacheDir 未找到 libvlc 归档，跳过 SHA-256 校验" +
                    "（本次打包可能复用了已解压产物）。"
            )
            return@doLast
        }
        if (expected == null) {
            logger.warn(
                "P2-25: ${archive.name} 未配置 SHA-256，本次未校验归档完整性。" +
                    "联网下载存在被篡改 / 截断风险；可用 -PvlcArchiveSha256=<sha256> 注入期望值，" +
                    "或把归档与其 SHA 登记到 third-party/binaries/SOURCE.txt。"
            )
            return@doLast
        }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { b -> "%02x".format(b) }
        check(actual.equals(expected, ignoreCase = true)) {
            "libvlc 归档 SHA-256 不匹配：$archive 期望 $expected，实际 $actual。请重新获取该文件。"
        }
        logger.lifecycle("P2-25: ${archive.name} SHA-256 校验通过")
    }
}

val pruneBundledVlc by tasks.registering {
    group = "compose desktop"
    description = "删除打包副本里与播放无关的 libvlc 插件（界面/可视化/推流/媒体库）"
    dependsOn("prepareAppResources")
    dependsOn(verifyVlcArchive)
    val stagedDir = layout.buildDirectory.dir("compose/tmp/prepareAppResources")
    val patterns = bundledVlcPrunePatterns
    // 用 doLast 直接删：Delete 任务接不住「Provider<FileTree>」形式的目标，
    // 会静默变成空目标然后显示 UP-TO-DATE。这里没有声明输出，所以每次都会跑（很快）。
    doLast {
        val root = stagedDir.get().asFile
        patterns.forEach { pattern ->
            val target = if (pattern.endsWith("/**")) {
                root.resolve(pattern.removeSuffix("/**"))
            } else {
                root.resolve(pattern)
            }
            if (target.exists()) target.deleteRecursively()
        }
    }
}

// jpackage 从暂存目录取资源，必须在它之前裁完
tasks.withType<AbstractJPackageTask>().configureEach {
    dependsOn(pruneBundledVlc)
}
tasks.matching { it.name == "runDistributable" }.configureEach {
    dependsOn(pruneBundledVlc)
}

// macOS 的 .app（含随包 ffmpeg）必须带签名，Apple Silicon 与 Rosetta 才肯执行。
// 默认仍是 ad-hoc 签名（-s -），无开发者证书的环境行为完全不变。
// P2-24：开发者可用 -PmacosSignIdentity="Developer ID Application: ..." 切换为真实签名，
// 此时补 --options runtime（notarytool 要求 hardened runtime）与 --timestamp（安全时间戳）。
// DMG/PKG 容器签名与公证不放在 Gradle 里（需要 Apple 凭证），见 scripts/notarize-macos.sh。
val macSignIdentity: String = providers.gradleProperty("macosSignIdentity").getOrElse("-")
val macDeveloperIdSigning: Boolean = macSignIdentity != "-"
if (System.getProperty("os.name").startsWith("Mac")) {
    val extraCodesignArgs =
        if (macDeveloperIdSigning) listOf("--options", "runtime", "--timestamp") else emptyList()
    tasks.withType<AbstractJPackageTask>().configureEach {
        if (targetFormat == TargetFormat.AppImage) {
            doLast {
                fun run(vararg command: String) =
                    ProcessBuilder(*command).inheritIO().start().waitFor()
                fun sign(path: String, deep: Boolean = false) {
                    val args = mutableListOf("codesign", "--force")
                    args.addAll(extraCodesignArgs)
                    if (deep) args.add("--deep")
                    args.addAll(listOf("--sign", macSignIdentity, path))
                    check(run(*args.toTypedArray()) == 0) { "codesign failed: $path" }
                }

                val appDir = destinationDir.get().asFile.resolve("${packageName.get()}.app")
                if (appDir.isDirectory) {
                    // Gradle Sync 复制资源时会丢掉可执行位，FfmpegLocator 会拒绝非可执行文件；
                    // 必须在签名前补回 +x（签名覆盖的是最终文件内容）。
                    val appResources = appDir.resolve("Contents/app/resources")
                    val bundledFfmpeg = appResources.resolve("ffmpeg/ffmpeg")
                    if (bundledFfmpeg.isFile) {
                        bundledFfmpeg.setExecutable(true, false)
                        // --deep 不会给 Resources 里的独立可执行文件补签名（实测 app 目录内仍是未签名），
                        // 而 Apple Silicon 要求所有二进制都有签名，所以单独签一次。
                        sign(bundledFfmpeg.absolutePath)
                    }
                    // 随包 libvlc 的 dylib（含 plugins/）同样是 Resources 下的独立 Mach-O，
                    // `--deep` 不会覆盖它们；不逐个签，arm64 上加载会被内核拒绝
                    // （x86_64 走 Rosetta 暂时侥幸能跑）。v9 修复，见 docs/IMPROVEMENTS.md。
                    appResources.walkTopDown()
                        .filter { it.isFile && it.extension == "dylib" }
                        .forEach { dylib -> sign(dylib.absolutePath) }
                    sign(appDir.absolutePath, deep = true)
                }
            }
        }
    }
}

// jcefmaven 的 jogl-all / gluegen-rt 是 macOS 渲染路径的可选依赖；
// Windows 打包用不到，排除掉可少带两个大 jar（与 YunX-Desktop 的做法一致）。
configurations.configureEach {
    exclude(group = "me.friwi", module = "jogl-all")
    exclude(group = "me.friwi", module = "gluegen-rt")
}
