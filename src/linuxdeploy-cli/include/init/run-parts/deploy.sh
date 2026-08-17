#!/bin/sh
# Linux Deploy Component
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3
# 重构版维护：全中文日志 + 路径存在性守卫（文件/目录缺失时静默跳过，避免 ls 报错）

[ -n "${INIT_USER}" ] || INIT_USER="root"

run_part()
{
    local path="$1"
    local action="$2"
    msg -n "${path##*/} ... "
    if [ "${INIT_ASYNC}" = "true" ]; then
        # 3>&-：关闭继承的 fd3（CLI exec 3>&1 保存的原始 stdout），
        # 防止脚本内后台化且不主动关 fd 的守护进程攥住调用方管道导致永不 EOF
        chroot_exec -u ${INIT_USER} "${path} ${action}" 1>&2 3>&- &
    else
        chroot_exec -u ${INIT_USER} "${path} ${action}" 1>&2 3>&-
    fi
    is_ok "失败" "完成"
}

do_start()
{
    [ -n "${INIT_PATH}" ] || return 0

    local full="${CHROOT_DIR}${INIT_PATH}"
    [ -e "${full}" ] || { msg "[跳过] 容器内不存在 ${INIT_PATH}"; return 0; }

    if [ -f "${full}" ]; then
        msg ":: 启动 run-parts 脚本: "
        run_part "${INIT_PATH}" start
    else
        local services=$(ls "${full}/")
        if [ -n "${services}" ]; then
            msg ":: 启动 run-parts 服务: "
            local part
            for part in ${services}
            do
                run_part "${INIT_PATH%/}/${part}" start
            done
        fi
    fi

    return 0
}

do_stop()
{
    [ -n "${INIT_PATH}" ] || return 0

    local full="${CHROOT_DIR}${INIT_PATH}"
    [ -e "${full}" ] || { msg "[跳过] 容器内不存在 ${INIT_PATH}"; return 0; }

    if [ -f "${full}" ]; then
        msg ":: 停止 run-parts 脚本: "
        run_part "${INIT_PATH}" stop
    else
        local services=$(ls "${full}/" | tac)
        if [ -n "${services}" ]; then
            msg ":: 停止 run-parts 服务: "
            local part
            for part in ${services}
            do
                run_part "${INIT_PATH%/}/${part}" stop
            done
        fi
    fi

    return 0
}

do_help()
{
cat <<EOF
   --init-path="${INIT_PATH}"
     容器内要执行的脚本文件或脚本目录。

   --init-user="${INIT_USER}"
     以指定用户执行，默认 "root"。

   --init-async
     异步启动进程。

EOF
}
