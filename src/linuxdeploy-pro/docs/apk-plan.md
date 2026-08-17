# LinuxDeploy-Pro APK 前端设计说明

> 状态：已实施 | 当前版本：4.1.13 | 关联：CLI（本工作区 src/linuxdeploy-cli）
> 目标：Miuix/HyperOS 风格 UI，适配 Android 16 特性，支持无障碍，全中文界面与日志

## 一、导航结构

底部三栏：主页 / 容器 / 日志。右上角固定齿轮进入设置。

- 主页：内核 / SELinux / Android 版本 / 架构 / ABI / SoC（不探测 GPU）
- 容器：配置列表（多配置），新建按钮进入配置编辑页（名称、路径、发行版、安装方式等），保存后列表显示
- 配置详情：部署 / 启停 / 终端 / 导入导出 / 镜像管理（resize2fs 调整镜像大小，不影响数据）/ 删除
- 日志：尾随 CLI 日志文件
- 设置：主题（跟随系统 / 浅色 / 纯黑 AMOLED）、重装运行环境、关于

## 二、与 CLI 对接

- 前端通过 `su -c "cli.sh -c <配置> <命令> --json"` 执行 CLI，解析 JSON 结果。
- 配置持久化以 CLI 为唯一数据源：编辑 = `config edit` 合并更新。
- 运行状态：CLI `status --json` 的 running（ldstatus 标记进程）与 mounted 独立输出。
- 开机自启：BootReceiver 在 BOOT_COMPLETED 后读取自启配置列表，逐个调用 start。

## 三、技术选型

- Kotlin + Jetpack Compose，AGP 9.3.1（内置 Kotlin），Compose 编译器 2.4.10
- Compose BOM 2026.06.01，Miuix 0.9.3（HyperOS 风格组件库）
- compileSdk 37（Miuix/core-ktx 最新版要求），targetSdk 36（Android 16），minSdk 28（私有二进制执行基线）
- 包名 io.github.ganyu256.linuxdeploypro，图标蓝紫渐变 + 终端提示符

## 四、执行后校验 / 无障碍

CLI 命令执行后由前端做结果校验与中文回显；CLI 侧输出保持结构化（退出码 + 关键行），
前端据此判断成功/失败。

## 五、里程碑

1. ✅ 空壳 UI（页面结构 + 主题 + 设备信息读取）
2. ✅ CLI 真实接入（执行 / 日志解析 / 状态回显）
3. ✅ 容器配置 CRUD + 镜像管理
4. ✅ 开机自启 + 自启开关 + 状态刷新
5. 🔲 素材：MiSans 字体、图标集完善（可选）
