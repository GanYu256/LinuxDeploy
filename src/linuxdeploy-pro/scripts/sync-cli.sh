#!/bin/bash
################################################################################
# 同步 CLI 仓库 → APK assets（单一数据源：linuxdeploy-cli 仓库）
#
# 背景：CLI 唯一数据源原则。任何 CLI 改动先在 src/linuxdeploy-cli 提交，
# 然后运行本脚本把权威版本同步进 APK assets，避免双仓库分叉（2026-08-14
# 曾因手工复制导致 327 行分叉，本脚本为修复该问题的机制保证）。
#
# 用法：scripts/sync-cli.sh [--check]
#   --check   只报告差异，不复制（CI 或提交前自检用）
#
# 复制范围：
#   cli.sh、statx_shim.c、statx_test.c、include/、docs/、LICENSE、README.md
#   + include/bootstrap/debian/debootstrap/pkgdetails（静态二进制）
# 注意：tools/（busybox/wget/e2fsck/resize2fs/mke2fs）由 buildtools 构建，
#   不在此脚本范围（见 /root/Ganyu256/buildtools/build.sh deploy）。
################################################################################

set -e
cd "$(dirname "$0")/.."

CLI="${CLI_REPO:-../linuxdeploy-cli}"          # CLI 仓库绝对/相对路径
DEST="app/src/main/assets/linuxdeploy-cli"     # APK assets 目标
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

[ -f "${CLI}/cli.sh" ] || { echo "!! CLI 仓库路径无效: ${CLI}（可用 CLI_REPO= 覆盖）"; exit 1; }

# 排除项：APK 侧维护的本地内容不覆盖
EXCLUDES=(--exclude='.git' --exclude='config' --exclude='tmp' --exclude='scripts'
          --exclude='tools' --exclude='*.log' --exclude='*.bak')

if [ "${CHECK_ONLY}" = "1" ]; then
    echo "==> [sync-cli] 差异检查（CLI 仓库 vs APK assets）："
    rsync -n -avc "${EXCLUDES[@]}" "${CLI}/" "${DEST}/" | grep -v '/$' || true
    exit 0
fi

echo "==> [sync-cli] 同步 ${CLI} → ${DEST}"
mkdir -p "${DEST}"
rsync -avc "${EXCLUDES[@]}" "${CLI}/" "${DEST}/"

# 同步后修正执行位（assets 需要 755；git 仅记录 100644 时 rsync -p 会带过来）
find "${DEST}" -type f \( -name "*.sh" -o -name "debootstrap" -o -name "pkgdetails" \) \
    -exec chmod 755 {} + 2>/dev/null || true

echo "==> [sync-cli] 完成。请检查 git status 后提交 APK 仓库。"
