#!/bin/sh
# Linux Deploy 组件：unchroot（容器逃逸到安卓宿主环境）
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3
# 维护：GanYu256（CLI 4.0 重构，中文注释与日志）

do_configure()
{
    msg ":: 正在配置 ${COMPONENT} ... "
    local unchroot="${CHROOT_DIR}/sbin/unchroot"
    cat > "${unchroot}" << UNCHROOT_EOF
#!/system/bin/sh
# 从容器逃逸到安卓宿主环境
# 原理：/proc/1/cwd 是 Android init 进程的工作目录，即宿主根目录。
# chroot 到该目录后即可进入宿主文件系统。
# 注意：Android 的 passwd 中没有 root 条目，不能使用 su -，直接 exec 宿主 sh。
export HOME=/data
export PATH=/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/sbin
export BOOTCLASSPATH=${BOOTCLASSPATH}
export ANDROID_DATA=${ANDROID_DATA}
export ANDROID_ROOT=${ANDROID_ROOT}
export ANDROID_STORAGE=${ANDROID_STORAGE}
export EXTERNAL_STORAGE=${EXTERNAL_STORAGE}
if [ \$# -eq 0 ]; then
    exec chroot /proc/1/cwd /system/bin/sh
else
    exec chroot /proc/1/cwd "\$@"
fi
UNCHROOT_EOF
    chmod 755 "${unchroot}"
    # 同时接管容器内 reboot 家族（依赖刚写入的 /sbin/unchroot）
    ld_install_reboot_override
    return 0
}

# 接管容器内 poweroff 家族（容器内无关机语义）：
#   reboot → 伪重启：经 unchroot 到宿主侧执行 cli.sh restart（结束容器进程并重新拉起
#            用户空间），不卸载挂载、不重启手机。
#            注意 Debian 下 /usr/sbin/reboot 是指向 ../bin/systemctl 的软链，必须
#            “先删链再写脚本”，否则只是改链接目标、等于没接管。
#   halt/poweroff/shutdown → 统一改为提示并返回失败。原样保留很危险：
#            systemctl halt 会停掉容器全部服务（SSH 断）却既不卸载也不重启，
#            容器直接变僵尸态。
# do_start 每次幂等重放：apt 升级可能把软链装回来。
ld_install_reboot_override()
{
    [ -x "${CHROOT_DIR}/sbin/unchroot" ] || return 0
    [ -n "${ENV_DIR}" ] && [ -n "${CURRENT_CONF}" ] || return 0
    local reboot="${CHROOT_DIR}/usr/sbin/reboot"
    if [ -L "${reboot}" ] || ! grep -q "Linux Deploy 容器内 reboot" "${reboot}" 2>/dev/null; then
        make_dirs "${CHROOT_DIR}/usr/sbin"
        rm -f "${reboot}"
        cat > "${reboot}" << REBOOT_EOF
#!/bin/sh
# Linux Deploy 容器内 reboot：触发容器伪重启（结束容器进程并重新拉起用户空间）。
# 不卸载挂载、不重启手机；实际动作由宿主侧 CLI 的 restart 子命令完成。
if [ ! -x /sbin/unchroot ]; then
    echo "reboot: 缺少 /sbin/unchroot，无法触发容器重启" >&2
    exit 1
fi
setsid sh /sbin/unchroot /system/bin/sh -c 'exec /system/bin/sh ${ENV_DIR}/cli.sh -c ${CURRENT_CONF} restart' </dev/null >/dev/null 2>&1 &
exit 0
REBOOT_EOF
        chmod 755 "${reboot}"
        msg ":: 已接管容器内 reboot（伪重启 → 宿主侧 cli.sh -c ${CURRENT_CONF} restart）"
    fi
    local tool tool_file
    for tool in halt poweroff shutdown
    do
        tool_file="${CHROOT_DIR}/usr/sbin/${tool}"
        if [ -L "${tool_file}" ] || ! grep -q "容器内不支持关机" "${tool_file}" 2>/dev/null; then
            make_dirs "${CHROOT_DIR}/usr/sbin"
            rm -f "${tool_file}"
            cat > "${tool_file}" << STOP_EOF
#!/bin/sh
echo "${tool}: 容器内不支持关机操作；如需重启容器请执行 reboot" >&2
exit 1
STOP_EOF
            chmod 755 "${tool_file}"
        fi
    done
    return 0
}

do_start()
{
    # 每次启动幂等重置 reboot 家族接管（防 apt 升级还原软链）
    ld_install_reboot_override
    return 0
}
