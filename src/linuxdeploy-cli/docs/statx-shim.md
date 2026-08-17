# 构建期 statx 兼容层（statx-shim）

## 问题背景

Android 内核（k30p 为 4.19）的 `statx()` 系统调用不支持
`STATX_MNT_ID`（内核 5.8+ 引入）。内核会**静默忽略**该掩码位：
系统调用返回成功，但 `stx_mask` 里始终没有 `STATX_MNT_ID`。

systemd 260+ 的 `xstatx_full()`（`src/basic/stat-util.c`）在请求
`XSTATX_MNT_ID_BEST` 后，发现返回的 `stx_mask` 既无 `STATX_MNT_ID`
也无 `STATX_MNT_ID_UNIQUE`，会主动返回 `-EUNATCH`
（`strerror` 为 "Protocol driver not attached"）。

后果：Kali rolling（systemd 261）等新发行版在 debootstrap 二阶段 /
apt 配置时，`systemctl enable`、`systemd-machine-id-setup` 等命令
直接失败，构建中止。

## 解决方案

`statx_shim.c` 是一个 LD_PRELOAD 小工具：

1. 拦截 libc 的 `statx()`；
2. 去掉请求掩码中内核不支持的 `STATX_MNT_ID / STATX_MNT_ID_UNIQUE`
   后再调用真实 `statx()`；
3. 成功后把 `STATX_MNT_ID` 位补回 `stx_mask`；
4. 从 `/proc/self/fdinfo/<fd>` 读取真实 mnt_id（内核 3.15+ 就有该
   字段）填入 `stx_mnt_id`，保证 systemd 的挂载点判断仍然正确。

只声明 `STATX_MNT_ID` 可用，**不伪造** `STATX_MNT_ID_UNIQUE`
（内核 6.9+ 才有的"唯一挂载 ID"，4.19 无法保证唯一性）。

## 生效范围

- 仅 arm64（本项目只维护 arm64；shim 经 libc `syscall()` 调用 `SYS_statx`，
  不依赖架构特定汇编）；
- 仅内核 < 5.8（`uname -r` 判断）；
- 仅 glibc 容器（检测 `ld-linux-*.so*`，musl/Alpine 自动跳过）；
- 仅构建期：部署开始时编译，debootstrap 一阶段完成后装入容器，
  部署结束由 `trap EXIT` 自动移除，不污染运行时容器。

## 相关文件

- `statx_shim.c`：兼容层源码（宿主 gcc 编译）；
- `statx_test.c`：兼容层自测程序，`gcc -o statx_test statx_test.c` 编译后
  分别用 `./statx_test` 与 `LD_PRELOAD=./statx_shim.so ./statx_test`
  运行，对比 stx_mask / stx_mnt_id 的差异；
- `cli.sh`：`kernel_need_statx_shim` / `statx_shim_ensure_compiled` /
  `statx_shim_install` / `statx_shim_cleanup` / `statx_shim_prepare`
  及 `chroot_exec` 注入逻辑；
- `include/bootstrap/debian/deploy.sh`：二阶段前启用；
- `include/bootstrap/archlinux/deploy.sh`：基础包解包后启用。
