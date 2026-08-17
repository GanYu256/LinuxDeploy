package io.github.ganyu256.linuxdeploypro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 删除容器第一步确认弹窗：危险提示 + "同时删除配置文件"勾选。
 */
@Composable
fun DeleteContainerConfirmDialog(
    name: String,
    alsoDeleteConfig: Boolean,
    onAlsoDeleteConfigChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                Text(
                    text = "删除容器",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "将删除容器「$name」的全部数据（容器目录）。此操作不可恢复。",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                // 勾选：同时删除配置文件
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAlsoDeleteConfigChange(!alsoDeleteConfig) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (alsoDeleteConfig) ToggleableState.On else ToggleableState.Off,
                        onClick = { onAlsoDeleteConfigChange(!alsoDeleteConfig) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "同时删除配置文件",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DialogButton("取消", MiuixTheme.colorScheme.onSurface, onDismiss, Modifier.weight(1f))
                    DialogButton("确定", Color(0xFFE53935), onConfirm, Modifier.weight(1f), destructive = true)
                }
            }
        }
    }
}

/**
 * 删除容器第二步确认弹窗：必须输入 "yes" 才允许删除。
 * yes 为前端字符文本校验，与 CLI 无关。
 */
@Composable
fun YesInputConfirmDialog(
    title: String,
    content: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val yesState = rememberTextFieldState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = content,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    state = yesState,
                    modifier = Modifier.fillMaxWidth(),
                    label = "请输入 yes 确认删除",
                    useLabelAsPlaceholder = true,
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DialogButton("取消", MiuixTheme.colorScheme.onSurface, onDismiss, Modifier.weight(1f))
                    DialogButton(
                        "确定删除",
                        Color(0xFFE53935),
                        onClick = { onConfirm(yesState.text.toString()) },
                        modifier = Modifier.weight(1f),
                        destructive = true,
                    )
                }
            }
        }
    }
}

/** 弹窗圆角按钮 */
@Composable
private fun DialogButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    Box(
        modifier = modifier
            .background(
                if (destructive) color.copy(alpha = 0.12f) else color.copy(alpha = 0.06f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}
