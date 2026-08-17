# LinuxDeploy-Pro APK 重构方案（前端）

> 状态：待确认 | 关联：CLI 4.0（本工作区 src/linuxdeploy-cli）
> 目标：完全重绘 UI（Miuix/HyperOS 风格），适配 Android 16 特性，支持无障碍，全中文界面与日志

## 一、现状（原版 3.1.0 APK）

- 纯 Java（28 个类，0 Kotlin），Material 1.1.0，targetSdk 28
- 侧边栏（DrawerLayout）导航：主页 / 配置 / 设置 / 关于 都在侧边栏入口
- 配置管理独立 Activity（ProfilesActivity），切换配置要先进侧边栏 → 配置文件页
- 二进制部署：assets/bin/{arm,arm_64,x86,x86_64} + UpdateEnvTask 解压并设置环境变量
- 已有 FullscreenActivity（终端）、RepositoryActivity（镜像仓库页）

不合理点（本次要解决）：
1. 侧边栏层级深，配置入口绕，切换配置要两步以上；
2. 主页是纯文字状态，没有设备信息概览；
3. 默认 Material 配色不符合预期（用户意见），无主题切换、无 AMOLED 纯黑。

## 二、导航结构（取消侧边栏）

主界面 = 单 Activity + 底部三栏（BottomNavigation）+ 右上角固定齿轮：

```
┌──────────────────────────────┐
│  LinuxDeploy      ⚙(设置)    │  ← 顶栏，右上角固定齿轮
├──────────────────────────────┤
│                              │
│     页面内容（切换区域）       │
│                              │
├──────────────────────────────┤
│  🏠 主页    📦 容器   ➕ 待定  │  ← 底部三栏
└──────────────────────────────┘
```

- 三个 tab：左侧**主页**、中间**容器**、右侧**待定**（见第五节候选）
- 右上角固定齿轮 → 设置页（Activity 或顶栏抽屉）
- 不再有侧边栏；配置的"新建/切换"全部在容器页内完成

## 三、页面设计

### 1. 主页（设备信息，参照 KSU / DroidSpace 风格）

信息卡片列表（每项：图标 + 名称 + 值）：

| 项目 | 取值途径 |
|---|---|
| 内核版本 | /proc/version（uname -r） |
| SELinux 状态 | /sys/fs/selinux/enforce（Enforcing / Permissive） |
| Android 版本 | Build.VERSION.RELEASE + SDK |
| 架构 | Build.SUPPORTED_ABIS[0] / uname -m |
| 支持的 ABI | Build.SUPPORTED_ABIS 全列表 |
| SoC 型号 | getprop ro.soc.model / ro.board.platform / ro.hardware |

说明：**不探测、不展示 GPU**（用户明确要求），主页只写 SoC。
风格：卡片 + 大图标 + 等宽数值，深色下也用高对比。

### 2. 容器页（核心页）

**列表态**：
- 卡片列表，每个配置一张卡：配置名称、发行版图标、容器目录、运行状态（运行/停止）、启动按钮
- 可点击卡片 → 配置详情/操作
- 多配置并存显示（不互斥）

**新建/编辑态**（右下角 FAB"＋"进入）：
- 字段与 CLI 4.0 参数一一对应：
  - 配置名称（唯一）
  - 发行版（debian / ubuntu / kali / alpine / archlinux / slackware / rootfs）
  - 架构（固定 arm64）
  - 发行版代号 SUITE（如 trixie）
  - 容器路径 CHROOT_DIR（默认自动隔离 builds/<名称>）
  - 软件源 SOURCE_PATH
  - 用户名 / 密码（默认 android / changeme）
  - 额外组件 INCLUDE（core、extra/ssh 等）
  - 额外软件包 EXTRA_PACKAGES
  - 安装方式（固定 chroot，界面只读展示）
- 保存 → 写 CLI 配置（config create / config edit），容器页刷新列表

**配置详情/操作**：部署、启动、停止、进入终端、导出/导入 rootfs、编辑、删除

**镜像管理**（file 型容器）：
- 在配置编辑内显示当前镜像大小 + "调整大小"入口
- 调整 = resize 镜像文件（不丢数据），仅支持 ext4 镜像
- 依赖 resize2fs（e2fsprogs），需要随包提供 arm64 二进制

### 3. 设置页（右上角齿轮进入）

- **重装运行环境**（原"重新部署二进制"有歧义，改名）：把内置 cli.sh + busybox + resize2fs 等重新解压部署到应用目录，并更新 PATH / 环境变量（对应原版 UpdateEnvTask；命名待确认）
- **主题**：浅色 / 深色（纯黑 AMOLED #000000）/ 跟随系统；配色走 Miuix（HyperOS）体系，不用安卓默认配色
- 其他保留项：日志输出、关于、开源许可

## 四、技术选型

| 项目 | 选择 | 说明 |
|---|---|---|
| 语言 | Kotlin | 原版是 Java，重构直接上 Kotlin |
| UI | Jetpack Compose + Miuix | top.yukonga.miuix.kmp:miuix-ui（最新 0.9.3，严格还原 HyperOS 设计）|
| 导航 | 单 Activity + Navigation Compose + 底部栏 | 取消侧边栏 |
| 主题 | Miuix 自定配色 | 浅色走 HyperOS 浅色体系，深色 = 纯黑 AMOLED；不用安卓默认 Material 配色 |
| 无障碍 | 全组件 contentDescription + 语义 + TalkBack 走查 | 用户明确要求；Miuix 0.9.2 起已修复导航项 TalkBack 双读 |
| 与 CLI 对接 | 执行 cli.sh 子进程，解析中文输出 | msg() 统一出口已为解析做准备 |
| 长任务 | 前台服务/协程 | deploy/export 等耗时长任务 |
| SDK | compileSdk 最新；targetSdk 视二进制执行实测 | 见待确认 |

Android 16 特性可用项（与 targetSdk 解耦/低耦合的部分）：
- Edge-to-edge 全面屏适配（Compose 天然支持）
- 预测性返回动画（需 targetSdk 35+ 才完整启用，待实测）
- Miuix 主题跟随系统 / 深浅色自动切换（安卓 12+）
- 大屏/折叠屏自适应布局（窗口尺寸类）

### UI 风格借鉴参考（开源项目，注意许可证）

- **Droidspaces**（ravindu644/Droidspaces-OSS，GPL-3.0）：主页信息卡片、容器列表/管理交互布局
- **MMRL / KernelSU-Next**：设置页、主题切换、开关组件风格
- **Miuix 官方 demo**（compose-miuix-ui/miuix 的 example 模块）：组件用法与配色样例
- 只借鉴布局/交互/视觉语言，代码按各自许可证合规使用

### 素材收集（新增，正式版前补齐）

| 素材 | 来源/方案 |
|---|---|
| 发行版 logo | 各发行版官方品牌资源：Debian 螺旋、Ubuntu 圆环、Kali 龙、Alpine、Arch、Slackware；SVG 转矢量/PNG 多密度 |
| 应用图标 | 基于原版图标重绘，Miuix 风格圆角/squircle |
| 中文字体 | MiSans（小米开源字体，免费商用），西文随 MiSans 拉丁部分；打包为 assets 字体 |
| 图标集 | miuix-icons（随 Miuix 提供）|

初版空壳 UI 可先用占位素材/系统图标，不影响页面测试。

## 五、右侧第三个 tab：候选

用户未定，候选：
1. **镜像仓库**：原版 RepositoryActivity 迁移（浏览/下载预置 rootfs 镜像）
2. **日志页**：集中查看 CLI 构建/运行日志（中文日志的展示价值高）
3. **终端页**：原版 FullscreenActivity 迁移（容器 shell 终端）

## 六、已知限制 / 待实测

1. **多容器同时运行**：chroot 技术上可同时挂载多个，但共享内核网络栈，端口必然冲突；
   列表允许多配置并存，运行时冲突先在文档/界面提示，不强制拦截。
2. **targetSdk 与二进制执行**：原版锁定 28 是因为"私有二进制执行"限制；
   需实测高 targetSdk（34/35/36）下执行 app 私有目录 busybox/cli 是否可行，
   可行则升（获得更多 Android 16 特性），不行保持 28。
3. **镜像 resize 与 resize2fs**：
   - Debian/Kali 现行源里没有包含 resize2fs 的静态包（只有 e2fsck-static）；
   - 方案：用 e2fsprogs 源码在 k30p 的 Debian 13 容器内静态编译（`LDFLAGS=--static` + `make all-static` → resize/resize2fs.static），产物放 vendor/ 并纳入 APK assets/bin/arm_64；
   - FerryAr/e2fsprogs-arm 已归档且为 32 位 ARM，不可用（仅作参考）。
4. **busybox**：现代 KernelSU/Magisk 一般内置 busybox，但不依赖 root 管理器版本；APK 仍随包内置自己的 busybox（沿用原版 assets 或按需重编），以 CLI 运行需要为准，root 管理器自带仅作兜底。
5. **Android 16 特性**：K30 Pro 实际系统可能低于 16，特性按 API 级别渐进降级，UI 不影响旧系统。

## 七、实施顺序（建议）

1. 确认本方案（导航、tab、页面字段、主题风格、素材、SDK 策略、设置项命名）
2. 空壳 UI 初版：Kotlin + Compose + Miuix + 三栏导航 + 主题 + 无障碍基础；不含后端，只测页面切换与开关
3. 素材收集与占位替换
4. 主页设备信息 → 容器列表/配置编辑 → 设置/重装运行环境 → 镜像管理（resize）
5. CLI 联调（执行、日志解析、状态回显）
6. 语言包（中文为主）与发布准备

## 八、待拍板

1. 右侧第三个 tab：镜像仓库 / 日志页 / 终端页（建议日志页，中文日志展示价值最高）
2. targetSdk 策略：先实测高 targetSdk 下二进制执行，能升则升
3. 多容器同时运行：允许多配置并存，端口冲突仅提示不拦截（确认）
4. 设置项名称："重装运行环境" 是否合适（原"重新部署二进制"有歧义）
5. 主题深色：纯黑 AMOLED #000000（确认）
