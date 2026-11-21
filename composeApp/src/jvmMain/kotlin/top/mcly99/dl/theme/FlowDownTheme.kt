package top.mcly99.dl.theme

/**
 * 应用主题系统
 * 
 * 该文件定义了应用程序的完整主题系统，包括深色和浅色主题方案、颜色配置、
 * 字体样式和主题应用机制，为整个应用程序提供统一的视觉风格。
 * 
 * 主要组成部分：
 * - 颜色方案：定义了深色（DarkFlowDown）和浅色（LightFlowDown）两套完整的颜色配置
 * - 颜色访问器：FlowDownColors对象提供便捷的颜色访问接口
 * - Material主题集成：与Android Material3主题系统深度集成
 * - 主题应用：FlowDownTheme Composable用于应用主题到UI组件
 * 
 * 设计特点：
 * - 双主题支持：同时支持深色和浅色主题，并可跟随系统设置
 * - 模块化设计：颜色、字体、主题应用分离，便于维护和扩展
 * - 类型安全：使用Color类型确保颜色值的安全性
 * - 性能优化：使用CompositionLocalProvider实现高效的主题传递
 * 
 * @see FlowDownColorScheme 颜色方案数据类
 * @see DarkFlowDown 深色主题方案
 * @see LightFlowDown 浅色主题方案
 * @see FlowDownColors 主题颜色访问器
 * @see FlowDownTheme 主题应用Composable
 */

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Color scheme holder */
data class FlowDownColorScheme(
    val Background: Color,
    val Foreground: Color,
    val Card: Color,
    val Border: Color,
    val Muted: Color,
    val MutedForeground: Color,
    val Primary: Color,
    val OnPrimary: Color,
    val Sidebar: Color,
    val SidebarBorder: Color,
    val SidebarAccent: Color,
    val Destructive: Color,
    val Warning: Color,
    val Info: Color,
    val Success: Color,
    val Orange: Color,
    val Purple: Color,
    val Blue: Color,
    val Green: Color,
    val Pink: Color,
    val Yellow: Color,
    val Red: Color,
)

val DarkFlowDown = FlowDownColorScheme(
    Background = Color(0xFF16161A),
    Foreground = Color(0xFFF2F2F2),
    Card = Color(0xFF1C1C22),
    Border = Color(0xFF3F3F46),
    Muted = Color(0xFF2A2A32),
    MutedForeground = Color(0xFF9CA3AF),
    Primary = Color(0xFF34D399),
    OnPrimary = Color(0xFF0F172A),
    Sidebar = Color(0xFF121214),
    SidebarBorder = Color(0xFF27272F),
    SidebarAccent = Color(0xFF2A2A32),
    Destructive = Color(0xFFEF4444),
    Warning = Color(0xFFEAB308),
    Info = Color(0xFF3B82F6),
    Success = Color(0xFF34D399),
    Orange = Color(0xFFFB923C),
    Purple = Color(0xFFA78BFA),
    Blue = Color(0xFF60A5FA),
    Green = Color(0xFF4ADE80),
    Pink = Color(0xFFF472B6),
    Yellow = Color(0xFFFACC15),
    Red = Color(0xFFF87171),
)

val LightFlowDown = FlowDownColorScheme(
    Background = Color(0xFFF8F9FA),
    Foreground = Color(0xFF1A1A2E),
    Card = Color(0xFFFFFFFF),
    Border = Color(0xFFD1D5DB),
    Muted = Color(0xFFF3F4F6),
    MutedForeground = Color(0xFF6B7280),
    Primary = Color(0xFF059669),
    OnPrimary = Color(0xFFFFFFFF),
    Sidebar = Color(0xFFF0F1F3),
    SidebarBorder = Color(0xFFD1D5DB),
    SidebarAccent = Color(0xFFE5E7EB),
    Destructive = Color(0xFFDC2626),
    Warning = Color(0xFFD97706),
    Info = Color(0xFF2563EB),
    Success = Color(0xFF059669),
    Orange = Color(0xFFEA580C),
    Purple = Color(0xFF7C3AED),
    Blue = Color(0xFF3B82F6),
    Green = Color(0xFF16A34A),
    Pink = Color(0xFFDB2777),
    Yellow = Color(0xFFCA8A04),
    Red = Color(0xFFDC2626),
)

val LocalFlowDownColors = staticCompositionLocalOf { DarkFlowDown }

/** Backward-compatible accessor: FlowDownColors.Primary etc. still works in composable context */
object FlowDownColors {
    val Background: Color @Composable get() = LocalFlowDownColors.current.Background
    val Foreground: Color @Composable get() = LocalFlowDownColors.current.Foreground
    val Card: Color @Composable get() = LocalFlowDownColors.current.Card
    val Border: Color @Composable get() = LocalFlowDownColors.current.Border
    val Muted: Color @Composable get() = LocalFlowDownColors.current.Muted
    val MutedForeground: Color @Composable get() = LocalFlowDownColors.current.MutedForeground
    val Primary: Color @Composable get() = LocalFlowDownColors.current.Primary
    val OnPrimary: Color @Composable get() = LocalFlowDownColors.current.OnPrimary
    val Sidebar: Color @Composable get() = LocalFlowDownColors.current.Sidebar
    val SidebarBorder: Color @Composable get() = LocalFlowDownColors.current.SidebarBorder
    val SidebarAccent: Color @Composable get() = LocalFlowDownColors.current.SidebarAccent
    val Destructive: Color @Composable get() = LocalFlowDownColors.current.Destructive
    val Warning: Color @Composable get() = LocalFlowDownColors.current.Warning
    val Info: Color @Composable get() = LocalFlowDownColors.current.Info
    val Success: Color @Composable get() = LocalFlowDownColors.current.Success
    val Orange: Color @Composable get() = LocalFlowDownColors.current.Orange
    val Purple: Color @Composable get() = LocalFlowDownColors.current.Purple
    val Blue: Color @Composable get() = LocalFlowDownColors.current.Blue
    val Green: Color @Composable get() = LocalFlowDownColors.current.Green
    val Pink: Color @Composable get() = LocalFlowDownColors.current.Pink
    val Yellow: Color @Composable get() = LocalFlowDownColors.current.Yellow
    val Red: Color @Composable get() = LocalFlowDownColors.current.Red
}

private val DarkMaterialScheme = darkColorScheme(
    primary = DarkFlowDown.Primary,
    onPrimary = DarkFlowDown.OnPrimary,
    secondary = DarkFlowDown.Muted,
    onSecondary = DarkFlowDown.Foreground,
    tertiary = DarkFlowDown.Info,
    background = DarkFlowDown.Background,
    onBackground = DarkFlowDown.Foreground,
    surface = DarkFlowDown.Card,
    onSurface = DarkFlowDown.Foreground,
    surfaceVariant = DarkFlowDown.Muted,
    onSurfaceVariant = DarkFlowDown.MutedForeground,
    outline = DarkFlowDown.Border,
    error = DarkFlowDown.Destructive,
    onError = Color.White,
)

private val LightMaterialScheme = lightColorScheme(
    primary = LightFlowDown.Primary,
    onPrimary = LightFlowDown.OnPrimary,
    secondary = LightFlowDown.Muted,
    onSecondary = LightFlowDown.Foreground,
    tertiary = LightFlowDown.Info,
    background = LightFlowDown.Background,
    onBackground = LightFlowDown.Foreground,
    surface = LightFlowDown.Card,
    onSurface = LightFlowDown.Foreground,
    surfaceVariant = LightFlowDown.Muted,
    onSurfaceVariant = LightFlowDown.MutedForeground,
    outline = LightFlowDown.Border,
    error = LightFlowDown.Destructive,
    onError = Color.White,
)

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
)

@Composable
fun FlowDownTheme(theme: String = "dark", content: @Composable () -> Unit) {
    val isDark = when (theme) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }

    val colors = if (isDark) DarkFlowDown else LightFlowDown
    val materialScheme = if (isDark) DarkMaterialScheme else LightMaterialScheme

    CompositionLocalProvider(LocalFlowDownColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
