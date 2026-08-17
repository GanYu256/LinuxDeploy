# LinuxDeploy-Pro 4.0（前端 APK）

LinuxDeploy 重构版前端：Kotlin + Jetpack Compose + Miuix（HyperOS 风格）。
CLI 4.0（`../linuxdeploy-cli`）为唯一数据源，前端只做可视化操作与日志展示，
真正干活的一律交给 `cli.sh`（`su -c` 以 root 执行）。

## 已实现

- 底部三栏：主页 / 容器 / 日志（取消旧版侧边栏）；右上角固定齿轮进入设置
- 主页：内核 / SELinux / Android 版本 / 架构 / ABI / SoC（按需求不探测 GPU）
- 容器页：多配置列表、新建/编辑配置（字段与 CLI 参数一一对应）、详情与操作
  （部署 / 启停 / 进入终端 / 导入导出 / 镜像 resize / 删除）
- CLI 真实接入：`CliManager` 执行 cli.sh 子进程，`--json` 协议解析，配置持久化
  以 CLI 为唯一数据源（编辑 = config edit 合并更新，保留用户配置）
- 日志页：尾随 CLI 生成的日志文件，终端大窗口可跨行复制
- 内置终端：容器 shell 会话
- 镜像管理：`resize` 调整 ext4 镜像大小（CLI 内置 e2fsck/resize2fs）
- 随包二进制：assets/linuxdeploy-cli（cli.sh + include + tools/busybox、wget、
  resize2fs、e2fsck、mke2fs、pkgdetails），EXTRACT_MARKER 4.0.5 按需重装
- 设置：主题（跟随系统 / 浅色 / 纯黑 AMOLED）、重装运行环境、关于
- 无障碍基础：导航项语义、图标 contentDescription、按钮 Role
- 安全：allowBackup=false、无明文 HTTP、详情页不展示明文密码
- 新包名 `io.github.ganyu256.linuxdeploypro`（与原版 ru.meefik.linuxdeploy 区分）
- 新启动图标：蓝紫渐变 + 终端提示符（自适应图标，支持 Android 13 单色图标）

## 技术栈

| 组件 | 版本 |
| --- | --- |
| AGP | 9.3.1（内置 Kotlin） |
| Kotlin / Compose 编译器 | 2.4.10 |
| Compose BOM | 2026.06.01 |
| Miuix | 0.9.3 |
| compileSdk / targetSdk | 37 / 36（编译基线 37，运行行为仍为 Android 16） |
| minSdk | 28 |

## 构建

```bash
# 在 k30p Termux 中执行（网络走 WiFi）
export ANDROID_HOME=$HOME/Ganyu256/android/sdk
export GRADLE_USER_HOME=$HOME/Ganyu256/linuxdeploy-pro/cache/gradle
cd ~/Ganyu256/linuxdeploy-pro
gradle :app:assembleDebug --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

构建要点：
- 手机上必须用 arm64 原生 aapt2 覆盖 AGP 自带 x86_64 版（`android.aapt2FromMavenOverride`），否则 aapt2 直接报语法错误跑不起来；
- compileSdk 37 是 Miuix 0.9.3 / core-ktx 1.19.0 的编译要求，targetSdk 仍锁定 36（Android 16 运行行为）；
- Android SDK 组件（platform-37、build-tools 36）已按需自动安装，许可证文件已就位。

## CLI 同步（重要）

CLI 是唯一数据源，APK 内置的 `assets/linuxdeploy-cli/` 必须从 CLI 仓库同步，
禁止手工复制（曾导致 327 行分叉，2026-08-14 修复）：

```bash
# CLI 仓库提交后，同步到 APK assets 并检查差异
scripts/sync-cli.sh --check   # 只报告差异
scripts/sync-cli.sh           # 同步后需人工 review git status 再提交
```

工具链静态二进制（tools/）由 `buildtools/build.sh` 构建后部署，见
`/root/Ganyu256/buildtools/build.sh`。

## 目录约定

- `scripts/`：CLI 同步等维护脚本
- `tmp/`：临时文件
- `cache/`：Gradle 用户缓存（GRADLE_USER_HOME）
- `logs/`：构建日志
- `docs/`：方案与素材

## 路线图

1. ✅ 空壳 UI（三栏导航 + 主题 + 设备信息）
2. ✅ CLI 真实接入（执行 / 日志解析 / 状态回显）
3. ✅ 容器配置 CRUD + 启停 + 终端 + 日志页 + 镜像 resize
4. 🔲 素材：发行版 logo、MiSans 字体、图标集（正式版前补齐）
5. 🔲 release 签名、混淆与发布准备
