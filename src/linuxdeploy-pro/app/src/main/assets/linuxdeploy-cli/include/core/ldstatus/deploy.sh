#!/bin/sh
# Linux Deploy Component

# 运行状态标记：容器启动时拉起进程 ldstatus（exec -a 改名），pid 写入
# /run/ldstatus/pid（容器磁盘，跨挂载命名空间可见），宿主机 status 据此
# 判断容器运行状态，不依赖挂载命名空间可见性。

do_install()
{
    msg ":: 正在安装 ${COMPONENT} ... "
    mkdir -p "${CHROOT_DIR}/run/ldstatus" "${CHROOT_DIR}/usr/local/sbin"
    cat > "${CHROOT_DIR}/usr/local/sbin/ldstatus" <<'EOF'
#!/bin/bash
/bin/mkdir -p /run/ldstatus
# 新会话完全脱离宿主（setsid），后台写 pid 后 exec 成 ldstatus 常驻
/bin/setsid /bin/bash -c 'echo $$ > /run/ldstatus/pid; exec -a ldstatus /bin/sleep infinity' </dev/null >/dev/null 2>&1 &
exit 0
EOF
    chmod 755 "${CHROOT_DIR}/usr/local/sbin/ldstatus"
    return 0
}

do_start()
{
    if ldstatus_running; then
        msg ":: 启动 ${COMPONENT} ... 已在运行"
        return 0
    fi
    msg -n ":: 启动 ${COMPONENT} ... "
    chroot_exec /usr/local/sbin/ldstatus
    is_ok "失败" "完成"
    return 0
}

do_stop()
{
    msg -n ":: 停止 ${COMPONENT} ... "
    local ld_pid
    ld_pid=$(cat "${CHROOT_DIR}/run/ldstatus/pid" 2>/dev/null | tr -cd '0-9')
    [ -n "${ld_pid}" ] && kill -9 "${ld_pid}" 2>/dev/null
    rm -f "${CHROOT_DIR}/run/ldstatus/pid"
    is_ok "失败" "完成"
    return 0
}

do_status()
{
    msg -n ":: ${COMPONENT} ... "
    if ldstatus_running; then
        msg "运行中"
        return 0
    fi
    msg "已停止"
    return 1
}
