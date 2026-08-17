package io.github.ganyu256.linuxdeploypro.ui

import io.github.ganyu256.linuxdeploypro.BuildConfig
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/** 主页设备信息数据（不探测 GPU，按需求只展示 SoC） */
private data class DeviceInfo(
    val kernel: String = "读取中…",
    val selinux: String = "读取中…",
    val androidVersion: String = "读取中…",
    val arch: String = "读取中…",
    val abis: String = "读取中…",
    val soc: String = "读取中…",
)

/**
 * 主页：顶部蓝色横幅（含 LinuxDeploy 标题）+ 设备信息卡。
 *
 * 背景使用主题默认色，不额外铺色块；设备信息统一收进一张卡内。
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
) {
    val info by produceState(initialValue = DeviceInfo(), Unit) {
        value = withContext(Dispatchers.IO) { loadDeviceInfo() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderCard()

        // 设备信息：整体一个框（深色下纯黑背景，与详情页卡片一致）
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.background,
                contentColor = MiuixTheme.colorScheme.onSurface,
            ),
        ) {
            InfoRow(label = "内核版本", value = info.kernel)
            HorizontalDivider()
            InfoRow(label = "SELinux", value = info.selinux)
            HorizontalDivider()
            InfoRow(label = "Android 版本", value = info.androidVersion)
            HorizontalDivider()
            InfoRow(label = "架构", value = info.arch)
            HorizontalDivider()
            InfoRow(label = "支持的 ABI", value = info.abis)
            HorizontalDivider()
            InfoRow(label = "SoC", value = info.soc)
        }
    }
}

/**
 * 顶部蓝色横幅：渐变底 + 终端提示符标识 + LinuxDeploy 标题 + 版本号。
 * 设置齿轮固定在右上角。
 */
@Composable
private fun HeaderCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF4F6BFF), Color(0xFF8B2FF7))))
            .padding(horizontal = 20.dp, vertical = 22.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ">_",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // Linux Deploy + 版本号同规格（白色粗体），整体上下居中
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Linux Deploy",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 信息行：左侧标签，右侧数值 */
/** 信息行：左侧标签，右侧数值 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
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

/** 读取第一行文本的小工具（读取失败返回 null，不抛异常） */
private fun readFirstLine(path: String): String? = try {
    val file = File(path)
    if (file.exists()) {
        file.bufferedReader().use { it.readLine() }
    } else {
        null
    }
} catch (_: Exception) {
    null
}

/** 执行 getprop 读取系统属性（只读，无需 root） */
private fun runGetProp(name: String): String? = try {
    val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
    val value = process.inputStream.bufferedReader().use { it.readLine() }
    process.waitFor()
    value?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/** 通过 root 执行 getenforce（仅在普通读取失败时兜底） */
private fun runSuGetEnforce(): String? = try {
    val process = ProcessBuilder("su", "-c", "getenforce").redirectErrorStream(true).start()
    val value = process.inputStream.bufferedReader().use { it.readLine() }
    process.waitFor()
    value?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/**
 * 读取内核版本：
 * 优先解析 /proc/version，失败时用 System.getProperty("os.version") 兜底。
 */
private fun readKernel(): String {
    val line = readFirstLine("/proc/version")
    line?.split(" ")?.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { return it }
    return System.getProperty("os.version")?.takeIf { it.isNotBlank() } ?: "未知"
}

/** 读取 SELinux 状态：系统属性 → 内核节点 → root getenforce 兜底 */
private fun readSelinux(): String {
    val enforce = readFirstLine("/sys/fs/selinux/enforce")
    when (enforce) {
        "1" -> return "Enforcing（强制）"
        "0" -> return "Permissive（宽松）"
    }

    val prop = runGetProp("ro.boot.selinux")?.trim()?.lowercase()
    when (prop) {
        "enforcing", "1" -> return "Enforcing（强制）"
        "permissive", "0" -> return "Permissive（宽松）"
        "disabled" -> return "已禁用"
    }

    val su = runSuGetEnforce()?.trim()
    return when (su?.lowercase()) {
        "enforcing" -> "Enforcing（强制）"
        "permissive" -> "Permissive（宽松）"
        "disabled" -> "已禁用"
        else -> "未启用 / 无法读取"
    }
}

/** 常见高通 / 联发科芯片型号 → 中文名称映射（未知型号回退显示原值） */
private val SOC_NAME_MAP = mapOf(
    // 高通骁龙
    "SM8150" to "骁龙 855",
    "SM8250" to "骁龙 865",
    "SM8350" to "骁龙 888",
    "SM8450" to "骁龙 8 Gen1",
    "SM8475" to "骁龙 8+ Gen1",
    "SM8550" to "骁龙 8 Gen2",
    "SM8650" to "骁龙 8 Gen3",
    "SM8750" to "骁龙 8 Elite",
    "SM8735" to "骁龙 8s Gen4",
    "SM8635" to "骁龙 8s Gen3",
    "SM7675" to "骁龙 7+ Gen3",
    "SM7550" to "骁龙 7 Gen3",
    "SM7325" to "骁龙 778G",
    "SM6375" to "骁龙 695",
    "SM6225" to "骁龙 680",
    "SM4350" to "骁龙 480",
    "MSM8998" to "骁龙 835",
    "MSM8996" to "骁龙 820",
    // 联发科天玑
    "MT6989" to "天玑 9300",
    "MT6985" to "天玑 9200",
    "MT6895" to "天玑 8100",
    "MT6893" to "天玑 1200",
    "MT6877" to "天玑 1300",
    "MT6833" to "天玑 810",
)

/** 读取 SoC 型号并转换为中文名称 */
private fun readSoc(): String {
    val model = runGetProp("ro.soc.model")
        ?: runGetProp("ro.board.platform")
        ?: runGetProp("ro.hardware")
        ?: return "未知"
    return SOC_NAME_MAP[model.trim().uppercase()] ?: model
}

/** 汇总设备信息：内核 / SELinux / 系统 / 架构 / ABI / SoC */
private fun loadDeviceInfo(): DeviceInfo {
    return DeviceInfo(
        kernel = readKernel(),
        selinux = readSelinux(),
        androidVersion = "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "未知",
        abis = Build.SUPPORTED_ABIS.joinToString("、"),
        soc = readSoc(),
    )
}
