package io.github.ganyu256.linuxdeploypro.model

/**
 * 容器配置：与 CLI 4.0 的 config create / config edit 参数一一对应。
 *
 * @param name          配置名称（唯一标识）
 * @param distro        系统：debian / ubuntu / kali / alpine / archlinux / slackware / rootfs
 * @param release       发行版本：如 trixie、bookworm（rootfs 本地包时为空）
 * @param path          容器根目录（留空表示部署时自动生成）
 * @param installType   安装方式：directory（目录）/ image（ext4 镜像文件）
 * @param imageSize     镜像大小（仅镜像方式使用，如 4G）
 * @param mirror        软件源地址（留空使用发行版官方源）
 * @param user          特权用户（默认 root，可修改）
 * @param userGroups    辅助组（空格分隔，如 aid_inet aid_sdcard_rw aid_graphics）
 * @param mountsEnabled 是否启用安卓目录挂载（关闭时忽略 mounts 列表）
 * @param mounts        安卓目录挂载列表，每项 "源:目标"（对应 CLI 的 MOUNTS）
 * @param sshEnabled    是否启用容器内 SSH
 * @param sshPort       SSH 端口
 * @param password      容器默认密码（与 SSH 开关无关，始终需要）
 * @param components    额外组件，如 core、extra/ssh（空格分隔，对应 CLI 的 INCLUDE）
 * @param running       运行状态（由 CLI status 回传；部署中状态由前端 opLabel 判断）
 */
data class ContainerConfig(
    val name: String,
    val distro: String = "debian",
    val release: String = "trixie",
    val path: String = "",
    val installType: String = "directory",
    val imageSize: String = "4G",
    val mirror: String = "",
    val user: String = "root",
    val userGroups: String = "aid_inet aid_sdcard_rw aid_graphics",
    val mountsEnabled: Boolean = false,
    val mounts: List<String> = emptyList(),
    val sshEnabled: Boolean = false,
    val sshPort: String = "22",
    val password: String = "changeme",
    val components: String = "core",
    var running: Boolean = false,
)
