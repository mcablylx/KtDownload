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
