package io.github.ganyu256.linuxdeploypro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 主题模式：跟随系统 / 浅色 / 深色（深色即 AMOLED 纯黑） */
enum class ThemeMode(val label: String) {
    FOLLOW_SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/**
 * 应用主题入口。
 *
 * 浅色走 Miuix 默认浅色体系；深色（无论手动还是跟随系统）
 * 都在 Miuix 深色体系基础上把背景/卡片压到接近 #000000（AMOLED 纯黑省电）。
 */
@Composable
fun AppTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) {
        // 跟随系统且系统为深色时同样使用 AMOLED 纯黑，不回落默认深色灰
        darkColorScheme().copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color.Black,
            surfaceContainer = Color(0xFF0A0A0A),
            surfaceContainerHigh = Color(0xFF121212),
            surfaceContainerHighest = Color(0xFF1A1A1A),
        )
    } else {
        lightColorScheme()
    }
    MiuixTheme(colors = colors, content = content)
}
