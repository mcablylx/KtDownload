# 保留所有应用类，防止 ProGuard 错误删除 Compose Desktop 所需的类
-keep class top.mcly99.dl.** { *; }
-keep class ktdownload.composeapp.generated.resources.** { *; }

# 保留资源文件中的入口
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
