package io.github.ganyu256.linuxdeploypro.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分组标题（HyperOS 设置页风格）：组内小标题，位于圆角卡片上方。
 * 供配置编辑页、设置页等列表页共用。
 */
@Composable
fun GroupTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 0.dp),
    )
}
