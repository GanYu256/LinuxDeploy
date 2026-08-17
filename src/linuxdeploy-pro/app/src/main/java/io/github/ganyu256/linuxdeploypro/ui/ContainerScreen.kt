package io.github.ganyu256.linuxdeploypro.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ganyu256.linuxdeploypro.R
import io.github.ganyu256.linuxdeploypro.model.ContainerConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 发行版 logo 资源映射（drawable-nodpi 下的官方品牌 SVG 转 PNG）。
 * 未收录的发行版（rootfs 等）返回 null，界面回退首字母头像。
 */
private fun distroLogoRes(distro: String): Int? = when (distro) {
    "debian" -> R.drawable.debian
    "ubuntu" -> R.drawable.ubuntu
    "archlinux" -> R.drawable.archlinux
    "alpine" -> R.drawable.alpine
    "kali" -> R.drawable.kali
    "slackware" -> R.drawable.slackware
    else -> null
}

/**
 * 容器页：配置列表（多个配置并存）或空状态。
 *
 * 每张配置卡直接提供“启动/停止”和“编辑”按钮，点击卡片主体进入详情页。
 */
@Composable
fun ContainerScreen(
    configs: List<ContainerConfig>,
    deployingName: String?,
    autostart: Set<String>,
    onOpen: (ContainerConfig) -> Unit,
    onToggleRunning: (ContainerConfig) -> Unit,
    onEdit: (ContainerConfig) -> Unit,
    onDeploy: (ContainerConfig) -> Unit,
    onLogs: (ContainerConfig) -> Unit,
    onTerminal: (ContainerConfig) -> Unit,
    onToggleAutostart: (ContainerConfig) -> Unit,
    onDelete: (ContainerConfig) -> Unit,
) {
    if (configs.isEmpty()) {
        // 空状态：引导用户点右下角 + 新建
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "还没有任何容器配置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "点击右下角 ➕ 新建第一个配置",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = "共 ${configs.size} 个配置",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
            configs.forEach { config ->
                ContainerCard(
                    config = config,
                    deployingName = deployingName,
                    autostart = autostart,
                    onClick = { onOpen(config) },
                    onToggleRunning = { onToggleRunning(config) },
                    onEdit = { onEdit(config) },
                    onDeploy = { onDeploy(config) },
                    onLogs = { onLogs(config) },
                    onTerminal = { onTerminal(config) },
                    onToggleAutostart = { onToggleAutostart(config) },
                    onDelete = { onDelete(config) },
                )
            }
        }
    }
}

/** 单个容器配置卡片 */
@Composable
private fun ContainerCard(
    config: ContainerConfig,
    deployingName: String?,
    autostart: Set<String>,
    onClick: () -> Unit,
    onToggleRunning: () -> Unit,
    onEdit: () -> Unit,
    onDeploy: () -> Unit,
    onLogs: () -> Unit,
    onTerminal: () -> Unit,
    onToggleAutostart: () -> Unit,
    onDelete: () -> Unit,
) {
    // 更多选项面板展开状态（⋮ 点击/长按触发）
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        // 卡片底色与页面背景保持一致（浅色/深色均如此），避免色块突兀
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.background,
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 发行版 logo（官方品牌资源 PNG）；未收录时回退首字母头像
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val logoRes = distroLogoRes(config.distro)
                    if (logoRes != null) {
                        Icon(
                            painter = painterResource(logoRes),
                            contentDescription = "${config.distro} 图标",
                            modifier = Modifier.size(36.dp),
                        )
                    } else {
                        Text(
                            text = config.distro.take(1).uppercase(),
                            color = MiuixTheme.colorScheme.primary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        text = buildString {
                            append(config.distro)
                            if (config.release.isNotBlank()) {
                                append(" ${config.release}")
                            }
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 右侧操作组：状态/启动/编辑/菜单 整体靠右（名称区 weight 占满左侧）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    StatusBadge(running = config.running, deploying = config.name == deployingName)
                    Spacer(modifier = Modifier.width(8.dp))
                    RowAction(
                        text = if (config.running) "停止" else "启动",
                        color = if (config.running) Color(0xFFE53935) else MiuixTheme.colorScheme.primary,
                        onClick = onToggleRunning,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    RowAction(
                        text = "编辑",
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        onClick = onEdit,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    IconButton(
                        onClick = { menuExpanded = !menuExpanded },
                        modifier = Modifier.combinedClickable(
                            onClick = { menuExpanded = !menuExpanded },
                            onLongClick = { menuExpanded = true },
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多选项",
                            tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }
            // 展开的更多选项面板
            if (menuExpanded) {
                Spacer(modifier = Modifier.padding(top = 6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                        .padding(8.dp),
                ) {
                    // 双排 2×2 操作网格：第一排 终端/开机自启，第二排 部署/删除配置
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MenuGridButton("终端", Icons.Filled.Edit, onTerminal, Modifier.weight(1f))
                        MenuGridButton(
                            "开机自启",
                            if (config.name in autostart) Icons.Filled.CheckCircle else Icons.Filled.Add,
                            onToggleAutostart,
                            Modifier.weight(1f),
                            active = config.name in autostart,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MenuGridButton("部署", Icons.Filled.Add, onDeploy, Modifier.weight(1f))
                        MenuGridButton("删除配置", Icons.Filled.Delete, onDelete, Modifier.weight(1f), destructive = true)
                    }
                    // 收起条：向上箭头
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { menuExpanded = false }
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "▲ 收起",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.padding(top = 6.dp))
            Text(
                text = "容器路径：" + config.path.ifBlank { "（部署时自动生成）" },
                fontSize = 12.sp,
                maxLines = 1,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
    }
}

/** 配置行内的紧凑文字操作按钮（带背景色块，与控件同框） */
@Composable
private fun RowAction(
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** 状态徽标三态：运行中（绿）/ 部署中（蓝）/ 已停止（灰），以 CLI 实际运行状态为准。 */
@Composable
private fun StatusBadge(running: Boolean, deploying: Boolean) {
    val (text, color) = when {
        running -> "运行中" to Color(0xFF2BCB77)
        deploying -> "部署中" to Color(0xFF4F6BFF)
        else -> "已停止" to MiuixTheme.colorScheme.onSurfaceContainerVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}


/** 菜单双排网格按钮：图标 + 文字 + 背景框（等宽）；active 时变绿 */
@Composable
private fun MenuGridButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    active: Boolean = false,
) {
    val tint = when {
        active -> Color(0xFF4CAF50)
        destructive -> Color(0xFFE53935)
        else -> MiuixTheme.colorScheme.onSurface
    }
    val bg = when {
        active -> Color(0xFF4CAF50).copy(alpha = 0.15f)
        destructive -> Color(0xFFE53935).copy(alpha = 0.1f)
        else -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = tint,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = tint,
        )
    }
}
