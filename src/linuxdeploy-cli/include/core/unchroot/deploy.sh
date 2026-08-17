#!/bin/sh
# Linux Deploy 组件：unchroot（容器逃逸到安卓宿主环境）
# (c) Anton Skshidlevsky <meefik@gmail.com>, GPLv3
# 维护：GanYu256（CLI 4.0 重构，中文注释与日志）

do_configure()
{
    msg ":: 正在配置 ${COMPONENT} ... "
    local unchroot="${CHROOT_DIR}/sbin/unchroot"
    cat > "${unchroot}" << UNCHROOT_EOF
#!/system/bin/sh
# 从容器逃逸到安卓宿主环境
# 原理：/proc/1/cwd 是 Android init 进程的工作目录，即宿主根目录。
# chroot 到该目录后即可进入宿主文件系统。
# 注意：Android 的 passwd 中没有 root 条目，不能使用 su -，直接 exec 宿主 sh。
export HOME=/data
export PATH=/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/sbin
export BOOTCLASSPATH=${BOOTCLASSPATH}
export ANDROID_DATA=${ANDROID_DATA}
export ANDROID_ROOT=${ANDROID_ROOT}
export ANDROID_STORAGE=${ANDROID_STORAGE}
export EXTERNAL_STORAGE=${EXTERNAL_STORAGE}
if [ \$# -eq 0 ]; then
    exec chroot /proc/1/cwd /system/bin/sh
else
    exec chroot /proc/1/cwd "\$@"
fi
UNCHROOT_EOF
    chmod 755 "${unchroot}"
    return 0
}
