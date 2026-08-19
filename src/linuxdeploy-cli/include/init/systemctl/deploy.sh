#!/bin/sh
# Linux Deploy Component
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3
# 基于 python 版 systemctl（Guido Draheim）的 init 模式：
# 启动时以 init 服务方式拉起 `systemctl --init`（常驻），由它按序拉起
# default.target 下的服务（含 sysv-generator 转换的 init.d 脚本与 enable 的
# systemd 服务）；停止时向 systemctl 进程发 SIGTERM 触发干净停机。
# 注意：不管理 ldstatus（其位于 /usr/local/sbin，非 init.d/unit，天然隔离）。

# systemctl 常驻进程检查：读容器内 pid 文件 + /proc/<pid>/cmdline 校验进程名
systemctl_running()
{
    local s_pid
    s_pid=$(cat "${CHROOT_DIR}/run/systemctl/pid" 2>/dev/null | tr -cd '0-9')
    [ -n "${s_pid}" ] || return 1
    [ -r "/proc/${s_pid}/cmdline" ] || return 1
    tr '\0' ' ' < "/proc/${s_pid}/cmdline" | grep -q systemctl
}

do_install()
{
    msg ":: 正在安装 ${COMPONENT} ... "
    local src="${INCLUDE_DIR}/init/systemctl/systemctl.py"
    if [ -f "${src}" ]; then
        make_dirs /usr/bin /run/systemctl
        cp -f "${src}" "${CHROOT_DIR}/usr/bin/systemctl"
        chmod 755 "${CHROOT_DIR}/usr/bin/systemctl"
        is_ok "失败" "完成"
    else
        msg "[错误] 缺少打包的 systemctl.py: ${src}"
        return 1
    fi
    return 0
}

do_start()
{
    if systemctl_running; then
        msg ":: 启动 ${COMPONENT} ... 已在运行"
        return 0
    fi
    msg -n ":: 启动 ${COMPONENT} ... "
    [ -x "${CHROOT_DIR}/usr/bin/systemctl" ] || { msg "失败（/usr/bin/systemctl 未安装，请重新部署）"; return 1; }
    make_dirs /run/systemctl
    # 拉起 init 前确保 ssh.service 已 enable：systemctl 的 default.target 才会
    # 启动 sshd（openssh 安装时未必 enable；此处兜底覆盖任何顺序/老容器）
    if [ -e "${CHROOT_DIR}/usr/lib/systemd/system/ssh.service" ] || [ -e "${CHROOT_DIR}/lib/systemd/system/ssh.service" ]; then
        chroot_exec /usr/bin/systemctl enable ssh.service 2>/dev/null || true
    fi
    # 以 init 服务方式拉起并常驻：setsid 脱离宿主会话；--init 进入 init 模式
    # （拉起 default.target 服务 → 阻塞 init 循环收僵尸、等 SIGTERM/SIGINT 干净停机）。
    # pid 由 setsid 子 shell 写入容器 /run/systemctl/pid（容器磁盘，跨挂载命名空间可见）。
    # 注意：必须先注入容器登录级环境（HOME/PATH/USER 等）——systemctl 会把自身环境
    # 原样传给其拉起的全部服务，CLI 侧 HOME 指向临时目录、PATH 为宿主路径，
    # 会导致 KDE 等读取无效 HOME（加载默认配置）、unchroot 等命令找不到。
    chroot_exec /bin/sh -c 'export HOME=/root USER=root LOGNAME=root SHELL=/bin/bash PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; setsid /bin/sh -c '\''echo $$ > /run/systemctl/pid; exec /usr/bin/systemctl --init'\'' </dev/null >/dev/null 2>&1 &'
    is_ok "失败" "完成"
    return 0
}

do_stop()
{
    msg -n ":: 停止 ${COMPONENT} ... "
    local s_pid
    s_pid=$(cat "${CHROOT_DIR}/run/systemctl/pid" 2>/dev/null | tr -cd '0-9')
    if [ -n "${s_pid}" ] && [ -r "/proc/${s_pid}/cmdline" ]; then
        # SIGTERM → init 循环执行干净停机（停止全部已拉起的服务）
        kill -TERM "${s_pid}" 2>/dev/null
        local i=0
        while [ ${i} -lt 20 ] && [ -d "/proc/${s_pid}" ]; do
            sleep 0.5
            i=$((i+1))
        done
        [ -d "/proc/${s_pid}" ] && kill -9 "${s_pid}" 2>/dev/null
        rm -f "${CHROOT_DIR}/run/systemctl/pid"
    fi
    is_ok "失败" "完成"
    return 0
}

do_status()
{
    msg -n ":: ${COMPONENT} ... "
    if systemctl_running; then
        msg "运行中"
        return 0
    fi
    msg "已停止"
    return 1
}

do_help()
{
cat <<EOF
   无参数：启动容器时以 init 模式拉起 systemctl（--init），
   自动按序启动 default.target 下的服务（init.d / enable 的 systemd 服务）。
EOF
}
