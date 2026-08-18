#!/bin/sh
# Linux Deploy Component
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3

[ -n "${SSH_PORT}" ] || SSH_PORT="22"

do_install()
{
    msg ":: 正在安装 ${COMPONENT} ... "
    local packages=""
    case "${DISTRIB}:${ARCH}:${SUITE}" in
    debian:*|ubuntu:*|kali:*)
        packages="openssh-server"
        [ "${METHOD}" = "proot" ] && packages="${packages} fakechroot"
        apt_install ${packages}
    ;;
    archlinux:*)
        packages="openssh"
        pacman_install ${packages}
    ;;
    fedora:*)
        packages="openssh-server"
        dnf_install ${packages}
    ;;
    centos:*)
        packages="openssh-server"
        yum_install ${packages}
    ;;
    slackware:*)
        packages="openssh"
        slackpkg_install ${packages}
    ;;
    alpine:*)
        packages="openssh-server"
        apk_install ${packages}
    ;;
    esac
}

do_configure()
{
    msg ":: 正在配置 ${COMPONENT} ... "
    local sshd_config
    sshd_config="${CHROOT_DIR}/etc/ssh/sshd_config"
    sed -i -E 's/#?PasswordAuthentication .*/PasswordAuthentication yes/g' "${sshd_config}"
    sed -i -E 's/#?PermitRootLogin .*/PermitRootLogin yes/g' "${sshd_config}"
    sed -i -E 's/#?AcceptEnv .*/AcceptEnv LANG/g' "${sshd_config}"
    # 端口写入 sshd_config（systemctl 模式经 ssh.service 启动 sshd 时无 -p 参数，走配置端口）
    sed -i -E 's/^#?Port .*/Port '"${SSH_PORT}"'/' "${sshd_config}"
    grep -q "^Port " "${sshd_config}" || echo "Port ${SSH_PORT}" >> "${sshd_config}"
    # 确保 host key 存在（systemctl 模式由 systemctl 拉起 sshd，需密钥就绪）
    if [ -z "$(ls "${CHROOT_DIR}/etc/ssh/" 2>/dev/null | grep 'key$')" ]; then
        chroot_exec -u root ssh-keygen -A >/dev/null 2>&1 || true
    fi
    # systemctl 模式：把 ssh.service 设为默认启用，交由 systemctl 的 default.target 拉起
    if [ "${INIT}" = "systemctl" ]; then
        chroot_exec /usr/bin/systemctl enable ssh.service 2>/dev/null || true
    fi
    return 0
}

do_start()
{
    msg -n ":: Starting ${COMPONENT} ... "
    # systemctl 模式：ssh 由 systemctl 管理（ssh.service），CLI 不直启
    if [ "${INIT}" = "systemctl" ]; then
        # 端口/密钥在每次启动也应用（do_configure 仅部署时执行，老容器靠此兜底）
        local sshd_config
        sshd_config="${CHROOT_DIR}/etc/ssh/sshd_config"
        sed -i -E 's/^#?Port .*/Port '"${SSH_PORT}"'/' "${sshd_config}" 2>/dev/null || true
        grep -q "^Port " "${sshd_config}" 2>/dev/null || echo "Port ${SSH_PORT}" >> "${sshd_config}"
        if [ -z "$(ls "${CHROOT_DIR}/etc/ssh/" 2>/dev/null | grep 'key$')" ]; then
            chroot_exec -u root ssh-keygen -A >/dev/null 2>&1 || true
        fi
        # 兜底：确保已 enable（覆盖部署早于本版本的容器）
        chroot_exec /usr/bin/systemctl enable ssh.service 2>/dev/null || true
        msg "跳过（systemctl 模式：ssh 由 systemctl 管理，端口 ${SSH_PORT} 已写入 sshd_config）"
        return 0
    fi
    is_stopped /var/run/sshd.pid /run/sshd.pid || test -z $(pidof /data/local/mnt/usr/sbin/sshd)
    is_ok "跳过" || return 0
    make_dirs /run/sshd /var/run/sshd
    # generate keys
    if [ $(ls "${CHROOT_DIR}/etc/ssh/" | grep -c key) -eq 0 ]; then
        chroot_exec -u root ssh-keygen -A >/dev/null
    fi
    # exec sshd
    if [ "${METHOD}" = "proot" ]; then
        chroot_exec -u root fakechroot /usr/sbin/sshd -p ${SSH_PORT} ${SSH_ARGS} &
    else
        chroot_exec -u root /usr/sbin/sshd -p ${SSH_PORT} ${SSH_ARGS}
    fi
    is_ok "失败" "完成"
    return 0
}

do_stop()
{
    msg -n ":: Stopping ${COMPONENT} ... "
    kill_pids /run/sshd.pid /var/run/sshd.pid
    is_ok "失败" "完成"
    return 0
}

do_status()
{
    msg -n ":: ${COMPONENT} ... "
    is_started /var/run/sshd.pid /run/sshd.pid
    is_ok "已停止" "已启动"
    return 0
}

do_help()
{
cat <<EOF
   --ssh-port="${SSH_PORT}"
     Port of SSH server.

   --ssh-args="${SSH_ARGS}"
     Defines other sshd options, separated by a space.

EOF
}
