package io.github.ganyu256.linuxdeploypro.data

import android.content.Context
import io.github.ganyu256.linuxdeploypro.model.ContainerConfig
import java.io.File

/**
 * 配置存储：前端直接读写 config/ 目录下 .conf 文件（app 私有目录）。
 * 列表/详情/新建/编辑/删除均直读直写；前端执行命令用 `cli.sh -c <配置名>` 显式指定配置。
 * 配置文件格式：每行 `KEY="value"`，# 开头为注释。
 */
object ConfigStore {

    /** config/ 目录（ensureExtracted 解压后存在） */
    fun configDir(context: Context): File =
        File(context.filesDir, "linuxdeploy-cli/config").apply { mkdirs() }

    /** 开机自启标记文件（每行一个配置名；独立小文件，CLI 不感知） */
    private fun autostartFile(context: Context): File =
        File(configDir(context), ".autostart")

    /** 读取开机自启配置名列表 */
    fun autostartList(context: Context): Set<String> =
        try {
            autostartFile(context).readLines().map { it.trim() }
                .filter { it.isNotEmpty() }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

    /** 设置/取消某配置的开机自启 */
    fun setAutostart(context: Context, name: String, enabled: Boolean) {
        val names = autostartList(context).toMutableSet()
        if (enabled) names.add(name) else names.remove(name)
        autostartFile(context).writeText(names.sorted().joinToString("\n") + if (names.isEmpty()) "" else "\n")
    }

    /** 删除某配置的开机自启标记（删除配置/容器时调用） */
    fun removeAutostart(context: Context, name: String) {
        if (autostartList(context).contains(name)) setAutostart(context, name, false)
    }

    /** 列出全部配置（读文件，不含 .current/.bak） */
    fun listConfigs(context: Context): List<ContainerConfig> =
        configDir(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".conf") && !f.name.endsWith(".bak") }
            ?.mapNotNull { file -> parseConf(file) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 读取单个配置 */
    fun readConfig(context: Context, name: String): ContainerConfig? {
        val file = configFile(context, name)
        return if (file.exists()) parseConf(file) else null
    }

    /** 配置是否存在 */
    fun exists(context: Context, name: String): Boolean =
        configFile(context, name).exists()

    /** 写入配置（新建或编辑，全量覆盖；临时文件 + rename 原子写，中断不丢配置） */
    fun writeConfig(context: Context, cfg: ContainerConfig) {
        val file = configFile(context, cfg.name)
        val sb = StringBuilder()
        sb.append("# ${file.name} ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}\n")
        put(sb, "DISTRIB", cfg.distro)
        put(sb, "ARCH", "arm64")
        put(sb, "SUITE", cfg.release)
        // INCLUDE 恒含 init 组件：初始化系统选项（sysv/run-parts）依赖它生效
        put(sb, "INCLUDE", withInit(cfg.components))
        put(sb, "METHOD", "chroot")
        if (cfg.path.isNotBlank()) put(sb, "CHROOT_DIR", cfg.path)
        if (cfg.installType == "image" && cfg.path.isNotBlank()) {
            put(sb, "TARGET_PATH", "${cfg.path}.img")
            put(sb, "TARGET_TYPE", "file")
            put(sb, "FS_TYPE", "ext4")
            if (cfg.imageSize.isNotBlank()) {
                imageSizeToMb(cfg.imageSize)?.let { put(sb, "DISK_SIZE", it.toString()) }
            }
        } else {
            put(sb, "TARGET_TYPE", "directory")
            if (cfg.path.isNotBlank()) put(sb, "TARGET_PATH", cfg.path)
        }
        put(sb, "SOURCE_PATH", cfg.mirror)
        put(sb, "USER_NAME", cfg.user.ifBlank { "root" })
        put(sb, "USER_PASSWORD", cfg.password)
        put(sb, "USER_GROUPS", cfg.userGroups)
        put(sb, "MOUNTS", cfg.mounts.joinToString(" "))
        put(sb, "SSH_PORT", cfg.sshPort)
        // 初始化系统：sysv 写 INIT_LEVEL；run-parts 写 INIT_PATH（对应 CLI include/init 组件参数）
        put(sb, "INIT", cfg.init)
        if (cfg.init == "sysv") put(sb, "INIT_LEVEL", cfg.initLevel)
        if (cfg.init == "run-parts") put(sb, "INIT_PATH", cfg.initPath)
        // 原子写：先写 .tmp 再 rename。
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(sb.toString())
            tmp.delete()
        }
        // 权限 644：owner 读写、组/其他只读（owner 为前端 app 自身，
        // 保证前端可读写；容器内/其他进程只读，不依赖 root 操作配置）
        file.setReadable(true, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    /** 删除配置文件 */
    fun deleteConfig(context: Context, name: String): Boolean {
        val file = configFile(context, name)
        return file.exists() && file.delete()
    }

    /** 配置文件路径 */
    private fun configFile(context: Context, name: String): File =
        File(configDir(context), "$name.conf")

    /** 写一行 KEY="value"（空值跳过，与 CLI params_write 一致） */
    private fun put(sb: StringBuilder, key: String, value: String) {
        if (value.isNotBlank()) sb.append("${key}=\"${value}\"\n")
    }

    /** 解析 .conf 文件 → ContainerConfig */
    private fun parseConf(file: File): ContainerConfig? {
        val kv = HashMap<String, String>()
        file.readLines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val eq = t.indexOf('=')
            if (eq <= 0) return@forEach
            val key = t.substring(0, eq).trim()
            var value = t.substring(eq + 1).trim()
            // 去掉首尾引号
            if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                value = value.substring(1, value.length - 1)
            }
            if (key.matches(Regex("[0-9A-Z_]{1,32}"))) kv[key] = value
        }
        val name = file.name.removeSuffix(".conf")
        val components = kv["INCLUDE"] ?: "core"
        val sshEnabled = components.split(Regex("[ ,]+")).contains("extra/ssh")
        val targetType = kv["TARGET_TYPE"] ?: "directory"
        val mounts = kv["MOUNTS"].orEmpty().split(" ").filter { it.isNotBlank() }
        // 初始化系统：旧配置无 INIT 键时默认 sysv（与 CLI INIT="${INIT:-sysv}" 兜底一致）
        val initRaw = kv["INIT"] ?: "sysv"
        return ContainerConfig(
            name = name,
            distro = kv["DISTRIB"] ?: "debian",
            release = kv["SUITE"] ?: "",
            path = kv["CHROOT_DIR"] ?: "",
            installType = if (targetType == "file") "image" else "directory",
            imageSize = mbToImageSize(kv["DISK_SIZE"]),
            mirror = kv["SOURCE_PATH"] ?: "",
            user = kv["USER_NAME"] ?: "root",
            userGroups = kv["USER_GROUPS"] ?: "",
            mountsEnabled = mounts.isNotEmpty(),
            mounts = mounts,
            sshEnabled = sshEnabled,
            sshPort = kv["SSH_PORT"] ?: "22",
            password = kv["USER_PASSWORD"] ?: "changeme",
            components = components,
            init = if (initRaw == "run-parts") "run-parts" else "sysv",
            initLevel = kv["INIT_LEVEL"] ?: "3",
            initPath = kv["INIT_PATH"] ?: "/etc/rc.d",
        )
    }

    /** INCLUDE 保证含 init 组件（初始化系统 sysv/run-parts 依赖它生效） */
    private fun withInit(components: String): String {
        val list = components.split(Regex("[ ,]+")).filter { it.isNotBlank() }.toMutableList()
        if ("init" !in list) list.add("init")
        return list.joinToString(" ")
    }

    /** MB 数值 → 可读大小串（2048 → "2G"；1024 内 → "1024M"） */
    private fun mbToImageSize(mbStr: String?): String {
        val mb = mbStr?.toLongOrNull() ?: return "4G"
        return when {
            mb >= 1024 && mb % 1024 == 0L -> "${mb / 1024}G"
            else -> "${mb}M"
        }
    }

    /** "4G/512M/1024" → MB 数值（解析失败返回 null） */
    private fun imageSizeToMb(raw: String): Long? {
        val s = raw.trim().uppercase()
        if (s.isEmpty()) return null
        val numStr = s.takeWhile { it.isDigit() }
        val num = numStr.toLongOrNull() ?: return null
        return when (s.lastOrNull()) {
            'K' -> num / 1024
            'M' -> num
            'G' -> num * 1024
            'T' -> num * 1024 * 1024
            else -> num
        }
    }
}
