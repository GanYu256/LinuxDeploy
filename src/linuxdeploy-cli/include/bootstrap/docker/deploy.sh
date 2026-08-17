#!/bin/sh
# Linux Deploy Component
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3

# 已废弃：安卓内核无法运行 Docker，本组件仅留档，不再维护。
[ -n "${SUITE}" ] || SUITE="linux"

if [ -z "${ARCH}" ]
then
    case "$(get_platform)" in
    x86) ARCH="386" ;;
    x86_64) ARCH="amd64" ;;
    arm) ARCH="arm" ;;
    arm_64) ARCH="arm64" ;;
    esac
fi

[ -n "${SOURCE_PATH}" ] || SOURCE_PATH="library/ubuntu:18.04"

do_install()
{
    msg ":: 正在安装 ${COMPONENT} ... "

    local image="${SOURCE_PATH%%:*}"
    local tag="${SOURCE_PATH##*:}"
    msg -n "正在验证 Docker 软件源 ... "
    local token=$(wget -q -O - "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${image}:pull" | grep -oE '"token":"[.a-zA-Z0-9_-]+"' | tr -d '"' | awk -F':' '{print $2}')
    test -n "${token}"
    is_ok "失败" "完成" || return 1
    msg -n "正在获取仓库清单 ... "
    local manifest=$(wget -q -O - --header "Authorization: Bearer ${token}" --header "Accept: application/vnd.docker.distribution.manifest.list.v2+json" "https://registry-1.docker.io/v2/${image}/manifests/${tag}" | sed 's/},{/\n/g' | grep "\"${ARCH}\".*\"${SUITE}\"" | grep -oE 'sha256:[a-f0-9]{64}')
    test -n "${manifest}"
    is_ok "失败" "完成" || return 1
    msg "正在获取 rootfs 数据块: "
    wget -q -O - --header "Authorization: Bearer ${token}" "https://registry-1.docker.io/v2/${image}/manifests/${manifest}" | tr '\n' ' ' | tr '{}' '\n' | grep 'tar.gzip.*digest' | grep -oE 'sha256:[0-9a-f]{64}' | while read digest
    do
      msg -n " * ${digest} ... "
      wget -q -O - --header "Authorization: Bearer ${token}" "https://registry-1.docker.io/v2/${image}/blobs/${digest}" | tar xz -C "${CHROOT_DIR}"
      is_ok "失败" "完成"
    done

    return 0
}

do_help()
{
cat <<EOF
   --arch="${ARCH}"
     Architecture of the container, supported "arm", "arm64", "386" and "amd64".

   --suite="${SUITE}"
     OS of the container, supported "linux".

   --source-path="${SOURCE_PATH}"
     Installation source, can specify name and version of the container.

EOF
}
