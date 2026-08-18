#!/bin/sh
# Linux Deploy 组件：Debian GNU/Linux 引导
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3
# 维护：GanYu256（CLI 4.0 重构，中文注释与日志）

# 发行版代号，默认 trixie（Debian 13）
# 可用值：trixie(13) bookworm(12) bullseye(11) stable testing unstable
[ -n "${SUITE}" ] || SUITE="trixie"

# 架构自动推断；本项目只维护 arm64，其余分支保留兼容
if [ -z "${ARCH}" ]
then
    case "$(get_platform)" in
    x86) ARCH="i386" ;;
    x86_64) ARCH="amd64" ;;
    arm) ARCH="armhf" ;;
    arm_64) ARCH="arm64" ;;
    esac
fi

# 软件源，默认中科大 HTTP 镜像源
# 注意：k30p 网络环境 https 解析不可用（实测），统一用 http；
# 若网络对镜像存在劫持，可在配置中指定 --source-path 覆盖为其他源
[ -n "${SOURCE_PATH}" ] || SOURCE_PATH="http://mirrors.ustc.edu.cn/debian/"

# 基础安装包清单（trixie 实测通过）
# - locales：本地化支持
# - sudo：权限管理
# - man-db：手册数据库
# - python3：systemctl（python 实现）运行依赖
# - fish：用户要求默认安装的 shell
BASE_INCLUDE_PACKAGES="locales,sudo,man-db,python3,fish"
# 排除包清单（容器内不需要 init 与 systemd 引导）
BASE_EXCLUDE_PACKAGES="init,systemd-sysv"

# apt 安装辅助函数：更新索引并静默安装指定包
apt_install()
{
    local packages="$@"
    [ -n "${packages}" ] || return 1
    (set -e
        log_exec chroot_exec -u root apt-get update -yq
        log_exec chroot_exec -u root "DEBIAN_FRONTEND=noninteractive apt-get install -yfq --no-install-recommends ${packages}"
        log_exec chroot_exec -u root apt-get clean
    exit 0)
    return $?
}

# 确保容器内核心虚拟文件系统可用
# debootstrap 二阶段结束退出时，会自动卸载 /proc、/sys、/dev、/dev/shm、/dev/pts，
# 导致后续 apt 安装额外软件包时 /dev/pts 不可用（posix_openpt 报错）、
# systemd-tmpfiles 报 /proc 未挂载。这里检测缺失项并补挂，保证后续组件在完整环境中执行。
ensure_container_fs()
{
    local missing=""
    for m in proc sys dev dev/shm dev/pts
    do
        is_mounted "${CHROOT_DIR}/${m}" || missing="${missing} ${m}"
    done
    [ -n "${missing}" ] || return 0
    msg "debootstrap 二阶段已卸载虚拟文件系统，正在补挂: ${missing}"
    container_mount ${missing} || return 1
    return 0
}

# 配置 apt 软件源
apt_repository()
{
    # 备份原始 sources.list（若存在）
    if [ -e "${CHROOT_DIR}/etc/apt/sources.list" ]; then
        cp "${CHROOT_DIR}/etc/apt/sources.list" "${CHROOT_DIR}/etc/apt/sources.list.bak"
    fi
    # 规避容器内 apt 因权限下降导致的 resolv 问题（老问题修复）
    echo 'Debug::NoDropPrivs "true";' > "${CHROOT_DIR}/etc/apt/apt.conf.d/00no-drop-privs"
    # 关闭 apt 沙箱 seccomp，安卓内核不完整时避免 apt 崩溃
    echo 'apt::sandbox::seccomp "false";' > "${CHROOT_DIR}/etc/apt/apt.conf.d/999seccomp-off"
    # 确保容器内 DNS 配置可用：若缺失或为空，复用宿主 resolv.conf
    if [ ! -s "${CHROOT_DIR}/etc/resolv.conf" ]; then
        cp /etc/resolv.conf "${CHROOT_DIR}/etc/resolv.conf" 2>/dev/null || true
    fi
    # 写入 trixie 软件源；Debian 12+ 固件组件为 non-free-firmware
    echo "deb ${SOURCE_PATH} ${SUITE} main contrib non-free non-free-firmware" > "${CHROOT_DIR}/etc/apt/sources.list"
    echo "deb-src ${SOURCE_PATH} ${SUITE} main contrib non-free non-free-firmware" >> "${CHROOT_DIR}/etc/apt/sources.list"
}

# 安装主流程：debootstrap 一阶段（解包）+ 二阶段（chroot 内配置）
do_install()
{
    # 支持直接导入 rootfs 归档作为安装源
    is_archive "${SOURCE_PATH}" && return 0

    msg ":: 正在安装 ${COMPONENT} ... "

    # 使用官方 debootstrap（本项目内置 1.0.144）
    local DEBOOTSTRAP_DIR rc_file debootstrap_rc
    DEBOOTSTRAP_DIR="$(component_dir bootstrap/debian)/debootstrap"
    rc_file=$(mktemp) || return 1

    # 一阶段：仅解包基础系统到目标目录
    # --no-check-gpg：跳过 Release 签名校验（离线/镜像场景必需）
    # --foreign：只做第一段（目标架构与主机不一致时的标准做法）
    # --extractor=ar：安卓环境缺少 dpkg-deb 时的降级解包器
    # 注意：必须以“执行”方式调用官方 debootstrap，绝不可 source（source 时 $0 变成外层 shell，save_variables 的 cp "$0" 会拿 shell 名当文件复制）
    # 输出经 log_stream 实时回显并写入日志文件
    (
        env DEBOOTSTRAP_DIR="${DEBOOTSTRAP_DIR}" "${DEBOOTSTRAP_DIR}/debootstrap" --no-check-gpg --foreign --extractor=ar --arch="${ARCH}" --exclude="${BASE_EXCLUDE_PACKAGES}" --include="${BASE_INCLUDE_PACKAGES}" "${SUITE}" "${CHROOT_DIR}" "${SOURCE_PATH}" 2>&1
        echo $? > "${rc_file}"
    ) | log_stream
    debootstrap_rc=$(cat "${rc_file}")
    rm -f "${rc_file}"
    if [ "${debootstrap_rc}" -ne 0 ]; then
        msg "debootstrap 一阶段失败（退出码 ${debootstrap_rc}），请检查安装源与网络"
        return 1
    fi

    # 一阶段完成：容器内已具备 glibc，此时启用构建期 statx 兼容层
    # （内核 <5.8 时，systemd≥260 的 postinst 需要 STATX_MNT_ID，见 cli.sh）
    statx_shim_install || true

    # 挂载核心虚拟文件系统，准备二阶段
    component_exec core/emulator core/mnt core/net

    # 二阶段：在 chroot 内完成包配置
    unset DEBOOTSTRAP_DIR
    rc_file=$(mktemp) || return 1
    (
        chroot_exec /debootstrap/debootstrap --no-check-gpg --second-stage 2>&1
        echo $? > "${rc_file}"
    ) | log_stream
    debootstrap_rc=$(cat "${rc_file}")
    rm -f "${rc_file}"
    if [ "${debootstrap_rc}" -ne 0 ]; then
        msg "debootstrap 二阶段失败（退出码 ${debootstrap_rc}），请查看上方日志"
        return 1
    fi

    # 二阶段退出时虚拟文件系统被卸载，先补挂再继续 apt，避免 /dev/pts、/proc 缺失
    ensure_container_fs || return 1

    apt_install -f -qq
    is_ok || return 1

    # 写入正式软件源并更新索引
    msg -n "正在更新软件源 ... "
    apt_repository
    is_ok "失败" "完成"

    # 默认安装 command-not-found：输入未知命令时提示正确的包名建议。
    # 依赖 apt 源已配置；失败不阻断部署（属增强功能，容器仍可用）。
    # 说明：前端已移除"额外软件包"输入，容器部署后手动安装即可。
    msg "正在安装 command-not-found ..."
    apt_install command-not-found
    is_ok || msg "[警告] command-not-found 安装失败（不影响容器使用）"

    # 安装额外软件包（可选，CLI 命令行参数仍支持）
    if [ -n "${EXTRA_PACKAGES}" ]; then
      local extra_pkgs
      extra_pkgs=$(normalize_packages "${EXTRA_PACKAGES}")
      if [ -n "${extra_pkgs}" ]; then
          msg "正在安装额外软件包: ${extra_pkgs}"
          apt_install ${extra_pkgs}
          is_ok || return 1
      fi
    fi

    return 0
}

# 帮助文本
do_help()
{
cat <<HELP_EOF
   --arch="${ARCH}"
     目标架构，支持 "armel", "armhf", "arm64", "i386" 和 "amd64"（本项目只维护 arm64）。

   --suite="${SUITE}"
     发行版代号，支持 "trixie"(13), "bookworm"(12), "bullseye"(11) 及 stable/testing/unstable。

   --source-path="${SOURCE_PATH}"
     安装源，可指定软件源地址或 rootfs 归档路径。

   --extra-packages="${EXTRA_PACKAGES}"
     额外安装的软件包列表，以空格分隔。

HELP_EOF
}
