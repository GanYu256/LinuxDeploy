/*
 * statx 兼容层自测程序（statx_test.c）
 *
 * 分别测试：
 *   - AT_EMPTY_PATH + fd 场景
 *   - 普通路径场景
 * 并打印 stx_mask / stx_mnt_id，用于对比加不加 LD_PRELOAD 的行为。
 */
#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* bionic（Android）头文件不提供 statx() 声明，手动补一个，
 * ABI 与 Linux UAPI 一致，glibc 下重复声明也无冲突。 */
int statx(int dirfd, const char *pathname, int flags,
          unsigned int mask, struct statx *buf);

#ifndef STATX_MNT_ID
#define STATX_MNT_ID 0x00001000U
#endif
#ifndef STATX_MNT_ID_UNIQUE
#define STATX_MNT_ID_UNIQUE 0x00004000U
#endif

static void show(const char *tag, const struct statx *sx)
{
    printf("%s: mask=0x%x mnt_id=%llu dev=%u:%u ino=%llu\n",
           tag,
           sx->stx_mask,
           (unsigned long long)sx->stx_mnt_id,
           sx->stx_dev_major, sx->stx_dev_minor,
           (unsigned long long)sx->stx_ino);
}

int main(void)
{
    struct statx sx;
    int fd;

    printf("offsetof(stx_mnt_id)=%zu sizeof(struct statx)=%zu\n",
           offsetof(struct statx, stx_mnt_id), sizeof(struct statx));

    /* 场景一：AT_EMPTY_PATH + fd（systemd chase_statx 的用法） */
    fd = open(".", O_PATH | O_CLOEXEC);
    if (fd < 0) {
        perror("open");
        return 1;
    }
    memset(&sx, 0, sizeof(sx));
    if (statx(fd, "", AT_EMPTY_PATH,
              STATX_TYPE | STATX_INO | STATX_MNT_ID | STATX_MNT_ID_UNIQUE,
              &sx) < 0) {
        perror("statx(AT_EMPTY_PATH)");
        return 1;
    }
    show("fd", &sx);
    close(fd);

    /* 场景二：普通路径 */
    memset(&sx, 0, sizeof(sx));
    if (statx(AT_FDCWD, "/", 0, STATX_MNT_ID, &sx) < 0) {
        perror("statx(path)");
        return 1;
    }
    show("path", &sx);

    return 0;
}
