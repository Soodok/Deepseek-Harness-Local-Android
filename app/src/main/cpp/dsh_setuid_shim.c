/*
 * dsh_setuid_shim —— 让 xorg 的 Popen() 在 Android App 域里正常工作。
 *
 * ## 症状与根因
 *
 * Xvfb 初始化键盘会调 xkbcomp，走 xserver 自己封装的 "safer popen"
 * （os/utils.c: Popen）。子进程分支：
 *
 *     if (setgid(getgid()) == -1) _exit(127);
 *     if (setuid(getuid()) == -1) _exit(127);
 *     ...
 *     execl("/bin/sh", "sh", "-c", command, NULL);
 *
 * 即"先放弃所有特权，再执行命令"。Android App 域（u:r:untrusted_app_27，
 * CapEff=0）内核一律拒绝 setuid/setgid —— 即使传的就是进程**自己当前**的
 * uid/gid —— 子进程按源码 _exit(127)，父进程 Pclose() 得 127，于是：
 *
 *     Error compiling keymap
 *     XKB: Failed to compile keymap
 *     Keyboard initialization failed...
 *     (Fatal) Failed to activate virtual core keyboard: 2
 *
 * Xvfb 当场退出、一个 listening socket 都不建 —— 用户看到的是"点开始画面
 * 无反应"，界面上没有任何线索。
 *
 * 真机取证（2026-11-21，PLZ110 / Android 16 / uid 10501）：
 *   /proc/self/status: Uid/Gid 全 10501、CapEff=0000000000000000、Seccomp 2
 *   LD_PRELOAD 追踪结果:  p11307 [getgid] 10501 → [setgid] 10501 -1 → [_exit] 127
 *
 * ## 这个 shim 做什么（以及刻意不做什么）
 *
 * **只**把「放弃自己本来就已经是的那个身份」按 no-op 处理：请求的 uid/gid
 * 与当前 real/effective 完全相等时直接返回成功。这是恒等变换 —— 进程本来
 * 就是这个身份，内核在 App 域里也绝无可能给它别的身份，所以"放弃"在语义上
 * 什么都没发生。
 *
 * 任何**其它**请求（切到别的 uid/gid、提权）原样转发给内核，失败就如实失败。
 * 绝不伪造成功 —— 否则会掩盖真实的权限问题。
 *
 * ## 为什么只用裸 syscall
 *
 * xorg 的 Popen 在 **fork 出来的子进程**里调 setuid。此时调 dlsym 可能撞上
 * dl 锁（多线程 fork 后锁状态未定义），等于自找死锁。裸 syscall 不碰任何
 * 运行时状态。
 *
 * ## 第二个用途：accept → accept4 兜底（2026-11-21 新增）
 *
 * Xvfb 的监听套接字走 os/access.c 的 _XSERVTransSocketUNIXAccept，底层是
 * **accept(2)**。在 App 域这一路被 zygote 那层 seccomp 拦死：
 *
 *     真机取证（PLZ110 / Android 16 / App 域，替身 .so 内直发裸 syscall）：
 *         [A] accept(202) → -1        ← -1 = -EPERM，被外层 seccomp 拒
 *         [A] accept4(242) → 16        ← 同样条件、同样 fd，成功
 *
 * 后果：accept 永远失败，Xvfb 死循环刷
 *     _XSERVTransSocketUNIXAccept: accept() failed
 * 一个客户端都接不进来，x11frsrv 连不上、dshframe.bin 永不落盘，UI 侧只看到
 * DSHFRAME_FAIL no-frame —— 与 setuid 那条症状完全不同的另一条故障。
 *
 * run-as 域（u:r:runas_app）的 Seccomp 是 0/1 层，accept 不被拦，所以
 * "同一脚本同 uid 手跑成功、App 跑失败"。这层差异只能靠替身 .so 里打裸
 * syscall 才看得见，LD_PRELOAD 的 dlsym 包装同样会被拦。
 *
 * 处理：accept 先直发 202；失败就转 242（flags=0，语义等同），成功的 fd
 * 原样返回。两条都失败时如实返回 accept4 的 errno。这层兜底同样**不伪造
 * 成功**：被拒的连接绝不会凭空变成已连上。
 *
 * aarch64 编号取自 NDK sysroot/usr/include/asm-generic/unistd.h；
 * x86_64 取自 asm/unistd_64.h。
 */
#include <stddef.h>

#if defined(__aarch64__)
#  define NR_getuid    174
#  define NR_geteuid   175
#  define NR_getgid    176
#  define NR_getegid   177
#  define NR_setuid    146
#  define NR_setgid    144
#  define NR_setreuid  145
#  define NR_setregid  143
#  define NR_setresuid 147
#  define NR_setresgid 149
#  define NR_accept    202
#  define NR_accept4   242
#elif defined(__x86_64__)
#  define NR_getuid    102
#  define NR_geteuid   107
#  define NR_getgid    104
#  define NR_getegid   108
#  define NR_setuid    105
#  define NR_setgid    106
#  define NR_setreuid  113
#  define NR_setregid  114
#  define NR_setresuid 117
#  define NR_setresgid 119
#  define NR_accept    43
#  define NR_accept4   288
#else
#  error "unsupported arch: only aarch64 and x86_64 are shipped"
#endif

#if defined(__aarch64__)
static long raw0(long n)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = 0;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8) : "memory");
    return x0;
}
static long raw1(long n, long a)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8) : "memory");
    return x0;
}
static long raw2(long n, long a, long b)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1) : "memory");
    return x0;
}
static long raw3(long n, long a, long b, long c)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}
static long raw4(long n, long a, long b, long c, long d)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
    return x0;
}
#else /* x86_64 */
static long raw0(long n)
{
    long r;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n) : "rcx", "r11", "memory");
    return r;
}
static long raw1(long n, long a)
{
    long r;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a) : "rcx", "r11", "memory");
    return r;
}
static long raw2(long n, long a, long b)
{
    long r;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b) : "rcx", "r11", "memory");
    return r;
}
static long raw3(long n, long a, long b, long c)
{
    long r;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c) : "rcx", "r11", "memory");
    return r;
}
static long raw4(long n, long a, long b, long c, long d)
{
    register long r10 __asm__("r10") = d;
    long r;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10)
                     : "rcx", "r11", "memory");
    return r;
}
#endif

/* 每次都现取：xorg 传进来的就是 getuid()/getgid() 的返回值。 */
static long cur_ruid(void) { return raw0(NR_getuid); }
static long cur_euid(void) { return raw0(NR_geteuid); }
static long cur_rgid(void) { return raw0(NR_getgid); }
static long cur_egid(void) { return raw0(NR_getegid); }

int setuid(int u)
{
    /* 放弃"自己本来就是"的 real uid：恒等变换，no-op。 */
    if ((long)u == cur_ruid() && (long)u == cur_euid()) return 0;
    return (int)raw1(NR_setuid, (long)u);
}

int setgid(int g)
{
    if ((long)g == cur_rgid() && (long)g == cur_egid()) return 0;
    return (int)raw1(NR_setgid, (long)g);
}

int seteuid(int u)
{
    if ((long)u == cur_euid()) return 0;
    return (int)raw1(NR_setuid, (long)u);
}

int setegid(int g)
{
    if ((long)g == cur_egid()) return 0;
    return (int)raw1(NR_setgid, (long)g);
}

int setreuid(int r, int e)
{
    if ((r == -1 || (long)r == cur_ruid()) && (e == -1 || (long)e == cur_euid())) return 0;
    return (int)raw2(NR_setreuid, (long)r, (long)e);
}

int setregid(int r, int e)
{
    if ((r == -1 || (long)r == cur_rgid()) && (e == -1 || (long)e == cur_egid())) return 0;
    return (int)raw2(NR_setregid, (long)r, (long)e);
}

int setresuid(int r, int e, int s)
{
    if ((r == -1 || (long)r == cur_ruid()) &&
        (e == -1 || (long)e == cur_euid()) &&
        (s == -1 || (long)s == cur_ruid())) return 0;
    return (int)raw3(NR_setresuid, (long)r, (long)e, (long)s);
}

int setresgid(int r, int e, int s)
{
    if ((r == -1 || (long)r == cur_rgid()) &&
        (e == -1 || (long)e == cur_egid()) &&
        (s == -1 || (long)s == cur_rgid())) return 0;
    return (int)raw3(NR_setresgid, (long)r, (long)e, (long)s);
}

/* ==========================================================================
 * accept → accept4 兜底
 *
 * 只在 accept(202) 真的失败时才试 accept4(242)：App 域里 202 被 zygote 的
 * seccomp 判死（实测恒返回 -1/-EPERM），242 放行。成功路径一个 syscall 都
 * 不多加，对没被拦的环境（run-as / adb shell / 老版本 Android）零开销。
 *
 * flags 传 0 —— 与 accept() 的语义等价，不加 CLOEXEC。Xvfb 的监听 fd 自己
 * 就在 exec 时关了 CLOEXEC（socket() 时设的），连接 fd 的 CLOEXEC 与否不影响
 * 它的行为。
 *
 * 用 **裸 syscall** 而不是转发给 libc 的 accept4：这层 shim 可能被
 * LD_PRELOAD 进静态/半静态程序，也可能在 dl 锁未定义状态下调用；裸 syscall
 * 不碰任何运行时状态，且不会被我们自己再拦一次（我们只导出了 accept/accept4
 * 两个符号，裸 syscall 不经过符号解析）。
 * ========================================================================== */

int accept(int fd, void *addr, unsigned int *alen)
{
    long r = raw3(NR_accept, (long)fd, (long)addr, (long)alen);
    if (r >= 0) return (int)r;
    /* 202 被拒：退到 242。语义相同，只是多了 flags 形参（传 0）。 */
    r = raw4(NR_accept4, (long)fd, (long)addr, (long)alen, 0L);
    /* 两条都失败：按 POSIX 返回 -1（裸 syscall 的 -errno 不能直接当返回值用）。 */
    return r >= 0 ? (int)r : -1;
}

int accept4(int fd, void *addr, unsigned int *alen, int flags)
{
    long r = raw4(NR_accept4, (long)fd, (long)addr, (long)alen, (long)flags);
    return r >= 0 ? (int)r : -1;
}
