package io.github.ganyu256.linuxdeploypro.ui

import io.github.ganyu256.linuxdeploypro.BuildConfig

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ganyu256.linuxdeploypro.data.CliManager
import io.github.ganyu256.linuxdeploypro.data.ConfigStore
import io.github.ganyu256.linuxdeploypro.data.KeepAliveService
import io.github.ganyu256.linuxdeploypro.model.ContainerConfig
import io.github.ganyu256.linuxdeploypro.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主界面框架。
 *
 * 轻量路由栈（main / settings / new / edit:xxx / detail:xxx / terminal:xxx），
 * 空壳阶段不引入 Navigation 库；系统返回键按层级逐步返回（终端 → 详情 →
 * 主页）。启停 / 部署 / 导出 / 删除等操作全部走真实 CLI 后端，日志实时
 * 追加到日志页；CLI 输出用中文。
 */
@Composable
fun MainScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 4.0.5：前端不再本地存配置，配置数据一律以 CLI config/ 目录为唯一数据源。
    // 启动 / 进容器页读 config list（轻量列表），点开配置再读 config show（完整详情）。
    val configs = remember { mutableStateListOf<ContainerConfig>() }
    var autostart by remember { mutableStateOf(ConfigStore.autostartList(context)) }
    // 当前详情页/编辑页展示的完整配置（从 CLI config show 读取）
    var detailConfig by remember { mutableStateOf<ContainerConfig?>(null) }
    // 导入 rootfs 的目标配置（SAF 文件选择回调里使用）
    var importTarget by remember { mutableStateOf<ContainerConfig?>(null) }
    val logs = rememberSaveable(saver = LogListSaver) {
        mutableStateListOf("v${BuildConfig.VERSION_NAME} 启动，CLI 后端已接入")
    }
    // 最新 CLI 操作日志文件（每次操作一个独立时间戳文件），供日志页尾随展示
    var logFile by remember { mutableStateOf<File?>(null) }
    // 日志页刷新信号：操作结束时 +1，让日志页兜底重读一次日志文件
    var logRefreshTick by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 路由栈：栈顶即当前页面，返回键弹栈，实现层级逐步返回
    val routeStack = rememberSaveable(saver = RouteListSaver) {
        mutableStateListOf("main")
    }
    // 操作锁：同一时间只允许一个 CLI 长任务（启停/部署/导出/删除/保存）
    var busy by remember { mutableStateOf(false) }
    // busy 开始时间（自动恢复兜底：异常挂起超过时限强制解除，避免永久卡死）
    var busySince by remember { mutableStateOf(0L) }
    // 当前进行中的长任务文案（null=空闲）；顶部横幅显示进度，结束自动清除
    var opLabel by remember { mutableStateOf<String?>(null) }
    // 正在部署的配置名（容器页徽标显示"部署中"；结束清除）
    var deployingName by remember { mutableStateOf<String?>(null) }
    // 新建配置保存成功后待引导部署的配置（触发"立即部署？"对话框）
    var pendingDeployConfig by remember { mutableStateOf<ContainerConfig?>(null) }
    // 待确认删除的配置名（触发删除确认对话框，替代 CLI stdin 确认）
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    // 部署目标目录非空时待确认的配置（触发"强制部署"确认窗）
    var pendingForceDeploy by remember { mutableStateOf<ContainerConfig?>(null) }
    // 删除容器两步确认：第一步勾选弹窗目标
    var deleteContainerTarget by remember { mutableStateOf<ContainerConfig?>(null) }
    // 删除容器勾选"同时删除配置文件"
    var alsoDeleteConfig by remember { mutableStateOf(false) }
    // 删除容器第二步 yes 输入确认目标
    var yesConfirmTarget by remember { mutableStateOf<ContainerConfig?>(null) }

    // 局部作用域不允许自定义 getter 属性，用函数读取栈顶路由
    fun route(): String = routeStack.last()

    /** 压栈进入子页面（超过 10 层丢弃最底层，防止无限增长） */
    fun pushRoute(newRoute: String) {
        if (routeStack.size > 10) routeStack.removeAt(0)
        routeStack.add(newRoute)
    }

    /** 弹栈返回上一层 */
    fun popRoute() {
        if (routeStack.size > 1) {
            routeStack.removeAt(routeStack.size - 1)
        } else {
            routeStack[0] = "main"
        }
    }

    /** 回到主页（清空路由栈） */
    fun backToMain() {
        routeStack.clear()
        routeStack.add("main")
    }

    /**
     * 追加一条日志（带时间戳）。
     * 注意：只在主线程调用（CLI 行回调里已通过 withContext(Main) 切回）。
     * 4.0.2 起日志按时间正序排列（旧日志在上，新日志追加到末尾）。
     */
    val appendLog: (String) -> Unit = { msg ->
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logs.add("$time  $msg")
        if (logs.size > 500) logs.removeAt(0)
    }

    /** 刷新单个配置的真实运行状态（-c 查询，不依赖操作结果） */
    fun refreshOneConfig(name: String) {
        scope.launch {
            val running = withContext(Dispatchers.IO) {
                try {
                    CliManager.isRunning(context, name)
                } catch (_: Exception) {
                    null
                }
            }
            if (running != null) {
                val idx = configs.indexOfFirst { it.name == name }
                if (idx >= 0) {
                    configs[idx] = configs[idx].copy(running = running)
                }
                detailConfig = detailConfig?.takeIf { it.name == name }
                    ?.copy(running = running)
            }
        }
    }

    /** 静默刷新全部配置的运行状态（与启停后刷新同一路径） */
    fun refreshRunning() {
        configs.toList().forEach { refreshOneConfig(it.name) }
    }

    /**
     * 加载配置列表（直读 config/ 文件，毫秒级）并刷新运行状态（挂起版）。
     * 状态查询用 cli.sh -c <配置名> 显式指定，不触碰 .current 锁，无并发副作用。
     */
    suspend fun loadConfigsFromCli() {
        val list = withContext(Dispatchers.IO) {
            try {
                CliManager.configListModels(context).toMutableList()
            } catch (_: Exception) {
                null
            }
        }
        if (list != null) {
            val runningMap = configs.associate { it.name to it.running }
            configs.clear()
            configs.addAll(list.map { it.copy(running = runningMap[it.name] ?: false) })
            // 启动/刷新后逐配置刷新运行状态（与启停后刷新同一路径）
            configs.toList().forEach { refreshOneConfig(it.name) }
        }
    }

    /** 从 CLI 重新加载配置列表（异步版，供普通刷新场景） */
    fun reloadConfigs() {
        scope.launch { loadConfigsFromCli() }
    }

    /**
     * 执行 CLI 长任务（统一入口）。
     * @param autoShowLog 长任务（部署/导出/导入）开始时自动切到日志页实时展示输出
     * @param timeoutMs   超时保护（毫秒）；超时强制终止 CLI 进程并解除 busy
     */
    fun launchCliOp(
        label: String,
        op: (onLine: (String) -> Unit) -> Boolean,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
        autoShowLog: Boolean = false,
        timeoutMs: Long = 0,
    ) {
        if (busy) {
            Toast.makeText(context, "有操作正在进行，请稍候（若持续请重启应用）", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        busySince = System.currentTimeMillis()
        opLabel = label
        if (autoShowLog && route() != "main") {
            // 长任务自动进入日志页，让用户实时看到 CLI 输出（无需手动切页）
            backToMain()
        }
        if (autoShowLog) {
            tab = 2
        }
        // 长任务保活：前台服务 + WakeLock
        val keepAliveIntent = Intent(context, KeepAliveService::class.java)
        try {
            ContextCompat.startForegroundService(context, keepAliveIntent)
        } catch (_: Exception) {
            // 部分 ROM 限制后台启动前台服务，失败仅降级（任务仍执行）
        }
        scope.launch {
            var ok = false
            var error: String? = null
            var timedOut = false
            try {
                if (timeoutMs > 0) {
                    val done = withTimeoutOrNull(timeoutMs) {
                        withContext(Dispatchers.IO) {
                            ok = op { line ->
                                // 回调非挂起上下文，这里另起主线程协程追加日志
                                scope.launch(Dispatchers.Main.immediate) {
                                    appendLog(line)
                                    // 从 CLI 输出中捕获日志文件路径，日志页据此尾随展示
                                    val prefix = "日志文件: "
                                    if (line.startsWith(prefix)) {
                                        logFile = File(line.substring(prefix.length).trim())
                                    }
                                }
                            }
                        }
                        true
                    }
                    if (done == null) {
                        timedOut = true
                        // 强制终止 CLI 进程（su→cli.sh 及子进程）
                        CliManager.killCurrentProcess()
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        ok = op { line ->
                            scope.launch(Dispatchers.Main.immediate) {
                                appendLog(line)
                                val prefix = "日志文件: "
                                if (line.startsWith(prefix)) {
                                    logFile = File(line.substring(prefix.length).trim())
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            withContext(Dispatchers.Main.immediate) {
                val logHint = logFile?.let { "，日志文件: ${it.absolutePath}" } ?: ""
                when {
                    timedOut -> {
                        appendLog("$label 超时（已强制终止，请检查是否重复部署）$logHint")
                        Toast.makeText(context, "操作超时，已终止", Toast.LENGTH_LONG).show()
                        onFailure()
                    }
                    error != null -> {
                        appendLog("$label 失败：$error$logHint")
                        onFailure()
                    }
                    ok -> {
                        appendLog("$label 完成$logHint")
                        onSuccess()
                    }
                    else -> {
                        appendLog("$label 失败（CLI 返回错误，详见上方日志）$logHint")
                        onFailure()
                    }
                }
                // 操作结束：通知日志页兜底重读一次，保证窗口内容与磁盘日志一致
                logRefreshTick++
                busy = false
                busySince = 0L
                opLabel = null
                // 部署类任务结束，清除"部署中"徽标
                deployingName = null
            }
            // 长任务结束：停止保活服务（释放 WakeLock、撤下前台通知）
            try {
                context.stopService(keepAliveIntent)
            } catch (_: Exception) {
            }
            refreshRunning()
        }
    }

    /** 保存（新建或编辑）配置：先写本地列表校验，再写 CLI，都成功才算保存 */
    fun saveConfig(cfg: ContainerConfig, originalName: String?) {
        val selfIndex = originalName?.let { n -> configs.indexOfFirst { it.name == n } } ?: -1
        val conflictIndex = configs.indexOfFirst { it.name == cfg.name }
        if (conflictIndex >= 0 && conflictIndex != selfIndex) {
            appendLog("保存失败：已存在同名配置 ${cfg.name}")
            Toast.makeText(context, "配置名称已存在：${cfg.name}", Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) {
            Toast.makeText(context, "有操作正在进行，请稍候（若持续请重启应用）", Toast.LENGTH_SHORT).show()
            return
        }
        val isNew = selfIndex < 0
        busy = true
        scope.launch {
            var result: CliManager.CliResult? = null
            var error: String? = null
            var resizeApplied = false
            var resizeFailed = false
            var resizeSkipped = false
            try {
                withContext(Dispatchers.IO) {
                    result = CliManager.writeConfig(context, cfg.name, cfg, isNew)
                    // 镜像安装时，保存后自动把已有镜像调整到新大小。
                    // 镜像文件实际为 路径.img（writeConfig 已按此规则写 TARGET_PATH）；
                    // 未部署/路径为空时文件不存在，跳过并提示（部署时会按配置大小创建）。
                    if (result.exitCode == 0 &&
                        cfg.installType == "image" &&
                        cfg.imageSize.isNotBlank() &&
                        cfg.path.isNotBlank()
                    ) {
                        val imageFile = File("${cfg.path}.img")
                        if (imageFile.exists()) {
                            val ok = CliManager.resize(context, cfg.name, cfg.imageSize) { line ->
                                scope.launch(Dispatchers.Main.immediate) { appendLog(line) }
                            }
                            if (ok) resizeApplied = true else resizeFailed = true
                        } else {
                            resizeSkipped = true
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            withContext(Dispatchers.Main.immediate) {
                val failed = error != null || result?.exitCode != 0
                if (failed) {
                    appendLog("写入 CLI 失败：${error ?: "CLI 返回错误"}")
                    result?.output?.trim()?.lineSequence()?.forEach { appendLog(it) }
                    Toast.makeText(context, "保存失败，请查看日志", Toast.LENGTH_SHORT).show()
                } else {
                    if (isNew) {
                        configs.add(cfg)
                        appendLog("已保存新配置：${cfg.name}")
                        Toast.makeText(context, "已保存配置：${cfg.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        configs[selfIndex] = cfg
                        appendLog("已更新配置：${cfg.name}")
                        Toast.makeText(context, "已更新配置：${cfg.name}", Toast.LENGTH_SHORT).show()
                    }
                    if (resizeApplied) {
                        appendLog("镜像大小已调整为 ${cfg.imageSize}")
                    }
                    if (resizeFailed) {
                        appendLog("镜像大小调整失败，请查看上方日志")
                        Toast.makeText(context, "镜像调整失败，请查看日志", Toast.LENGTH_SHORT).show()
                    }
                    if (resizeSkipped) {
                        appendLog("镜像文件尚不存在（未部署），部署时将按 ${cfg.imageSize} 创建")
                    }
                    // 配置已由 CLI 落盘，重新从 CLI 拉取列表（唯一数据源）
                    backToMain()
                    // 串行等待列表加载完成再弹部署引导（避免与部署并发）
                    loadConfigsFromCli()
                    // 新建配置：弹出"立即部署"引导，避免用户找不到部署入口
                    if (isNew && pendingDeployConfig == null) {
                        pendingDeployConfig = cfg
                    }
                }
                busy = false
                busySince = 0L
            }
        }
    }

    /** 启动 / 停止容器（真实 CLI，输出进日志页） */
    fun toggleRunning(cfg: ContainerConfig) {
        launchCliOp(
            label = if (cfg.running) "停止 ${cfg.name}" else "启动 ${cfg.name}",
            op = { onLine ->
                if (cfg.running) {
                    CliManager.stop(context, cfg.name, onLine)
                } else {
                    CliManager.start(context, cfg.name, onLine)
                }
            },
            onSuccess = {
                refreshOneConfig(cfg.name)
            },
            onFailure = {
                // 启动/停止即使部分失败也刷新真实状态（CLI start 已容错）
                refreshOneConfig(cfg.name)
            },
        )
    }


    /** 真正执行部署（CLI 长任务，输出实时进日志页） */
    fun doDeploy(cfg: ContainerConfig) {
        deployingName = cfg.name
        launchCliOp(
            label = "部署 ${cfg.name}",
            op = { onLine -> CliManager.deploy(context, cfg.name, onLine) },
            onSuccess = {
                Toast.makeText(context, "部署完成", Toast.LENGTH_SHORT).show()
            },
            autoShowLog = true,
            // 部署最长 60 分钟；超时强制终止，防止误对已部署容器触发挂起卡死
            timeoutMs = 60 * 60 * 1000L,
        )
    }

    /**
     * 部署容器：先检查目标目录是否非空。
     * 非空（已部署/残留数据）→ 弹"强制部署"确认（后果自负）→ 确认后才真正执行；
     * 目录为空 → 直接部署。
     */
    fun deployConfig(cfg: ContainerConfig) {
        if (busy) {
            Toast.makeText(context, "有操作正在进行，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val checkResult = withContext(Dispatchers.IO) {
                try {
                    CliManager.isTargetDirNonEmpty(context, cfg.name)
                } catch (_: Exception) {
                    Pair<Boolean?, String>(null, "check 异常")
                }
            }
            val dirNonEmpty = checkResult.first
            val checkDiag = checkResult.second
            withContext(Dispatchers.Main.immediate) {
                when (dirNonEmpty) {
                    // 检查失败/配置不存在：明确提示 + 诊断输出到日志
                    null -> {
                        appendLog("部署 ${cfg.name} 失败：配置不存在或读取失败")
                        if (checkDiag.isNotBlank()) appendLog("  [诊断] $checkDiag")
                        Toast.makeText(context, "配置不存在或读取失败，请先检查保存是否成功", Toast.LENGTH_LONG).show()
                    }
                    // 目录非空：弹强制部署确认
                    true -> pendingForceDeploy = cfg
                    // 目录为空：直接部署
                    false -> doDeploy(cfg)
                }
            }
        }
    }

    /** 调整镜像大小（详情页弹窗确认后调用） */
    fun resizeConfig(name: String, sizeGb: Int) {
        launchCliOp(
            label = "调整镜像大小 $name",
            op = { onLine -> CliManager.resize(context, name, "${sizeGb}G", onLine) },
            onSuccess = {
                appendLog("镜像已调整为 ${sizeGb}G")
                Toast.makeText(context, "镜像大小已调整", Toast.LENGTH_SHORT).show()
            },
            autoShowLog = true,
            timeoutMs = 10 * 60 * 1000L,
        )
    }

    /** 导出当前容器 rootfs 归档 */
    fun exportConfig(cfg: ContainerConfig) {
        val outFile = CliManager.defaultExportFile(context, cfg.name)
        launchCliOp(
            label = "导出 ${cfg.name}",
            op = { onLine -> CliManager.exportRootfs(context, cfg.name, outFile, onLine) },
            onSuccess = {
                appendLog("导出文件：${outFile.absolutePath}")
                Toast.makeText(context, "已导出到 ${outFile.name}", Toast.LENGTH_SHORT).show()
            },
            autoShowLog = true,
        )
    }

    /**
     * 导入 rootfs 归档：SAF 文件选择 → 复制到应用私有目录 → CLI import。
     * SAF Uri 不能直接给 CLI 子进程读取，必须先落地为本地文件。
     * launcher 在组合期注册，cfg 由点击时的 importConfig 传入目标配置。
     */
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val cfg = importTarget
        if (cfg == null) return@rememberLauncherForActivityResult
        val destDir = CliManager.defaultImportDir(context)
        val destFile = File(destDir, "${cfg.name}-import.tar.gz")
        launchCliOp(
            label = "导入 ${cfg.name}",
            op = { onLine ->
                onLine("正在复制归档到应用目录 ...")
                val copied = try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                        true
                    } ?: false
                } catch (_: Exception) {
                    false
                }
                if (!copied) {
                    onLine("复制归档失败")
                    return@launchCliOp false
                }
                onLine("归档已就绪：${destFile.absolutePath}")
                CliManager.importRootfs(context, cfg.name, destFile, onLine)
            },
            onSuccess = {
                appendLog("导入完成：${destFile.absolutePath}")
                Toast.makeText(context, "rootfs 导入完成", Toast.LENGTH_SHORT).show()
            },
            autoShowLog = true,
        )
    }

    fun importConfig(cfg: ContainerConfig) {
        importTarget = cfg
        importLauncher.launch(
            arrayOf("application/gzip", "application/x-tar", "application/x-xz", "application/x-bzip2", "application/zstd", "*/*"),
        )
    }

    /** 切换某配置的开机自启 */
    fun toggleAutostart(cfg: ContainerConfig) {
        ConfigStore.setAutostart(context, cfg.name, !autostart.contains(cfg.name))
        autostart = ConfigStore.autostartList(context)
    }

    /** 删除配置（保留容器目录数据，不 purge）；调用前先经 ConfirmDialog 确认 */
    fun deleteConfig(name: String) {
        launchCliOp(
            label = "删除配置 $name",
            op = { onLine ->
                ConfigStore.removeAutostart(context, name)
                val result = CliManager.deleteConfig(context, name)
                result.output.trim().lineSequence().forEach(onLine)
                result.exitCode == 0
            },
            onSuccess = {
                configs.removeAll { it.name == name }
                detailConfig = detailConfig?.takeIf { it.name != name }
                Toast.makeText(context, "已删除配置：$name", Toast.LENGTH_SHORT).show()
                backToMain()
                reloadConfigs()
            },
        )
    }

    /** 删除容器（前端两步确认后执行）：删容器目录 + 可选删配置文件 */
    fun doDeleteContainer(cfg: ContainerConfig, alsoConfig: Boolean) {
        launchCliOp(
            label = "删除容器 ${cfg.name}",
            op = { onLine ->
                ConfigStore.removeAutostart(context, cfg.name)
                onLine("正在删除容器目录：${cfg.path.ifBlank { "（路径为空，跳过）" }}")
                val dirOk = if (cfg.path.isNotBlank()) {
                    CliManager.deleteContainerDir(context, cfg.path)
                } else {
                    true
                }
                var cfgOk = true
                if (alsoConfig) {
                    onLine("正在删除配置文件：${cfg.name}.conf")
                    cfgOk = ConfigStore.deleteConfig(context, cfg.name)
                }
                dirOk && cfgOk
            },
            onSuccess = {
                Toast.makeText(context, "容器已删除${if (alsoConfig) "（含配置文件）" else ""}", Toast.LENGTH_SHORT).show()
                backToMain()
                reloadConfigs()
            },
        )
    }

    /** 打开配置详情：从 CLI 读取完整配置后再进入（点开配置才读 config show） */
    fun openDetail(name: String) {
        scope.launch {
            val cfg = withContext(Dispatchers.IO) {
                try {
                    CliManager.configToModel(context, name)
                } catch (_: Exception) {
                    null
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (cfg != null) {
                    detailConfig = cfg
                    pushRoute("detail:$name")
                } else {
                    Toast.makeText(context, "配置不存在或读取失败：$name", Toast.LENGTH_SHORT).show()
                    reloadConfigs()
                }
            }
        }
    }

    /**
     * 打开编辑页：先预加载完整配置再进入，避免进入后无内容闪烁。
     */
    fun openEdit(name: String) {
        if (detailConfig?.name == name) {
            pushRoute("edit:$name")
            return
        }
        scope.launch {
            val cfg = withContext(Dispatchers.IO) {
                try {
                    CliManager.configToModel(context, name)
                } catch (_: Exception) {
                    null
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (cfg != null) {
                    detailConfig = cfg
                    pushRoute("edit:$name")
                } else {
                    Toast.makeText(context, "配置不存在或读取失败：$name", Toast.LENGTH_SHORT).show()
                    reloadConfigs()
                }
            }
        }
    }

    // 首屏进入时从 CLI 加载配置列表（唯一数据源，含运行状态）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            CliManager.ensureConfigWritable(context)
        }
        reloadConfigs()
        // busy 自动恢复：若操作异常挂起超过 20 分钟仍未结束，强制解除
        while (true) {
            kotlinx.coroutines.delay(60_000)
            if (busy && busySince > 0 && System.currentTimeMillis() - busySince > 20 * 60 * 1000L) {
                appendLog("检测到操作异常挂起超过 20 分钟，已自动解除操作锁（busy）")
                busy = false
                opLabel = null
                deployingName = null
                CliManager.killCurrentProcess()
            }
        }
    }

    // 从后台/重进时刷新容器状态：LaunchedEffect 只在首次组合触发，
    // 进程存活被恢复（划掉后台后重进）时不会再次触发，用生命周期 ON_RESUME 兜底
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResume) {
                    firstResume = false
                } else {
                    refreshRunning()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 系统返回键：按路由栈逐级返回，回到主页后再按才退出应用
    BackHandler(enabled = route() != "main") {
        popRoute()
    }

    // 全局内容层：路由分发 + 顶部长任务进度横幅（所有页面可见）
    Box(modifier = Modifier.fillMaxSize()) {
        when {
        route() == "settings" -> {
            SettingsScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onBack = { popRoute() },
            )
        }

        route() == "new" -> {
            ConfigScreen(
                initial = null,
                onBack = { popRoute() },
                onSave = { cfg -> saveConfig(cfg, null) },
            )
        }

        route().startsWith("edit:") -> {
            val editName = route().removePrefix("edit:")
            val initial = detailConfig?.takeIf { it.name == editName }
            if (initial == null) {
                // 进程重建后直接进入编辑页：先从 CLI 读取完整配置
                LoadingRouteHint(text = "正在读取配置：$editName")
                LaunchedEffect(editName) {
                    val loaded = withContext(Dispatchers.IO) {
                        try {
                            CliManager.configToModel(context, editName)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (loaded != null) detailConfig = loaded else backToMain()
                    }
                }
            } else {
                ConfigScreen(
                    initial = initial,
                    onBack = { popRoute() },
                    onSave = { cfg -> saveConfig(cfg, editName) },
                )
            }
        }

        route().startsWith("terminal:") -> {
            val termName = route().removePrefix("terminal:")
            TerminalScreen(
                configName = termName,
                distro = detailConfig?.takeIf { it.name == termName }?.distro ?: "",
                onBack = { popRoute() },
            )
        }

        route().startsWith("detail:") -> {
            val detailName = route().removePrefix("detail:")
            val cfg = detailConfig?.takeIf { it.name == detailName }
            if (cfg == null) {
                // 进程重建后直接进入详情：先从 CLI 读取完整配置
                LoadingRouteHint(text = "正在读取配置：$detailName")
                LaunchedEffect(detailName) {
                    val loaded = withContext(Dispatchers.IO) {
                        try {
                            CliManager.configToModel(context, detailName)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (loaded != null) detailConfig = loaded else backToMain()
                    }
                }
            } else {
                ConfigDetailScreen(
                    config = cfg,
                    onBack = { popRoute() },
                    onEdit = { openEdit(detailName) },
                    onToggleRunning = { toggleRunning(cfg) },
                    onDeploy = { deployConfig(cfg) },
                    onTerminal = { pushRoute("terminal:$detailName") },
                    onExport = { exportConfig(cfg) },
                    onImport = { importConfig(cfg) },
                    onResize = { gb -> resizeConfig(detailName, gb) },
                    onViewLogs = {
                        backToMain()
                        tab = 2
                    },
                    onDelete = { deleteTarget = detailName },
                    onDeleteContainer = { deleteContainerTarget = detailConfig },
                )
            }
        }

        else -> {
            MainTabScreen(
                tab = tab,
                onTabChange = { newTab ->
                    tab = newTab
                },
                configs = configs,
                autostart = autostart,
                onToggleAutostart = ::toggleAutostart,
                logs = logs,
                logFile = logFile,
                logRefreshTick = logRefreshTick,
                onNewConfig = { pushRoute("new") },
                onOpenConfig = { openDetail(it.name) },
                onToggleRunning = { toggleRunning(it) },
                onEditConfig = { openEdit(it.name) },
                onOpenSettings = { pushRoute("settings") },
                onClearLogs = {
                    logs.clear()
                    appendLog("日志已清空")
                },
                opLabel = opLabel,
                deployingName = deployingName,
                onBannerClick = {
                    backToMain()
                    tab = 2
                },
                onDeployConfig = { deployConfig(it) },
                onViewLogs = {
                    backToMain()
                    tab = 2
                },
                onTerminalConfig = { pushRoute("terminal:${it.name}") },
                onDeleteTarget = { deleteTarget = it.name },
            )
        }
        }
        // 长任务进度横幅：主页面由 MainTabScreen 放在底栏正上方（紧贴）；
        // 其他路由（详情/设置等）这里贴屏幕底部显示。
        if (route() != "main") {
            opLabel?.let { label ->
                OpProgressBanner(
                    label = label,
                    onClick = {
                        backToMain()
                        tab = 2
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }
        // 新建配置引导部署对话框
        pendingDeployConfig?.let { cfg ->
            ConfirmDialog(
                title = "配置已创建",
                content = "「${cfg.name}」已保存。是否立即部署容器？\n（部署为长任务，可在日志页查看实时进度）",
                confirmText = "立即部署",
                onConfirm = {
                    pendingDeployConfig = null
                    deployConfig(cfg)
                },
                onDismiss = { pendingDeployConfig = null },
            )
        }
        // 删除配置确认对话框（容器目录数据保留，仅删除配置）
        deleteTarget?.let { name ->
            ConfirmDialog(
                title = "删除配置",
                content = "确定删除配置「$name」？\n容器目录数据将保留。",
                confirmText = "删除",
                destructive = true,
                onConfirm = {
                    deleteTarget = null
                    deleteConfig(name)
                },
                onDismiss = { deleteTarget = null },
            )
        }
        // 删除容器第一步：勾选"同时删除配置文件"
        deleteContainerTarget?.let { cfg ->
            DeleteContainerConfirmDialog(
                name = cfg.name,
                alsoDeleteConfig = alsoDeleteConfig,
                onAlsoDeleteConfigChange = { alsoDeleteConfig = it },
                onConfirm = {
                    deleteContainerTarget = null
                    yesConfirmTarget = cfg
                },
                onDismiss = { deleteContainerTarget = null },
            )
        }
        // 删除容器第二步：必须输入 yes 确认
        yesConfirmTarget?.let { cfg ->
            YesInputConfirmDialog(
                title = "确认删除容器",
                content = "输入 yes 确认删除「${cfg.name}」的容器目录${if (alsoDeleteConfig) "及配置文件" else ""}：",
                onConfirm = { input ->
                    if (input.trim().equals("yes", ignoreCase = false)) {
                        yesConfirmTarget = null
                        val alsoCfg = alsoDeleteConfig
                        alsoDeleteConfig = false
                        doDeleteContainer(cfg, alsoCfg)
                    } else {
                        Toast.makeText(context, "输入不是 yes，已取消删除", Toast.LENGTH_SHORT).show()
                        yesConfirmTarget = null
                        alsoDeleteConfig = false
                    }
                },
                onDismiss = {
                    yesConfirmTarget = null
                    alsoDeleteConfig = false
                },
            )
        }
        // 部署目标目录非空：强制部署确认（后果自负）
        pendingForceDeploy?.let { cfg ->
            ConfirmDialog(
                title = "目标目录非空",
                content = "「${cfg.name}」的容器目录已存在内容（可能已部署或残留数据）。\n" +
                    "强制部署可能覆盖或冲突，风险由你承担。是否继续？",
                confirmText = "强制部署",
                destructive = true,
                onConfirm = {
                    pendingForceDeploy = null
                    doDeploy(cfg)
                },
                onDismiss = { pendingForceDeploy = null },
            )
        }
    }
}

/** 路由加载占位：背景铺满主题色 + 进度圈，避免读取期间黑屏闪烁 */
@Composable
private fun LoadingRouteHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
    }
}

/**
 * 长任务进度横幅：顶部悬浮条，Miuix 无限进度条 + 任务文案。
 * 任意页面可见；点击跳转日志页查看实时输出。
 */
@Composable
private fun OpProgressBanner(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(MiuixTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "查看日志 ›",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}

/** 日志列表保存器：进程重建后仍保留（String 行，无需结构解析） */
private val LogListSaver = listSaver<MutableList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/** 路由栈保存器：进程重建后仍保留当前页面 */
private val RouteListSaver = listSaver<MutableList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/**
 * 主页面：底部三栏 + 各 tab 内容。
 * 主页无顶栏（蓝色横幅置顶）；容器/日志页自带顶栏与设置齿轮。
 * 底栏使用 Miuix 默认高度（三项图标+文字高度一致），底部导航条自动沉浸。
 */
@Composable
private fun MainTabScreen(
    tab: Int,
    onTabChange: (Int) -> Unit,
    configs: List<ContainerConfig>,
    autostart: Set<String>,
    onToggleAutostart: (ContainerConfig) -> Unit,
    logs: List<String>,
    logFile: File?,
    logRefreshTick: Int,
    onNewConfig: () -> Unit,
    onOpenConfig: (ContainerConfig) -> Unit,
    onToggleRunning: (ContainerConfig) -> Unit,
    onEditConfig: (ContainerConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onClearLogs: () -> Unit,
    opLabel: String?,
    deployingName: String?,
    onBannerClick: () -> Unit,
    onDeployConfig: (ContainerConfig) -> Unit,
    onViewLogs: () -> Unit,
    onTerminalConfig: (ContainerConfig) -> Unit,
    onDeleteTarget: (ContainerConfig) -> Unit,
) {
    Scaffold(
        topBar = {
            // 四个 tab 统一使用紧凑顶栏（主页/容器/日志 + 齿轮），顶部高度一致
            SmallTopAppBar(
                title = when (tab) {
                    0 -> "主页"
                    1 -> "容器"
                    else -> "日志"
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // 进度横幅紧贴底栏上方（不留空），仅长任务进行时显示
                opLabel?.let { label ->
                    OpProgressBanner(
                        label = label,
                        onClick = onBannerClick,
                    )
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { onTabChange(0) },
                        icon = Icons.Filled.Home,
                        label = "主页",
                    )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { onTabChange(1) },
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "容器",
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { onTabChange(2) },
                    icon = Icons.Filled.Info,
                    label = "日志",
                )
                }
            }
        },
        floatingActionButton = if (tab == 1) {
            {
                FloatingActionButton(onClick = onNewConfig) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "新建配置",
                    )
                }
            }
        } else {
            {}
        },
        containerColor = MiuixTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                0 -> HomeScreen(onOpenSettings = onOpenSettings)
                1 -> ContainerScreen(
                    configs = configs,
                    deployingName = deployingName,
                    autostart = autostart,
                    onOpen = onOpenConfig,
                    onToggleRunning = onToggleRunning,
                    onEdit = onEditConfig,
                    onDeploy = onDeployConfig,
                    onLogs = { onViewLogs() },
                    onTerminal = onTerminalConfig,
                    onToggleAutostart = onToggleAutostart,
                    onDelete = onDeleteTarget,
                )

                else -> LogScreen(
                    logs = logs,
                    logFile = logFile,
                    refreshTick = logRefreshTick,
                    onClear = onClearLogs,
                )
            }
        }
    }
}
