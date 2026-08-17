#!/bin/sh
# Linux Deploy Component
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3

do_install()
{
    msg ":: 正在安装 ${COMPONENT} ... "
    local packages=""
    case "${DISTRIB}:${ARCH}:${SUITE}" in
    debian:*|ubuntu:*|kali:*)
        packages="hostname"
    ;;
    archlinux:*)
        packages="hostname"
        pacman_install ${packages}
    ;;
    fedora:*)
        packages="hostname"
        dnf_install ${packages}
    ;;
    centos:*)
        packages="hostname"
        yum_install ${packages}
    ;;
    slackware:*)
        packages="hostname"
        slackpkg_install ${packages}
    ;;
    alpine:*)
        packages="hostname"
        apk_install ${packages}
    ;;
    esac
}
do_configure()
{
    msg ":: 正在配置 ${COMPONENT} ... "
    echo ${HOST_NAME} > "${CHROOT_DIR}/etc/hostname"
    return 0
}

do_start()
{
    # chroot 下调用 hostname 会直接作用于宿主系统主机名，且非 root 用户通常无权限，
    # 容器主机名由 /etc/hostname 文件管理（见 do_configure），启动时无需额外操作。
    msg ":: 启动 ${COMPONENT}: chroot 中主机名由 /etc/hostname 管理，跳过"
    return 0
}

do_stop()
{
    # 停止时同样无需重置主机名（避免影响宿主系统）。
    msg ":: 停止 ${COMPONENT}: chroot 中无需重置主机名，跳过"
    return 0
}
