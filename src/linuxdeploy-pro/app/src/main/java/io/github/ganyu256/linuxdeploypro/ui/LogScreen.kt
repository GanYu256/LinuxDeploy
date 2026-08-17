package io.github.ganyu256.linuxdeploypro.ui

import android.content.ClipData
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 日志页：专属大窗口输出日志（终端风格）。
 *
 * 整个窗口是一块可选中文本：长按可跨行拖动多选复制；
 * 顶部提供“复制全部”与“清空”。日志按时间正序排列
 * （旧在上、新在下），新日志到达时自动滚动到底部。
 *
 * 4.0.4 起优先尾随 CLI 的日志文件（每次操作一个独立时间戳文件）：
 * 轮询增量读取，旧在上新在下，跨行可复制；没有可用日志文件时
 * 回退显示内存会话日志（如启动提示、保存配置等短消息）。
 */
@Composable
fun LogScreen(
    logs: List<String>,
    logFile: java.io.File?,
    refreshTick: Int,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    // 剪贴板：LocalClipboardManager 已弃用，改用系统 ClipboardManager
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val scrollState = rememberScrollState()
    // 从日志文件读出的完整行（旧在上、新在下）
    val fileLines = remember { mutableStateListOf<String>() }

    // 尾随日志文件：500ms 轮询增量读取。
    // 文件路径变化（新操作）或 refreshTick 变化（操作结束兜底刷新）时，
    // 从文件开头整体重读，保证窗口内容与磁盘日志一致。
    LaunchedEffect(logFile?.absolutePath, refreshTick) {
        fileLines.clear()
        val f = logFile ?: return@LaunchedEffect
        var offset = 0L
        val pending = ByteArrayOutputStream()
        while (true) {
            if (f.exists()) {
                try {
                    val len = f.length()
                    if (len < offset) {
                        // 文件被替换/截断：整体重读
                        offset = 0L
                        pending.reset()
                        fileLines.clear()
                    }
                    if (len > offset) {
                        val chunk = ByteArray((len - offset).toInt().coerceAtMost(64 * 1024))
                        val read = RandomAccessFile(f, "r").use { raf ->
                            raf.seek(offset)
                            raf.read(chunk)
                        }
                        if (read > 0) {
                            offset += read
                            fileLines.addAll(decodeUtf8Lines(pending, chunk.copyOf(read)))
                        }
                    }
                } catch (_: Exception) {
                    // 文件写入过程中短暂不可读（如刚创建/被占用），下一轮重试
                }
            }
            delay(500)
        }
    }

    // 有日志文件优先显示文件内容，否则显示会话日志
    val showingFile = logFile != null
    val displayLogs = if (showingFile) fileLines else logs
    val fullText = remember(displayLogs.size) { displayLogs.joinToString("\n") }

    // 新日志到达（条数变化）时自动滚到底部，模拟终端输出跟屏；
    // 先等一帧让布局完成，再按当前最大滚动位置落底。
    LaunchedEffect(displayLogs.size) {
        if (displayLogs.isNotEmpty()) {
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 专属日志大窗口（终端风格：深色底 + 等宽字体），顶部不再占行，窗口尽量扩大
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0D1117)),
        ) {
            // 窗口标题栏：三色圆点 + 标题 + 右侧操作（复制全部 / 清空）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TerminalDot(Color(0xFFFF5F56))
                Spacer(modifier = Modifier.width(6.dp))
                TerminalDot(Color(0xFFFFBD2E))
                Spacer(modifier = Modifier.width(6.dp))
                TerminalDot(Color(0xFF27C93F))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "linuxdeploy",
                    fontSize = 12.sp,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.weight(1f),
                )
                WindowAction(
                    text = "复制全部",
                    color = MiuixTheme.colorScheme.primary,
                    onClick = {
                        if (displayLogs.isNotEmpty()) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("LinuxDeploy 日志", fullText))
                            Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "暂无日志可复制", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                Spacer(modifier = Modifier.width(10.dp))
                WindowAction(
                    text = "清空",
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    onClick = {
                        fileLines.clear()
                        onClear()
                    },
                )
            }
            // 标题栏分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(Color(0xFF21262D)),
            )
            if (displayLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无日志\n执行部署、启停容器等操作后会在这里输出",
                        fontSize = 13.sp,
                        color = Color(0xFF8B949E),
                    )
                }
            } else {
                // 逐行渲染：按级别着色（正常绿 / 警告黄 / 错误红），仍支持跨行复制
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        displayLogs.forEach { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                color = logLineColor(line),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日志行按级别着色：
 * - 错误（失败/错误/Error）→ 红
 * - 警告 → 黄
 * - 其他正常输出 → 绿
 */
private fun logLineColor(line: String): Color {
    return when {
        line.contains("失败") || line.contains("错误") ||
            line.contains("Error") || line.contains("error") -> Color(0xFFFF6B6B)
        line.contains("警告") -> Color(0xFFE3B341)
        else -> Color(0xFF7EE787)
    }
}

/**
 * 增量解码 UTF-8 字节流为完整行列表。
 *
 * 轮询读取可能把一行拆到两次读取中间，末尾的不完整多字节序列需要
 * 保留到下一轮继续拼接（否则中文日志会出现乱码/断行）。
 *
 * @param pending 上一轮残留的不完整字节
 * @param chunk   本轮新读到的字节
 * @return 本轮可展示的完整行（以换行符结尾的部分）
 */
private fun decodeUtf8Lines(pending: ByteArrayOutputStream, chunk: ByteArray): List<String> {
    pending.write(chunk)
    val bytes = pending.toByteArray()
    // 末尾最多可能是 3 字节的不完整 UTF-8 序列；从 0 到 3 逐字节试探截断，
    // 找到能完整解码的最大前缀，剩下的字节留在 pending 里下一轮拼接。
    for (cut in 0..3) {
        if (cut > bytes.size) break
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes, 0, bytes.size - cut)).toString()
            pending.reset()
            if (cut > 0) pending.write(bytes, bytes.size - cut, cut)
            val lines = text.split("\n")
            if (lines.isNotEmpty() && lines.last().isEmpty()) {
                // 末尾是换行符：没有未完成行
                return lines.dropLast(1)
            }
            // 最后一段是不完整行，放回 pending 下一轮拼接
            pending.reset()
            pending.write(lines.last().toByteArray(Charsets.UTF_8))
            return lines.dropLast(1)
        } catch (_: CharacterCodingException) {
            // 截断后仍无法解码，继续增加截断长度
        }
    }
    // 全部无法解码（异常字节流）：丢弃，避免 pending 无限增长
    pending.reset()
    return emptyList()
}

/** 窗口标题栏里的装饰圆点 */
@Composable
private fun TerminalDot(color: Color) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 窗口顶部操作按钮（文字按钮样式） */
@Composable
private fun WindowAction(
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}
