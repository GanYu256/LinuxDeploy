package io.github.ganyu256.linuxdeploypro

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.ganyu256.linuxdeploypro.ui.MainScreen
import io.github.ganyu256.linuxdeploypro.ui.theme.AppTheme
import io.github.ganyu256.linuxdeploypro.ui.theme.ThemeMode

/**
 * 应用根组件：持有主题状态并持久化（重启应用后保持用户选择）。
 * 主题存 SharedPreferences，进程重启后仍生效。
 */
@Composable
fun App() {
    val context = LocalContext.current.applicationContext
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var themeMode by remember {
        mutableStateOf(
            ThemeMode.entries.getOrNull(
                prefs.getInt("theme_mode", ThemeMode.FOLLOW_SYSTEM.ordinal),
            ) ?: ThemeMode.FOLLOW_SYSTEM,
        )
    }
    AppTheme(themeMode = themeMode) {
        MainScreen(
            themeMode = themeMode,
            onThemeModeChange = { newMode ->
                themeMode = newMode
                prefs.edit().putInt("theme_mode", newMode.ordinal).apply()
            },
        )
    }
}
