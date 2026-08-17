package io.github.ganyu256.linuxdeploypro.ui

import android.content.Context
import android.os.StatFs
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 调整镜像大小弹窗：
 * - 显示当前大小
 * - 输入新大小（单位 GB，仅数字）
 * - 校验目标分区剩余容量：不足时输入框变红 + 底部红字"存储空间不足"
 */
@Composable
fun ResizeImageDialog(
    context: Context,
    currentSize: String,
    imagePath: String,
    inputState: TextFieldState,
    insufficient: Boolean,
    onInputChange: () -> Unit,
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
                    text = "调整镜像大小",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "当前大小：$currentSize\n请输入新大小（单位 GB，仅数字）：",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    state = inputState,
                    modifier = Modifier.fillMaxWidth(),
                    label = "新大小（GB）",
                    useLabelAsPlaceholder = true,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    // 仅数字键盘
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                )
                // 容量不足红字提示
                if (insufficient) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "存储空间不足",
                        fontSize = 12.sp,
                        color = Color(0xFFE53935),
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DialogBtn("取消", MiuixTheme.colorScheme.onSurface, onDismiss, Modifier.weight(1f))
                    DialogBtn("确定", MiuixTheme.colorScheme.primary, onConfirm, Modifier.weight(1f), primary = true)
                }
            }
        }
    }
}

/** 弹窗圆角按钮（与删除容器弹窗一致） */
@Composable
private fun DialogBtn(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    Box(
        modifier = modifier
            .background(
                if (primary) color.copy(alpha = 0.12f) else color.copy(alpha = 0.06f),
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
