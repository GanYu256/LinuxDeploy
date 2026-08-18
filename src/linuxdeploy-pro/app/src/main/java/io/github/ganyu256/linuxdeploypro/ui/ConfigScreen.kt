package io.github.ganyu256.linuxdeploypro.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ganyu256.linuxdeploypro.model.ContainerConfig
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 系统 → 可选发行版本映射（与 CLI 4.0 / debootstrap 对齐） */
private val DISTRO_RELEASES = mapOf(
    "debian" to listOf("trixie", "bookworm", "bullseye"),
    "ubuntu" to listOf("noble", "jammy", "focal"),
    "kali" to listOf("kali-rolling"),
    "alpine" to listOf("edge", "3.22", "3.21", "3.20"),
    "archlinux" to listOf("rolling"),
    "slackware" to listOf("15.0", "current"),
    // 本地 rootfs 包：没有发行版本概念
    "rootfs" to emptyList(),
)

/** 系统显示名称（内部值保持英文，界面展示中文名） */
private val DISTRO_LABELS = mapOf(
    "debian" to "Debian",
    "ubuntu" to "Ubuntu",
    "kali" to "Kali Linux",
    "alpine" to "Alpine Linux",
    "archlinux" to "Arch Linux",
    "slackware" to "Slackware",
    "rootfs" to "本地 rootfs 包",
)

/**
 * 系统 → 默认软件源。
 *
 * 新建配置时按所选系统自动填入对应默认源；用户仍可手动修改，
 * 清空后回退发行版官方源（CLI 层处理）。
 * 注意：
 * - 统一用 http；
 * - alpine / archlinux 用清华镜像（USTC 索引文件不可下）。
 */
private val DISTRO_MIRRORS = mapOf(
    "debian" to "http://mirrors.ustc.edu.cn/debian/",
    "ubuntu" to "http://mirrors.ustc.edu.cn/ubuntu-ports/",
    "kali" to "http://mirrors.ustc.edu.cn/kali/",
    "alpine" to "http://mirrors.tuna.tsinghua.edu.cn/alpine/",
    "archlinux" to "http://mirrors.tuna.tsinghua.edu.cn/archlinuxarm/",
    "rootfs" to "",
)

/** 系统内部值的有序列表（保证选择器选项顺序稳定） */
private val DISTRO_KEYS = DISTRO_LABELS.keys.toList()

/** 取某个系统的默认发行版本（rootfs 返回空） */
private fun defaultRelease(distro: String): String =
    DISTRO_RELEASES[distro]?.firstOrNull() ?: ""

/** 取某个系统的默认软件源（rootfs 返回空串，表示使用官方源） */
private fun defaultMirror(distro: String): String =
    DISTRO_MIRRORS[distro] ?: ""

/**
 * 新建 / 编辑配置页（Miuix 列表式风格）。
 *
 * 文本类字段直接平铺在页面上：小字标题在上方，下方就是输入框；
 * 选项类字段用 OverlayDropdownPreference（HyperOS 下拉面板）；
 * 开关类字段用 SwitchPreference。系统与发行版本两级联动；
 * 默认密码始终可用（与 SSH 开关无关）；安卓挂载用“开关 + 列表 + 弹窗添加”；
 */
@Composable
fun ConfigScreen(
    initial: ContainerConfig?,
    onBack: () -> Unit,
    onSave: (ContainerConfig) -> Unit,
) {
    val context = LocalContext.current
    val isEdit = initial != null

    // 文本输入框状态（Miuix TextField 基于 TextFieldState 管理文本，直接平铺编辑）
    val nameState = rememberTextFieldState(initial?.name ?: "")
    val imageSizeState = rememberTextFieldState(initial?.imageSize ?: "4G")
    val pathState = rememberTextFieldState(initial?.path ?: "")
    // 新建时按系统自动填入默认中科大镜像；编辑时保留用户已保存的值
    val mirrorState = rememberTextFieldState(
        initial?.mirror ?: defaultMirror(initial?.distro ?: "debian"),
    )
    val userState = rememberTextFieldState(initial?.user ?: "root")
    val userGroupsState = rememberTextFieldState(
        initial?.userGroups ?: "aid_inet aid_sdcard_rw aid_graphics",
    )
    val sshPortState = rememberTextFieldState(initial?.sshPort ?: "22")
    val passwordState = rememberTextFieldState(initial?.password ?: "changeme")

    // 基础信息
    var distro by rememberSaveable(initial?.distro) { mutableStateOf(initial?.distro ?: "debian") }
    var release by rememberSaveable(initial?.release, initial?.distro) {
        mutableStateOf(initial?.release ?: defaultRelease(initial?.distro ?: "debian"))
    }

    // 安装设置
    var installType by rememberSaveable(initial?.installType) { mutableStateOf(initial?.installType ?: "directory") }

    // SSH 设置（旧配置可能在 INCLUDE 里带 extra/ssh 但没有 sshEnabled，读入时一并同步）
    var sshEnabled by rememberSaveable(initial?.sshEnabled, initial?.components) {
        mutableStateOf(
            (initial?.sshEnabled == true) ||
                initial?.components.orEmpty().split(Regex("[ ,]+")).contains("extra/ssh"),
        )
    }

    // 初始化系统：sysv（默认，启动时执行 /etc/rcN.d 的 S 脚本）/ run-parts（自定义脚本目录）
    var initSystem by rememberSaveable(initial?.init) { mutableStateOf(initial?.init ?: "sysv") }
    val initLevelState = rememberTextFieldState(initial?.initLevel ?: "3")
    val initPathState = rememberTextFieldState(initial?.initPath ?: "/etc/rc.d")

    // 安卓文件挂载：开关 + 挂载行列表（每行“源:目标”两个输入框，行内编辑）
    val mountRows = remember {
        mutableStateListOf<MountRow>().apply {
            initial?.mounts.orEmpty().forEach { entry ->
                val sep = entry.indexOf(':')
                val src = if (sep > 0) entry.substring(0, sep) else entry
                val dst = if (sep > 0) entry.substring(sep + 1) else ""
                add(MountRow(src, dst))
            }
        }
    }
    var mountsEnabled by rememberSaveable(initial?.mountsEnabled, initial?.mounts?.isEmpty()) {
        // 旧配置没有 mountsEnabled 字段时，列表非空视为已启用
        mutableStateOf(initial?.mountsEnabled ?: initial?.mounts?.isNotEmpty() == true)
    }

    /** 切换系统：发行版与默认镜像联动 */
    fun selectDistro(newDistro: String) {
        distro = newDistro
        val releases = DISTRO_RELEASES[newDistro].orEmpty()
        release = if (releases.isEmpty()) "" else if (release in releases) release else releases.first()
        // 切换系统后自动更新镜像源为新系统的默认源
        mirrorState.edit { replace(0, length, defaultMirror(newDistro)) }
    }

    /**
     * 组装 INCLUDE：编辑时保留用户已有组件（desktop/x11 等前端无选择器、
     * 但可能通过 CLI 或手工配置过的组件不能保存即丢），仅同步 SSH 开关；
     * 新建时 core 必选 + SSH（启用时）。
     */
    fun buildComponents(): String {
        val base = if (isEdit) {
            initial?.components
                ?.split(Regex("[ ,]+"))
                ?.filter { it.isNotBlank() && it != "extra/ssh" }
                ?.toMutableList()
                ?: mutableListOf()
        } else {
            mutableListOf("core")
        }
        if (base.isEmpty()) base.add("core")
        if (sshEnabled && "extra/ssh" !in base) base.add("extra/ssh")
        // 初始化系统启用时保证 INCLUDE 含 init 组件（sysv/run-parts 均依赖它生效）
        if ("init" !in base) base.add("init")
        return base.joinToString(" ")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (isEdit) "编辑配置" else "新建配置",
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
            // ===== 基础信息 =====
            GroupTitle("基础信息")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
            ) {
                Column {
                    LabeledTextField(
                        label = "配置名称",
                        state = nameState,
                    )
                    HorizontalDivider()
                    OverlayDropdownPreference(
                        title = "系统",
                        summary = DISTRO_LABELS[distro] ?: distro,
                        items = DISTRO_LABELS.values.toList(),
                        selectedIndex = DISTRO_KEYS.indexOf(distro).coerceAtLeast(0),
                        onSelectedIndexChange = { selectDistro(DISTRO_KEYS[it]) },
                    )
                    HorizontalDivider()
                    OverlayDropdownPreference(
                        title = "发行版本",
                        summary = if (distro == "rootfs") "本地包无需版本" else release.ifBlank { "请选择" },
                        items = DISTRO_RELEASES[distro].orEmpty().ifEmpty { listOf("本地包无需版本") },
                        selectedIndex = if (distro == "rootfs") {
                            0
                        } else {
                            DISTRO_RELEASES[distro].orEmpty().indexOf(release).coerceAtLeast(0)
                        },
                        enabled = distro != "rootfs" && DISTRO_RELEASES[distro].orEmpty().isNotEmpty(),
                        onSelectedIndexChange = { release = DISTRO_RELEASES[distro].orEmpty()[it] },
                    )
                }
            }

            // ===== 安装设置 =====
            GroupTitle("安装设置")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
            ) {
                Column {
                    OverlayDropdownPreference(
                        title = "安装方式",
                        summary = if (installType == "image") "镜像（ext4 镜像文件）" else "目录（直接部署到目录）",
                        items = listOf("目录（直接部署到目录）", "镜像（ext4 镜像文件）"),
                        selectedIndex = if (installType == "image") 1 else 0,
                        onSelectedIndexChange = { installType = if (it == 1) "image" else "directory" },
                    )
                    if (installType == "image") {
                        HorizontalDivider()
                        LabeledTextField(
                            label = "镜像大小",
                            state = imageSizeState,
                        )
                    }
                    HorizontalDivider()
                    LabeledTextField(
                        label = "容器路径",
                        state = pathState,
                    )
                }
            }

            // ===== 系统设置 =====
            GroupTitle("系统设置")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
            ) {
                Column {
                    LabeledTextField(
                        label = "软件源地址",
                        state = mirrorState,
                    )
                    HorizontalDivider()
                    LabeledTextField(
                        label = "特权用户",
                        state = userState,
                    )
                    HorizontalDivider()
                    LabeledTextField(
                        label = "辅助组",
                        state = userGroupsState,
                    )
                    HorizontalDivider()
                    // 默认密码始终需要（无论是否启用 SSH），放在系统设置里
                    LabeledTextField(
                        label = "默认密码",
                        state = passwordState,
                    )
                    HorizontalDivider()
                    SwitchPreference(
                        checked = mountsEnabled,
                        onCheckedChange = { mountsEnabled = it },
                        title = "安卓文件挂载",
                        summary = "将安卓目录挂载进容器，关闭时忽略以下列表",
                    )
                    if (mountsEnabled) {
                        HorizontalDivider()
                        // “+ 添加挂载”：直接新增一行，行内填写源/目标路径
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    mountRows.add(MountRow())
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "添加挂载",
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "添加挂载",
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                        // 已添加的挂载行：源/目标两个输入框（左上角小标题）+ 右侧删除
                        mountRows.forEachIndexed { index, row ->
                            HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row {
                                        MountFieldLabel("源路径（安卓）", Modifier.weight(1f))
                                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                        MountFieldLabel("目标路径（容器内）", Modifier.weight(1f))
                                    }
                                    Spacer(modifier = Modifier.padding(top = 6.dp))
                                    Row {
                                        TextField(
                                            state = row.source,
                                            modifier = Modifier.weight(1f),
                                            lineLimits = TextFieldLineLimits.SingleLine,
                                        )
                                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                        TextField(
                                            state = row.target,
                                            modifier = Modifier.weight(1f),
                                            lineLimits = TextFieldLineLimits.SingleLine,
                                        )
                                    }
                                }
                                IconButton(onClick = { mountRows.removeAt(index) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "删除挂载",
                                        tint = Color(0xFFE53935),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ===== SSH 设置 =====
            GroupTitle("SSH 服务")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
            ) {
                Column {
                    SwitchPreference(
                        checked = sshEnabled,
                        onCheckedChange = { sshEnabled = it },
                        title = "启用 SSH",
                        summary = "部署时自动加入 extra/ssh 组件并配置 sshd",
                    )
                    HorizontalDivider()
                    LabeledTextField(
                        label = "SSH 端口",
                        state = sshPortState,
                        enabled = sshEnabled,
                    )
                }
            }

            // ===== 初始化系统 =====
            GroupTitle("初始化系统")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
            ) {
                Column {
                    // systemctl 选项仅 Debian 系列（debian/ubuntu/kali）提供
                    val debianFamily = distro in listOf("debian", "ubuntu", "kali")
                    val initItems = if (debianFamily) {
                        listOf("SysV（/etc/rcN.d）", "run-parts（自定义目录）", "systemctl（init 模式）")
                    } else {
                        listOf("SysV（/etc/rcN.d）", "run-parts（自定义目录）")
                    }
                    val initValues = if (debianFamily) {
                        listOf("sysv", "run-parts", "systemctl")
                    } else {
                        listOf("sysv", "run-parts")
                    }
                    // 非 Debian 系列不支持 systemctl：选中态回落为 sysv
                    val displayInit = if (initSystem == "systemctl" && !debianFamily) "sysv" else initSystem
                    OverlayDropdownPreference(
                        title = "初始化系统",
                        summary = when (displayInit) {
                            "run-parts" -> "run-parts（自定义脚本目录）"
                            "systemctl" -> "systemctl（init 模式，拉起 default.target 服务）"
                            else -> "SysV（/etc/rcN.d，Debian 默认）"
                        },
                        items = initItems,
                        selectedIndex = initValues.indexOf(displayInit).coerceAtLeast(0),
                        onSelectedIndexChange = { initSystem = initValues[it] },
                    )
                    if (displayInit == "sysv") {
                        HorizontalDivider()
                        LabeledTextField(
                            label = "运行级别",
                            state = initLevelState,
                            hint = "默认 3：启动时按序执行 /etc/rc3.d/ 的 S 脚本，停止时执行 rc6.d 的 K 脚本",
                        )
                    } else if (displayInit == "run-parts") {
                        HorizontalDivider()
                        LabeledTextField(
                            label = "脚本目录（容器内路径）",
                            state = initPathState,
                            hint = "目录内脚本按顺序执行 start；停止时逆序执行 stop",
                        )
                    } else {
                        HorizontalDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "systemctl（init 模式）",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                            Spacer(modifier = Modifier.padding(top = 6.dp))
                            Text(
                                text = "需要 python3（部署时默认安装）；启动时拉起 systemctl --init，自动按序启动 init.d 与已启用（enable）的 systemd 服务",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            // ===== 图形配置（占位，暂不实现） =====
            GroupTitle("图形配置")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "图形配置",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            text = "VNC / X11 等图形界面参数（尚未接入）",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f),
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f),
                    )
                }
            }

            Spacer(modifier = Modifier.padding(top = 4.dp))

            Button(
                onClick = {
                    val trimmed = nameState.text.toString().trim()
                    if (trimmed.isEmpty()) {
                        Toast.makeText(context, "配置名称不能为空", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    // 路径为空会写出缺 CHROOT_DIR/TARGET_PATH 的配置，因此路径必填。
                    val pathTrimmed = pathState.text.toString().trim()
                    if (pathTrimmed.isEmpty()) {
                        Toast.makeText(context, "请填写容器路径", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    onSave(
                        ContainerConfig(
                            name = trimmed,
                            distro = distro,
                            release = release,
                            path = pathTrimmed,
                            installType = installType,
                            imageSize = imageSizeState.text.toString().trim().ifBlank { "4G" },
                            mirror = mirrorState.text.toString().trim(),
                            user = userState.text.toString().trim().ifBlank { "root" },
                            userGroups = userGroupsState.text.toString().trim()
                                .ifBlank { "aid_inet aid_sdcard_rw aid_graphics" },
                            mountsEnabled = mountsEnabled,
                            // 开关关闭时不传任何挂载；未填完整（源/目标任一为空）的行保存时跳过
                            mounts = if (mountsEnabled) {
                                mountRows.mapNotNull { it.toEntry() }
                            } else {
                                emptyList()
                            },
                            sshEnabled = sshEnabled,
                            sshPort = sshPortState.text.toString().trim().ifBlank { "22" },
                            password = passwordState.text.toString().ifBlank { "changeme" },
                            components = buildComponents(),
                            init = initSystem,
                            initLevel = initLevelState.text.toString().trim().ifBlank { "3" },
                            initPath = initPathState.text.toString().trim().ifBlank { "/etc/rc.d" },
                            running = initial?.running ?: false,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存配置")
            }
        }
    }
}

/** 一行挂载：源路径（安卓） + 目标路径（容器内），行内直接编辑 */
private class MountRow {
    val source = TextFieldState()
    val target = TextFieldState()

    constructor() : this("", "")

    constructor(sourceText: String, targetText: String) {
        if (sourceText.isNotEmpty()) source.edit { replace(0, length, sourceText) }
        if (targetText.isNotEmpty()) target.edit { replace(0, length, targetText) }
    }

    /** 转成 "源:目标" 配置项；源/目标任一为空返回 null（保存时跳过） */
    fun toEntry(): String? {
        val s = source.text.toString().trim()
        val t = target.text.toString().trim()
        return if (s.isEmpty() || t.isEmpty()) null else "$s:$t"
    }
}

/** 挂载行输入框上方的小字标题 */
@Composable
private fun MountFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 11.sp,
        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
    )
}

/**
 * 平铺文本字段：小字标题在输入框上方（左上角），下方直接是输入框。
 *
 * 与 Miuix 下拉行水平对齐（左右各 16dp），文本直接编辑，无需点击弹窗。
 */
@Composable
private fun LabeledTextField(
    label: String,
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (enabled) {
                MiuixTheme.colorScheme.onSurfaceContainerVariant
            } else {
                MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f)
            },
        )
        Spacer(modifier = Modifier.padding(top = 8.dp))
        TextField(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            lineLimits = TextFieldLineLimits.SingleLine,
        )
        if (!hint.isNullOrBlank()) {
            Spacer(modifier = Modifier.padding(top = 6.dp))
            Text(
                text = hint,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.8f),
            )
        }
    }
}
