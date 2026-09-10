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
    # 同时接管容器内 reboot 家族与看门狗（均依赖刚写入的 /sbin/unchroot）
    ld_install_reboot_override
    ld_install_watchdog
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
    # 每次启动幂等重置 reboot 家族接管（防 apt 升级还原软链）与看门狗注入
    ld_install_reboot_override
    ld_install_watchdog
    return 0
}

# 注入容器内看门狗：由 systemctl 作为服务拉起，反向监视 init（systemctl --init）存活。
# 形态：
#   脚本   /usr/local/sbin/ldwatchdog
#   服务   /etc/systemd/system/ldwatchdog.service（Restart=always + RestartSec=1s）
# 分工（相互守护，不成环）：
#   - systemctl 守护看门狗：看门狗进程意外退出 → systemctl 按 Restart=always 拉回；
#   - 看门狗监视 init：init 非正常消失（被 OOM/信号杀死等）→ 无人在容器内能拉起它，
#     于是经 unchroot 到宿主侧执行 cli.sh restart（stop + 3 秒 + start），
#     由 CLI 把整个容器用户空间换新（新 init 会重新拉起本看门狗）。
# 判活：主通道读 /run/systemctl/pid（CLI 拉起 init 时写入，已修复该文件此前不被创建的问题），
#       并校验 cmdline 含 systemctl；主通道不可用时兜底扫描本容器内的 systemctl --init
#       （容器内视角下 root 为 "/" 的进程属于本容器）。连续 2 次判定消失才触发（去抖）。
# 防误触发：正常停机时 systemctl 会先给服务发 SIGTERM，这里 trap 后直接退出、绝不触发。
# 覆盖不到：init 与看门狗同时死（整个会话被杀）→ 只能手动 reboot 或 App 启停。
ld_install_watchdog()
{
    # 仅在 systemctl 模式容器里有意义（服务由 systemctl 拉起）
    [ "${INIT}" = "systemctl" ] || return 0
    [ -x "${CHROOT_DIR}/sbin/unchroot" ] || return 0
    [ -e "${CHROOT_DIR}/usr/bin/systemctl" ] || return 0
    [ -n "${ENV_DIR}" ] && [ -n "${CURRENT_CONF}" ] || return 0
    local marker="LD-WATCHDOG-v1"
    local script="${CHROOT_DIR}/usr/local/sbin/ldwatchdog"
    local unit="${CHROOT_DIR}/etc/systemd/system/ldwatchdog.service"
    local changed=0
    if ! grep -q "${marker}" "${script}" 2>/dev/null; then
        make_dirs "${CHROOT_DIR}/usr/local/sbin"
        cat > "${script}" << WATCHDOG_EOF
#!/bin/sh
# Linux Deploy 容器内看门狗：监视 init（systemctl --init）存活，异常消失则触发容器重启。
# 正常停机由 systemctl 先发 SIGTERM，本脚本 trap 后直接退出、不触发。
# ${marker}
INIT_PID_FILE=/run/systemctl/pid
INTERVAL=2
DEBOUNCE=2
trap 'exit 0' TERM INT
log() { echo "[ldwatchdog] \$(date '+%F %T') \$*"; }
# 取当前 init 的 pid：主通道 pid 文件（校验进程名），兜底扫描本容器的 systemctl --init
init_pid() {
    local p a
    p=\$(cat "\${INIT_PID_FILE}" 2>/dev/null | tr -cd '0-9')
    if [ -n "\${p}" ] && [ -r "/proc/\${p}/cmdline" ]; then
        a=\$(tr '\0' ' ' < "/proc/\${p}/cmdline" 2>/dev/null)
        case "\${a}" in *systemctl*) echo "\${p}"; return 0 ;; esac
    fi
    for d in /proc/[0-9]*; do
        [ "\$(readlink "\${d}/root" 2>/dev/null)" = "/" ] || continue
        a=\$(tr '\0' ' ' < "\${d}/cmdline" 2>/dev/null)
        case "\${a}" in *"/usr/bin/systemctl --init"*|*"systemctl --init"*) echo "\${d#/proc/}"; return 0 ;; esac
    done
    return 1
}
log "启动，监视 init（pid 文件 \${INIT_PID_FILE}，间隔 \${INTERVAL}s，去抖 \${DEBOUNCE} 次）"
fails=0
while :; do
    sleep "\${INTERVAL}"
    if init_pid >/dev/null; then
        fails=0
        continue
    fi
    fails=\$((fails + 1))
    log "未发现 init（连续 \${fails} 次）"
    [ "\${fails}" -ge "\${DEBOUNCE}" ] || continue
    log "init 已消失，触发容器重启：cli.sh -c ${CURRENT_CONF} restart"
    if [ -x /sbin/unchroot ]; then
        setsid sh /sbin/unchroot /system/bin/sh -c 'exec /system/bin/sh ${ENV_DIR}/cli.sh -c ${CURRENT_CONF} restart' </dev/null >/dev/null 2>&1 &
    else
        log "缺少 /sbin/unchroot，无法触发重启"
    fi
    # 闭锁：等待宿主侧 restart 把整个容器用户空间换掉，期间不再重复触发
    while :; do sleep 60; done
done
WATCHDOG_EOF
        chmod 755 "${script}"
        changed=1
    fi
    if ! grep -q "${marker}" "${unit}" 2>/dev/null; then
        make_dirs "${CHROOT_DIR}/etc/systemd/system"
        cat > "${unit}" << UNIT_EOF
[Unit]
Description=Linux Deploy init watchdog (container)
After=sysinit.target
# ${marker}

[Service]
Type=simple
ExecStart=/usr/local/sbin/ldwatchdog
Restart=always
RestartSec=1s

[Install]
WantedBy=multi-user.target
UNIT_EOF
        changed=1
    fi
    [ "${changed}" = "1" ] && msg ":: 已注入容器内看门狗（ldwatchdog.service：监视 init，异常时触发容器重启）"
    return 0
}
