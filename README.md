# Linux Deploy

在 Android 上部署 Linux 发行版（chroot），无需 root 系统分区修改。

## 项目结构

```
src/
├── linuxdeploy-cli/    # 命令行核心（POSIX sh，Android 上经 su 调用）
└── linuxdeploy-pro/    # Android 前端 App（Kotlin + Jetpack Compose）
```

- **linuxdeploy-cli**：部署/启动/停止/配置管理 CLI。支持 Debian/Ubuntu/Kali/Archlinux/CentOS/Fedora/Alpine/Slackware 等发行版（arm64 优先），含 ssh、VNC、X11、LXDE/MATE/XFCE 桌面组件。运行状态检测基于容器内 `ldstatus` 标记进程，跨 su 命名空间可靠。
- **linuxdeploy-pro**：Android App（minSdk 28 / targetSdk 36），内置最新 CLI 资源（`app/src/main/assets/linuxdeploy-cli/`），首次启动自动解压；支持配置管理、部署、启动/停止、容器内终端、开机自启。

## 版本

当前版本：**4.1.13**（CLI `VERSION` 与 App `versionCode 40113 / versionName 4.1.13` 保持一致；App 内置 CLI 资源的 `EXTRACT_MARKER` 同步为 4.1.13，版本变化时自动重新解压）。

## 构建

### CLI

`cli.sh` 为单文件入口，依赖 `include/` 组件目录，无编译步骤；在 Android 上由 App 解压至应用私有目录后经 `su -c` 调用。

### Android App

```bash
cd src/linuxdeploy-pro
./gradlew assembleRelease   # 产出 app/build/outputs/apk/release/app-release.apk
```

签名密钥（`keystore/linuxdeploy-pro.jks`）不入库，构建前需自行放置。

## 许可

GPLv3。CLI 沿袭自 [linuxdeploy](https://github.com/meefik/linuxdeploy) 的 GPLv3 代码，App 前端为独立实现。
