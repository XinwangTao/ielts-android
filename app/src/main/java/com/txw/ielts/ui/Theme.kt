package com.txw.ielts.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 「练习册」主题：打开 App 像翻开一本书。
 * 纸张底色 + 墨色正文 + 朱批强调 + 衬线标题（章节感）。
 */
private val Paper = Color(0xFFFAF6EC)        // 书页米白
private val PaperDim = Color(0xFFF1EBDC)     // 旧页/次级纸面
private val Ink = Color(0xFF2B2622)          // 墨色正文
private val InkDim = Color(0xFF6A6255)       // 淡墨（次要文字，对比 5.6:1 满足 WCAG AA）
private val Cinnabar = Color(0xFFB3432B)     // 朱砂红（批注/强调，印章色）
private val InkBlue = Color(0xFF2C4A6E)      // 墨蓝（链接/次强调）

private val NightBg = Color(0xFF171410)      // 夜读底
private val NightCard = Color(0xFF221E18)    // 夜读纸面
private val NightInk = Color(0xFFE8E2D5)     // 夜读文字（暖白）
private val NightInkDim = Color(0xFF9C9483)
private val NightCinnabar = Color(0xFFE06A4F)
private val NightInkBlue = Color(0xFF8FB3D9)

private val LightColors = lightColorScheme(
    primary = Cinnabar,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5E3DC),
    onPrimaryContainer = Color(0xFF6B2416),
    secondary = InkBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1B3048),
    tertiary = Color(0xFF8A6D1F),   // 旧金：掌握/完成
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFDFBF5),
    onSurface = Ink,
    surfaceVariant = PaperDim,
    onSurfaceVariant = InkDim,
    error = Color(0xFFA63A2E),
    errorContainer = Color(0xFFF7DDD6),
    onErrorContainer = Color(0xFF5B1A11),
    // 补齐 Material3 其余角色，避免暖纸主题里混入默认冷紫灰（分隔线、弹窗、底部栏）
    outline = Color(0xFF8A8271),
    outlineVariant = Color(0xFFD8D0BE),
    surfaceTint = Cinnabar,
    surfaceContainerLowest = Color(0xFFFFFDF7),
    surfaceContainerLow = Color(0xFFF7F1E3),
    surfaceContainer = Color(0xFFF3EDDF),
    surfaceContainerHigh = Color(0xFFEDE6D5),
    surfaceContainerHighest = Color(0xFFE7DFCC),
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = Color(0xFFE8A99B),
)

private val DarkColors = darkColorScheme(
    primary = NightCinnabar,
    onPrimary = Color(0xFF2B120C),
    primaryContainer = Color(0xFF4A2016),
    onPrimaryContainer = Color(0xFFF5C9BC),
    secondary = NightInkBlue,
    onSecondary = Color(0xFF10202F),
    secondaryContainer = Color(0xFF1F3247),
    onSecondaryContainer = Color(0xFFCFE0F2),
    tertiary = Color(0xFFC9A94A),
    background = NightBg,
    onBackground = NightInk,
    surface = NightCard,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF2C2620),
    onSurfaceVariant = NightInkDim,
    error = Color(0xFFE08573),
    errorContainer = Color(0xFF5A2A20),
    onErrorContainer = Color(0xFFF5C9BC),
    outline = Color(0xFF8A8271),
    outlineVariant = Color(0xFF3E3830),
    surfaceTint = NightCinnabar,
    surfaceContainerLowest = Color(0xFF12100D),
    surfaceContainerLow = Color(0xFF1C1915),
    surfaceContainer = Color(0xFF211D18),
    surfaceContainerHigh = Color(0xFF2B2620),
    surfaceContainerHighest = Color(0xFF352F27),
    inverseSurface = NightInk,
    inverseOnSurface = NightBg,
    inversePrimary = Cinnabar,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // 书页几乎直角
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

/** 练习册排版：标题衬线（书卷感），正文无衬线（可读性）。 */
private val BookTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun IeltsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = BookTypography, content = content)
}
