#!/bin/sh
# Linux Deploy Component
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3
#
# Linux Deploy Pro CLI 4.0 维护版：本组件负责 Arch Linux / Arch Linux ARM 的引导安装。

if [ -z "${ARCH}" ]
then
    case "$(get_platform)" in
    x86) ARCH="i686" ;;
    x86_64) ARCH="x86_64" ;;
    arm) ARCH="armv7h" ;;
    arm_64) ARCH="aarch64" ;;
    esac
else
    # 将常见架构别名归一化为 Arch 官方命名：
    # 例如配置里写 arm64 时，仓库路径与软件源实际需要的是 aarch64。
    case "$(get_platform ${ARCH})" in
    arm_64) ARCH="aarch64" ;;
    x86) ARCH="i686" ;;
    esac
fi

if [ -z "${SOURCE_PATH}" ]
then
    case "$(get_platform ${ARCH})" in
    x86) SOURCE_PATH="http://mirror.archlinux32.org/" ;;
    x86_64) SOURCE_PATH="http://mirrors.kernel.org/archlinux/" ;;
    arm*) SOURCE_PATH="http://mirror.archlinuxarm.org/" ;;
    esac
fi

pacman_install()
{
    local packages="$@"
    [ -n "${packages}" ] || return 1
    (set -e
        #rm -f ${CHROOT_DIR}/var/lib/pacman/db.lck || true
        chroot_exec -u root pacman -Syq --overwrite="*" --noconfirm ${packages}
        rm -f "${CHROOT_DIR}"/var/cache/pacman/pkg/* || true
    exit 0)
    return $?
}

pacman_repository()
{
    case "$(get_platform ${ARCH})" in
    x86_64) local repo_url="${SOURCE_PATH%/}/\$repo/os/\$arch" ;;
    arm*|x86) local repo_url="${SOURCE_PATH%/}/\$arch/\$repo" ;;
    *) return 1 ;;
    esac
    sed -i "s|^[[:space:]]*Architecture[[:space:]]*=.*$|Architecture = ${ARCH}|" "${CHROOT_DIR}/etc/pacman.conf"
    sed -i "s|^[[:space:]]*\(CheckSpace\)|#\1|" "${CHROOT_DIR}/etc/pacman.conf"
    sed -i "s|^[[:space:]]*SigLevel[[:space:]]*=.*$|SigLevel = Never|" "${CHROOT_DIR}/etc/pacman.conf"
    # 新版 pacman 默认以 alpm 用户降权下载，但引导容器内没有该用户，
    # 会导致“设置 DownloadUser 'alpm' 时发生问题”而失败。
    # chroot 容器本就无提权风险，统一改为 root 执行。
    sed -i "s|^[[:space:]]*DownloadUser[[:space:]]*=.*$|DownloadUser = root|" "${CHROOT_DIR}/etc/pacman.conf"
    # 新版 pacman 沙盒依赖 Landlock（内核 ≥ 5.13），而安卓旧内核（如 4.19）
    # 不支持，会导致“内核不支持Landlock/切换沙盒用户失败”。
    # chroot 容器无外部攻击面，直接禁用文件系统与系统调用沙盒。
    sed -i "s|^[[:space:]]*#*DisableSandboxFilesystem|DisableSandboxFilesystem|" "${CHROOT_DIR}/etc/pacman.conf"
    sed -i "s|^[[:space:]]*#*DisableSandboxSyscalls|DisableSandboxSyscalls|" "${CHROOT_DIR}/etc/pacman.conf"
    if $(grep -q "^[[:space:]]*Server" "${CHROOT_DIR}/etc/pacman.d/mirrorlist")
    then sed -i "s|^[[:space:]]*Server[[:space:]]*=.*|Server = ${repo_url}|" "${CHROOT_DIR}/etc/pacman.d/mirrorlist"
    else echo "Server = ${repo_url}" >> "${CHROOT_DIR}/etc/pacman.d/mirrorlist"
    fi
}

do_install()
{
    is_archive "${SOURCE_PATH}" && return 0

    msg ":: 正在安装 ${COMPONENT} ... "

	local def_extra_packages=libbsd
    local repo_url
    case "$(get_platform ${ARCH})" in
    x86_64) repo_url="${SOURCE_PATH%/}/core/os/${ARCH}" ;;
    arm*|x86) repo_url="${SOURCE_PATH%/}/${ARCH}/core" ;;
    *) return 1 ;;
    esac

    msg -n "正在准备部署 ... "
    local cache_dir="${CHROOT_DIR}/var/cache/pacman/pkg"
    mkdir -p "${cache_dir}"
    is_ok "失败" "完成" || return 1

    msg -n "正在获取软件包列表 ... "
    # 下载软件包索引（core.db.tar.gz），失败时明确报错，
    # 避免后续管道在空数据上“成功”退出而掩盖问题。
    local db_file="${cache_dir}/core.db.tar.gz"
    local wget_err="${cache_dir}/wget.err"
    rm -f "${wget_err}"
    local wget_rc
    wget -q -T 60 -O "${db_file}" "${repo_url}/core.db.tar.gz" 2>"${wget_err}"
    wget_rc=$?
    if [ "${wget_rc}" -ne 0 ]; then
        msg "下载软件包索引失败（退出码 ${wget_rc}）"
        if [ -s "${wget_err}" ]; then
            msg "  wget 错误: $(head -c 300 "${wget_err}" | tr -c '[:print:]' '.')"
        fi
        return 1
    fi
    # wget 返回 0 但文件为空：wget 实现异常或网络返回空响应（0 字节）。
    if [ ! -s "${db_file}" ]; then
        msg "软件包索引下载为空文件（0 字节）——wget 异常或网络返回空响应"
        msg "  当前 wget: $(command -v wget 2>/dev/null || echo 未找到)"
        return 1
    fi
    # 部分镜像站（如中科大 USTC）对索引/db 文件返回 403 错误页，
    # wget 可能仍以 0 退出，下载到的是 HTML 而非 gzip。校验真实内容。
    if command -v gzip >/dev/null 2>&1 && ! gzip -t "${db_file}" 2>/dev/null; then
        # 诊断输出：打印实际下载内容的大小与开头，便于定位
        # （403 错误页 / wget 异常 / 网络污染 / 空文件）。
        local db_size db_head
        db_size=$(wc -c < "${db_file}" 2>/dev/null || echo 0)
        db_head=$(head -c 64 "${db_file}" 2>/dev/null | tr -c '[:print:]' '.')
        msg "软件包索引无效：大小 ${db_size} 字节，内容开头: ${db_head}"
        msg "（镜像禁止索引下载 / wget 异常 / 网络异常均可能，请更换镜像源）"
        return 1
    fi
    # 从索引的 desc 元数据中提取真实包文件名（%FILENAME% 字段），
    # 并排除内核/引导/调试等不参与基础安装的软件包。
    # 注意：不能依赖 tar --wildcards -O——busybox tar（APK 内置）不认识
    # --wildcards，会静默失败导致“未解析到任何软件包”。改为先解包再
    # find 遍历 desc，GNU tar 与 busybox tar 均兼容。
    local db_dir="${cache_dir}/core-db"
    rm -rf "${db_dir}" 2>/dev/null
    mkdir -p "${db_dir}"
    if ! tar xzf "${db_file}" -C "${db_dir}" 2>/dev/null; then
        msg "软件包索引解包失败，请更换镜像源"
        return 1
    fi
    local core_files=$(find "${db_dir}" -type f -name desc \
        -exec grep -A1 '^%FILENAME%$' {} \; 2>/dev/null | \
        grep -v -e '^%FILENAME%$' -e '^--$' -e '^$' | \
        grep -v -e '^linux-' -e '^grub-' -e '^efibootmgr-' -e '^openssh-' \
                -e 'ca-certificates-cacert' -e 'iptables-nft' -e 'openssl-cryptodev' \
                -e 'systemd-resolvconf' -e 'debug' -e 'qgpgme' -e '^gcc-' \
                -e 'libgccjit' -e 'llvm-libs' | sort)
    if [ -z "${core_files}" ]
    then
        msg "软件包索引中未解析到任何软件包，请检查安装源"
        return 1
    fi
    is_ok "失败" "完成" || return 1

    msg "正在获取软件包: "
    # filesystem 软件包需最先解包（提供基础目录结构）。
    # 注意保持多行匹配，避免换行被展开成空格后无法命中行首。
    local fs_file=$(printf '%s\n' ${core_files} | grep -m1 '^filesystem-')
    for pkg_file in ${fs_file} ${core_files}
    do
        msg -n "${pkg_file%-*} ... "
        # download
        local i
        for i in 1 2 3
        do
            wget -q -c -T 60 -O "${cache_dir}/${pkg_file}" "${repo_url}/${pkg_file}" && break
            sleep 5s
        done
        # unpack：
        # 优先用 GNU tar 直接解包进容器（--exclude 排除挂载点与元数据，
        # 多包自动合并目录内容）。env_init_tools 已把 PATH 中 tar 部署为
        # 静态 GNU tar（tools/tar.gnu）。
        # busybox tar 兜底：解包到临时目录 + cp -a 内容合并（注意不能用
        # mv——busybox mv 对已存在目录报 "Directory not empty" 静默失败，
        # 导致多包内容丢失）。
        if tar --version 2>/dev/null | grep -q "GNU tar"; then
            tar xJf "${cache_dir}/${pkg_file}" -C "${CHROOT_DIR}" \
                --exclude='./dev' --exclude='./sys' --exclude='./proc' \
                --exclude='.INSTALL' --exclude='.MTREE' --exclude='.PKGINFO' 2>/dev/null
        else
            local extract_dir="${cache_dir}/extract"
            rm -rf "${extract_dir}" 2>/dev/null
            mkdir -p "${extract_dir}"
            tar xJf "${cache_dir}/${pkg_file}" -C "${extract_dir}" 2>/dev/null
            (cd "${extract_dir}" && find . -mindepth 1 -maxdepth 1 \
                ! -name dev ! -name sys ! -name proc \
                ! -name .INSTALL ! -name .MTREE ! -name .PKGINFO \
                -exec sh -c 'cp -a "$1/." "${CHROOT_DIR}"/' _ {} \; 2>/dev/null)
            rm -rf "${extract_dir}"
        fi
        is_ok "失败" "完成" || return 1
    done

    # 基础包解包完成：容器内已具备 glibc，启用构建期 statx 兼容层
    # （旧内核上 systemd≥260 需要 STATX_MNT_ID，见 cli.sh）
    statx_shim_install || true

    component_exec core/emulator core/mnt core/net

    msg -n "正在更新软件源 ... "
    pacman_repository
    is_ok "失败" "完成"

    msg "正在安装软件包: "
    # We must update the certificate before install
	chroot_exec -u root update-ca-trust
    pacman_install base $(echo ${core_files} | sed 's/ /\n/g' | awk '{ sub(/-[0-9].*$/,""); print $1 }') ${EXTRA_PACKAGES} ${def_extra_packages}
    is_ok || return 1

    msg -n "正在清理缓存 ... "
    rm -rf "${cache_dir}/core-db" 2>/dev/null
    rm -f "${cache_dir}"/* $(find "${CHROOT_DIR}/etc" -type f -name "*.pacnew")
    is_ok "跳过" "完成"

    return 0
}

do_help()
{
cat <<EOF
   --arch="${ARCH}"
     Architecture of Linux distribution, supported "arm", "armv6h", "armv7h", "aarch64", "i686" and "x86_64".

   --source-path="${SOURCE_PATH}"
     Installation source, can specify address of the repository or path to the rootfs archive.

   --extra-packages="${EXTRA_PACKAGES}"
     List of optional installation packages, separated by spaces.

EOF
}
