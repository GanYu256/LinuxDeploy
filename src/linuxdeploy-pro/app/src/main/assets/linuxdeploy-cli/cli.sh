#!/bin/bash
################################################################################
#
# Linux Deploy CLI 4.1.13
# 维护：GanYu256
# 说明：本版本为全面重构，命令层重设计、配置切换即锁定、全中文注释与日志。
# 基线：app 锁定版 79924f593556（原 VERSION 2.5.1）
#
################################################################################

VERSION="4.1.13"

################################################################################
# 公共工具
################################################################################

# 输出消息（统一入口，便于前端解析与日志落盘）
# 当 LOG_FILE 非空时，所有消息同步追加到日志文件（带时间戳），供前端轮询读取
msg()
{
    if [ "${1}" = "-n" ]; then
        shift
        echo -n "$@"
        if [ -n "${LOG_FILE}" ]; then
            printf '%s %s' "$(date '+%F %T')" "$@" >> "${LOG_FILE}"
        fi
    else
        echo "$@"
        if [ -n "${LOG_FILE}" ]; then
            printf '%s %s\n' "$(date '+%F %T')" "$@" >> "${LOG_FILE}"
        fi
    fi
}

# 原始输出流式处理：逐行实时回显，并同步写入当前日志文件
# 用于 debootstrap / apt 等直接向 stdout 输出原始文本的环节。
# 注意：管道会吞掉退出码，需配合 log_exec 或在外部自行捕获。
log_stream()
{
    while IFS= read -r line || [ -n "${line}" ]
    do
        # 去掉回车符（debootstrap 进度条用 \r 刷新行）
        line=$(printf '%s' "${line}" | tr -d '\r')
        echo "${line}"
        if [ -n "${LOG_FILE}" ]; then
            printf '%s %s\n' "$(date '+%F %T')" "${line}" >> "${LOG_FILE}"
        fi
    done
}

# 归一化软件包列表：兼容空格 / 半角逗号 / 全角逗号 / 顿号 / 分号分隔，统一转成空格。
normalize_packages()
{
    local raw="$1" out
    [ -n "${raw}" ] || { echo ""; return 0; }
    out=$(printf '%s' "${raw}" | tr ',;' ' ')
    out=$(printf '%s' "${out}" | tr '，、；' ' ')
    out=$(printf '%s' "${out}" | tr -s ' ')
    out=${out# }
    out=${out% }
    printf '%s' "${out}"
}

# 在子 shell 中执行命令，输出经 log_stream 实时回显并写入日志，
# 返回原命令的真实退出码（解决管道吞退出码问题）
log_exec()
{
    local rc_file rc
    rc_file=$(mktemp) || return 1
    (
        "$@" 2>&1
        echo $? > "${rc_file}"
    ) | log_stream
    rc=$(cat "${rc_file}")
    rm -f "${rc_file}"
    return ${rc}
}

# JSON 字符串转义（--json 模式输出安全字段）
json_escape()
{
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g; s/\r//g'
}

# 输出一行 JSON 键值（--json 模式使用）
json_kv()
{
    printf '  "%s": "%s"' "$1" "$(json_escape "$2")"
}

# 打开操作日志（部署/启停/导入导出等），前端可轮询读取
# 用法: log_open [配置名]
log_open()
{
    local name="${1:-default}"
    LOG_DIR="${LOG_DIR:-${BUILD_DIR}/logs}"
    mkdir -p "${LOG_DIR}" 2>/dev/null
    # 时间戳命名（配置名-日期-时分秒）：每次操作独立日志文件，前端按最新文件尾随展示。
    LOG_FILE="${LOG_DIR}/${name}-$(date '+%Y%m%d-%H%M%S').log"
    : > "${LOG_FILE}"
    # 日志文件 chown 给应用，使其可读取/删除
    if [ -n "${APP_UID}" ]; then
        chown "${APP_UID}" "${LOG_FILE}" 2>/dev/null || true
        chown "${APP_UID}" "${LOG_DIR}" 2>/dev/null || true
    fi
    msg "日志文件: ${LOG_FILE}"
}

# 检查上一条命令结果，并输出指定消息
is_ok()
{
    if [ $? -eq 0 ]; then
        if [ -n "$2" ]; then
            msg "$2"
        fi
        return 0
    else
        if [ -n "$1" ]; then
            msg "$1"
        fi
        return 1
    fi
}

# 获取平台类型（安卓 arm64 映射为 arm_64）
get_platform()
{
    local arch="$1"
    if [ -z "${arch}" ]; then
        arch=$(uname -m)
    fi
    case "${arch}" in
    arm64|aarch64)
        echo "arm_64"
    ;;
    arm*)
        echo "arm"
    ;;
    x86_64|amd64)
        echo "x86_64"
    ;;
    i[3-6]86|x86)
        echo "x86"
    ;;
    *)
        echo "unknown"
    ;;
    esac
}

# 生成 UUID（用于隔离目录命名）
get_uuid()
{
    cat /proc/sys/kernel/random/uuid
}

# 检查 binfmt_misc 是否可用
multiarch_support()
{
    if [ -d "/proc/sys/fs/binfmt_misc" ]; then
        return 0
    else
        return 1
    fi
}

# 检查 SELinux 是否关闭
selinux_inactive()
{
    if [ -e "/sys/fs/selinux/enforce" ]; then
        return $(cat /sys/fs/selinux/enforce)
    else
        return 0
    fi
}

# 检查 loop 设备是否可用
loop_support()
{
    [ -e "/dev/loop-control" ] || [ -e "/dev/loop0" ]
}

# 获取指定用户的 home 目录
user_home()
{
    getent passwd "$1" 2>/dev/null | cut -d: -f6
}

# 获取指定用户的登录 shell
user_shell()
{
    getent passwd "$1" 2>/dev/null | cut -d: -f7
}

# 判断路径是否已挂载
is_mounted()
{
    grep -q " $(readlink -f "$1") " /proc/mounts
}

# 判断路径是否为归档文件
is_archive()
{
    local file="$1"
    case "${file}" in
    *tar|*tar.gz|*tgz|*tar.bz2|*tbz2|*tar.xz|*txz|*tar.zst|*tzst|*gz|*bz2|*xz|*zst)
        return 0
    ;;
    esac
    return 1
}

# 获取容器内运行的进程 PID 列表
get_pids()
{
    # 列出 chroot 进容器目录的进程 pid（按 /proc/<pid>/root 软链目标匹配），
    # 用一次 ls -l 批量列出，避免逐进程 fork。
    ls -l /proc/[0-9]*/root 2>/dev/null \
        | grep " -> ${CHROOT_DIR%/}\$" \
        | sed 's#^.*/proc/\([0-9][0-9]*\)/root.*#\1#'
}

# 判断容器是否已启动（存在相关进程）
is_started()
{
    [ -n "$(get_pids)" ]
}

# 判断容器是否已停止
is_stopped()
{
    [ -z "$(get_pids)" ]
}

# 检查 ldstatus 标记进程是否存活：读容器内 pid 文件（跨命名空间）+ 进程名校验。
# 名称校验直接读 /proc/<pid>/cmdline 的 argv[0]（ldstatus 用 exec -a 改名），
# 兼容 procps（ps -A 显示 comm）与 toybox（显示 argv[0]）的输出差异。
ldstatus_running()
{
    local ld_pid
    ld_pid=$(cat "${CHROOT_DIR}/run/ldstatus/pid" 2>/dev/null | tr -cd '0-9')
    [ -n "${ld_pid}" ] || return 1
    [ -r "/proc/${ld_pid}/cmdline" ] || return 1
    tr '\0' ' ' < "/proc/${ld_pid}/cmdline" | grep -q ldstatus
}

# 结束进程列表：入参为 pid 或 pid 文件路径（自动读取文件内数字 pid）。
# 先 TERM，等 1 秒，再对残留进程 KILL。
kill_pids()
{
    local items="$@"
    local item pid
    # 第一轮：对所有存活 pid 发 TERM（不逐进程 sleep）
    for item in ${items}
    do
        [ -n "${item}" ] || continue
        pid="${item}"
        # pid 文件路径 → 读取文件内数字 pid（兼容 ssh do_stop 的调用方式）
        if [ -f "${item}" ]; then
            pid=$(head -1 "${item}" 2>/dev/null | tr -cd '0-9')
        fi
        [ -n "${pid}" ] || continue
        kill -0 "${pid}" 2>/dev/null && kill "${pid}" 2>/dev/null
    done
    # 统一等 1 秒（不是每进程 1 秒，避免 N 进程 N 秒），再对仍未退出的发 KILL
    sleep 1
    for item in ${items}
    do
        [ -n "${item}" ] || continue
        pid="${item}"
        if [ -f "${item}" ]; then
            pid=$(head -1 "${item}" 2>/dev/null | tr -cd '0-9')
        fi
        [ -n "${pid}" ] || continue
        kill -0 "${pid}" 2>/dev/null && kill -9 "${pid}" 2>/dev/null
    done
    # 清理是尽力而为，进程已不存在/无权限 kill 都不算失败
    return 0
}

# 删除文件列表
remove_files()
{
    local files="$@"
    file
    for file in ${files}
    do
        rm -f "${file}"
    done
}

# 创建目录列表
make_dirs()
{
    local dirs="$@"
    local dir
    for dir in ${dirs}
    do
        [ -d "${dir}" ] || mkdir -p "${dir}"
    done
}

################################################################################
# 容器内执行
################################################################################

# 构建期 statx 兼容层：LD_PRELOAD shim（statx_shim.c）拦截 libc statx()，
# 为缺少 STATX_MNT_ID 的旧内核补齐该掩码位。只影响构建期子进程，
# 部署结束后从容器中移除。
# 判断当前内核是否需要 statx 兼容层（仅 arm64 + 内核 < 5.8）
kernel_need_statx_shim()
{
    # 本项目只维护 arm64，shim 也只针对 arm64 容器验证
    [ "${ARCH}" = "arm64" ] || return 1
    local ver major minor
    ver=$(uname -r 2>/dev/null | cut -d. -f1-2)
    [ -n "${ver}" ] || return 1
    major=${ver%%.*}
    minor=${ver#*.}
    minor=${minor%%.*}
    if [ "${major}" -lt 5 ] || { [ "${major}" -eq 5 ] && [ "${minor}" -lt 8 ]; }; then
        return 0
    fi
    return 1
}

# 编译 statx 兼容层到临时目录（幂等，编译产物可跨部署复用）
statx_shim_ensure_compiled()
{
    [ -n "${STATX_SHIM_SO}" ] && [ -f "${STATX_SHIM_SO}" ] && return 0
    local src="${ENV_DIR}/statx_shim.c"
    if [ ! -f "${src}" ]; then
        msg "[跳过] 缺少 statx 兼容层源码: ${src}"
        return 1
    fi
    if ! command -v gcc >/dev/null 2>&1; then
        msg "[跳过] 宿主未安装 gcc，无法编译 statx 兼容层（旧内核 + systemd≥260 时 Kali 等构建可能失败）"
        return 1
    fi
    local out="${TEMP_DIR}/statx_shim.so"
    if ! gcc -shared -fPIC -O2 -Wall -o "${out}" "${src}" 2>"${TEMP_DIR}/statx_shim_build.log"; then
        msg "[警告] statx 兼容层编译失败，详情见 ${TEMP_DIR}/statx_shim_build.log（构建将继续，systemd≥260 的发行版可能失败）"
        return 1
    fi
    STATX_SHIM_SO="${out}"
    return 0
}

# 把 statx 兼容层装入当前容器（幂等，可在子 shell 中调用）
# 注意：component_exec 的每个组件都跑在子 shell 里，环境变量传不出去，
# 因此这里以"容器内存在 shim 文件"作为 chroot_exec 的注入开关，
# 而不是用 shell 变量。
statx_shim_install()
{
    kernel_need_statx_shim || return 1
    [ -d "${CHROOT_DIR}" ] || return 1
    statx_shim_ensure_compiled || return 1
    # 仅注入 glibc 容器：shim 按 glibc ABI 编译，musl（Alpine）无法加载
    if ! find "${CHROOT_DIR}" -maxdepth 3 -name "ld-linux-*.so*" 2>/dev/null | grep -q .; then
        msg "[跳过] 容器内未检测到 glibc（ld-linux），无需 statx 兼容层"
        return 1
    fi
    mkdir -p "${CHROOT_DIR}/usr/lib/linuxdeploy"
    cp -f "${STATX_SHIM_SO}" "${CHROOT_DIR}/usr/lib/linuxdeploy/statx-shim.so"
    chmod 755 "${CHROOT_DIR}/usr/lib/linuxdeploy/statx-shim.so"
    msg "[通过] 已启用 statx 兼容层（内核 $(uname -r | cut -d. -f1-2) < 5.8，为 systemd≥260 补齐 STATX_MNT_ID）"
    return 0
}

# 部署结束后移除 shim，避免污染运行时容器
statx_shim_cleanup()
{
    local shim_path="${CHROOT_DIR}/usr/lib/linuxdeploy/statx-shim.so"
    if [ -f "${shim_path}" ]; then
        rm -f "${shim_path}"
        rmdir "${CHROOT_DIR}/usr/lib/linuxdeploy" 2>/dev/null || true
        msg "[完成] 已移除构建期 statx 兼容层"
    fi
}

# 部署入口：编译兼容层；若容器已存在（重部署/导入 rootfs）则直接启用
statx_shim_prepare()
{
    kernel_need_statx_shim || return 0
    statx_shim_ensure_compiled || return 0
    statx_shim_install || true
    return 0
}

# 在 chroot 容器内执行命令
# 用法：chroot_exec [-u 用户] 命令 [参数...]
chroot_exec()
{
    # 清理可能干扰容器执行的环境变量
    unset TMP TEMP TMPDIR LD_DEBUG
    local path="${PATH}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    local username=""
    # 构建期 statx 兼容层注入开关：仅当部署流程把 shim 装进容器后才生效
    local shim_lib="/usr/lib/linuxdeploy/statx-shim.so"
    local shim_preload=""
    if [ -e "${CHROOT_DIR}${shim_lib}" ]; then
        shim_preload="LD_PRELOAD=${shim_lib}"
    fi
    if [ "$1" = "-u" ]; then
        username="$2"
        shift 2
    fi
    # 本项目固定使用 chroot 方式（unshare 暂不支持）
    if [ -n "${username}" ]; then
        if [ $# -gt 0 ]; then
            if [ -n "${shim_preload}" ]; then
                # su 登录 shell 会清空环境变量，需在命令串里显式赋值
                chroot ${METHOD_OPTIONS} "${CHROOT_DIR}" /bin/su - ${username} -c "${shim_preload} $*"
            else
                chroot ${METHOD_OPTIONS} "${CHROOT_DIR}" /bin/su - ${username} -c "$*"
            fi
        else
            chroot ${METHOD_OPTIONS} "${CHROOT_DIR}" /bin/su - ${username}
        fi
    else
        if [ -n "${shim_preload}" ]; then
            # 导出变量让 chroot 内进程继承（ld.so 会在容器内解析该路径），
            # 只对本次 chroot 生效，执行完立即撤销
            export LD_PRELOAD="${shim_lib}"
            PATH="${path}" chroot ${METHOD_OPTIONS} "${CHROOT_DIR}" "$@"
            local rc=$?
            unset LD_PRELOAD
            return ${rc}
        fi
        # 用 "$@" 保持参数边界，避免命令串被二次解析。
        PATH="${path}" chroot ${METHOD_OPTIONS} "${CHROOT_DIR}" "$@"
    fi
}

################################################################################
# 参数系统
################################################################################

# 从配置文件读取参数到当前环境（KEY="value" 形式）
params_read()
{
    local conf_file="$1"
    [ -e "${conf_file}" ] || return 1
    local item key val
    while read item
    do
        # 仅接受全大写字母、数字、下划线的键名，防止注入
        key=$(echo "${item}" | grep -o '^[0-9A-Z_]\{1,32\}')
        val=${item#${key}=}
        if [ -n "${key}" ]; then
            eval ${key}="${val}"
            if [ -n "${OPTLST##* ${key} *}" ]; then
                OPTLST="${OPTLST}${key} "
            fi
        fi
    done < "${conf_file}"
}

# 将当前环境中的参数写回配置文件
params_write()
{
    local conf_file="$1"
    [ -n "${conf_file}" ] || return 1
    # 原子写：先写临时文件，再 mv 覆盖目标。
    local tmp="${conf_file}.tmp.$$"
    local key val
    {
        echo "# ${conf_file##*/} $(date '+%F %R')"
        for key in ${OPTLST}
        do
            eval "val=\$${key}"
            if [ -n "${key}" ] && [ -n "${val}" ]; then
                echo "${key}=\"${val}\""
            fi
        done
    } > "${tmp}"
    mv -f "${tmp}" "${conf_file}"
}

# 解析命令行参数：--key=value 或 --key（布尔）
params_parse()
{
    OPTIND=1
    if [ $# -gt 0 ]; then
        local item key val
        for item in "$@"
        do
            key=$(expr "${item}" : '--\([0-9a-z-]\{1,32\}=\{0,1\}\)' | sed 'y/-abcdefghijklmnopqrstuvwxyz/_ABCDEFGHIJKLMNOPQRSTUVWXYZ/')
            if [ -n "${key##*=*}" ]; then
                val="true"
            else
                key=${key%*=}
                val=$(expr "${item}" : '--[0-9a-z-]\{1,32\}=\(.*\)')
            fi
            if [ -n "${key}" ]; then
                eval ${key}=\"${val}\"
                OPTIND=$((OPTIND+1))
                if [ -n "${OPTLST##* ${key} *}" ]; then
                    OPTLST="${OPTLST}${key} "
                fi
            fi
        done
    fi
}

# 检查必填参数是否齐全
params_check()
{
    local params_list="$@"
    local key val params_lost
    for key in ${params_list}
    do
        eval "val=\$${key}"
        if [ -z "${val}" ]; then
            params_lost="${params_lost} ${key}"
        fi
    done
    if [ -n "${params_lost}" ]; then
        msg "缺少必要参数:${params_lost}"
        return 1
    fi
    return 0
}

################################################################################
# 配置系统（4.0：切换即锁定）
################################################################################

# 当前配置持久化文件路径
config_current_file()
{
    echo "${CONFIG_DIR}/.current"
}

# 读取当前配置名（锁定机制核心）
# 优先读 .current；若无效则回退到第一个存在的配置
config_current()
{
    local cur
    if [ -f "${CONFIG_DIR}/.current" ]; then
        cur=$(cat "${CONFIG_DIR}/.current" 2>/dev/null)
        if [ -n "${cur}" ] && [ -f "${CONFIG_DIR}/${cur}.conf" ]; then
            echo "${cur}"
            return 0
        fi
    fi
    # 回退：取配置目录中第一个 .conf
    local f
    for f in "${CONFIG_DIR}"/*.conf; do
        [ -e "${f}" ] || continue
        echo "$(basename "${f}" .conf)"
        return 0
    done
    return 1
}

# 写入当前配置名（切换即锁定）
config_set_current()
{
    local name="$1"
    [ -n "${name}" ] || return 1
    echo "${name}" > "${CONFIG_DIR}/.current"
}

# 校验配置名并返回配置文件路径
config_file_of()
{
    local name="$1"
    [ -n "${name}" ] || return 1
    if [ -n "${name##*/*}" ]; then
        echo "${CONFIG_DIR}/${name}.conf"
    else
        echo "${name}"
    fi
}

# 配置是否存在
config_exists()
{
    conf_file
    local conf_file=$(config_file_of "$1")
    [ -e "${conf_file}" ]
}

# 获取当前配置的 CHROOT_DIR 默认值（隔离目录）
config_default_chroot_dir()
{
    # 容器目录默认放在项目 builds 目录下（与源码、缓存、日志分离）
    echo "${BUILD_DIR:-${ENV_DIR}/mnt}/$1"
}

# 列出全部配置
config_list()
{
    local conf
    local count=0
    # --json 模式：输出机器可读列表（前端解析用）
    if [ "${JSON_MODE}" = "true" ]; then
        local first="true"
        local name distrib arch suite chroot_dir target_type
        printf '{\n'
        printf '  "current": "%s",\n' "$(json_escape "$(config_current)")"
        printf '  "configs": [\n'
        for conf in "${CONFIG_DIR}"/*.conf; do
            [ -e "${conf}" ] || continue
            # .conf 行形如 KEY="value"（前端/CLI 写入）或 KEY=value（手写），
            # 统一去掉 KEY= 前缀与可选引号（cut -d'"' 对无引号值会输出整行）
            name=$(basename "${conf}" .conf)
            distrib=$(grep '^DISTRIB=' "${conf}" | head -1 | sed -e 's/^[^=]*=//' -e 's/^"//' -e 's/"$//')
            arch=$(grep '^ARCH=' "${conf}" | head -1 | sed -e 's/^[^=]*=//' -e 's/^"//' -e 's/"$//')
            suite=$(grep '^SUITE=' "${conf}" | head -1 | sed -e 's/^[^=]*=//' -e 's/^"//' -e 's/"$//')
            chroot_dir=$(grep '^CHROOT_DIR=' "${conf}" | head -1 | sed -e 's/^[^=]*=//' -e 's/^"//' -e 's/"$//')
            target_type=$(grep '^TARGET_TYPE=' "${conf}" | head -1 | sed -e 's/^[^=]*=//' -e 's/^"//' -e 's/"$//')
            if [ "${first}" = "true" ]; then first="false"; else printf ',\n'; fi
            printf '    {"name": "%s", "distrib": "%s", "arch": "%s", "suite": "%s", "chroot_dir": "%s", "target_type": "%s"}' \
                "$(json_escape "${name}")" "$(json_escape "${distrib}")" "$(json_escape "${arch}")" \
                "$(json_escape "${suite}")" "$(json_escape "${chroot_dir}")" "$(json_escape "${target_type}")"
        done
        printf '\n  ]\n}\n'
        return 0
    fi
    msg "配置列表:"
    msg "--------------------------------------------------------------"
    msg "名称              发行版      架构      代号      容器目录"
    for conf in "${CONFIG_DIR}"/*.conf; do
        [ -e "${conf}" ] || continue
        count=$((count+1))
        (
            unset DISTRIB ARCH SUITE CHROOT_DIR INCLUDE
            . "${conf}"
            msg "$(printf "%-18s %-10s %-8s %-8s %s" "$(basename "${conf}" .conf)" "${DISTRIB}" "${ARCH}" "${SUITE}" "${CHROOT_DIR}")"
        )
    done
    msg "--------------------------------------------------------------"
    msg "共 ${count} 个配置；当前配置: $(config_current)"
}

# 显示配置详情（默认当前配置）
config_show()
{
    local name="$1"
    if [ -z "${name}" ]; then
        name=$(config_current)
        [ -n "${name}" ] || { msg "尚无任何配置，请先执行: config create <名称>"; return 1; }
    fi
    conf_file
    local conf_file=$(config_file_of "${name}")
    [ -e "${conf_file}" ] || { msg "配置不存在: ${name}"; return 1; }
    # --json 模式：输出配置键值对（前端解析用）
    if [ "${JSON_MODE}" = "true" ]; then
        local first="true"
        local line key val
        printf '{\n'
        printf '  "name": "%s",\n' "$(json_escape "${name}")"
        printf '  "config": {\n'
        while read line
        do
            case "${line}" in
            \#*|"") continue ;;
            esac
            key=$(echo "${line}" | grep -o '^[0-9A-Z_]\{1,32\}')
            [ -n "${key}" ] || continue
            val=${line#${key}=}
            val=${val#\"}
            val=${val%\"}
            if [ "${first}" = "true" ]; then first="false"; else printf ',\n'; fi
            printf '    "%s": "%s"' "${key}" "$(json_escape "${val}")"
        done < "${conf_file}"
        printf '\n  }\n}\n'
        return 0
    fi
    msg "配置名称: ${name}"
    msg "配置文件: ${conf_file}"
    msg "----------------------------------------"
    cat "${conf_file}"
    msg "----------------------------------------"
    # 标记当前配置
    if [ "${name}" = "$(config_current)" ]; then
        msg "[当前使用]"
    fi
}

# 切换当前配置（锁定）
config_use()
{
    local name="$1"
    [ -n "${name}" ] || { msg "用法: config use <配置名称>"; return 1; }
    config_exists "${name}" || { msg "配置不存在: ${name}"; return 1; }
    config_set_current "${name}"
    msg "已切换到配置: ${name}"
}

# 新建配置（自动切换并锁定）
# 用法: config create <名称> [--key=value ...]
config_create()
{
    local name="$1"
    [ -n "${name}" ] || { msg "用法: config create <配置名称> [--key=value ...]"; return 1; }
    # 名称合法性检查：仅允许字母数字下划线连字符
    case "${name}" in
    *[!a-zA-Z0-9_-]*)
        msg "配置名称只能包含字母、数字、下划线和连字符: ${name}"
        return 1
    ;;
    esac
    local conf_file
    conf_file=$(config_file_of "${name}")
    [ -e "${conf_file}" ] && { msg "配置已存在: ${name}"; return 1; }

    # 重置全部配置键，避免继承当前配置的旧值
    unset DISTRIB ARCH SUITE INCLUDE METHOD CHROOT_DIR TARGET_PATH SOURCE_PATH USER_NAME USER_PASSWORD EXTRA_PACKAGES USER_GROUPS MOUNTS SSH_PORT GRAPHICS TARGET_TYPE DISK_SIZE FS_TYPE DNS LOCALE HOST_NAME INIT INIT_LEVEL INIT_PATH
    # 先解析用户传入的参数（进入当前变量与 OPTLST）
    local OPTLST=" "
    params_parse "$@"
    # 记录用户是否显式指定了容器目录（此时仅命令行参数会写入 CHROOT_DIR）
    local user_chroot_dir="${CHROOT_DIR:-}"

    # 确保默认键都在参数表中（用户传入的键已存在）
    local key
    for key in DISTRIB ARCH SUITE INCLUDE METHOD CHROOT_DIR TARGET_PATH USER_NAME USER_PASSWORD USER_GROUPS MOUNTS SSH_PORT GRAPHICS TARGET_TYPE DISK_SIZE FS_TYPE INIT INIT_LEVEL
    do
        if [ -n "${OPTLST##* ${key} *}" ]; then
            OPTLST="${OPTLST}${key} "
        fi
    done

    # 默认参数（仅当用户未指定时生效）
    DISTRIB="${DISTRIB:-debian}"
    ARCH="${ARCH:-arm64}"
    SUITE="${SUITE:-trixie}"
    # 默认 INCLUDE 含 init 组件：启动容器时执行初始化脚本（默认 SysV，见 INIT 键）
    INCLUDE="${INCLUDE:-bootstrap init}"
    METHOD="${METHOD:-chroot}"
    # 初始化系统默认 SysV：start 时执行 /etc/rc3.d/ 的 S 脚本（Debian 系布局）
    INIT="${INIT:-sysv}"
    INIT_LEVEL="${INIT_LEVEL:-3}"
    USER_NAME="${USER_NAME:-root}"
    USER_PASSWORD="${USER_PASSWORD:-changeme}"
    USER_GROUPS="${USER_GROUPS:-aid_inet aid_sdcard_rw aid_graphics}"
    MOUNTS="${MOUNTS:-}"
    SSH_PORT="${SSH_PORT:-22}"
    GRAPHICS="${GRAPHICS:-}"
    # 安装方式：directory（目录）/ file（镜像文件）/ ram / partition。
    # 前端“镜像安装”映射为 file；FS_TYPE 默认 ext4（与前端“ext4 镜像”文案一致）。
    TARGET_TYPE="${TARGET_TYPE:-directory}"
    DISK_SIZE="${DISK_SIZE:-}"
    FS_TYPE="${FS_TYPE:-ext4}"
    # 未显式指定容器目录时，自动分配独立隔离目录（防止共享目录事故）
    if [ -z "${user_chroot_dir}" ]; then
        CHROOT_DIR=$(config_default_chroot_dir "${name}")
    fi
    # TARGET_PATH 默认与容器目录一致（rootfs 组件依赖此检查）
    TARGET_PATH="${TARGET_PATH:-${CHROOT_DIR}}"
    params_write "${conf_file}"

    # 创建后立即切换锁定
    config_set_current "${name}"
    msg "已创建配置: ${name}"
    msg "容器目录: ${CHROOT_DIR}"
    msg "注意: 新配置已自动切换并锁定，后续命令均作用于该配置。"
}

# 编辑当前配置参数
# 用法: config edit [--key=value ...]
config_edit()
{
    local name
    local name=$(config_current)
    [ -n "${name}" ] || { msg "尚无当前配置，请先执行: config create <名称>"; return 1; }
    conf_file
    local conf_file=$(config_file_of "${name}")
    [ -e "${conf_file}" ] || { msg "配置文件缺失: ${conf_file}"; return 1; }

    # 保存编辑前的配置备份
    cp "${conf_file}" "${conf_file}.bak"

    # 读取现有配置，再合并新参数
    local OPTLST=" "
    params_read "${conf_file}"
    params_parse "$@"
    params_write "${conf_file}"

    msg "已更新配置: ${name}"
}

# 复制配置
# 用法: config copy <源名称> <新名称>
config_copy()
{
    local src="$1"
    local dst="$2"
    [ -n "${src}" ] && [ -n "${dst}" ] || { msg "用法: config copy <源名称> <新名称>"; return 1; }
    config_exists "${src}" || { msg "源配置不存在: ${src}"; return 1; }
    config_exists "${dst}" && { msg "目标配置已存在: ${dst}"; return 1; }
    src_file dst_file
    local src_file=$(config_file_of "${src}")
    local dst_file=$(config_file_of "${dst}")
    cp "${src_file}" "${dst_file}"
    msg "已复制配置: ${src} -> ${dst}"
}

# 删除配置
# 用法: config delete <名称> [--purge]
# 默认仅删除配置；--purge 连同容器目录一起删除（需二次确认）
config_delete()
{
    local name="$1"
    [ -n "${name}" ] || { msg "用法: config delete <配置名称> [--purge]"; return 1; }
    config_exists "${name}" || { msg "配置不存在: ${name}"; return 1; }
    local purge="false"
    if [ "$2" = "--purge" ]; then
        purge="true"
    fi

    # 读取该配置的容器目录
    conf_file chroot_dir
    local conf_file=$(config_file_of "${name}")
    local chroot_dir=""
    if [ -e "${conf_file}" ]; then
        eval $(grep '^CHROOT_DIR=' "${conf_file}")
    fi

    msg "即将删除配置: ${name}"
    msg "配置文件: ${conf_file}"
    if [ "${purge}" = "true" ]; then
        msg "警告: --purge 将连同容器目录一起删除: ${CHROOT_DIR}"
        confirm_yes "确认删除配置及容器目录？" || { msg "已取消。"; return 1; }
        if [ -n "${CHROOT_DIR}" ] && [ -d "${CHROOT_DIR}" ]; then
            rm -rf "${CHROOT_DIR}"
            msg "已删除容器目录: ${CHROOT_DIR}"
        fi
    else
        confirm_yes "确认删除该配置（容器目录数据保留）？" || { msg "已取消。"; return 1; }
    fi
    rm -f "${conf_file}"
    msg "已删除配置: ${name}"

    # 若删除的是当前配置，自动切换到剩余配置或清空锁定
    if [ "${name}" = "$(config_current)" ]; then
        local next
        next=$(config_current)
        if [ -n "${next}" ]; then
            config_set_current "${next}"
            msg "当前配置已自动切换为: ${next}"
        else
            rm -f "${CONFIG_DIR}/.current"
            msg "已无可用配置，当前配置锁定已清空。"
        fi
    fi
}

# 导出当前配置到文件
# 用法: config export <文件>
config_export()
{
    local out_file="$1"
    [ -n "${out_file}" ] || { msg "用法: config export <导出文件>"; return 1; }
    name conf_file
    local name=$(config_current)
    [ -n "${name}" ] || { msg "尚无当前配置。"; return 1; }
    local conf_file=$(config_file_of "${name}")
    [ -e "${conf_file}" ] || { msg "配置文件缺失: ${conf_file}"; return 1; }
    cp "${conf_file}" "${out_file}"
    msg "已导出配置 ${name} 到: ${out_file}"
}

# 导入配置（从文件或已有配置名复制）
# 用法: config import <文件|名称>
config_import()
{
    local src="$1"
    local dst="$2"
    [ -n "${src}" ] || { msg "用法: config import <配置文件|配置名称> [新名称]"; return 1; }

    src_file
    if [ -f "${src}" ]; then
        src_file="${src}"
    elif config_exists "${src}"; then
        src_file=$(config_file_of "${src}")
    else
        msg "导入源不存在: ${src}"
        return 1
    fi

    # 目标名称：显式指定，或从文件名推导
    if [ -z "${dst}" ]; then
        dst=$(basename "${src_file}" .conf)
    fi
    dst_file
    local dst_file=$(config_file_of "${dst}")
    [ -e "${dst_file}" ] && { msg "目标配置已存在: ${dst}"; return 1; }

    # 复制并确保容器目录隔离
    cp "${src_file}" "${dst_file}"
    # 若导入的配置未指定 CHROOT_DIR 或与已有配置冲突，重新分配独立目录
    local imported_dir
    local imported_dir=$(grep '^CHROOT_DIR=' "${dst_file}" | head -1 | sed -e 's/^[^=]*=//' -e 's/^"//' -e 's/"$//')
    if [ -z "${imported_dir}" ]; then
        sed -i "s|^CHROOT_DIR=.*|CHROOT_DIR=\"$(config_default_chroot_dir "${dst}")\"|" "${dst_file}"
        imported_dir=$(config_default_chroot_dir "${dst}")
        msg "容器目录: ${imported_dir}"
    fi

    # 导入后自动切换锁定
    config_set_current "${dst}"
    msg "已导入配置: ${dst}"
    msg "注意: 已自动切换并锁定到新配置。"
}

################################################################################
# 组件系统
################################################################################

# 判断组件是否与当前发行版/架构/代号兼容
component_is_compatible()
{
    local target="$@"
    [ -n "${target}" ] || return 0
    local item
    for item in ${target}
    do
        case "${DISTRIB}:${ARCH}:${SUITE}" in
        ${item})
            return 0
        ;;
        esac
    done
    return 1
}

# 判断组件是否被排除（deploy -n 指定）
component_is_exclude()
{
    local component="$1"
    [ -n "${component}" ] || return 1
    local item
    for item in ${EXCLUDE_COMPONENTS}
    do
        case "${component}" in
        ${item}*)
            return 0
        ;;
        esac
    done
    return 1
}

# 递归解析组件依赖，按依赖顺序输出组件列表
component_depends()
{
    local components="$@"
    [ -n "${components}" ] || return 0
    # 递归解析必须全部用 local，否则内层调用会覆盖外层循环变量 component
    local component conf_file TARGET DEPENDS
    for component in ${components}
    do
        component="${component%/}"
        # 防止循环依赖
        [ -z "${IGNORE_DEPENDS##* ${component} *}" ] && continue
        IGNORE_DEPENDS="${IGNORE_DEPENDS}${component} "
        # 组件必须存在
        conf_file="${INCLUDE_DIR}/${component}/deploy.conf"
        [ -e "${conf_file}" ] || continue
        # 读取组件声明
        eval $(grep -e '^TARGET=' -e '^DEPENDS=' "${conf_file}")
        # 兼容性检查
        if [ "${WITHOUT_CHECK}" != "true" ]; then
            component_is_compatible ${TARGET} || continue
        fi
        if [ "${REVERSE_DEPENDS}" = "true" ]; then
            # 反向依赖模式（帮助场景）
            echo ${component}
            component_depends ${DEPENDS}
        else
            # 先处理依赖，再输出自身（拓扑顺序）
            component_depends ${DEPENDS}
            echo ${component}
        fi
    done
}

# 执行组件动作（安装/配置/启动/停止/状态）
component_exec()
{
    local components="$@"
    if [ "${WITHOUT_DEPENDS}" != "true" ]; then
        components=$(IGNORE_DEPENDS=" " component_depends ${components})
    fi
    [ -n "${components}" ] || return 1
    (set -e
        for COMPONENT in ${components}
        do
            COMPONENT_DIR="${INCLUDE_DIR}/${COMPONENT}"
            [ -d "${COMPONENT_DIR}" ] || continue
            # 重置组件声明变量
            unset NAME DESC TARGET PARAMS DEPENDS EXTENDS
            TARGET='*:*:*'
            # 读取组件配置
            . "${COMPONENT_DIR}/deploy.conf"
            # 提供空动作默认实现
            do_install() { return 0; }
            do_configure() { return 0; }
            do_start() { return 0; }
            do_stop() { return 0; }
            do_status() { return 0; }
            do_help() { return 0; }
            # 加载组件自身及扩展脚本
            for component in ${EXTENDS} ${COMPONENT}
            do
                if [ -e "${INCLUDE_DIR}/${component}/deploy.sh" ]; then
                    . "${INCLUDE_DIR}/${component}/deploy.sh"
                fi
            done
            # 排除检查
            component_is_exclude ${COMPONENT} && continue
            # 参数完整性检查
            [ "${WITHOUT_CHECK}" != "true" ] && params_check ${PARAMS}
            # 执行动作
            [ "${DEBUG_MODE}" = "true" ] && msg "## ${COMPONENT} : ${DO_ACTION}"
            set +e
            eval ${DO_ACTION} || exit 1
            set -e
        done
    exit 0)
    is_ok || return 1
}

# 列出组件（默认仅当前发行版兼容的）
component_list()
{
    local components="$@"
    local component output DESC
    if [ -z "${components}" ]; then
        components=$(cd "${INCLUDE_DIR}" && find . -type f -name "deploy.conf" | while read f
            do
                component="${f%/*}"
                component="${component#*/}"
                echo "${component}"
            done)
    fi
    local components=$(IGNORE_DEPENDS=" " component_depends ${components} | sort)
    for component in $components
    do
        DESC=''
        eval $(grep '^DESC=' "${INCLUDE_DIR}/${component}/deploy.conf")
        output=$(printf "%-30s %.49s\n" "${component}" "${DESC}")
        msg "${output}"
    done
}

# 返回组件所在目录
component_dir()
{
    echo "${INCLUDE_DIR}/$1"
}

################################################################################
# 容器系统
################################################################################

# 判断容器是否已挂载
container_mounted()
{
    is_mounted "${CHROOT_DIR}"
}

# 文件系统检查（仅对 file/partition 类型的容器有效）
fs_check()
{
    if is_mounted "${CHROOT_DIR}"; then
        return 1
    fi
    local checkfs=$(which e2fsck)
    if [ -z "${checkfs}" ]; then
        return 1
    fi
    case "${TARGET_TYPE}" in
    file|partition)
        ${checkfs} -p "${TARGET_PATH}" >/dev/null
        return 0
    ;;
    esac
    return 1
}

# 解析大小字符串为字节数（支持整数 K/M/G/T，不区分大小写）
# 用法: parse_size_bytes 4G → 4294967296；解析失败返回非 0
parse_size_bytes()
{
    local s="$1" num unit
    [ -n "${s}" ] || return 1
    s=$(printf '%s' "${s}" | tr '[:lower:]' '[:upper:]')
    case "${s}" in
    *K) num="${s%K}"; unit=1024 ;;
    *M) num="${s%M}"; unit=$((1024*1024)) ;;
    *G) num="${s%G}"; unit=$((1024*1024*1024)) ;;
    *T) num="${s%T}"; unit=$((1024*1024*1024*1024)) ;;
    *) num="${s}"; unit=1 ;;
    esac
    # 只保留整数部分（4.5G 这类小数暂不支持，直接截断）
    num=$(printf '%s' "${num}" | tr -cd '0-9')
    [ -n "${num}" ] && [ "${num}" -gt 0 ] 2>/dev/null || return 1
    echo $(( num * unit ))
}

# 调整 ext2/3/4 磁盘镜像大小（仅支持 TARGET_TYPE=file 的镜像安装）
# 流程：停止容器并卸载 → e2fsck 检查 → 扩容(先扩文件再扩文件系统)/
# 缩容(先缩文件系统再裁剪文件) → 复检 → 同步写回配置 DISK_SIZE。
# 用法: image_resize <新大小>，例如 8G / 512M
image_resize()
{
    local new_size="$1" new_bytes cur_bytes new_mb
    new_bytes=$(parse_size_bytes "${new_size}")
    if [ -z "${new_bytes}" ]; then
        msg "无法解析大小: ${new_size}（示例: 512M / 8G）"
        return 1
    fi
    if [ "${TARGET_TYPE}" != "file" ]; then
        msg "当前配置不是镜像安装（TARGET_TYPE=${TARGET_TYPE:-未设置}），无需调整镜像大小"
        return 1
    fi
    if [ ! -f "${TARGET_PATH}" ]; then
        msg "镜像文件不存在: ${TARGET_PATH}（部署后才会生成）"
        return 1
    fi
    cur_bytes=$(stat -c %s "${TARGET_PATH}" 2>/dev/null)
    if [ -z "${cur_bytes}" ]; then
        msg "无法读取镜像大小: ${TARGET_PATH}"
        return 1
    fi
    if [ "${cur_bytes}" -eq "${new_bytes}" ]; then
        msg "镜像大小已是 ${new_size}，无需调整"
        return 0
    fi

    # 调整前先停止容器并卸载挂载（避免文件系统忙）
    if container_mounted; then
        msg "正在停止容器并卸载挂载 ..."
        container_stop || return 1
    fi

    # 调整前检查文件系统
    msg -n "正在检查文件系统 ... "
    if command -v e2fsck >/dev/null 2>&1; then
        if e2fsck -fy "${TARGET_PATH}" >/dev/null 2>&1; then
            msg "完成"
        else
            msg "失败（文件系统异常，建议先手动修复）"
            return 1
        fi
    else
        msg "跳过（未找到 e2fsck）"
    fi

    # resize2fs 裸数字按“块”解释，统一换算成 MB 后缀，避免歧义
    new_mb=$(( new_bytes / 1048576 ))
    [ "${new_mb}" -gt 0 ] || { msg "新大小过小（至少 1M）"; return 1; }

    if [ "${new_bytes}" -gt "${cur_bytes}" ]; then
        # 扩容：先扩大镜像文件，再扩大文件系统
        msg -n "正在扩容镜像文件 ... "
        truncate -s "${new_bytes}" "${TARGET_PATH}" || { msg "失败"; return 1; }
        msg "完成"
        msg -n "正在扩容文件系统 ... "
        resize2fs "${TARGET_PATH}" "${new_mb}M" || { msg "失败"; return 1; }
        msg "完成"
    else
        # 缩容：先缩小文件系统，再裁剪镜像文件
        msg -n "正在缩小文件系统 ... "
        resize2fs "${TARGET_PATH}" "${new_mb}M" || { msg "失败"; return 1; }
        msg "完成"
        msg -n "正在裁剪镜像文件 ... "
        truncate -s "${new_bytes}" "${TARGET_PATH}" || { msg "失败"; return 1; }
        msg "完成"
    fi

    # 调整后复检
    msg -n "正在复检文件系统 ... "
    if command -v e2fsck >/dev/null 2>&1; then
        if e2fsck -fy "${TARGET_PATH}" >/dev/null 2>&1; then
            msg "完成"
        else
            msg "警告（文件系统异常，请尽快备份数据）"
        fi
    else
        msg "跳过（未找到 e2fsck）"
    fi

    # 同步写回配置，避免下次部署按旧大小重建镜像
    params_parse --disk-size=${new_mb}
    params_write "${CONF_FILE}"
    msg "镜像已调整为: ${new_size}（${new_mb} MB）"
    return 0
}

# 挂载单个分区/虚拟文件系统
mount_part()
{
    case "$1" in
    root)
        msg -n "/ ... "
        if ! is_mounted "${CHROOT_DIR}" ; then
            [ -d "${CHROOT_DIR}" ] || mkdir -p "${CHROOT_DIR}"
            local mnt_opts
            # 目录型容器使用 bind 挂载，文件型容器直接挂载
            [ -d "${TARGET_PATH}" ] && mnt_opts="bind" || mnt_opts="rw,relatime"
            mount -o ${mnt_opts} "${TARGET_PATH}" "${CHROOT_DIR}" &&
            mount -o remount,exec,suid,dev "${CHROOT_DIR}"
            is_ok "失败" "完成" || return 1
        else
            msg "跳过"
        fi
    ;;
    proc)
        msg -n "/proc ... "
        local target="${CHROOT_DIR}/proc"
        if ! is_mounted "${target}" ; then
            [ -d "${target}" ] || mkdir -p "${target}"
            mount -t proc proc "${target}"
            is_ok "失败" "完成" || return 1
        else
            msg "跳过"
        fi
    ;;
    sys)
        msg -n "/sys ... "
        local target="${CHROOT_DIR}/sys"
        if ! is_mounted "${target}" ; then
            [ -d "${target}" ] || mkdir -p "${target}"
            mount -t sysfs sys "${target}"
            is_ok "失败" "完成" || return 1
        else
            msg "跳过"
        fi
    ;;
    dev)
        msg -n "/dev ... "
        local target="${CHROOT_DIR}/dev"
        if ! is_mounted "${target}" ; then
            [ -d "${target}" ] || mkdir -p "${target}"
            mount -o bind /dev "${target}"
            is_ok "失败" "完成" || return 1
        else
            msg "跳过"
        fi
    ;;
    shm)
        msg -n "/dev/shm ... "
        if ! is_mounted "/dev/shm" ; then
            [ -d "/dev/shm" ] || mkdir -p /dev/shm
            mount -o rw,nosuid,nodev,mode=1777 -t tmpfs tmpfs /dev/shm
        fi
        local target="${CHROOT_DIR}/dev/shm"
        if ! is_mounted "${target}" ; then
            mount -o bind /dev/shm "${target}"
            is_ok "失败" "完成" || return 1
        else
            msg "跳过"
        fi
    ;;
    pts)
        msg -n "/dev/pts ... "
        if ! is_mounted "/dev/pts" ; then
            [ -d "/dev/pts" ] || mkdir -p /dev/pts
            mount -o rw,nosuid,noexec,gid=5,mode=620,ptmxmode=000 -t devpts devpts /dev/pts
        fi
        local target="${CHROOT_DIR}/dev/pts"
        if ! is_mounted "${target}" ; then
            mount -o bind /dev/pts "${target}"
            is_ok "失败" "完成" || return 1
        else
            msg "跳过"
        fi
    ;;
    fd)
        if [ ! -e "/dev/fd" -o ! -e "/dev/stdin" -o ! -e "/dev/stdout" -o ! -e "/dev/stderr" ]; then
            msg -n "/dev/fd ... "
            [ -e "/dev/fd" ] || ln -s /proc/self/fd /dev/
            [ -e "/dev/stdin" ] || ln -s /proc/self/fd/0 /dev/stdin
            [ -e "/dev/stdout" ] || ln -s /proc/self/fd/1 /dev/stdout
            [ -e "/dev/stderr" ] || ln -s /proc/self/fd/2 /dev/stderr
            is_ok "失败" "完成"
        fi
    ;;
    tty)
        if [ ! -e "/dev/tty0" ]; then
            msg -n "/dev/tty ... "
            ln -s /dev/null /dev/tty0
            is_ok "失败" "完成"
        fi
    ;;
    tun)
        if [ ! -e "/dev/net/tun" ]; then
            msg -n "/dev/net/tun ... "
            [ -d "/dev/net" ] || mkdir -p /dev/net
            mknod /dev/net/tun c 10 200
            is_ok "失败" "完成"
        fi
    ;;
    binfmt_misc)
        # 支持跨架构时挂载 binfmt_misc
        multiarch_support || return 0
        local binfmt_dir="/proc/sys/fs/binfmt_misc"
        if ! is_mounted "${binfmt_dir}" ; then
            msg -n "${binfmt_dir} ... "
            mount -t binfmt_misc binfmt_misc "${binfmt_dir}"
            is_ok "失败" "完成"
        fi
    ;;
    esac

    return 0
}

# 挂载容器（默认挂载全部虚拟文件系统）
container_mount()
{
    [ "${METHOD}" = "chroot" ] || return 0

    if [ $# -eq 0 ]; then
        container_mount root proc sys dev shm pts fd tty tun binfmt_misc
        return $?
    fi

    params_check TARGET_PATH || return 1

    msg -n "检查文件系统 ... "
    fs_check
    is_ok "跳过" "完成"

    msg "正在挂载容器: "
    local item
    for item in $*
    do
        mount_part ${item} || return 1
    done

    return 0
}

# 卸载容器
container_umount()
{
    params_check TARGET_PATH || return 1
    container_mounted || { msg "容器未挂载。" ; return 0; }

    msg -n "释放资源 ... "
    local is_release=0
    local pids
    if command -v lsof >/dev/null 2>&1; then
        pids=$(get_pids)
    fi
    kill_pids ${pids}; is_ok "失败" "完成"

    msg "正在卸载分区: "
    local is_mnt=0
    local mask
    for mask in '.*' '*'
    do
        local parts=$(cat /proc/mounts | awk '{print $2}' | grep "^${CHROOT_DIR%/}/${mask}$" | sort -r)
        local part
        for part in ${parts}
        do
            local part_name=$(echo ${part} | sed "s|^${CHROOT_DIR%/}/*|/|g")
            msg -n "${part_name} ... "
            local i
            for i in 1 2 3
            do
                umount ${part} && break
                sleep 1
            done
            is_ok "失败" "完成"
            is_mnt=1
        done
    done
    [ "${is_mnt}" -eq 1 ]; is_ok " ...没有已挂载分区"

    # 释放 loop 设备
    local loop
    if command -v losetup >/dev/null 2>&1; then
        loop=$(losetup -a | grep "${TARGET_PATH%/}" | awk -F: '{print $1}')
        if [ -n "${loop}" ]; then
            msg -n "解除 loop 设备关联 ... "
            losetup -d "${loop}"
            is_ok "失败" "完成"
        fi
    fi

    return 0
}

# 查找可用的 nsenter：
# 1) PATH 中的 nsenter（Linux 宿主 util-linux；Android 10+ toybox 自带于 /system/bin）
# 2) /system/bin、/system/xbin 显式路径（部分设备 PATH 不含）
# 3) 打包 busybox 的 nsenter applet（工具链兜底，恒存在）
# 输出：可直接执行的 nsenter 命令串；找不到返回非 0。
nsenter_bin()
{
    local c
    if command -v nsenter >/dev/null 2>&1; then
        command -v nsenter
        return 0
    fi
    for c in /system/bin/nsenter /system/xbin/nsenter "${ENV_DIR}/tools/busybox"; do
        if [ -x "${c}" ]; then
            if [ "${c##*/}" = "busybox" ]; then
                echo "${c} nsenter"
            else
                echo "${c}"
            fi
            return 0
        fi
    done
    return 1
}

# 跨命名空间执行：当前命名空间看不到容器挂载，但 ldstatus 判定容器运行中
# （app 重启后新 su 会话处于不同挂载命名空间，容器挂载所在 ns 由 ldstatus
# 常驻进程等持有）。通过 nsenter 切入容器 ns 后重新执行本命令。
# 前置判定：ldstatus 存活（跨 ns 的 pid+进程名校验）确认容器确在运行才切入，
# 避免 pid 复用/误判时钻进无关进程的命名空间。
container_nsenter_run()
{
    [ "${LD_NSENTER}" = "1" ] && return 1    # 已切入，防递归
    ldstatus_running || return 1             # 容器必须确认为运行中
    local ld_pid ns_bin
    ld_pid=$(cat "${CHROOT_DIR}/run/ldstatus/pid" 2>/dev/null | tr -cd '0-9')
    [ -n "${ld_pid}" ] || return 1
    ns_bin=$(nsenter_bin) || { msg "[警告] 容器运行在其它挂载命名空间，但未找到可用的 nsenter"; return 1; }
    msg "容器挂载位于其它命名空间（ldstatus pid ${ld_pid}），正在切入命名空间执行 ..."
    # 显式 sh 解释器执行：直接 exec 脚本会因 shebang #!/bin/sh 在目标命名空间
    # 解析不到而 ENOENT（真机实测）；-c 显式带当前配置名（配置锁），不依赖
    # .current 兜底；仅影响跨命名空间回退分支，正常 stop/umount 路径不变。
    LD_NSENTER=1 ${ns_bin} -t ${ld_pid} -m -- sh "${ENV_DIR}/cli.sh" -c "${CURRENT_CONF}" "$@" </dev/null
    return $?
}

# 启动容器：自动挂载系统文件（proc/sys/dev 等）后执行组件启动。
# 自定义挂载点（MOUNTS 的 "源:目标"）由 core/mnt 组件的 do_start 负责挂载，
# 因此 start 一步到位，不需要像原版那样先手动 mount。
container_start()
{
    # 未挂载则自动挂载；根已挂载但 /proc 缺失时补齐（如部署后直接 start）
    if ! container_mounted; then
        # 跨命名空间场景：ldstatus 判定容器运行中但当前 ns 看不到挂载
        # （app 重启后 su 会话命名空间变化）→ 不重复挂载/启动，提示先 stop
        # （stop 会自动切入容器命名空间执行）。start 不做 nsenter（保持简单）。
        if ldstatus_running; then
            msg "[提示] 容器运行在其它挂载命名空间中，启动已跳过；请执行 stop 停止（将自动切入命名空间）"
            return 0
        fi
        container_mount || return 1
    elif ! is_mounted "${CHROOT_DIR}/proc"; then
        container_mount >/dev/null 2>&1 || true
    fi

    local DO_ACTION='do_start'
    if [ $# -gt 0 ]; then
        component_exec "$@" || msg "[警告] 部分组件启动失败（容器已启动，可进入终端检查）"
    else
        component_exec "${INCLUDE}" || msg "[警告] 部分组件启动失败（容器已启动，可进入终端检查）"
    fi
    return 0
}

# 停止容器：执行组件停止后自动卸载全部挂载，一步到位。
container_stop()
{
    if ! container_mounted; then
        # 跨命名空间场景：当前 ns 看不到挂载但容器运行中 → 切入容器 ns 后
        # 重跑 stop（nsenter 实例中挂载可见，可正常停组件并卸载）
        if [ "${LD_NSENTER}" != "1" ] && container_nsenter_run stop "$@"; then
            return 0
        fi
        msg "容器未挂载，无需停止。"
        return 0
    fi

    local DO_ACTION='do_stop'
    if [ $# -gt 0 ]; then
        component_exec "$@"
    else
        component_exec "${INCLUDE}"
    fi
    local rc=$?

    # 停止后自动卸载（不学原版的分步复杂化）
    if container_mounted >/dev/null 2>&1; then
        msg "正在卸载容器挂载 ..."
        container_umount
    fi

    return ${rc}
}

# 进入容器 shell
container_shell()
{
    # 已挂载（运行中）的容器直接进入，不重复挂载、不重复启动组件；
    # 未挂载才挂载 root 并启动 core 组件（用户要求：先启动系统再直接 chroot 登录）
    if ! container_mounted; then
        container_mount || return 1
        local DO_ACTION='do_start'
        component_exec core
    fi

    local USER="root"
    # 按真实文件探测容器内 shell（排除符号链接），缺失则退回 sh/ash。
    local SHELL=""
    local candidate
    for candidate in /bin/bash /usr/bin/bash /bin/sh /usr/bin/sh /bin/dash /usr/bin/dash /bin/ash /usr/bin/ash
    do
        if [ -f "${CHROOT_DIR}${candidate}" ] && [ ! -L "${CHROOT_DIR}${candidate}" ] && [ -x "${CHROOT_DIR}${candidate}" ]; then
            SHELL="${candidate}"
            break
        fi
    done
    if [ -z "${SHELL}" ]; then
        msg "[错误] 容器内未找到可用 shell（bash/sh 均缺失），容器不完整，请重新部署"
        return 1
    fi
    # 动态链接 shell 依赖动态链接器（ld-linux），缺失则判定容器不完整
    local ld_found=""
    local ld_cand
    for ld_cand in "${CHROOT_DIR}"/lib/ld-linux* "${CHROOT_DIR}"/usr/lib/ld-linux* "${CHROOT_DIR}"/lib64/ld-linux*
    do
        [ -e "${ld_cand}" ] && ld_found="yes" && break
    done
    if [ -z "${ld_found}" ]; then
        msg "[错误] 容器内缺少动态链接器（ld-linux），bash 无法执行，请重新部署"
        return 1
    fi
    local HOME="$(user_home ${USER})"
    [ -n "${TERM}" ] || TERM="linux"
    [ -n "${PS1}" ] || PS1="\u@\h:\w\\$ "
    export USER SHELL HOME TERM PS1

    if [ -e "${CHROOT_DIR}/etc/motd" ]; then
        msg $(cat "${CHROOT_DIR}/etc/motd")
    fi

    # 支持 shell -c "命令"：chroot 本身不识别 -c，需显式转交给容器内 shell
    if [ "${1}" = "-c" ]; then
        shift
        chroot_exec "${SHELL}" -c "$*" 2>&1
    else
        chroot_exec "${SHELL}" 2>&1
    fi

    return $?
}

################################################################################
# rootfs 导入 / 导出
################################################################################

# 导入 rootfs 归档到当前容器
# 支持格式：tar, tar.gz, tar.bz2, tar.xz, tar.zst
rootfs_import()
{
    local rootfs_file="$1"
    [ -n "${rootfs_file}" ] || { msg "用法: import <归档文件|URL>"; return 1; }

    container_mounted || container_mount root || return 1

    msg -n "正在导入 rootfs ... "
    local ok="false"
    case "${rootfs_file}" in
    *tar|*tar.gz|*tgz|*tar.bz2|*tbz2|*tar.xz|*txz|*tar.zst|*tzst)
        if [ -e "${rootfs_file}" ]; then
            tar axf "${rootfs_file}" -C "${CHROOT_DIR}" && ok="true"
        elif [ -z "${rootfs_file##http*}" ]; then
            wget -q -O - "${rootfs_file}" | tar ax -C "${CHROOT_DIR}" && ok="true"
        fi
    ;;
    *gz)
        if [ -e "${rootfs_file}" ]; then
            tar axf "${rootfs_file}" -C "${CHROOT_DIR}" && ok="true"
        elif [ -z "${rootfs_file##http*}" ]; then
            wget -q -O - "${rootfs_file}" | tar xz -C "${CHROOT_DIR}" && ok="true"
        fi
    ;;
    *bz2)
        if [ -e "${rootfs_file}" ]; then
            tar axf "${rootfs_file}" -C "${CHROOT_DIR}" && ok="true"
        elif [ -z "${rootfs_file##http*}" ]; then
            wget -q -O - "${rootfs_file}" | tar xj -C "${CHROOT_DIR}" && ok="true"
        fi
    ;;
    *xz)
        if [ -e "${rootfs_file}" ]; then
            tar axf "${rootfs_file}" -C "${CHROOT_DIR}" && ok="true"
        elif [ -z "${rootfs_file##http*}" ]; then
            wget -q -O - "${rootfs_file}" | tar xJ -C "${CHROOT_DIR}" && ok="true"
        fi
    ;;
    *zst)
        if [ -e "${rootfs_file}" ]; then
            tar axf "${rootfs_file}" -C "${CHROOT_DIR}" && ok="true"
        elif [ -z "${rootfs_file##http*}" ]; then
            wget -q -O - "${rootfs_file}" | tar x --zstd -C "${CHROOT_DIR}" && ok="true"
        fi
    ;;
    *)
        msg "失败"
        msg "不支持的文件格式，仅支持 tar/tar.gz/tar.bz2/tar.xz/tar.zst。"
        return 1
    ;;
    esac

    if [ "${ok}" = "true" ]; then
        msg "完成"
        return 0
    else
        msg "失败"
        return 1
    fi
}

# 导出当前容器为 rootfs 归档
# 支持格式：gz, bz2, xz, zst
rootfs_export()
{
    local rootfs_file="$1"
    [ -n "${rootfs_file}" ] || { msg "用法: export <归档文件>"; return 1; }

    container_mounted || container_mount root || return 1

    msg -n "正在导出 rootfs ... "
    local ok="false"
    case "${rootfs_file}" in
    *gz)
        tar acf "${rootfs_file}" --exclude='./dev' --exclude='./sys' --exclude='./proc' -C "${CHROOT_DIR}" . >/dev/null && ok="true"
    ;;
    *bz2)
        tar acf "${rootfs_file}" --exclude='./dev' --exclude='./sys' --exclude='./proc' -C "${CHROOT_DIR}" . >/dev/null && ok="true"
    ;;
    *xz)
        tar acf "${rootfs_file}" --exclude='./dev' --exclude='./sys' --exclude='./proc' -C "${CHROOT_DIR}" . >/dev/null && ok="true"
    ;;
    *zst)
        tar acf "${rootfs_file}" --exclude='./dev' --exclude='./sys' --exclude='./proc' -C "${CHROOT_DIR}" . >/dev/null && ok="true"
    ;;
    *)
        msg "失败"
        msg "不支持的文件格式，仅支持 gz/bz2/xz/zst。"
        return 1
    ;;
    esac

    if [ "${ok}" = "true" ]; then
        msg "完成"
        return 0
    else
        msg "失败"
        return 1
    fi
}

################################################################################
# 容器状态
################################################################################

# 显示容器与系统状态
container_status()
{
    # --json 模式：输出机器可读状态（前端解析用）
    if [ "${JSON_MODE}" = "true" ]; then
        local model="" android="" arch="" kernel=""
        local mem_total=0 mem_used=0 swap_total=0 swap_used=0
        local selinux=false loop=false binfmt=false mounted=false running=false
        local installed="未知" supported_fs=""
        if command -v getprop >/dev/null 2>&1; then
            model=$(getprop ro.product.model 2>/dev/null)
            android=$(getprop ro.build.version.release 2>/dev/null)
        fi
        arch=$(uname -m)
        kernel=$(uname -r)
        mem_total=$(grep ^MemTotal /proc/meminfo | awk '{print $2}')
        mem_total=$(( ${mem_total:-0}/1024 ))
        mem_used=$(( mem_total - $(grep ^MemFree /proc/meminfo | awk '{print $2}')/1024 ))
        swap_total=$(grep ^SwapTotal /proc/meminfo | awk '{print $2}')
        swap_total=$(( ${swap_total:-0}/1024 ))
        swap_used=$(( swap_total - $(grep ^SwapFree /proc/meminfo | awk '{print $2}')/1024 ))
        selinux_inactive || selinux=true
        loop_support && loop=true
        multiarch_support && binfmt=true
        supported_fs=$(printf '%s ' $(grep -v nodev /proc/filesystems | sort))
        if [ -r "${CHROOT_DIR}/etc/os-release" ]; then
            installed=$(. "${CHROOT_DIR}/etc/os-release" 2>/dev/null; printf '%s' "${PRETTY_NAME:-未知}")
        fi
        # running 判定：ldstatus 标记进程（跨命名空间）为主，挂载兜底（旧容器无标记）
        container_mounted >/dev/null 2>&1 && mounted=true
        [ "${mounted}" = "true" ] && running=true
        ldstatus_running && running=true
        printf '{\n'
        printf '  "model": "%s",\n' "$(json_escape "${model}")"
        printf '  "android": "%s",\n' "$(json_escape "${android}")"
        printf '  "arch": "%s",\n' "$(json_escape "${arch}")"
        printf '  "kernel": "%s",\n' "$(json_escape "${kernel}")"
        printf '  "mem_used_mb": %d,\n' "${mem_used}"
        printf '  "mem_total_mb": %d,\n' "${mem_total}"
        printf '  "swap_used_mb": %d,\n' "${swap_used}"
        printf '  "swap_total_mb": %d,\n' "${swap_total}"
        printf '  "selinux": %s,\n' "${selinux}"
        printf '  "loop": %s,\n' "${loop}"
        printf '  "binfmt": %s,\n' "${binfmt}"
        printf '  "fs": "%s",\n' "$(json_escape "${supported_fs}")"
        printf '  "installed": "%s",\n' "$(json_escape "${installed}")"
        printf '  "chroot_dir": "%s",\n' "$(json_escape "${CHROOT_DIR}")"
        printf '  "mounted": %s,\n' "${mounted}"
        printf '  "running": %s\n' "${running}"
        printf '}\n'
        return 0
    fi
    local model=$(which getprop >/dev/null 2>&1 && getprop ro.product.model)
    if [ -n "${model}" ]; then
        msg -n "设备型号: "
        msg "${model}"
    fi

    local android=$(which getprop >/dev/null 2>&1 && getprop ro.build.version.release)
    if [ -n "${android}" ]; then
        msg -n "Android 版本: "
        msg "${android}"
    fi

    msg -n "系统架构: "
    msg "$(uname -m)"

    msg -n "内核版本: "
    msg "$(uname -r)"

    msg -n "内存: "
    local mem_total=$(grep ^MemTotal /proc/meminfo | awk '{print $2}')
    mem_total=$((mem_total/1024))
    local mem_free=$(grep ^MemFree /proc/meminfo | awk '{print $2}')
    mem_free=$((mem_free/1024))
    msg "${mem_free}/${mem_total} MB"

    msg -n "Swap: "
    local swap_total=$(grep ^SwapTotal /proc/meminfo | awk '{print $2}')
    swap_total=$((swap_total/1024))
    local swap_free=$(grep ^SwapFree /proc/meminfo | awk '{print $2}')
    swap_free=$((swap_free/1024))
    msg "${swap_free}/${swap_total} MB"

    msg -n "SELinux: "
    selinux_inactive && msg "关闭" || msg "开启"

    msg -n "Loop 设备: "
    loop_support && msg "可用" || msg "不可用"

    msg -n "binfmt_misc 支持: "
    multiarch_support && msg "支持" || msg "不支持"

    msg -n "支持的挂载文件系统: "
    local supported_fs=$(printf '%s ' $(grep -v nodev /proc/filesystems | sort))
    msg "${supported_fs}"

    msg -n "已安装系统: "
    local linux_version=$([ -r "${CHROOT_DIR}/etc/os-release" ] && . "${CHROOT_DIR}/etc/os-release"; [ -n "${PRETTY_NAME}" ] && echo "${PRETTY_NAME}" || echo "未知")
    msg "${linux_version}"

    msg -n "运行状态: "
    local running=false mounted=false
    container_mounted >/dev/null 2>&1 && mounted=true
    [ "${mounted}" = "true" ] && running=true
    ldstatus_running && running=true
    [ "${running}" = "true" ] && msg "运行中" || msg "已停止"

    msg "组件状态: "
    local DO_ACTION='do_status'
    component_exec "${INCLUDE}"

    msg "已挂载分区: "
    local item
    for item in $(grep "${CHROOT_DIR%/}" /proc/mounts | awk '{print $2}' | sed "s|${CHROOT_DIR%/}/*|/|g")
    do
        msg "* ${item}"
    done
}

################################################################################
# 安全护栏与自检
################################################################################

# 交互确认（--yes 跳过）
# 用法: confirm_yes "提示文本" [默认值]
confirm_yes()
{
    local prompt="$1"
    local def="${2:-n}"
    [ "${YES}" = "true" ] && return 0
    [ "${def}" = "y" ] && local hint="[Y/n]" || local hint="[y/N]"
    msg -n "${prompt} ${hint} "
    local ans
    read ans
    case "${ans}" in
    y|Y|yes|YES|Yes) return 0 ;;
    n|N|no|NO|No) return 1 ;;
    "")
        [ "${def}" = "y" ] && return 0 || return 1
    ;;
    *) return 1 ;;
    esac
}

# 部署安全护栏：目标目录存在且非空时返回 1
check_target_dir()
{
    if [ -d "${CHROOT_DIR}" ] && [ -n "$(ls -A "${CHROOT_DIR}" 2>/dev/null)" ]; then
        msg "安全护栏: 目标目录非空，可能已存在系统文件: ${CHROOT_DIR}"
        return 1
    fi
    return 0
}

# 自检：环境、配置、依赖、目录权限
do_check()
{
    local fail=0
    local warn=0

    # 运行身份
    if [ "$(id -u)" = "0" ]; then
        msg "[通过] 运行身份: root"
    else
        msg "[失败] 运行身份: 非 root（需要 root 权限）"
        fail=$((fail+1))
    fi

    # 核心工具
    local tool_list="wget curl ar tar xz zstd dpkg-deb getprop"
    local missing=""
    for tool in ${tool_list}; do
        if ! command -v "${tool}" >/dev/null 2>&1; then
            missing="${missing} ${tool}"
        fi
    done
    if [ -z "${missing}" ]; then
        msg "[通过] 核心工具齐全"
    else
        msg "[警告] 缺少工具:${missing}（部分功能将不可用）"
        warn=$((warn+1))
    fi

    # wget 可用性：应指向内置静态 wget1（busybox wget applet 段错误、GNU
    # Wget2 动态版部分 ROM 异常，均不可靠）。顺带做一次连通性探测。
    local wget_bin
    wget_bin=$(command -v wget 2>/dev/null)
    if [ -n "${wget_bin}" ]; then
        msg "[通过] wget 可用: ${wget_bin}"
        if "${wget_bin}" -T 8 -t 1 -q -O /dev/null https://mirrors.ustc.edu.cn/ 2>/dev/null; then
            msg "[通过] wget 连通性正常（mirrors.ustc.edu.cn）"
        else
            msg "[警告] wget 网络连通性异常（不影响自检，请检查网络与镜像站）"
            warn=$((warn+1))
        fi
    else
        msg "[失败] 缺少 wget（下载功能不可用）"
        fail=$((fail+1))
    fi

    # debootstrap 完整性
    local db_dir="${INCLUDE_DIR}/bootstrap/debian/debootstrap"
    if [ -f "${db_dir}/debootstrap" ] && [ -f "${db_dir}/functions" ] && [ -d "${db_dir}/scripts" ]; then
        msg "[通过] debootstrap 文件完整（官方 debootstrap）"
    else
        msg "[失败] debootstrap 文件缺失: ${db_dir}"
        fail=$((fail+1))
    fi

    # debootstrap 关键依赖：pkgdetails（静态编译，必须可执行）
    local pkgdetails="${db_dir}/pkgdetails"
    if [ -x "${pkgdetails}" ]; then
        msg "[通过] pkgdetails 可执行（debootstrap 解包关键依赖）"
    elif [ -f "${pkgdetails}" ]; then
        msg "[失败] pkgdetails 存在但缺少执行权限: ${pkgdetails}"
        fail=$((fail+1))
    else
        msg "[失败] pkgdetails 缺失: ${pkgdetails}"
        fail=$((fail+1))
    fi

    # 内置工具链（busybox / e2fsck / resize2fs / mke2fs）
    local tool_missing=""
    for tool in busybox e2fsck resize2fs mke2fs; do
        [ -x "${TOOLS_DIR}/${tool}" ] || tool_missing="${tool_missing} ${tool}"
    done
    if [ -z "${tool_missing}" ]; then
        msg "[通过] 内置工具链齐全: ${TOOLS_DIR}"
    else
        msg "[警告] 内置工具缺失:${tool_missing}（镜像功能将不可用）"
        warn=$((warn+1))
    fi

    # 工具链是否已生效（busybox applet 软链 + PATH）
    if [ -x "${TOOLS_DIR}/busybox" ]; then
        case ":${PATH}:" in
        *":${TOOLS_DIR}:"*)
            msg "[通过] 工具链已加入 PATH（${TOOLS_DIR}）"
        ;;
        *)
            msg "[警告] 工具链目录未在 PATH 中，部分命令可能仍缺失"
            warn=$((warn+1))
        ;;
        esac
    fi

    # 当前配置
    local cur
    local cur=$(config_current)
    if [ -n "${cur}" ]; then
        msg "[通过] 当前配置: ${cur}"
    else
        msg "[失败] 没有可用配置，请先执行: config create <名称>"
        fail=$((fail+1))
    fi

    # 配置目录权限
    if [ -d "${CONFIG_DIR}" ] && [ -w "${CONFIG_DIR}" ]; then
        msg "[通过] 配置目录可写: ${CONFIG_DIR}"
    else
        msg "[失败] 配置目录不可写: ${CONFIG_DIR}"
        fail=$((fail+1))
    fi

    # 容器目录
    if [ -n "${CHROOT_DIR}" ]; then
        if [ -d "${CHROOT_DIR}" ]; then
            if [ -n "$(ls -A "${CHROOT_DIR}" 2>/dev/null)" ]; then
                msg "[警告] 容器目录非空（可能已部署）: ${CHROOT_DIR}"
                warn=$((warn+1))
            else
                msg "[通过] 容器目录存在且为空: ${CHROOT_DIR}"
            fi
        else
            msg "[通过] 容器目录不存在（部署时将创建）: ${CHROOT_DIR}"
        fi
    else
        msg "[失败] 容器目录未设置"
        fail=$((fail+1))
    fi

    # 挂载能力
    if [ -r /proc/mounts ]; then
        msg "[通过] 可读取挂载表"
    else
        msg "[失败] 无法读取 /proc/mounts"
        fail=$((fail+1))
    fi

    # SELinux
    if selinux_inactive; then
        msg "[通过] SELinux 未强制（permissive/关闭）"
    else
        msg "[警告] SELinux 强制模式，容器内操作可能受限"
        warn=$((warn+1))
    fi

    # 汇总
    msg "----------------------------------------"
    if [ "${fail}" -eq 0 ] && [ "${warn}" -eq 0 ]; then
        msg "[通过] 自检全部通过"
        return 0
    elif [ "${fail}" -eq 0 ]; then
        msg "[通过] 自检通过（${warn} 项警告）"
        return 0
    else
        msg "[失败] 自检未通过（${fail} 项失败，${warn} 项警告）"
        return 1
    fi
}

################################################################################
# 帮助
################################################################################

helper()
{
    printf '%s\n' \
"Linux Deploy CLI ${VERSION}"
    printf '%s\n' \
"维护：GanYu256 | 全中文日志 | 配置切换即锁定"
    printf '%s\n' \
"" \
"用法: ${0##*/} [选项] 命令 [参数]" \
"" \
"选项:" \
"  -d            调试模式（输出详细日志）" \
"  -t            跟踪模式（set -x）" \
"  -j, --json    JSON 输出模式（供前端解析）" \
"  -c <配置名>   显式指定操作配置（绕过 .current 锁；前端专用，锁留给纯 CLI）" \
"  -h, --help    显示本帮助" \
"" \
"配置命令:" \
"  config list                    列出全部配置" \
"  config show [名称]             显示当前（或指定）配置详情" \
"  config current                 查看当前配置名称" \
"  config use <名称>              切换当前配置（锁定）" \
"  config create <名称> [--k=v]   新建配置并自动切换（自动隔离容器目录）" \
"  config edit [--k=v ...]        修改当前配置参数" \
"  config copy <源> <新名称>      复制配置" \
"  config delete <名称> [--purge] 删除配置（--purge 连同容器目录删除）" \
"  config export <文件>           导出当前配置" \
"  config import <文件|名称>      导入配置（自动隔离容器目录）" \
"" \
"容器命令:" \
"  deploy [--dry-run] [--yes] [--k=v]  部署当前配置（含安全护栏与确认）" \
"  start  [--mount]                启动容器" \
"  stop   [--umount]               停止容器" \
"  status                          查看容器状态" \
"  shell  [-u 用户] [命令]         进入容器（默认 /bin/bash）" \
"  check                           自检环境与配置" \
"  resize <大小>                   调整镜像大小（如 8G / 512M，仅镜像安装）" \
"" \
"rootfs 命令:" \
"  import <归档|URL>               导入 rootfs 到当前容器" \
"  export <归档>                   导出当前容器为 rootfs 归档" \
"  mount                           挂载容器" \
"  umount                          卸载容器" \
"" \
"常用部署参数（--key=value 形式，写入当前配置）:" \
"  --distrib=debian|ubuntu|kali|alpine|archlinux" \
"  --arch=arm64                    目标架构（本项目只维护 arm64）" \
"  --suite=trixie                  发行版代号" \
"  --source-path=URL               软件源（可覆盖为镜像源）" \
"  --chroot-dir=路径               容器目录（部署前确认）" \
"  --include=组件                  包含的组件列表（如 core extra/ssh）" \
"  --extra-packages=包名           额外安装的软件包" \
"  --user-name=用户名              容器用户名（默认 root）" \
"  --user-password=密码            用户密码（默认 changeme）" \
"  --user-groups=组名列表          辅助组，空格分隔（默认 aid_inet aid_sdcard_rw aid_graphics）" \
"  --mounts=源:目标                安卓目录挂载到容器，空格分隔多个" \
"  --ssh-port=端口                 SSH 端口（配合 extra/ssh 组件使用，默认 22）" \
"  --graphics=子系统               图形子系统（预留，暂未启用）"
}

################################################################################
# 主流程
################################################################################
# 主流程
################################################################################

# 基础环境设置
umask 0022
unset LANG

# 定位环境目录（兼容 app 生成的入口脚本：入口软链接指向本脚本所在目录）
if [ -z "${ENV_DIR}" ]; then
    ENV_DIR=$(dirname "$(readlink -f "$0")")
fi
# 项目根目录（ENV_DIR 为 <项目根>/src/linuxdeploy-cli）与构建目录：
# 容器部署产物统一放在 builds 下，与源码、缓存、日志分离
PROJECT_ROOT=$(dirname "$(dirname "${ENV_DIR}")")
if [ -z "${BUILD_DIR}" ]; then
    BUILD_DIR="${PROJECT_ROOT}/builds"
fi
# 读取环境全局配置（可覆盖以下默认目录）
if [ -e "${ENV_DIR}/cli.conf" ]; then
    . "${ENV_DIR}/cli.conf"
fi
# 目录默认值
if [ -z "${CONFIG_DIR}" ]; then
    CONFIG_DIR="${ENV_DIR}/config"
fi
if [ -z "${INCLUDE_DIR}" ]; then
    INCLUDE_DIR="${ENV_DIR}/include"
fi
if [ -z "${TEMP_DIR}" ]; then
    TEMP_DIR="${ENV_DIR}/tmp"
fi
# 容器目录默认值（新配置创建时会自动分配独立目录）
if [ -z "${CHROOT_DIR}" ]; then
    CHROOT_DIR="${ENV_DIR}/mnt"
fi
if [ -z "${METHOD}" ]; then
    METHOD="chroot"
fi

# 全局选项解析：把 --json/--help/-j/-h 提升到参数最前（命令前或命令后均可）。
# 用换行分隔重建位置参数（POSIX，不用 bash 数组）。
# 命令自身参数（deploy --yes、config create --k=v 等）原样保留。
opt_head=""
opt_rest=""
for arg in "$@"
do
    case "${arg}" in
    --json) opt_head="${opt_head}-j
" ;;
    --help) opt_head="${opt_head}-h
" ;;
    -j|-h)  opt_head="${opt_head}${arg}
" ;;
    *)      opt_rest="${opt_rest}${arg}
" ;;
    esac
done
oldifs="${IFS}"
set -f
IFS='
'
set -- ${opt_head}${opt_rest}
IFS="${oldifs}"
set +f

# 解析全局选项（-d 调试 / -t 跟踪 / -j JSON / -h 帮助 / -c 指定配置）
# -c <配置名>：显式指定操作目标配置，绕过 .current 锁（锁仅服务纯 CLI 用户）。
# 前端 app 直读 config/ 目录 + 用 -c 指定配置执行命令，不触碰锁文件。
OPTIND=1
while getopts :djtc:h FLAG
do
    case "${FLAG}" in
    d)
        DEBUG_MODE="true"
    ;;
    j)
        JSON_MODE="true"
    ;;
    t)
        TRACE_MODE="true"
    ;;
    h)
        helper
        exit 0
    ;;
    c)
        OPT_CONF_NAME="${OPTARG}"
    ;;
    *)
        # 未知选项：停止选项解析，交给命令分发处理。
        break
    ;;
    esac
done
shift $((OPTIND-1))

# 日志级别：默认隐藏错误输出，调试模式显示
exec 3>&1
if [ "${DEBUG_MODE}" != "true" ] && [ "${TRACE_MODE}" != "true" ]; then
    exec 2>/dev/null
fi
if [ "${TRACE_MODE}" = "true" ]; then
    set -x
fi

# 确保基础目录存在
[ -d "${CONFIG_DIR}" ] || mkdir -p "${CONFIG_DIR}"
[ -d "${INCLUDE_DIR}" ] || mkdir -p "${INCLUDE_DIR}"
[ -d "${TEMP_DIR}" ] || mkdir -p "${TEMP_DIR}"

# ===== 内置工具链初始化 =====
# 目录：ENV_DIR/tools，存放面向安卓宿主的内置工具：
#   busybox   —— 提供 ar/xz/zstd 等 applet（宿主缺失时启用）
#   tar.gnu   —— GNU tar 1.35（静态编译，含 gzip/xz/zstd 支持）
#   wget      —— 动态链接 wget（bionic，走 Android 系统 DNS）
#   e2fsck    —— ext2/3/4 文件系统检查
#   resize2fs —— 镜像扩容 / 缩容
#   mke2fs    —— 创建 ext2/3/4 磁盘镜像
# pkgdetails 放在 debootstrap 目录内，供官方 debootstrap 直接执行。
TOOLS_DIR="${ENV_DIR}/tools"

# 修正内置脚本与二进制的执行权限（assets 解压丢失 exec 位，首次补齐）。
env_fix_permissions()
{
    if [ ! -x "${INCLUDE_DIR}/bootstrap/debian/debootstrap/debootstrap" ]; then
        find "${INCLUDE_DIR}" -type f \( -name "*.sh" -o -name "debootstrap" \) \
            -exec chmod 755 {} + 2>/dev/null || true
    fi
    if [ ! -x "${TOOLS_DIR}/busybox" ]; then
        chmod 755 "${TOOLS_DIR}"/* 2>/dev/null || true
    fi
    chmod 755 "${INCLUDE_DIR}/bootstrap/debian/debootstrap/pkgdetails" 2>/dev/null || true
}

# 补齐宿主缺失的工具软链并加入 PATH。
# 安卓 toybox 没有 ar，镜像解包与 .deb 读取依赖它。
env_init_tools()
{
    env_fix_permissions
    [ -x "${TOOLS_DIR}/busybox" ] || return 0
    # 只补齐宿主缺失的关键 applet（ar/xz/zstd/dpkg-deb），
    # 清掉旧软链后按需重建，不覆盖系统命令。
    find "${TOOLS_DIR}" -maxdepth 1 -type l -delete >/dev/null 2>&1 || true
    local app
    for app in ar xz xzcat unxz lzma unlzma lzcat dpkg-deb
    do
        if ! command -v "${app}" >/dev/null 2>&1; then
            ln -sf busybox "${TOOLS_DIR}/${app}" 2>/dev/null || true
        fi
    done
    for app in unzstd zstdcat zstdmt
    do
        ln -sf zstd "${TOOLS_DIR}/${app}" 2>/dev/null || true
    done
    # 用 GNU tar（tools/tar.gnu → tools/tar），支持 --exclude/--zstd 等扩展。
    if [ -f "${TOOLS_DIR}/tar.gnu" ] && [ ! -f "${TOOLS_DIR}/tar" ]; then
        cp "${TOOLS_DIR}/tar.gnu" "${TOOLS_DIR}/tar"
        chmod 755 "${TOOLS_DIR}/tar"
    fi
    case ":${PATH}:" in
    *":${TOOLS_DIR}:"*) ;;
    *) PATH="${TOOLS_DIR}:${PATH}" ;;
    esac
    export PATH
    # HOME/TMPDIR 指向应用 tmp（安卓宿主 /tmp 通常不可写）。
    export HOME="${TEMP_DIR}"
    export TMPDIR="${TEMP_DIR}"
    export TMP="${TEMP_DIR}"
    mkdir -p "${TEMP_DIR}/xdg/wget" 2>/dev/null || true
    export XDG_DATA_HOME="${TEMP_DIR}/xdg"
}
env_init_tools

# 确定操作配置：-c 显式指定优先（只读取，不写 .current 锁）；否则读 .current 锁
if [ -n "${OPT_CONF_NAME}" ]; then
    if config_exists "${OPT_CONF_NAME}"; then
        CURRENT_CONF="${OPT_CONF_NAME}"
    else
        msg "指定配置不存在: ${OPT_CONF_NAME}"
        exit 1
    fi
else
    CURRENT_CONF=$(config_current)
fi

# 读取当前配置参数
OPTLST=" " # 首字符必须为空格
if [ -n "${CURRENT_CONF}" ]; then
    CONF_FILE=$(config_file_of "${CURRENT_CONF}")
    params_read "${CONF_FILE}"
else
    CONF_FILE=""
fi

# 固定启动方式为 chroot（unshare 暂不支持，4.0 目标）
METHOD="chroot"

# 初始化系统兜底：旧配置可能无 INIT 键，统一默认 sysv（Debian 系 /etc/rcN.d 布局）；
# INCLUDE 含 init 组件时组件依赖解析依赖 INIT 值，缺省会直接报“缺少必要参数”。
INIT="${INIT:-sysv}"
INIT_LEVEL="${INIT_LEVEL:-3}"

# 常驻显示当前配置（--json 模式隐藏横幅，避免污染机器可读输出）
if [ "${JSON_MODE}" != "true" ]; then
    msg "=========================================="
    msg " Linux Deploy CLI ${VERSION} | 当前配置: ${CURRENT_CONF:-无}"
    msg " 容器目录: ${CHROOT_DIR}"
    msg "=========================================="
fi

# 解析命令
OPTCMD="$1"; shift
case "${OPTCMD}" in

config)
    # 配置管理子命令
    sub="$1"; shift
    case "${sub}" in
    list)
        config_list
        exit $?
    ;;
    show)
        config_show "$1"
        exit $?
    ;;
    current)
        # 查看当前配置名称（--json 模式输出结构化结果）
        if [ "${JSON_MODE}" = "true" ]; then
            printf '{"current": "%s"}\n' "$(json_escape "$(config_current)")"
        else
            config_current
        fi
        exit $?
    ;;
    use)
        config_use "$1"
        exit $?
    ;;
    create)
        config_create "$@"
        exit $?
    ;;
    edit)
        config_edit "$@"
        exit $?
    ;;
    copy)
        config_copy "$1" "$2"
        exit $?
    ;;
    delete)
        config_delete "$@"
        exit $?
    ;;
    export)
        config_export "$1"
        exit $?
    ;;
    import)
        config_import "$@"
        exit $?
    ;;
    *)
        helper
        exit 1
    ;;
    esac
;;

deploy)
    # 部署当前配置
    log_open "${CURRENT_CONF:-deploy}"
    dry_run="false"
    yes_mode="false"
    mount_flag="false"
    keep_mounted="false"
    DO_ACTION='do_install && do_configure'
    deploy_params=""

    # 解析部署选项
    for arg in "$@"
    do
        case "${arg}" in
        --dry-run)
            dry_run="true"
        ;;
        --yes|-y)
            yes_mode="true"
            YES="true"   # confirm_yes 依赖全局 YES；不置位则 --yes 无法跳过最终确认
        ;;
        --mount|-m)
            mount_flag="true"
        ;;
        --keep-mounted)
            # 部署成功后保留挂载（默认部署完成自动卸载，更安全）
            keep_mounted="true"
        ;;
        --install|-i)
            DO_ACTION='do_install'
        ;;
        --configure|-c)
            DO_ACTION='do_configure'
        ;;
        --exclude=*)
            EXCLUDE_COMPONENTS="${EXCLUDE_COMPONENTS} ${arg#--exclude=}"
        ;;
        *)
            deploy_params="${deploy_params}${arg}
"
        ;;
        esac
    done

    # 合并部署参数（--key=value 写入当前配置）；换行分隔重建位置参数（POSIX，无数组）
    if [ -n "${deploy_params}" ]; then
        oldifs="${IFS}"
        set -f
        IFS='
'
        set -- ${deploy_params}
        IFS="${oldifs}"
        set +f
        params_parse "$@"
        params_write "${CONF_FILE}"
    fi

    # 部署计划摘要
    msg "部署计划:"
    msg "  发行版: ${DISTRIB:-未设置}"
    msg "  架构: ${ARCH:-未设置}"
    msg "  发行代号: ${SUITE:-未设置}"
    msg "  安装源: ${SOURCE_PATH:-默认}"
    msg "  容器目录: ${CHROOT_DIR}"
    msg "  包含组件: ${INCLUDE:-bootstrap}"
    msg "  排除组件: ${EXCLUDE_COMPONENTS:-无}"
    extra_pkgs_summary=$(normalize_packages "${EXTRA_PACKAGES}")
    [ -n "${extra_pkgs_summary}" ] || extra_pkgs_summary="无"
    msg "  额外软件包: ${extra_pkgs_summary}"

    # 演练模式：只展示计划
    if [ "${dry_run}" = "true" ]; then
        msg "演练模式（--dry-run）: 以上为执行计划，未执行任何操作。"
        exit 0
    fi

    # 安全护栏：目标目录非空检查（防止覆盖已有工作环境）
    if [ -d "${CHROOT_DIR}" ] && [ -n "$(ls -A "${CHROOT_DIR}" 2>/dev/null)" ]; then
        msg "安全护栏: 目标目录非空: ${CHROOT_DIR}"
        if [ "${yes_mode}" = "true" ]; then
            msg "已指定 --yes，继续执行（风险由用户承担）。"
        else
            confirm_yes "目标目录非空，继续将混入/覆盖已有文件，是否继续？" || { msg "已取消部署。"; exit 1; }
        fi
    fi

    # 部署前确认
    confirm_yes "确认开始部署？" || { msg "已取消部署。"; exit 1; }

    # 挂载容器（可选）
    if [ "${mount_flag}" = "true" ]; then
        container_mount || exit 1
    fi

    # 构建期 statx 兼容层：编译并（容器已存在时）启用；部署结束自动清理
    statx_shim_prepare
    # 部署收尾：无论成败都清理构建期 shim；
    # 失败时顺带卸载组件残留的挂载，避免容器目录被占用、无法重建
    # 成功时默认自动卸载（可用 --keep-mounted 保留），状态干净、避免误操作
    deploy_cleanup()
    {
        local rc=$?
        statx_shim_cleanup
        if [ "${rc}" -ne 0 ]; then
            if container_mounted >/dev/null 2>&1; then
                msg "部署失败，正在卸载残留挂载 ..."
                container_umount >/dev/null 2>&1 || true
            fi
        elif [ "${keep_mounted}" != "true" ] && container_mounted >/dev/null 2>&1; then
            msg "部署完成，正在卸载容器挂载（可用 start 启动）..."
            container_umount >/dev/null 2>&1 || true
        fi
        return ${rc}
    }
    trap 'deploy_cleanup' EXIT

    # 执行安装与配置。
    # 显式把 bootstrap 放在最前：bootstrap 依赖 core，组件拓扑排序会
    # 自动先跑 core 再跑 bootstrap，避免“core 配置先于容器部署”的顺序错误。
    component_exec bootstrap ${INCLUDE:-core}
    exit $?
;;

start)
    # 启动容器
    log_open "${CURRENT_CONF:-start}"
    mount_flag="false"
    start_args=""
    for arg in "$@"
    do
        case "${arg}" in
        --mount|-m) mount_flag="true" ;;
        *) start_args="${start_args}${arg}
" ;;
        esac
    done
    if [ "${mount_flag}" = "true" ]; then
        container_mount || exit 1
    fi
    oldifs="${IFS}"
    set -f
    IFS='
'
    set -- ${start_args}
    IFS="${oldifs}"
    set +f
    container_start "$@"
    exit $?
;;

stop)
    # 停止容器
    log_open "${CURRENT_CONF:-stop}"
    umount_flag="false"
    stop_args=""
    for arg in "$@"
    do
        case "${arg}" in
        --umount|-u) umount_flag="true" ;;
        *) stop_args="${stop_args}${arg}
" ;;
        esac
    done
    oldifs="${IFS}"
    set -f
    IFS='
'
    set -- ${stop_args}
    IFS="${oldifs}"
    set +f
    container_stop "$@" || exit 1
    if [ "${umount_flag}" = "true" ]; then
        container_umount
    fi
    exit $?
;;

resize)
    # 调整镜像大小（仅支持 TARGET_TYPE=file 的镜像安装）
    log_open "${CURRENT_CONF:-resize}"
    image_resize "$1"
    exit $?
;;

status)
    # 容器状态
    if [ $# -gt 0 ]; then
        DO_ACTION='do_status'
        component_exec "$@"
    else
        container_status
    fi
    exit $?
;;

shell)
    # 进入容器
    container_shell "$@"
    exit $?
;;

check)
    # 自检（--json 模式输出结构化检查结果，供前端展示）
    if [ "${JSON_MODE}" = "true" ]; then
        raw=$(do_check 2>&1)
        rc=$?
        fail=0
        warn=0
        first="true"
        # do_check 输出经临时文件回读：POSIX sh 兼容（<<< 是 bash 扩展，
        # dash/部分 sh 下直接解析失败、静默退出 2）
        tmpfile="${TEMP_DIR}/check_json_$$.tmp"
        printf '%s\n' "${raw}" > "${tmpfile}"
        printf '{\n'
        printf '  "ok": %s,\n' "$([ ${rc} -eq 0 ] && echo true || echo false)"
        printf '  "items": [\n'
        while IFS= read -r item
        do
            case "${item}" in
            "["*)
                level=$(echo "${item}" | sed -n 's/^\[\([^]]*\)\].*/\1/p')
                text=$(echo "${item}" | sed -n 's/^\[[^]]*\]//p')
                case "${level}" in
                通过) ok_text="true" ;;
                警告) ok_text="true"; warn=$((warn+1)) ;;
                失败) ok_text="false"; fail=$((fail+1)) ;;
                *) ok_text="null" ;;
                esac
                if [ "${first}" = "true" ]; then first="false"; else printf ',\n'; fi
                printf '    {"ok": %s, "level": "%s", "detail": "%s"}' \
                    "${ok_text}" "$(json_escape "${level}")" "$(json_escape "${text}")"
            ;;
            esac
        done < "${tmpfile}"
        rm -f "${tmpfile}"
        printf '\n  ],\n'
        printf '  "fail": %d,\n  "warn": %d\n' "${fail}" "${warn}"
        printf '}\n'
        exit ${rc}
    fi
    log_open "${CURRENT_CONF:-check}"
    do_check
    exit $?
;;

import)
    # 导入 rootfs
    log_open "${CURRENT_CONF:-import}"
    rootfs_import "$@"
    exit $?
;;

export)
    # 导出 rootfs
    log_open "${CURRENT_CONF:-export}"
    rootfs_export "$@"
    exit $?
;;

rootfs)
    # rootfs 子命令分组（与顶层 import/export/mount/umount 等价，对齐 usage 文档）
    sub="$1"; shift
    case "${sub}" in
    import)
        log_open "${CURRENT_CONF:-import}"
        rootfs_import "$@"
        exit $?
    ;;
    export)
        log_open "${CURRENT_CONF:-export}"
        rootfs_export "$@"
        exit $?
    ;;
    mount)
        log_open "${CURRENT_CONF:-mount}"
        container_mount
        exit $?
    ;;
    umount)
        log_open "${CURRENT_CONF:-umount}"
        container_umount
        exit $?
    ;;
    *)
        helper
        exit 1
    ;;
    esac
;;

mount)
    log_open "${CURRENT_CONF:-mount}"
    container_mount
    exit $?
;;

umount)
    log_open "${CURRENT_CONF:-umount}"
    # 跨命名空间场景：当前 ns 看不到挂载但容器运行中 → 切入容器 ns 后卸载
    if ! container_mounted && [ "${LD_NSENTER}" != "1" ] && container_nsenter_run umount; then
        exit 0
    fi
    container_umount
    exit $?
;;

help|-h|--help|"")
    helper
    exit 0
;;

*)
    helper
    exit 1
;;

esac
