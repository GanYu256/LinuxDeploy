package io.github.ganyu256.linuxdeploypro.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import android.os.StatFs
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ganyu256.linuxdeploypro.model.ContainerConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 配置详情页：展示配置信息与操作入口。
 *
 * 操作已接到真实 CLI：部署 / 启停 / 终端 / 导出 / 导入 rootfs；
 * 镜像调整走编辑页（保存后自动 resize）。卡片底色与页面背景一致。
 */
@Composable
fun ConfigDetailScreen(
    config: ContainerConfig,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onToggleRunning: () -> Unit,
    onStatusQuery: () -> Unit,
    onDeploy: () -> Unit,
    onTerminal: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onResize: (Int) -> Unit,
    onViewLogs: () -> Unit,
    onDelete: () -> Unit,
    onDeleteContainer: () -> Unit,
) {
    val context = LocalContext.current
    // 调整镜像大小弹窗状态
    var showResizeDialog by remember { mutableStateOf(false) }
    var resizeInsufficient by remember { mutableStateOf(false) }
    val resizeInput = remember { TextFieldState() }
    val resizeContext = context

    // 调整镜像大小弹窗
    if (showResizeDialog) {
        ResizeImageDialog(
            context = resizeContext,
            currentSize = config.imageSize,
            imagePath = config.path,
            inputState = resizeInput,
            insufficient = resizeInsufficient,
            onInputChange = { resizeInsufficient = false },
            onConfirm = {
                val gb = resizeInput.text.toString().trim().toLongOrNull()
                if (gb == null || gb <= 0) {
                    resizeInsufficient = true
                    return@ResizeImageDialog
                }
                // 校验目标分区剩余容量
                val need = gb * 1024L * 1024L * 1024L
                val avail = try {
                    StatFs(config.path.ifBlank { "/data" }).availableBytes
                } catch (_: Exception) {
                    -1L
                }
                if (avail < 0) {
                    resizeInsufficient = true
                    return@ResizeImageDialog
                }
                if (need > avail) {
                    resizeInsufficient = true
                    return@ResizeImageDialog
                }
                showResizeDialog = false
                onResize(gb.toInt())
            },
            onDismiss = {
                showResizeDialog = false
                resizeInsufficient = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = config.name,
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 操作卡（含删除，统一风格）
            Card(modifier = Modifier.fillMaxWidth(), colors = cardColors()) {
                Column {
                    ActionRow(
                        title = if (config.running) "停止容器" else "启动容器",
                        subtitle = "chroot 方式启动，自动挂载/卸载系统文件",
                        onClick = onToggleRunning,
                    )
                    HorizontalDivider()
                    ActionRow(
                        title = "打开终端",
                        subtitle = "内置终端进入容器 shell",
                        onClick = onTerminal,
                    )
                    HorizontalDivider()
                    ActionRow(
                        title = "编辑配置",
                        subtitle = "修改参数并保存",
                        onClick = onEdit,
                    )
                    HorizontalDivider()
                    ActionRow(
                        title = "查看日志",
                        subtitle = "部署 / 启停等操作的实时输出",
                        onClick = onViewLogs,
                    )
                    HorizontalDivider()
                    ActionRow(
                        title = "状态查询",
                        subtitle = "查询运行状态、组件与挂载详情（自动跳转日志页）",
                        onClick = onStatusQuery,
                    )
                    HorizontalDivider()
                    ActionRow(
                        title = "部署容器",
                        subtitle = "构建 / 更新容器 rootfs（自动跳过确认）",
                        onClick = onDeploy,
                    )
                    if (config.installType == "image") {
                        HorizontalDivider()
                        ActionRow(
                            title = "调整镜像大小",
                            subtitle = "仅镜像安装：弹窗输入新大小（GB）",
                            onClick = { showResizeDialog = true },
                        )
                    }
                    HorizontalDivider()
                    ActionRow(
                        title = "导入容器",
                        subtitle = "从 tar 归档恢复容器（支持 tar.gz/xz/bz2/zst）",
                        onClick = onImport,
                    )
                    HorizontalDivider()
                    ActionRow(
                        title = "导出容器",
                        subtitle = "备份当前容器为 tar.gz 归档",
                        onClick = onExport,
                    )
                    HorizontalDivider()
                    ActionRow(
                        title = "删除配置",
                        subtitle = "删除该配置（保留容器目录数据）",
                        onClick = onDelete,
                        destructive = true,
                    )
                    HorizontalDivider()
                    ActionRow(
                        title = "删除容器",
                        subtitle = "删除容器目录数据（需输入 yes 确认）",
                        onClick = onDeleteContainer,
                        destructive = true,
                    )
                }
            }

            // 基本信息卡
            GroupTitle("配置详情")
            // 基本信息卡
            Card(modifier = Modifier.fillMaxWidth(), colors = cardColors()) {
                Column {
                    InfoLine("系统", config.distro)
                    HorizontalDivider()
                    InfoLine("发行版本", config.release.ifBlank { "—" })
                    HorizontalDivider()
                    InfoLine("架构", "arm64")
                    HorizontalDivider()
                    InfoLine(
                        "安装方式",
                        if (config.installType == "image") "镜像（ext4）" else "目录",
                    )
                    HorizontalDivider()
                    InfoLine("容器路径", config.path.ifBlank { "（部署时自动生成）" })
                    HorizontalDivider()
                    if (config.installType == "image") {
                        InfoLine("镜像大小", config.imageSize)
                        HorizontalDivider()
                    }
                    InfoLine("状态", if (config.running) "运行中" else "已停止")
                }
            }

            // 系统设置卡
            Card(modifier = Modifier.fillMaxWidth(), colors = cardColors()) {
                Column {
                    InfoLine("特权用户", config.user)
                    HorizontalDivider()
                    InfoLine("辅助组", config.userGroups)
                    HorizontalDivider()
                    InfoLine(
                        "安卓挂载",
                        if (!config.mountsEnabled || config.mounts.isEmpty()) {
                            "未启用"
                        } else {
                            config.mounts.joinToString("\n") { entry ->
                                val sep = entry.indexOf(':')
                                if (sep > 0) {
                                    entry.substring(0, sep) + " >> " + entry.substring(sep + 1)
                                } else {
                                    entry
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                    InfoLine(
                        "初始化系统",
                        when (config.init) {
                            "run-parts" -> "run-parts（${config.initPath.ifBlank { "未设置路径" }}）"
                            "systemctl" -> "systemctl（init 模式）"
                            else -> "SysV（rc${config.initLevel}）"
                        },
                    )
                    HorizontalDivider()
                    InfoLine("额外组件", config.components.ifBlank { "core" })
                }
            }

            // SSH 设置卡
            Card(modifier = Modifier.fillMaxWidth(), colors = cardColors()) {
                Column {
                    InfoLine("SSH", if (config.sshEnabled) "已启用" else "未启用")
                    HorizontalDivider()
                    InfoLine("SSH 端口", config.sshPort)
                    HorizontalDivider()
                    // 不展示明文密码（安全）；编辑页可修改
                    InfoLine("默认密码", if (config.password.isBlank()) "未设置" else "已设置（编辑页可修改）")
                }
            }

            // 软件源卡
            Card(modifier = Modifier.fillMaxWidth(), colors = cardColors()) {
                Column {
                    InfoLine("软件源", config.mirror.ifBlank { "发行版官方源" })
                }
            }

        }
    }
}

/** 卡片配色：底色与页面背景一致（浅色/深色统一） */
@Composable
private fun cardColors() = CardDefaults.defaultColors(
    color = MiuixTheme.colorScheme.background,
    contentColor = MiuixTheme.colorScheme.onSurface,
)

/** 信息行 */
@Composable
private fun InfoLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.36f),
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 15.sp,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.64f),
            textAlign = TextAlign.End,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 操作行：标题 + 说明 + 右箭头（不加圆角裁剪，避免遮字） */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val titleColor = if (destructive) Color(0xFFE53935) else MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 无障碍：声明按钮语义，TalkBack 朗读为"按钮"而非"双击激活"
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
    }
}
