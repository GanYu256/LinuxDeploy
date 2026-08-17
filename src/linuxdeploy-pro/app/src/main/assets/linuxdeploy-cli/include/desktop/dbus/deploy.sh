#!/bin/sh
# Linux Deploy Component
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3

do_install()
{
    msg ":: 正在安装 ${COMPONENT} ... "
    local packages=""
    case "${DISTRIB}:${ARCH}:${SUITE}" in
    debian:*|ubuntu:*|kali:*)
        packages="dbus"
        apt_install ${packages}
    ;;
    archlinux:*)
        packages="dbus"
        pacman_install ${packages}
    ;;
    fedora:*)
        packages="dbus dbus-tools"
        dnf_install ${packages}
    ;;
    centos:*)
        packages="dbus"
        yum_install ${packages}
    ;;
    esac
}

do_configure()
{
    msg ":: 正在配置 ${COMPONENT} ... "
    make_dirs /run/dbus /var/run/dbus
    chmod 644 "${CHROOT_DIR}/etc/machine-id"
    chroot_exec -u root dbus-uuidgen > "${CHROOT_DIR}/etc/machine-id"
    return 0
}

do_start()
{
    msg -n ":: Starting ${COMPONENT} ... "
    is_stopped /run/dbus/pid /run/dbus/messagebus.pid /run/messagebus.pid /var/run/dbus/pid /var/run/dbus/messagebus.pid /var/run/messagebus.pid
    is_ok "跳过" || return 0
    remove_files /run/dbus/pid /run/dbus/messagebus.pid /run/messagebus.pid /var/run/dbus/pid /var/run/dbus/messagebus.pid /var/run/messagebus.pid
    chroot_exec -u root dbus-daemon --system --fork
    is_ok "失败" "完成"
    return 0
}

do_stop()
{
    msg -n ":: Stopping ${COMPONENT} ... "
    kill_pids /run/dbus/pid /run/dbus/messagebus.pid /run/messagebus.pid /var/run/dbus/pid /var/run/dbus/messagebus.pid /var/run/messagebus.pid
    is_ok "失败" "完成"
    return 0
}

do_status()
{
    msg -n ":: ${COMPONENT} ... "
    is_started /run/dbus/pid /run/dbus/messagebus.pid /run/messagebus.pid /var/run/dbus/pid /var/run/dbus/messagebus.pid /var/run/messagebus.pid
    is_ok "已停止" "已启动"
    return 0
}
