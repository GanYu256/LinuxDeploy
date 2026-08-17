# LinuxDeploy-Pro 4.1.13（Android 前端）

LinuxDeploy Android 前端：Kotlin + Jetpack Compose + Miuix（HyperOS 风格）。
CLI（`../linuxdeploy-cli`）为唯一数据源，前端通过 `su -c` 调用 `cli.sh` 执行
容器操作并以 `--json` 协议解析结果。

## 已实现

- 底部三栏：主页 / 容器 / 日志；右上角固定齿轮进入设置
- 主页：内核 / SELinux / Android 版本 / 架构 / ABI / SoC（不探测 GPU）
- 容器页：多配置列表、新建/编辑配置（字段与 CLI 参数一一对应）、详情与操作
  （部署 / 启停 / 进入终端 / 导入导出 / 镜像 resize / 删除）
- CLI 接入：`CliManager` 执行 cli.sh 子进程，`--json` 协议解析，配置持久化
  以 CLI 为唯一数据源（编辑 = config edit 合并更新，保留用户配置）
- 日志页：尾随 CLI 生成的日志文件，终端大窗口可跨行复制
- 内置终端：容器 shell 会话
- 镜像管理：`resize` 调整 ext4 镜像大小（CLI 内置 e2fsck/resize2fs）
- 随包资源：assets/linuxdeploy-cli（cli.sh + include + tools/busybox、wget、
  resize2fs、e2fsck、mke2fs、pkgdetails），EXTRACT_MARKER 4.1.13 按需重装
- 开机自启：BootReceiver 在系统启动后按自启配置列表逐个 start；
  容器卡菜单提供自启开关，删除配置/容器时自动清除自启条目
- 运行状态：基于 CLI `status --json` 的 running（ldstatus 标记进程）与 mounted
  独立显示；应用启动/回前台与启停操作后刷新
- 设置：主题（跟随系统 / 浅色 / 纯黑 AMOLED）、重装运行环境、关于
- 无障碍：导航项语义、图标 contentDescription、按钮 Role
- 安全：allowBackup=false、无明文 HTTP、详情页不展示明文密码
- 包名 `io.github.ganyu256.linuxdeploypro`
- 启动图标：蓝紫渐变 + 终端提示符（自适应图标，支持 Android 13 单色图标）

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
cd src/linuxdeploy-pro
./gradlew assembleRelease   # 产出 app/build/outputs/apk/release/app-release.apk
```

构建要点：
- compileSdk 37 是 Miuix 0.9.3 / core-ktx 1.19.0 的编译要求，targetSdk 仍锁定 36（Android 16 运行行为）；
- release 签名使用本地 keystore（不入库，见 keystore/README.md）。

## CLI 同步

APK 内置的 `assets/linuxdeploy-cli/` 必须从 CLI 仓库同步，禁止手工复制：

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
4. ✅ 开机自启 + 自启开关 + 状态刷新（ON_RESUME / 启停后）
5. 🔲 素材：MiSans 字体、图标集完善（可选）
