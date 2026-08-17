package io.github.ganyu256.linuxdeploypro.ui

import io.github.ganyu256.linuxdeploypro.BuildConfig
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ganyu256.linuxdeploypro.data.CliManager
import io.github.ganyu256.linuxdeploypro.ui.theme.ThemeMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：主题 + 运行环境 + 关于。
 * 顶栏用 SmallTopAppBar 与容器/日志页一致；行样式统一 Miuix（关于区
 * 用 ArrowPreference / 信息行）；重装运行环境前弹确认窗。
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 重装运行环境确认窗
    var showReinstallConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "设置",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        containerColor = MiuixTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupTitle("显示")
            // 主题
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
            ) {
                OverlayDropdownPreference(
                    title = "主题",
                    summary = themeMode.label,
                    items = ThemeMode.entries.map { it.label },
                    selectedIndex = themeMode.ordinal,
                    onSelectedIndexChange = { onThemeModeChange(ThemeMode.entries[it]) },
                )
            }

            GroupTitle("运行环境")
            // 重装运行环境（确认后执行）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
            ) {
                ArrowPreference(
                    title = "重装运行环境",
                    summary = "重新部署内置 cli.sh / busybox / resize2fs 并更新环境变量",
                    onClick = { showReinstallConfirm = true },
                )
            }

            GroupTitle("关于")
            // 关于：作者/开源地址可点击（右箭头）置顶；版本/包名/许可信息行置底
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
            ) {
                Column {
                    ArrowPreference(
                        title = "作者",
                        summary = "Ganyu256",
                        onClick = { openUrl(context, "https://github.com/Ganyu256") },
                    )
                    ArrowPreference(
                        title = "开源地址",
                        summary = "GitHub 仓库",
                        onClick = { openUrl(context, "https://github.com/Ganyu256/Linuxdeploy-Pro") },
                    )
                    AboutInfoLine("版本", "v${BuildConfig.VERSION_NAME}")
                    AboutInfoLine("包名", "io.github.ganyu256.linuxdeploypro")
                    AboutInfoLine("许可", "GPL-3.0")
                }
            }
        }
    }

    // 重装运行环境确认窗
    if (showReinstallConfirm) {
        ConfirmDialog(
            title = "重装运行环境",
            content = "将重新部署内置的 cli.sh / busybox / resize2fs 等文件，\n并更新环境变量。会覆盖这些程序文件，但保留你的配置与容器数据。",
            confirmText = "重新部署",
            onConfirm = {
                showReinstallConfirm = false
                Thread {
                    val ok = CliManager.reinstall(context)
                    val msg = if (ok) {
                        "运行环境已重新部署"
                    } else {
                        "运行环境重新部署失败，请检查应用数据目录"
                    }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }.start()
            },
            onDismiss = { showReinstallConfirm = false },
        )
    }
}

/** 打开外部链接（失败时 Toast 提示） */
private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
        )
    } catch (_: Exception) {
        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}

/** 关于信息行（Miuix 风格）：标签左、值右对齐，无箭头 */
@Composable
private fun AboutInfoLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
    }
}
