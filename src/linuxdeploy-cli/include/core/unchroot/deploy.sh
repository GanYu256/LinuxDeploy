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
#   reboot → 容器重启：经 unchroot 到宿主侧执行 cli.sh restart（等价 stop + 3 秒 + start），
#            不重启手机。触发侧只负责认准配置名，动作全部交给 CLI 通用逻辑。
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
    # 内容版本标记：脚本内容变化时递增，保证已注入过的容器也会被重写
    #（只判断“是否我们写的”会让旧内容永远不被更新）
    local marker="LD-REBOOT-OVERRIDE-v2"
    if [ -L "${reboot}" ] || ! grep -q "${marker}" "${reboot}" 2>/dev/null; then
        make_dirs "${CHROOT_DIR}/usr/sbin"
        rm -f "${reboot}"
        cat > "${reboot}" << REBOOT_EOF
#!/bin/sh
# Linux Deploy 容器内 reboot：触发容器重启（宿主侧 cli.sh restart = stop + 3 秒 + start）。
# 不重启手机；容器配置名在部署/启动时已写死，这里不做任何判断。
# ${marker}
if [ ! -x /sbin/unchroot ]; then
    echo "reboot: 缺少 /sbin/unchroot，无法触发容器重启" >&2
    exit 1
fi
echo "正在重新启动容器（${CURRENT_CONF}）... 容器将停止约 40 秒后自动恢复，SSH 会话会断开"
sync 2>/dev/null || true
setsid sh /sbin/unchroot /system/bin/sh -c 'exec /system/bin/sh ${ENV_DIR}/cli.sh -c ${CURRENT_CONF} restart' </dev/null >/dev/null 2>&1 &
exit 0
REBOOT_EOF
        chmod 755 "${reboot}"
        msg ":: 已接管容器内 reboot（→ 宿主侧 cli.sh -c ${CURRENT_CONF} restart：停止后重新启动）"
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
