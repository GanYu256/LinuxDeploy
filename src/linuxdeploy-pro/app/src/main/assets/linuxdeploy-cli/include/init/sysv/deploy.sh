#!/bin/sh
# Linux Deploy Component
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3
# 重构版维护：全中文日志 + 目录存在性守卫（目录缺失时静默跳过，避免 ls 报错）

[ -n "${INIT_USER}" ] || INIT_USER="root"

do_start()
{
    #local init_level=$(grep ':initdefault:' "${CHROOT_DIR}/etc/inittab" | cut -f2 -d:)
    [ -n "${INIT_LEVEL}" ] || return 0

    local rcdir="${CHROOT_DIR}/etc/rc${INIT_LEVEL}.d"
    [ -d "${rcdir}" ] || { msg "[跳过] 容器内不存在 ${rcdir}，无 SysV 启动脚本"; return 0; }
    local services=$(ls "${rcdir}" | grep '^S')
    if [ -n "${services}" ]; then
        msg ":: 启动 SysV 服务（rc${INIT_LEVEL}.d）: "
        local item
        for item in ${services}
        do
            msg -n "${item/S[0-9][0-9]/} ... "
            if [ "${INIT_ASYNC}" = "true" ]; then
                # 3>&-：关闭继承的 fd3（CLI exec 3>&1 保存的原始 stdout），
                # 防止脚本内后台化且不主动关 fd 的守护进程攥住调用方管道导致永不 EOF
                chroot_exec -u ${INIT_USER} "/etc/rc${INIT_LEVEL}.d/${item} start" 1>&2 3>&- &
            else
                chroot_exec -u ${INIT_USER} "/etc/rc${INIT_LEVEL}.d/${item} start" 1>&2 3>&-
            fi
            is_ok "失败" "完成"
        done
    fi

    return 0
}

do_stop()
{
    [ -n "${INIT_LEVEL}" ] || return 0

    local rcdir="${CHROOT_DIR}/etc/rc6.d"
    [ -d "${rcdir}" ] || { msg "[跳过] 容器内不存在 ${rcdir}，无 SysV 停止脚本"; return 0; }
    local services=$(ls "${rcdir}" | grep '^K')
    if [ -n "${services}" ]; then
        msg ":: 停止 SysV 服务（rc6.d）: "
        local item
        for item in ${services}
        do
            msg -n "${item/K[0-9][0-9]/} ... "
            if [ "${INIT_ASYNC}" = "true" ]; then
                chroot_exec -u ${INIT_USER} "/etc/rc6.d/${item} stop" 1>&2 3>&- &
            else
                chroot_exec -u ${INIT_USER} "/etc/rc6.d/${item} stop" 1>&2 3>&-
            fi
            is_ok "失败" "完成"
        done
    fi

    return 0
}

do_help()
{
cat <<EOF
   --init-level="${INIT_LEVEL}"
     SysV 运行级别编号，如 "3"（启动时执行 /etc/rcN.d/ 的 S 脚本）。

   --init-user="${INIT_USER}"
     以指定用户执行，默认 "root"。

   --init-async
     异步启动进程。

EOF
}
