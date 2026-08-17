# LinuxDeploy-Pro APK 前端设计说明

> 状态：已实施 | 当前版本：4.1.13 | 关联：CLI（本工作区 src/linuxdeploy-cli）
> 目标：Miuix/HyperOS 风格 UI，适配 Android 16 特性，支持无障碍，全中文界面与日志

## 一、导航结构

主界面 = 单 Activity + 底部三栏（主页 / 容器 / 日志）+ 右上角固定齿轮（设置）。

- 主页：设备信息（内核 / SELinux / Android 版本 / 架构 / ABI / SoC，不探测 GPU）
- 容器：配置列表（多配置），新建/编辑配置（字段与 CLI 参数一一对应），
  详情操作（部署 / 启停 / 终端 / 导入导出 / 镜像 resize / 删除）
- 日志：尾随 CLI 生成的日志文件
- 设置：主题（跟随系统 / 浅色 / 纯黑 AMOLED）、重装运行环境、关于

## 二、与 CLI 对接

- 前端通过 `su -c "cli.sh -c <配置> <命令> --json"` 执行 CLI，解析 JSON 结果。
- 配置持久化以 CLI 为唯一数据源：编辑 = `config edit` 合并更新。
- 运行状态：CLI `status --json` 的 running（ldstatus 标记进程）与 mounted 独立输出。
- 开机自启：BootReceiver 在 BOOT_COMPLETED 后读取自启配置列表，逐个调用 start。

## 三、技术选型

| 项目 | 选择 | 说明 |
|---|---|---|
| 语言 | Kotlin | Jetpack Compose |
| UI | Compose + Miuix | top.yukonga.miuix.kmp:miuix-ui（0.9.3，HyperOS 设计体系） |
| 导航 | 单 Activity + 底部三栏 | 取消侧边栏 |
| 主题 | Miuix 自定配色 | 浅色走 HyperOS 体系，深色 = 纯黑 AMOLED |
| 无障碍 | contentDescription + 语义 + TalkBack | Miuix 0.9.2 起已修复导航项 TalkBack 双读 |
| 长任务 | 前台服务/协程 | deploy/export 等耗时长任务 |
| SDK | compileSdk 37 / targetSdk 36 / minSdk 28 | 见下 |

- compileSdk 37：Miuix 0.9.3 / core-ktx 1.19.0 的编译要求。
- targetSdk 36：Android 16 运行行为。
- minSdk 28：私有二进制执行限制基线（Android 9）。

## 四、随包资源

- `assets/linuxdeploy-cli/`：内嵌 CLI（cli.sh + include + tools/ 静态二进制），
  由 `scripts/sync-cli.sh` 从 CLI 仓库同步，EXTRACT_MARKER 版本变化时自动重新解压。
- 工具链二进制（tools/busybox、wget、resize2fs、e2fsck、mke2fs、pkgdetails）由
  `buildtools/build.sh` 构建部署。
- 发行版 logo 素材来源记录见 `docs/branding/README.md`。

## 五、安全

- `allowBackup=false`，无明文 HTTP，详情页不展示明文密码。
- 包名 `io.github.ganyu256.linuxdeploypro`。
- release 签名使用本地 keystore（不入库），见 `keystore/README.md`。

## 六、里程碑

1. ✅ 空壳 UI（三栏导航 + 主题 + 设备信息）
2. ✅ CLI 真实接入（执行 / 日志解析 / 状态回显）
3. ✅ 容器配置 CRUD + 启停 + 终端 + 日志页 + 镜像 resize
4. ✅ 开机自启 + 容器卡菜单自启开关（删除配置/容器自动清除自启）
5. 🔲 素材：MiSans 字体、图标集完善（可选）
