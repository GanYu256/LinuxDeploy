# Linux Deploy CLI 4.1.15

> 维护：GanYu256 | 全中文日志 | 无配置锁（统一 -c 指定配置） | 只维护 arm64

Linux Deploy CLI 是 LinuxDeploy-Pro 的命令行工具，提供 chroot 容器核心能力
（构建、挂载、启动、停止、导入导出），命令层、配置管理与安全机制统一设计，
输出全面中文。

本仓库只维护 CLI 脚本，APK 前端不在范围内。

## 版本沿革

- 基线：app 锁定版 `79924f593556`（VERSION 2.5.1）
- 4.0：全面重构——命令层重设计、安全护栏、中文日志
- 4.1.x：无配置锁（统一 -c 指定配置）、运行状态检测改为容器内 ldstatus 标记进程
  与配置侧锚点（跨命名空间可靠）、systemctl 初始化模式、版本号统一（当前 4.1.15）
- 设计文档：[docs/设计说明.md](docs/设计说明.md)

## 环境要求

- Android 手机 + root（chroot 需要 root 权限）
- 架构：**仅 arm64**（x86 / arm32 不维护）
- 内核：4.x 即可；旧内核（如 4.19）已通过构建期 statx 兼容层验证
- 依赖工具：`wget`、`curl`、`ar`、`tar`、`xz`、`zstd`、`dpkg-deb`、`gcc`
  （编译 statx 兼容层时需要，见 [docs/statx-shim.md](docs/statx-shim.md)）
- debootstrap：官方 1.0.144（已内置在 `include/bootstrap/debian/debootstrap/`）

## 快速开始

```bash
# 1. 新建配置（自动切换并锁定；未指定容器目录时自动隔离到 builds/ 下）
./cli.sh config create mydebian --distrib=debian --suite=trixie --arch=arm64

# 2. 自检环境与配置
./cli.sh check

# 3. 部署（安全护栏确认后执行；--yes 跳过交互确认）
./cli.sh deploy --yes

# 4. 启动容器（自动挂载系统文件与自定义挂载点，无需手动 mount）
./cli.sh start

# 5. 进入容器（支持 -c 直接执行命令）
./cli.sh shell
./cli.sh shell -c "cat /etc/os-release"

# 6. 停止（自动卸载全部挂载）
./cli.sh stop
```

## 命令参考

```
用法: cli.sh [选项] 命令 [参数]

选项:
  -d            调试模式（输出详细日志）
  -t            跟踪模式（set -x）
  -j, --json    JSON 输出模式（供前端解析，不打印中文横幅）
  -h, --help    显示本帮助

配置命令:
  config list                    列出全部配置
  config show [名称]             显示配置详情（名称或 -c 指定）
  config create <名称> [--k=v]   新建配置（自动隔离容器目录）
  config edit [--k=v ...]        修改 -c 指定配置参数
  config copy <源> <新名称>      复制配置
  config delete <名称> [--purge] 删除配置（--purge 连同容器目录删除）
  config export <文件>           导出 -c 指定配置
  config import <文件|名称>      导入配置（自动隔离容器目录）

容器命令:
  deploy [--dry-run] [--yes] [--keep-mounted] [--k=v]  部署 -c 指定配置（含安全护栏与确认）
  start                           启动容器（自动挂载系统文件与 MOUNTS 自定义挂载点）
  stop                            停止容器（自动卸载全部挂载）
  status                          查看容器状态（--json 输出机器可读结果）
  shell  [-u 用户] [-c 命令]      进入容器（默认 /bin/bash；-c 直接执行命令）
  check                           自检环境与配置（--json 输出结构化结果）

rootfs 命令:
  import <归档|URL>               导入 rootfs 到 -c 指定容器
  export <归档>                   导出 -c 指定容器为 rootfs 归档
  mount                           挂载容器
  umount                          卸载容器
```

## 行为说明

- **显式指定配置（-c）**：无配置锁，所有容器操作命令通过 `-c <配置名>` 显式指定
  操作配置；未带 `-c` 时容器操作命令拒绝执行并提示。任何命令输出顶部都会
  显示操作配置名称与容器目录，避免误操作。
- **部署后自动卸载**：`deploy` 成功默认卸载容器挂载（可用 `--keep-mounted` 保留）；
  失败时也会清理残留挂载，保证容器目录不被占用。
- **启动即挂载、停止即卸载**：`start` 自动挂载 proc/sys/dev 等系统文件，
  并挂载配置里的 `MOUNTS` 自定义挂载点（`"源:目标"` 空格分隔）；
  `stop` 停止组件后自动卸载全部挂载，无需额外步骤。
- **运行状态检测**：容器内 `ldstatus` 标记进程（`/run/ldstatus/pid`）作为运行
  标记，`status` 据此判断 running，跨 su 命名空间可靠；`mounted` 表示当前
  命名空间内可见的挂载状态，二者独立输出。
- **JSON 协议**：`--json config list/show`、`--json check`、`--json status`
  均输出机器可读结果，供 APK 前端解析。

## 变更日志

### 4.1.13（2026-08，当前主线）

- 版本号统一为 4.1.13（cli.sh VERSION 与 App versionName/EXTRACT_MARKER 一致）
- 运行状态检测：新增 `core/ldstatus` 组件，容器内标记进程跨命名空间可靠判定
- 开机自启（App 侧）：BootReceiver 读取自启配置调用 start

### 4.1（2026-08）

- 自启开关（App 侧）：容器卡菜单开关，删除配置/容器时自动清除自启条目
- 状态刷新（App 侧）：启动/停止后与 ON_RESUME 时刷新运行状态

### 4.0（2026-08）

- 命令层重构：`config` 子命令统一、`check` 自检、`--json` 协议输出
- 无配置锁：容器操作统一 `-c <配置名>` 指定，杜绝误操作其他配置
- 中文日志全面落地；debootstrap / apt 输出流式写入日志文件
- 修复 `chroot_exec` 参数二次解析 bug（含 `;`/`|` 的命令串不再挂起）
- `shell` 自动补齐 `/proc` 等虚拟文件系统挂载；支持 `shell -c` 直接执行命令
- `start` 自动挂载系统文件与 `MOUNTS` 自定义挂载点，`stop` 自动卸载（一步到位）
- 部署成功默认自动卸载，失败清理残留挂载
- 构建期 statx 兼容层（旧内核 4.19 上 Debian 13 可正常构建）
- 实测构建成功：Debian 13 (trixie)、Alpine 3.24、Arch Linux (aarch64)、
  Kali 2026.3、Ubuntu 24.04（arm64，清华/阿里镜像源）

执行任意命令时，输出顶部都会显示**操作配置名称与容器目录**，时刻提醒你操作对象。

## 配置参数（`--key=value` 形式）

常用部署参数：

| 参数 | 说明 |
|---|---|
| `--distrib=` | 发行版：`debian` / `ubuntu` / `kali` / `alpine` / `archlinux` |
| `--arch=` | 目标架构，本项目固定 `arm64` |
| `--suite=` | 发行版代号，如 Debian 13 为 `trixie` |
| `--source-path=` | 软件源 URL（可覆盖为镜像源） |
| `--chroot-dir=` | 容器目录（部署前会确认） |
| `--include=` | 包含的组件列表，如 `core`、`extra/ssh`（默认 `bootstrap`） |
| `--extra-packages=` | 额外安装的软件包，空格分隔 |
| `--user-name=` / `--user-password=` | 容器内用户（默认 `android` / `changeme`） |

组件级参数（由各组件读取，如 rootfs 的 `--target-type`、`--fs-type`、
`--disk-size`、`--mounts`、`--dns`、`--locale` 等）：

| 参数 | 说明 |
|---|---|
| `--target-type=` | 容器部署类型：`file` / `directory` / `partition` / `ram` / `custom` |
| `--target-path=` | 安装路径，取决于部署类型 |
| `--disk-size=` | 镜像文件大小（MB），`0` 表示自动 |
| `--fs-type=` | 镜像文件系统：`ext2` / `ext3` / `ext4` |
| `--emulator=` | 模拟器（qemu 组件，默认 QEMU） |
| `--mounts=` | 挂载资源，格式 `源:目标`，空格分隔 |
| `--dns=` | DNS 服务器，多个用空格分隔 |
| `--net-trigger=` | 容器内网络变更脚本路径 |
| `--locale=` | 容器内区域设置 |
| `--privileged-users=` | 附加权限用户列表，格式 `UID:GID` |

## 配置管理机制

### 显式指定配置（-c）

- 无配置锁：所有容器操作命令通过 `-c <配置名>` 显式指定操作配置；
- 未带 `-c` 时容器操作命令拒绝执行并提示；
- 操作对象由 `-c` 显式确定，避免误操作。

### 配置隔离

- `config create` 未显式指定 `--chroot-dir` 时，自动分配独立目录
  `<项目根>/builds/<配置名>`，与既有容器物理隔离；
- 既有配置保留其原有 `CHROOT_DIR`，迁移需手工确认。

### 安全护栏

- 部署前：目标目录存在且非空 → 拒绝执行（除非显式 `--yes` 并二次确认）；
- `config delete --purge` 连容器目录删除前需二次确认；
- 部署失败时自动卸载残留挂载，避免容器目录被占用；
- rootfs 组件不再无条件 `rm -rf` 目标目录。

## 发行版支持

| 发行版 | 配置代号 | 状态 |
|---|---|---|
| Debian 13 (trixie) | `trixie` | ✅ 构建与回归通过 |
| Ubuntu 24.04 | `noble` | ✅ 构建与回归通过 |
| Kali Rolling | `kali-rolling` | ✅ 构建与回归通过 |
| Alpine Linux | `latest` | ✅ 构建与回归通过 |
| Arch Linux ARM | `latest` | ✅ 构建与回归通过（136 包） |
| Slackware | `current` | 保留，未验证 |
| CentOS / Fedora / Docker | — | ⚠️ 已废弃，仅留档（官方源关闭或安卓不可运行） |
| rootfs（本地归档导入） | — | ✅ 组件逻辑正常，配合其他发行版收尾使用 |

## 组件结构

```
include/
├── bootstrap/   发行版构建（debian/ubuntu/kali/alpine/archlinux/slackware/rootfs）
├── core/        容器核心（aid、ldstatus、mnt、net、hostname、hosts、locale、su、sudo 等）
├── init/        容器初始化（sysv、run-parts）
├── extra/       附加服务（ssh、pulse）
├── desktop/     桌面环境（lxde、xfce、mate、xterm、dbus 等）
└── graphics/    图形输出（fb、vnc、x11）
```

每个组件由 `deploy.conf`（元信息）与 `deploy.sh`（实现）组成。

## 目录结构

```
linuxdeploy/               项目根
├── src/linuxdeploy-cli/   CLI 源码（本仓库）
│   ├── cli.sh             主入口
│   ├── config/            配置（.conf 与 .ldstatus 锚点）
│   ├── include/           组件库
│   ├── tmp/               临时文件
│   ├── statx_shim.c       构建期 statx 兼容层源码
│   └── docs/              设计文档
├── builds/                容器部署目录（按配置名隔离）
├── cache/                 下载缓存（debootstrap 上游等）
├── logs/                  构建日志
└── vendor/                第三方参考实现（如 systemctl.py）
```

## 日志与调试

- 全部日志输出为中文（变量、路径等保持原样）；
- 所有输出统一走 `msg()` 函数，便于 APK 前端解析；
- 需要详细输出时加 `-d`（调试模式），跟踪执行流程用 `-t`；
- 构建日志与 statx 编译日志见 `tmp/` 与项目 `logs/`。

## 已知限制

- 容器化方式固定为 **chroot**，`unshare` 不实现（安卓内核限制）；
- 旧内核（<5.8）上运行时 systemd 工具仍受 `STATX_MNT_ID` 缺失影响，容器内
  服务建议走传统 sysvinit / init.d（与 Android 环境兼容）；
- Docker 无法在安卓内核运行，docker 组件已废弃；
- 本项目只维护 arm64，其他架构不处理。

## 相关文档

- [docs/设计说明.md](docs/设计说明.md) —— 命令层与核心机制设计
- [docs/statx-shim.md](docs/statx-shim.md) —— 旧内核 statx 兼容层说明

## 许可证

GPLv3。版权归 Anton Skshidlevsky 与 LinuxDeploy 社区；重构部分由 GanYu256 维护。
