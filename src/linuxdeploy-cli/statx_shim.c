/*
 * statx 兼容层（statx_shim.c）
 *
 * 背景
 * ----
 * k30p 的内核是 Android 4.19（< 5.8），其 statx() 系统调用不支持
 * STATX_MNT_ID / STATX_MNT_ID_UNIQUE 掩码位。内核的行为是"静默忽略"
 * 这些位：系统调用本身返回成功，但 stx_mask 永远只有基本字段（0x7ff）。
 *
 * systemd >= 260 的 xstatx_full()（src/basic/stat-util.c）在请求
 * XSTATX_MNT_ID_BEST 后，如果返回的 stx_mask 里既没有 STATX_MNT_ID
 * 也没有 STATX_MNT_ID_UNIQUE，会主动返回 -EUNATCH（strerror 显示为
 * "Protocol driver not attached"）。这导致 Kali rolling 等新发行版里
 * systemd 的 dpkg postinst（systemctl enable / systemd-machine-id-setup）
 * 在构建阶段直接失败。
 *
 * 本 shim 通过 LD_PRELOAD 拦截 libc 的 statx()：
 *   1. 先去掉请求掩码里内核不支持的 MNT_ID 相关位，再调用真实 statx；
 *   2. 成功后把 STATX_MNT_ID 位补回 stx_mask；
 *   3. 尽量从 /proc/self/fdinfo/<fd> 读出真实 mnt_id 填入 stx_mnt_id
 *      （该字段自内核 3.15 起就存在于 fdinfo，4.19 完全可用）。
 *
 * 只声明 STATX_MNT_ID 可用、绝不伪造 STATX_MNT_ID_UNIQUE：后者是
 * 内核 6.9+ 才有的"唯一挂载 ID"能力，4.19 无法保证唯一性。
 *
 * 使用方式（仅构建期）：
 *   export LD_PRELOAD=/usr/lib/statx-shim.so
 * 只对 debootstrap stage2 / 软件包配置阶段的子进程生效，不进运行时容器。
 */

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* ---- 常量定义（直接写数值，避免依赖宿主头文件版本差异） ---- */

/* statx 掩码位（linux/stat.h UAPI，数值稳定） */
#define SHIM_STATX_MNT_ID        0x00001000U
#define SHIM_STATX_MNT_ID_UNIQUE 0x00004000U

/* statx 标志位（linux/fcntl.h UAPI，数值稳定） */
#define SHIM_AT_EMPTY_PATH        0x1000
#define SHIM_AT_SYMLINK_NOFOLLOW  0x0100

/* openat 标志（aarch64 上 O_PATH/O_CLOEXEC/O_NOFOLLOW 的取值） */
#define SHIM_O_PATH      0x200000
#define SHIM_O_CLOEXEC   0x080000
#define SHIM_O_NOFOLLOW  0x020000

#ifndef SYS_statx
/* aarch64 上 statx 的系统调用号（老工具链兜底） */
#define SYS_statx 291
#endif

/* ---- 真实 statx 系统调用 ----
 * 用 libc 的 syscall() 入口而非内联汇编：
 * 1) 不依赖架构特定的寄存器约定，宿主 gcc/clang 都能编译；
 * 2) 我们拦截的是 statx() 符号，调用 syscall() 不会递归回本函数；
 * 3) 失败时返回值即 -errno，与内核 ABI 一致。 */
static long shim_raw_statx(int dirfd, const char *pathname, int flags,
                           unsigned int mask, struct statx *buf)
{
    return syscall(SYS_statx, dirfd, pathname, flags, mask, buf);
}

/* ---- 从 /proc/self/fdinfo/<fd> 读取真实 mnt_id ---- */
static int shim_mnt_id_from_fd(int fd, uint64_t *out)
{
    char path[64];
    char buf[1024];
    char *p;
    ssize_t n;
    int f;

    /* /proc 没挂载（debootstrap 极早期）时直接放弃，mnt_id 保持 0 */
    snprintf(path, sizeof(path), "/proc/self/fdinfo/%d", fd);
    f = open(path, O_RDONLY | SHIM_O_CLOEXEC);
    if (f < 0)
        return -1;

    n = read(f, buf, sizeof(buf) - 1);
    close(f);
    if (n <= 0)
        return -1;
    buf[n] = '\0';

    /* fdinfo 里形如 "mnt_id:\t123" 的一行 */
    p = strstr(buf, "mnt_id:");
    if (!p)
        return -1;
    p += strlen("mnt_id:");
    while (*p == ' ' || *p == '\t')
        p++;
    if (*p < '0' || *p > '9')
        return -1;

    *out = (uint64_t)strtoull(p, NULL, 10);
    return 0;
}

/* ---- 被拦截的 statx：给旧内核补上 MNT_ID 能力 ---- */
int statx(int dirfd, const char *pathname, int flags, unsigned int mask,
          struct statx *buf)
{
    unsigned int want;
    const char *p_path;
    long ret;

    /* 去掉内核 4.19 不支持的位，再交给真实 statx */
    want = mask & ~(SHIM_STATX_MNT_ID | SHIM_STATX_MNT_ID_UNIQUE);
    /* 头文件把 pathname 声明成 nonnull，但拦截层需要兼容 NULL 调用，
     * 先复制到本地变量再判空，避开编译器的 nonnull-compare 告警 */
    p_path = pathname;
    ret = shim_raw_statx(dirfd, p_path, flags, want, buf);
    if (ret < 0) {
        /* 通过 errno 宏写回，编译器会按目标 libc 生成正确的
         * 线程局部 errno 访问（glibc 走 __errno_location） */
        errno = (int)(-ret);
        return -1;
    }

    /* 调用方请求了 MNT_ID 相关位时才需要补位，其余情况原样返回 */
    if (mask & (SHIM_STATX_MNT_ID | SHIM_STATX_MNT_ID_UNIQUE)) {
        uint64_t mnt_id = 0;

        /* AT_EMPTY_PATH：dirfd 就是目标，直接读它的 fdinfo */
        if ((flags & SHIM_AT_EMPTY_PATH) && dirfd >= 0) {
            if (shim_mnt_id_from_fd(dirfd, &mnt_id) < 0)
                mnt_id = 0;

        /* 普通路径：用 O_PATH 打开拿到 fd 再读 fdinfo，语义与 statx 一致 */
        } else if (p_path) {
            int ofd = openat(dirfd, p_path,
                             SHIM_O_PATH | SHIM_O_CLOEXEC |
                             ((flags & SHIM_AT_SYMLINK_NOFOLLOW) ?
                              SHIM_O_NOFOLLOW : 0));
            if (ofd >= 0) {
                if (shim_mnt_id_from_fd(ofd, &mnt_id) < 0)
                    mnt_id = 0;
                close(ofd);
            }
        }

        buf->stx_mnt_id = mnt_id;
        buf->stx_mask |= SHIM_STATX_MNT_ID;
    }

    return 0;
}
