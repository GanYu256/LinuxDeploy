package io.github.ganyu256.linuxdeploypro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 风格确认对话框：半透明遮罩 + 圆角卡片 + 双按钮。
 * 用于删除确认、部署引导等需要用户决策的交互（替代 CLI stdin 确认）。
 *
 * @param title      标题（如"删除配置"）
 * @param content    说明文字
 * @param confirmText 确认按钮文案（默认"确定"）
 * @param destructive 确认按钮是否警示色（删除等危险操作）
 * @param onConfirm  确认回调
 * @param onDismiss  取消/关闭回调
 */
@Composable
fun ConfirmDialog(
    title: String,
    content: String,
    confirmText: String = "确定",
    destructive: Boolean = false,
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
                    .background(
                        color = MiuixTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                    )
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
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 取消
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable(onClick = onDismiss)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "取消",
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.width(0.dp))
                    // 确认
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = if (destructive) {
                                    Color(0xFFE53935).copy(alpha = 0.12f)
                                } else {
                                    MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                },
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable(onClick = onConfirm)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = confirmText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (destructive) Color(0xFFE53935) else MiuixTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
