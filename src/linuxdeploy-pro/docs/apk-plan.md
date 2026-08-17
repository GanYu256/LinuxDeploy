# LinuxDeploy-Pro APK 重构方案（前端）

> 状态：已确认（空壳 UI 先行） | 关联：CLI 4.0（容器工作区 src/linuxdeploy-cli）
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
3. 默认 Material 配色不符合预期，无主题切换、无 AMOLED 纯黑。

## 二、导航结构（取消侧边栏）

底部三栏：主页 / 容器 / 待定（右侧预留）。右上角固定齿轮进入设置。

- 主页：内核 / SELinux / Android 版本 / 架构 / ABI / SoC（按需求不探测 GPU）
- 容器：配置列表（多配置），新建按钮进入配置编辑页（名称、路径、发行版、安装方式等），保存后列表显示
- 配置详情：部署 / 启停 / 终端 / 导入导出 / 镜像管理（resize2fs 调整镜像大小，不影响数据）/ 删除
- 设置：主题（跟随系统 / 浅色 / 纯黑 AMOLED）、重装运行环境、关于

## 三、技术选型

- Kotlin + Jetpack Compose，AGP 9.3.1（内置 Kotlin），Compose 编译器 2.4.10
- Compose BOM 2026.06.01，Miuix 0.9.3（HyperOS 风格组件库）
- compileSdk 37（Miuix/core-ktx 最新版要求），targetSdk 36（Android 16），minSdk 28（私有二进制执行基线）
- 包名改为 io.github.ganyu256.linuxdeploypro，图标重绘（蓝紫渐变 + 终端提示符）

## 四、执行后校验 / 回调（无障碍）

CLI 命令执行后由前端做结果校验与中文回显；CLI 侧输出保持结构化（退出码 + 关键行），
前端据此判断成功/失败，避免"跑没跑起来都不知道"。

## 五、里程碑

1. 空壳 UI（本版：页面结构 + 主题 + 设备信息读取）
2. 素材：发行版 logo、MiSans 字体、图标集
3. 容器配置 CRUD 完善 + 镜像管理
4. CLI 联调：执行 cli.sh、解析中文日志、状态回显
5. 语言包与发布准备
