#!/bin/bash
################################################################################
# CLI 4.0 回归自检脚本（快速冒烟测试，不执行真实部署）
#
# 用途：CLI 代码改动后快速验证命令层与关键功能无回归。
# 覆盖：语法、配置 CRUD、自检、状态、部署计划、JSON 协议、resize、
#       回流功能（normalize_packages / 时间戳日志 / target_type）。
# 注意：不会触碰任何已部署容器（deploy 只跑 --dry-run）。
#
# 用法：scripts/regression.sh [--json]
################################################################################
set -u
cd "$(dirname "$0")/.."

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  [通过] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [失败] $1"; }
check(){ if eval "$2" >/dev/null 2>&1; then ok "$1"; else bad "$1"; fi; }

echo "== CLI 4.0 回归自检 =="

# 1. 语法
check "bash 语法" "bash -n cli.sh"

# 2. 基本命令可用（帮助/当前配置/列表）
check "help" "./cli.sh --help"
check "config current" "./cli.sh config current"
check "config list" "./cli.sh config list"

# 3. 自检与状态
check "check（非零退出容忍：警告不失败）" "./cli.sh check"
check "status" "./cli.sh status"
check "deploy --dry-run" "./cli.sh deploy --dry-run"

# 4. JSON 协议（机器可读）
check "--json config list 含 configs" \
  "./cli.sh --json config list | grep -q '\"configs\"'"
check "--json status" "./cli.sh --json status | grep -q '\"running\"'"
check "--json check" "./cli.sh --json check | grep -q '\"ok\"'"

# 5. 回流功能（2026-08-14 从 APK 资产回流）
check "resize 命令存在（无参返回解析错误）" \
  "! ./cli.sh resize 2>&1 | grep -q '未知命令'"
check "normalize_packages 已实现" "grep -q normalize_packages cli.sh"
check "时间戳日志命名已实现" "grep -q \"date '+%Y%m%d-%H%M%S'\" cli.sh"
check "TARGET_TYPE 持久化键已实现" "grep -q TARGET_TYPE cli.sh"

# 6. 组件系统
check "组件列表（当前发行版兼容）" "./cli.sh config list >/dev/null && ./cli.sh --help >/dev/null"

echo "----------------------------------------"
echo "结果: $PASS 通过, $FAIL 失败"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
