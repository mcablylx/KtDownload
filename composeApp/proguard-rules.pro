# 桌面端打包会带入若干只服务于其他平台的可选依赖（Android、Conscrypt、
# BouncyCastle、JOGL 等），它们的引用类不在桌面 classpath 中，ProGuard 会报
# 500+ 条警告并中止构建。这些类在桌面运行路径上用不到，统一忽略警告
# （ProGuard 日志本身也建议如此处理）。
-ignorewarnings

# 保留所有应用类，防止 ProGuard 错误删除 Compose Desktop 所需的类
-keep class top.mcly99.dl.** { *; }
-keep class ktdownload.composeapp.generated.resources.** { *; }

# 保留资源文件中的入口
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# ── 以下规则仅在 proguard 重新启用时才生效（当前 release 已关闭裁剪，见
# composeApp/build.gradle.kts 的 isEnabled = false）──
#
# 桌面端有一批「静态分析看不见引用」的库，一旦被 shrink 就会在打包版里静默失效，
# 开发环境（不裁剪）永远复现不出来：
#   org.cef / me.friwi  Chromium(JCEF) 的 native 层按**类名反射**实例化回调类
#                       （如 org.cef.callback.CefCommandLine_N）。裁剪掉之后浏览器
#                       照常初始化、窗口也出来，但页面永远是白的。
#   uk.co.caprica.vlcj  vlcj 靠 JNA 的接口映射按方法名找 native 函数。
#   com.sun.jna         JNA 的 Native.load / 结构体映射依赖字段与方法原名。
#   okhttp3 / okio      okhttp 内部有反射与平台探测分支，okio 裁出过 VerifyError。
#   org.json            解析异常里按类名回溯。
-keep class org.cef.** { *; }
-keep class me.friwi.** { *; }
-keep class uk.co.caprica.vlcj.** { *; }
-keep class uk.co.caprica.vlcj-natives.** { *; }
-keep class com.sun.jna.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.json.** { *; }

-dontwarn org.cef.**
-dontwarn me.friwi.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.sun.jna.**
