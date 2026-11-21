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
tasks.named<Test>("jvmTest") {
    systemProperty("sun.net.http.allowRestrictedHeaders", "true")
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

        // release 构建默认开启 ProGuard，必须显式接入规则文件：
        // 否则 -dontwarn / -keep 等规则不生效，桌面端用不到的可选依赖警告会让打包失败。
        buildTypes.release.proguard {
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
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Pkg)
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
                iconFile = file("src/jvmMain/composeResources/drawable/img_log.ico")
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
//   Linux    vlc-3.0.21.jar            / upx-4.2.4.tar.xz
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

// 仓库内预置归档（third-party/binaries/）：别人 clone 后无需联网即可打包。
// 在 vlcDownload 之前把它还原到 gradle 缓存，于是下面的 onlyIf 会判定「已存在」
// 而跳过联网下载。校验 SHA-256，防止归档损坏或被人替换。
val bundledVlcArchive = rootProject.layout.projectDirectory.file("third-party/binaries/$vlcArchiveName")
// 仅 macOS 的预置归档随仓库分发；Windows/Linux 目前仍走联网下载（见 README）。
// 对应 SHA-256 记录在 third-party/binaries/SOURCE.txt。
val bundledVlcSha256 = when (vlcArchiveName) {
    "vlc-$vlcBundleVersion.dmg" -> "50380e56f81d630e3531426a265abf8b3dee6c0822bb7c9e30b532c7d69a65c0"
    else -> null
}
val restoreVlcFromRepo by tasks.registering {
    group = "compose desktop"
    description = "Restore the pinned libvlc archive from third-party/binaries/ into the gradle cache"
    val cacheFile = vlcSetupCacheDir.resolve(vlcArchiveName)
    val repoFile = bundledVlcArchive.asFile
    val expectedSha = bundledVlcSha256
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
    val archive = vlcSetupCacheDir.resolve(vlcArchiveName)
    onlyIf("压缩包已预置在 $vlcSetupCacheDir") { !archive.isFile }
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
val pruneBundledVlc by tasks.registering {
    group = "compose desktop"
    description = "删除打包副本里与播放无关的 libvlc 插件（界面/可视化/推流/媒体库）"
    dependsOn("prepareAppResources")
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
// 无开发者证书时用 ad-hoc 签名（-s -）；在 jpackage 生成 .app 后、打包 DMG/PKG 之前执行，
// 因此 DMG/PKG 内的应用也带签名。真实证书签名仍走 macOS.signing 配置。
if (System.getProperty("os.name").startsWith("Mac")) {
    tasks.withType<AbstractJPackageTask>().configureEach {
        if (targetFormat == TargetFormat.AppImage) {
            doLast {
                fun run(vararg command: String) =
                    ProcessBuilder(*command).inheritIO().start().waitFor()

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
                        check(run("codesign", "--force", "--sign", "-", bundledFfmpeg.absolutePath) == 0) {
                            "ad-hoc codesign failed: $bundledFfmpeg"
                        }
                    }
                    // 随包 libvlc 的 dylib（含 plugins/）同样是 Resources 下的独立 Mach-O，
                    // `--deep` 不会覆盖它们；不逐个签，arm64 上加载会被内核拒绝
                    // （x86_64 走 Rosetta 暂时侥幸能跑）。v9 修复，见 docs/IMPROVEMENTS.md。
                    appResources.walkTopDown()
                        .filter { it.isFile && it.extension == "dylib" }
                        .forEach { dylib ->
                            check(run("codesign", "--force", "--sign", "-", dylib.absolutePath) == 0) {
                                "ad-hoc codesign failed: $dylib"
                            }
                        }
                    check(run("codesign", "--force", "--deep", "--sign", "-", appDir.absolutePath) == 0) {
                        "ad-hoc codesign failed: $appDir"
                    }
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
