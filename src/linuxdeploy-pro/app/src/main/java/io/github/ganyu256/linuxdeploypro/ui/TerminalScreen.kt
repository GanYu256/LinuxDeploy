package io.github.ganyu256.linuxdeploypro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ganyu256.linuxdeploypro.data.CliManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.text.input.ImeAction

/** ANSI 控制序列清洗：颜色 / 光标移动 / 换行回车等原始控制符不进入界面文本 */
private val ANSI_PATTERN = Regex(
    "\u001B\\[[0-9;?]*[ -/]*[@-~]" + // CSI：颜色、光标、清屏等
        "|\u001B\\][^\u0007]*\u0007" + // OSC：标题、超链接等（以 BEL 结尾）
        "|\u001B[()][A-Z0-9]" + // 字符集切换
        "|\r", // 回车（进度条刷新），避免撑出多余空行
)

/** 终端窗口标题栏三色点 */
@Composable
private fun TerminalDot(color: Color) {
    Box(
        modifier = Modifier
            .width(10.dp)
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

/**
 * 内置终端页：进入容器 shell（日志页同款黑框布局）。
 *
 * 通过 su 拉起 cli.sh shell 的持久进程；容器已挂载直接登录（不重复挂载、
 * 不启动组件），未挂载才先启动再 chroot。输出区黑色背景、等宽字体、
 * 可长按跨行复制；输入框回车发送。
 */
@Composable
fun TerminalScreen(
    configName: String,
    distro: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val outputLines = remember { mutableStateListOf<String>() }
    val inputState = rememberTextFieldState()
    val listState = rememberLazyListState()

    // 终端会话与状态：0 连接中 / 1 运行中 / 2 已退出 / 3 连接失败
    var session by remember { mutableStateOf<CliManager.ShellSession?>(null) }
    var sessionState by remember { mutableIntStateOf(0) }
    var ptyMode by remember { mutableStateOf(false) }
    var sessionSeq by remember { mutableIntStateOf(0) }

    // 启动（或重连）终端进程；LaunchedEffect 被取消时读循环一并终止
    LaunchedEffect(sessionSeq) {
        // 重连前先销毁旧进程，避免残留 root 进程
        session?.process?.destroy()
        session = null
        outputLines.clear()

        val shellSession = withContext(Dispatchers.IO) {
            CliManager.openShell(context, configName)
        }
        if (shellSession == null) {
            sessionState = 3
            outputLines.add("连接失败：未获得 root 权限、CLI 不可用或容器尚未部署")
            return@LaunchedEffect
        }
        session = shellSession
        ptyMode = shellSession.ptyMode
        sessionState = 1
        outputLines.add("已连接到容器：$configName")
        outputLines.add(
            if (shellSession.ptyMode) {
                "提示：输入 exit 退出；返回页面即断开"
            } else {
                "提示：简化模式（无提示符），输入命令回车执行；输入 exit 退出"
            },
        )

        // 阻塞读循环放到 IO 线程；逐行切回主线程追加，避免卡 UI
        val proc = shellSession.process
        withContext(Dispatchers.IO) {
            try {
                val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    val clean = ANSI_PATTERN.replace(line, "")
                    if (clean.isNotBlank()) {
                        withContext(Dispatchers.Main.immediate) { outputLines.add(clean) }
                    }
                }
            } catch (_: Exception) {
                // 进程被销毁（用户返回 / 重连）时忽略
            }
            withContext(Dispatchers.Main.immediate) {
                sessionState = 2
                outputLines.add("终端已退出")
            }
        }
    }

    // 离开页面时销毁终端进程，防止残留 root 进程
    DisposableEffect(Unit) {
        onDispose {
            session?.process?.destroy()
            session = null
        }
    }

    // 新输出时自动滚到底部
    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            listState.animateScrollToItem(outputLines.lastIndex)
        }
    }

    /** 发送一行命令到终端进程 */
    fun sendCommand() {
        val sess = session
        val cmd = inputState.text.toString()
        if (cmd.isBlank()) return
        if (sess == null || sessionState != 1) {
            outputLines.add("终端未连接，无法发送命令（可点右上角重连）")
            return
        }
        try {
            sess.process.outputStream.write((cmd + "\n").toByteArray(Charsets.UTF_8))
            sess.process.outputStream.flush()
            // PTY 模式下终端自身会回显输入，无需重复显示
            if (!ptyMode) outputLines.add(">>> $cmd")
        } catch (_: Exception) {
            outputLines.add("发送失败：终端进程已结束")
            sessionState = 2
        }
        inputState.edit { replace(0, length, "") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 终端黑框（日志页同款布局）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0D1117)),
        ) {
            // 标题栏：三色点 + 发行版 · 终端 + 右侧状态/重连
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
                    text = "$distro · 终端",
                    fontSize = 12.sp,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.weight(1f),
                )
                // 状态点 + 重连
                val stateColor = when (sessionState) {
                    1 -> Color(0xFF2BCB77)
                    2 -> Color(0xFF8B949E)
                    else -> Color(0xFFE3B341)
                }
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(stateColor),
                )
                if (sessionState == 2 || sessionState == 3) {
                    IconButton(onClick = { sessionSeq++ }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "重连",
                            tint = Color(0xFF8B949E),
                        )
                    }
                }
            }
            // 分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(Color(0xFF21262D)),
            )
            // 输出区：黑底 + 等宽字体（12sp 与日志页一致）+ 长按可复制
            if (outputLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (sessionState) {
                            0 -> "正在连接容器…"
                            3 -> "连接失败"
                            else -> "终端已连接"
                        },
                        fontSize = 13.sp,
                        color = Color(0xFF8B949E),
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(outputLines) { _, line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                color = if (line.startsWith(">>> ")) {
                                    Color(0xFF79C0FF)
                                } else {
                                    Color(0xFFE6EDF3)
                                },
                            )
                        }
                    }
                }
            }
        }
        // 输入行：imePadding 只作用于输入行，IME 弹出时输出区由
        // adjustResize 压缩、输入行稳定贴在输入法上方（不整体跳位）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                state = inputState,
                modifier = Modifier.weight(1f),
                label = "输入命令，回车发送",
                useLabelAsPlaceholder = true,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                onKeyboardAction = { performDefault ->
                    // 回车触发 IME 发送：先发命令，再执行默认行为（收起键盘）
                    sendCommand()
                    performDefault()
                },
            )
            IconButton(
                onClick = { sendCommand() },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}
