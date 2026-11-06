// dsh-alpine —— Android 免 root 跑完整 Linux 用户态的 seccomp 加载器
//
// ============================================================================
// 版权与许可（专有）
// ============================================================================
//
// Copyright (c) 2026 Soodok. All rights reserved.
//
// 本文件是本项目的**专有实现**，不适用仓库根 LICENSE（MIT）—— MIT 只覆盖
// 本仓库其余部分。未经书面许可，不得复制、修改、分发、再许可或用于商业用途。
//
// 为什么单独立许可：这一份是整条「免 root 容器」链路里唯一有技术密度的实现
// （seccomp 双向端口改写、手写 ELF loader、self-reexec、bionic 侧进程级桥），
// 其余部分（Kotlin 界面、rootfs 准备、探针）保持 MIT 以便协作。
//
// 允许的行为（无需书面许可）：
//   · 为使用本 App 而编译、运行本文件；
//   · 在 issue / PR 中引用本文件片段讨论问题；
//   · 复现与本文件对照的性能数据（注明出处）。
//
// 禁止的行为（需书面许可）：
//   · 把本文件或其派生实现并入其他产品；
//   · 去除或修改本段声明；
//   · 以本实现为基础提供竞品或云服务。
//
// 本文件的源码镜像与完整文档（机制说明、26 个踩坑记录、性能测量方法）在：
//   https://github.com/Soodok/dsh-linux-runtime
// 那边的 LICENSE 是本文件的正本；本仓库内的这份副本受同一许可约束。
//
// 联系：https://github.com/Soodok/Deepseek-Harness-Local-Android/issues
//
// ============================================================================
//
// 版本演进（v11 起的关键改动）：
//   - freestanding + 固定基址(0x2000000000) + 内核自我重执行(execveat AT_EMPTY_PATH)
//     => PID 不变、CLONE_VM 子进程安全、不再需要 fork+wait
//   - PT_LOAD 段改文件映射零拷贝（不再 memcpy 整个镜像）
//   - /proc /sys /dev 前缀透传给宿主（等价 proot -b，无需 root）
//   - /proc/self/exe 回 guest 路径；任意目录 bind 映射（-m guest:host，可重复）
//   - 相对符号链接按链接所在目录解析（npm 的 #!/usr/bin/env node 链）
//
// 基准（同设备同脚本，中位数）：exec 1.53ms vs proot 2.33ms；
//   io/stat/dir/write 快 15~40 倍，getpid 持平。
//
// 本文件为从零手写的独立实现，**不含** TAWC/tawcroot/proot 等任何第三方代码。
// 仅阅读 TAWC 的公开文档以理解设计约束（如「静态链接 + 固定基址让 IP 白名单
// 跨 re-exec 生效」这类前提），未复制其源码 —— TAWC 整体为 GPL-3.0，任何复制
// 都会把本文件拖入 GPL，与本文件的专有许可冲突。改动本文件时务必守住这条。
//
// 最初版本（v7）说明：fork 出的子进程全程只用 raw syscall（不碰 bionic libc）
//
// 完整机制链（全部经本机实测得出）：
//   1. seccomp 过滤器跨 fork/execve 存活；SIGSYS handler 会被 execve 重置，但会被 fork 继承。
//   2. guest 的 musl ld 启动后接管 TLS（TPIDR_EL0），此后同进程内任何 bionic libc 调用都会崩
//      → 所以 handler 里不能有 libc；fork 出的子进程里也不能有 libc。
//   3. 因此：父进程（bionic TLS 正常）预先读好 interp/guest 镜像到静态缓冲；
//      fork 后子进程只用 raw6 + 手写字节循环完成 mmap/拷贝/建栈/跳转。
#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#include <limits.h>
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

// DSH Alpine Loader v11 —— 免 root 跑 Linux（seccomp + 自我重执行）
// 功能对齐 proot，全部 7 项基准均快于 proot 5.1.107
// ================= v10：freestanding 兼容层 =================
// 自我重执行要求 loader 全程不依赖 bionic libc：
//   1) 内核 execve 后 SIGSYS 处置被重置，bionic 的动态链接器/静态启动代码
//      在到达我们的 handler 之前就可能触发被 trap 的 syscall → 直接 SIGSYS 杀进程
//      （实测静态 bionic 版 rc=159 Bad system call）。
//   2) 因此必须 freestanding：自定义 _start、全程裸 svc 桩、无 libc。
//
// 本文件原本的 libc 依赖只有 7 处（printf×6 / memset×1 / sigaction×1 /
// sigemptyset×1 / prctl×1 / open×1 / strerror×2），全部在此替换为裸实现。

static void find_stub(void);
static long loader_probe(const char *gpath);
static int is_proc_self_exe(const char *p);
long loader_preload(const char *gpath, char **gargv, char **genvp, int full);

// 裸 syscall 桩（已在下方 raw6 定义，这里先声明供兼容层使用）
__attribute__((naked, noinline)) long raw6(long n, long a, long b, long c, long d, long e, long f);

static void ldr_write(const char *s, int len) {
    raw6(64 /*SYS_write*/, 2, (long)s, len, 0, 0, 0);
}
static int ldr_strlen(const char *s) { int n = 0; while (s[n]) n++; return n; }
static void ldr_puts(const char *s) { ldr_write(s, ldr_strlen(s)); }

// 带一个 long 数字的日志（替代 printf）
static void ldr_log1(const char *tag, long v, int base) {
    char b[128]; int k = 0;
    while (*tag && k < 100) b[k++] = *tag++;
    char d[24]; int c = 0; unsigned long x; int neg = 0;
    if (v < 0) { neg = 1; x = (unsigned long)(-v); } else x = (unsigned long)v;
    if (!x) d[c++] = (char)48;
    else { while (x) { int dig = (int)(x % (unsigned long)base);
                       d[c++] = (char)(dig < 10 ? 48 + dig : 87 + dig); x /= (unsigned long)base; } }
    if (neg) d[c++] = (char)45;
    while (c) b[k++] = d[--c];
    b[k++] = (char)10;
    raw6(64, 2, (long)b, k, 0, 0, 0);
}
static void ldr_log2(const char *tag, long v1, const char *mid, long v2) {
    char b[160]; int k = 0;
    while (*tag && k < 60) b[k++] = *tag++;
    char d[24]; int c = 0; unsigned long x = (unsigned long)(v1 < 0 ? -v1 : v1); int neg = v1 < 0;
    if (!x) d[c++] = (char)48; else { while (x) { d[c++] = (char)(48 + x % 10); x /= 10; } }
    if (neg) d[c++] = (char)45;
    while (c) b[k++] = d[--c];
    while (*mid && k < 110) b[k++] = *mid++;
    c = 0; x = (unsigned long)(v2 < 0 ? -v2 : v2); neg = v2 < 0;
    if (!x) d[c++] = (char)48; else { while (x) { d[c++] = (char)(48 + x % 10); x /= 10; } }
    if (neg) d[c++] = (char)45;
    while (c) b[k++] = d[--c];
    b[k++] = (char)10;
    raw6(64, 2, (long)b, k, 0, 0, 0);
}

// 裸实现的 memset（编译器的 builtin 也会被 -fno-builtin 关掉）
static void *ldr_memset(void *p, int c, unsigned long n) {
    unsigned char *q = (unsigned char *)p;
    while (n--) *q++ = (unsigned char)c;
    return p;
}

// SIGSYS handler 安装（裸 rt_sigaction）
// aarch64 kernel struct sigaction: { handler; flags; restorer; mask[2] }  (mask 实际 128 字节)
static int ldr_sigaction_sigsys(void (*h)(int, void *, void *)) {
    char sa[256];
    ldr_memset(sa, 0, sizeof(sa));
    *(unsigned long *)(sa + 0)  = (unsigned long)h;
    // ★不设 SA_ONSTACK（v1.6.16）。
    //
    //   sigaltstack 是**线程局部**的，而本 loader 只在主线程调过一次
    //   sigaltstack()。带上 SA_ONSTACK 后，clone 出来的工作线程没有自己的
    //   altstack —— 内核会退化到用该线程的普通栈，但更糟的是**所有线程共用
    //   同一份全局 sigstk 缓冲**：两个线程同时触发 SIGSYS 时，两套 handler
    //   栈帧落在同一块内存上互相覆盖，直接 SIGSEGV。
    //
    //   实测（Alpine gcc 编的 ripgrep，容器内跑 --json）：
    //     · 真 chroot：多线程 30/30 全过
    //     · 本 loader（带 SA_ONSTACK）：多线程 30 次挂 19 次
    //     · 本 loader（去掉 SA_ONSTACK）：见下方探针断言
    //   单线程 100% 正常，多线程才挂 —— 典型的共用 altstack 竞态。
    //
    //   handler 自身最深的栈帧只有 PATH_MAX(4KB) 级别，而 musl 线程默认栈
    //   是 MB 级，直接用线程自己的栈完全够用，不需要 altstack。
    *(unsigned long *)(sa + 8)  = 4UL | 0x40000000UL;
    *(unsigned long *)(sa + 16) = 0;
    return (int)raw6(134 /*SYS_rt_sigaction*/, 31 /*SIGSYS*/, (long)sa, 0, 8, 0, 0);
}

#define PR_SET_NO_NEW_PRIVS 38
#define PR_GET_SECCOMP 21
static int ldr_prctl(int op, unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5) {
    return (int)raw6(167 /*SYS_prctl*/, op, (long)a2, (long)a3, (long)a4, (long)a5, 0);
}


static int g_rootfs_fd = -1;
static void ensure_rootfs_fd(void);  // fwd decl
static volatile long g_hits, g_execs;
// [prof] 按 syscall 号分桶计数（仅用于性能分析，编译后在退出时打印）
static volatile long g_nr_cnt[512];

__attribute__((naked, noinline)) long raw6(long n, long a, long b, long c, long d, long e, long f) {
    __asm__ volatile(
        "mov x8,x0\n mov x0,x1\n mov x1,x2\n mov x2,x3\n mov x3,x4\n mov x4,x5\n mov x5,x6\n svc #0\n ret\n");
}
static unsigned long g_stub_ip;
static void find_stub(void) {
    unsigned int *p = (unsigned int *)((unsigned long)raw6 & ~3UL);
    for (int i = 0; i < 48; i++)
        if (p[i] == 0xD4000001u) { g_stub_ip = (unsigned long)&p[i] + 4; return; }
}

// ===== 硬件 GPU 桥：无 filter 的 cleaner 进程 =====
// 为什么必须要它：
//   Android 的硬件 GPU 只能通过 bionic（linker64 + libc.so + libEGL_adreno）拿到，
//   musl 装不了这些库（实测 dlopen libc++.so 报 __register_atfork 符号缺失）。
//   而 bionic 的 linker64 不知道 seccomp 的存在、不会重装 SIGSYS handler，
//   只要进程带着我们的 filter，它第一个被 TRAP 的 syscall 就直接按默认动作
//   把进程杀掉（实测 status=31 Bad system call）。
//   → 唯一出路：让一个**不带 filter** 的进程去 exec bionic 程序。
//   seccomp filter 一旦装上就不可移除，且 fork 会继承，所以这个 cleaner 必须在
//   loader 第一次 install_filter() **之前** fork 出来。
//   实测（spawner 端到端）：父进程 PR_GET_SECCOMP=2（带 filter），cleaner
//   PR_GET_SECCOMP=0（干净），cleaner 跑的 bionic 程序拿到
//   GL_RENDERER = Adreno (TM) 840 —— 硬件 GPU 可用。
//   注：cleaner 代跑意味着 bionic 程序的 PID 与容器进程不同（exec 语义变化），
//   对图形/计算类程序无影响，但对依赖 $ 的脚本有影响，属已知边界。
// ★cleaner 走「AF_UNIX 抽象 socket」而不是继承 fd。
//   为什么不用 pipe+dup3 固定 fd 号：实测 busybox sh 起子进程后，198/199
//   在高位 fd 上直接消失（/proc/self/fd/198 → ENOENT），自我重执行与
//   sh 的子进程管理都会把它们关掉，探活与写入全部失败。
//   抽象命名空间 socket（sun_path[0]==0）不落文件系统、进程退出自动回收、
//   按 uid 隔离，且每次 connect 都是新 fd，不受任何 close_fds 影响。
#define CLEANER_SOCK_PREFIX "dsh-cleaner-"
struct ldr_sockaddr_un { unsigned short sun_family; char sun_path[108]; };
// aarch64 Linux 上这些结构的布局（全部按 8 字节对齐，指针/size_t 都是 8 字节）
struct ldr_iovec { void *base; unsigned long len; };
struct ldr_cmsghdr { unsigned long len; int level; int type; };
struct ldr_msghdr {
    void *name; unsigned int namelen; unsigned int pad0;
    struct ldr_iovec *iov; unsigned long iovlen;
    void *control; unsigned long controllen;
    int flags; unsigned int pad1;
};
static int g_cleaner_on;
static long g_cleaner_pid;
static char g_cleaner_name[112];   // ★必须只算一次：getpid() 在父子进程里不同
static int  g_cleaner_namelen;
static int  g_cleaner_addrlen;        // cleaner 的 pid：它原地 exec 后 PID 不变，父进程直接 wait4 它
static char **g_envp_for_cleaner; // 容器 envp（fork 时已复制进子进程地址空间）

// 生成抽象 socket 名（用 uid 隔离多实例）
static int cleaner_sockname(char *out) {
    // 名字 = <prefix><uid>-<pid>，保证同机多实例、崩溃残留都互不干扰
    long uid = raw6(174 /*getuid*/, 0, 0, 0, 0, 0, 0);
    char num[16]; int n = 0;
    if (uid == 0) num[n++] = '0';
    else { char t[16]; int k = 0; long u = uid; while (u) { t[k++] = (char)('0' + (u % 10)); u /= 10; }
           while (k) num[n++] = t[--k]; }
    // out 只装名字字符（不含前导 NUL，前导 NUL 由调用方放到 sun_path[0]）
    int p = 0;
    const char *pre = CLEANER_SOCK_PREFIX;
    for (int i = 0; pre[i]; i++) out[p++] = pre[i];
    for (int i = 0; i < n; i++) out[p++] = num[i];
    out[p++] = '_';
    long pid = raw6(172 /*getpid*/, 0, 0, 0, 0, 0, 0);   // ★仅在 fork 前调用
    char pt[16]; int k2 = 0;
    if (!pid) pt[k2++] = '0';
    while (pid) { pt[k2++] = (char)('0' + (pid % 10)); pid /= 10; }
    while (k2) out[p++] = pt[--k2];
    return p;   // 名字字符数
}

// cleaner 侧：只服务**一次**请求然后退出。
//   ★为什么不做常驻循环：cleaner 若常驻，它必然在整个容器生命周期里持有某些
//   fd（哪怕只是 accept 用的 listen fd 或残留的 stdio 副本），adb shell 的管道
//   就一直不关，命令永不返回（实测 exit 124 且 GL 输出丢失）。
//   改成「一次一进程」后，父进程退出时 cleaner 自然消失，无泄漏、无僵尸。
//   代价仅一次 fork+listen（实测 <0.5ms），且只发生在 bionic 程序启动时。
static void cleaner_loop(long lfd, int socklen) {
    static char buf[8192];
    struct ldr_sockaddr_un sa;
    {
        unsigned int sl = (unsigned int)socklen;
        long cfd = raw6(242 /*accept4*/, lfd, (long)&sa, (long)&sl, 0x80000 /*SOCK_CLOEXEC*/, 0, 0);
        if (cfd < 0) raw6(94 /*exit_group*/, 0, 0, 0, 0, 0, 0);
        long n = raw6(63 /*read*/, cfd, (long)buf, sizeof(buf) - 1, 0, 0, 0);
        if (n <= 0) { raw6(57, cfd, 0, 0, 0, 0, 0); raw6(94, 0, 0, 0, 0, 0, 0); }
        buf[n] = 0;
        char *path = buf;
        long plen = 0; while (path[plen]) plen++;
        char *p = buf + plen + 1;
        char *av[64]; int ac = 0;
        av[ac++] = path;
        while (p < buf + n && ac < 63) {
            if (!*p) break;
            av[ac++] = p;
            while (*p) p++;
            p++;
        }
        av[ac] = 0;
        // ★不能在 cleaner 自己身上 exec：cleaner 与请求方是兄弟进程（同父 = 最初的
        //   loader，现已变成 sh），请求方无法 wait4 一个兄弟 → ECHILD(-10)。
        //   正确做法：cleaner fork 出孙进程去 exec（孙进程继承 cleaner 的无 filter
        //   环境，所以 bionic 能跑），cleaner wait4 它，再把退出码写回 socket。
        long gp = raw6(220 /*clone*/, 17 /*SIGCHLD*/, 0, 0, 0, 0, 0);
        if (gp == 0) {
            long er = raw6(221 /*execve*/, (long)path, (long)av, (long)g_envp_for_cleaner, 0, 0, 0);
            ldr_log2("CLOOP-child: execve rc=", er, "", 0);
            raw6(94 /*exit_group*/, 127, 0, 0, 0, 0, 0);
        }
        unsigned char st = 126;
        int wst = 0;
        if (gp == 0) {
            raw6(221 /*execve*/, (long)path, (long)av, (long)g_envp_for_cleaner, 0, 0, 0);
            raw6(94 /*exit_group*/, 127, 0, 0, 0, 0, 0);
        }
        if (gp > 0) {
            for (;;) {
                long r = raw6(260 /*wait4*/, gp, (long)&wst, 0, 0, 0, 0);
                if (r == gp) break;
                if (r < 0 && r != -4) break;
            }
            st = (wst & 0x7f) ? (unsigned char)(128 + (wst & 0x7f))
                              : (unsigned char)((wst >> 8) & 0xff);
        }
        raw6(64 /*write*/, cfd, (long)&st, 1, 0, 0, 0);
        raw6(57 /*close*/, cfd, 0, 0, 0, 0, 0);
        // ★必须关掉本次借来的 stdio 副本：cleaner 是常驻进程，若一直持有
        //   这两个 fd，adb shell 的管道永远不关，命令永不返回（实测超时丢输出）。
    }
    raw6(94 /*exit_group*/, 0, 0, 0, 0, 0, 0);   // 服务完一次即退出
}

// loader 侧：在 install_filter **之前** fork 出 cleaner 并启动监听
static void cleaner_start(void) {
    if (g_cleaner_on) return;
    // ★名字在这里（fork 之前）算一次存进全局：父进程与 cleaner 子进程的
    //   getpid() 不同，若各自调用 cleaner_sockname() 会得到两个不同名字，
    //   bind 与 connect 永远对不上（实测 connect FAIL fd=60）。
    g_cleaner_namelen = cleaner_sockname(g_cleaner_name);
    char *name = g_cleaner_name;
    int slen = g_cleaner_namelen;
    long lfd = raw6(198 /*socket*/, 1 /*AF_UNIX*/, 1 /*SOCK_STREAM*/, 0, 0, 0, 0);
    if (lfd < 0) { ldr_log2("CLEANER: socket FAIL=", lfd, "", 0); return; }
    // struct sockaddr_un 布局：u16 sun_family(偏移0) + char sun_path[108](偏移2)
    // 抽象命名空间 = sun_path[0] 为 0；addrlen = 2 + 1 + 名字长度
    struct ldr_sockaddr_un sa;
    ((unsigned short *)&sa)[0] = 1;              // AF_UNIX
    ((char *)&sa)[2] = 0;                        // 抽象命名空间标记
    for (int i = 0; i < slen; i++) ((char *)&sa)[3 + i] = name[i];
    int addrlen = 3 + slen;
    if (raw6(200 /*bind*/, lfd, (long)&sa, addrlen, 0, 0, 0) < 0) {
        ldr_log2("CLEANER: bind FAIL rc=", 0, " addrlen=", addrlen);
        raw6(57, lfd, 0, 0, 0, 0, 0); return;
    }
    if (raw6(201 /*listen*/, lfd, 8, 0, 0, 0, 0) < 0) { ldr_puts("CLEANER: listen FAIL"); raw6(57, lfd, 0, 0, 0, 0, 0); return; }
    g_cleaner_addrlen = addrlen;
    long pid = raw6(220 /*clone*/, 17 /*SIGCHLD*/, 0, 0, 0, 0, 0);
    if (pid < 0) { ldr_puts("CLEANER: clone FAIL"); return; }
    if (pid == 0) {
        // 必须设 PDEATHSIG：cleaner 若这次没等到请求，会一直阻塞在 accept()，
        // 父进程退出后被 init 收养继续挂着（实测每次启动漏一个进程，ps -A 里
        // 残留 dsh-final2 处于 __skb_wait_for_more_packets 状态）。
        // PR_SET_PDEATHSIG(1) + SIGKILL(9) 让内核在父进程死亡时直接回收它。
        ldr_prctl(1 /*PR_SET_PDEATHSIG*/, 9 /*SIGKILL*/, 0, 0, 0);
        // 竞态兜底：若父进程在设 PDEATHSIG 之前就已退出，则自行退出
        if (raw6(173 /*getppid*/, 0, 0, 0, 0, 0, 0) == 1)
            raw6(94, 0, 0, 0, 0, 0, 0);
        cleaner_loop(lfd, slen); raw6(94, 0, 0, 0, 0, 0, 0);
    }
    raw6(57, lfd, 0, 0, 0, 0, 0);
    g_cleaner_on = 1;
    g_cleaner_pid = pid;
}

// 请 cleaner 代跑一个宿主真实路径的 bionic 程序，返回它的退出码
// ★cleaner 原地 execve，PID 不变，所以这里直接 wait4(g_cleaner_pid) 就是目标程序的
//   真实退出状态 —— 无需回执管道，也不会因为 close_fds / 高位 fd 被回收而失效。
static int cleaner_exec(const char *hostpath, char **gargv) {
    if (!g_cleaner_on || g_cleaner_pid <= 0) return -1;
    if (!g_cleaner_namelen) return -1;
    char *name = g_cleaner_name;
    int slen = g_cleaner_namelen;
    long fd = raw6(198 /*socket*/, 1 /*AF_UNIX*/, 1 /*SOCK_STREAM*/, 0, 0, 0, 0);
    if (fd < 0) return -1;
    struct ldr_sockaddr_un sa;
    ((unsigned short *)&sa)[0] = 1;
    ((char *)&sa)[2] = 0;
    for (int i = 0; i < slen; i++) ((char *)&sa)[3 + i] = name[i];
    int addrlen = 3 + slen;
    long cr = raw6(203 /*connect*/, fd, (long)&sa, addrlen, 0, 0, 0);
    if (cr < 0) { raw6(57, fd, 0, 0, 0, 0, 0); return -1; }
    static char msg[8192];
    long n = 0;
    for (const char *q = hostpath; *q && n < (long)sizeof(msg) - 2; q++) msg[n++] = *q;
    msg[n++] = 0;
    if (gargv) {
        for (int i = 1; gargv[i] && n < (long)sizeof(msg) - 300; i++) {
            for (const char *q = gargv[i]; *q && n < (long)sizeof(msg) - 2; q++) msg[n++] = *q;
            msg[n++] = 0;
        }
    }
    msg[n++] = 0;
    // cleaner 是**一次性进程**（服务一次就 exit），因此它继承的 stdout/stderr
    // 会随它退出而释放，不需要 SCM_RIGHTS 传递，也不需要主动断开：
    //   - 若 cleaner 常驻且持有 shell 的 stdout，adb shell 永不返回（实测 124 超时）
    //   - 断开 stdio 又会丢掉 bionic 程序的输出（实测只剩 RC=0）
    //   一次性模型两个问题同时消失：输出正常 + 进程退出即释放。
    long wr = raw6(64 /*write*/, fd, (long)msg, n, 0, 0, 0);
    // ★这里绝不能 close：还要在本 fd 上读回执。曾在此处 close，导致下面 read
    //   拿 EBADF(-9)，于是误判失败并返回 ENOENT → sh 报 "not found"、RC=127，
    //   而实际上 bionic 程序已经跑完、GPU 输出全都拿到了。
    if (wr != n) { raw6(57, fd, 0, 0, 0, 0, 0); return -1; }
    // 读回执（阻塞直到 cleaner 的孙进程结束）
    unsigned char st = 126;
    for (;;) {
        long r = raw6(63 /*read*/, fd, (long)&st, 1, 0, 0, 0);
        if (r == 1) break;
        if (r < 0 && r != -4 /*EINTR*/) { raw6(57, fd, 0, 0, 0, 0, 0); return -1; }
        if (r == 0) { raw6(57, fd, 0, 0, 0, 0, 0); return -1; }
    }
    raw6(57 /*close*/, fd, 0, 0, 0, 0, 0);
    return (int)st;
}

// ================= 预加载镜像（父进程填，fork 后子进程只读） =================
// ★不能用固定大小静态数组：Alpine 的 cc1 有 34MB、cc1plus 有 36MB，
//   6MB 固定缓冲会让 gcc 调子编译器时静默失败（编译不出结果、无报错）。
//   改为按实际大小 raw mmap（handler 内 libc 不可用，必须 raw syscall）。
static unsigned char *g_interp_buf;
static unsigned long g_interp_len, g_interp_cap;
// interp 零拷贝装载：父进程把 musl ld 直接 mmap 到固定只读区，fork 后子进程
// 继承该映射（页表共享物理页），不再每次 exec 都 memcpy 723KB。
static unsigned char *g_interp_filemap;   // mmap 的文件基址（NULL = 未映射）
static int g_interp_filemap_ok;
static unsigned char *g_guest_buf;
static int g_interp_fd = -1;
static int g_guest_fd  = -1;
static unsigned long g_guest_len, g_guest_cap;
static char g_interp_path[PATH_MAX];
// shebang 脚本：execve 目标是 #! 开头文本时，改写为「解释器 + 可选参数 + 脚本 + 原参数」
static char g_script_interp[PATH_MAX];
static char g_script_path[PATH_MAX];
static char g_script_arg[PATH_MAX];
static int  g_is_script;
// rootfs 的宿主真实路径（用于 getcwd 前缀裁剪：cwd 是宿主路径，guest 要看到 / 开头视图）
static char g_rootfs_host[PATH_MAX];
static int  g_rootfs_host_len;
static char g_guest_path[PATH_MAX];
static char g_argv_store[256][PATH_MAX];
static int  g_argv_count;
static char g_env_store[128][PATH_MAX];
static int  g_env_count;
// 首次启动时即使继承宿主 envp，也必须用容器自己的 PATH。
// 宿主 PATH 是 /system/bin 那一套，直接继承会让 cat/gzip 全部 not found。
static int  g_force_container_path;

/**
 * 判断一个 PATH 值是不是「Android 宿主那套」。
 *
 * 为什么需要它：自我重执行（execveat AT_EMPTY_PATH）会重新初始化 .data 段，
 * [g_force_container_path] 随之变回 0。于是 re-exec 之后，从宿主继承来的
 * `/product/bin:/apex/com.android.runtime/bin:/system/bin:...` 会被当成
 * 「用户在容器里自己设的 PATH」原样保留 —— 容器内 `cat` / `head` / `gzip`
 * 全部 not found（这些目录在 rootfs 里不存在，而真正的 busybox 在 /bin）。
 *
 * 症状极难定位：shell 内建命令（echo/cd/export）都正常，只有外部命令全挂，
 * 看起来像「rootfs 缺 applet」。
 */
static int path_looks_like_android(const char *v) {
    if (!v) return 0;
    static const char *marks[] = {
        "/system/bin", "/system_ext/", "/product/bin", "/odm/bin",
        "/vendor/bin", "/apex/", "/data/adb/",
    };
    for (unsigned k = 0; k < sizeof(marks) / sizeof(marks[0]); k++) {
        const char *m = marks[k];
        for (const char *p = v; *p; p++) {
            const char *a = p, *b = m;
            while (*b && *a == *b) { a++; b++; }
            if (!*b) return 1;
        }
    }
    return 0;
}

// ---------------- 手写字节工具（无 libc） ----------------
static void bzero_(void *p, unsigned long n) { unsigned char *c = (unsigned char *)p; while (n--) *c++ = 0; }
static void copy_(void *d, const void *s, unsigned long n) {
    unsigned char *dd = (unsigned char *)d; const unsigned char *ss = (const unsigned char *)s;
    while (n--) *dd++ = *ss++;
}
static unsigned long slen_(const char *s) { unsigned long n = 0; while (s[n]) n++; return n; }

// [prof] 无符号长整型转十进制，返回写入字节数（供 trap 统计输出用）
static int utoa_(char *d, unsigned long v) {
    char t[24]; int n = 0;
    if (!v) { d[0] = '0'; return 1; }
    while (v) { t[n++] = (char)('0' + (v % 10)); v /= 10; }
    for (int i = 0; i < n; i++) d[i] = t[n - 1 - i];
    return n;
}

// 宿主环境变量过滤：继承用户自定义变量（http_proxy / 自己的 KEY），
// 但宿主专有的路径类变量必须丢掉——它们指向 Android 宿主目录，容器里不存在。
// 实测后果：宿主 TMPDIR=/data/local/tmp 泄漏进来后，
// make 报 "TMPDIR value /data/local/tmp: No such file or directory"；HOME 被改成 "/"。
// 该不该把宿主继承来的环境变量丢掉？
//
// 判据只有一条：**这个变量的值会不会让 musl 运行时出错、或指向宿主上才有的路径**。
//   - `LD_LIBRARY_PATH` / `LD_PRELOAD`：bionic 库路径，musl 的 dlopen 会走错 → 必须丢
//   - `ANDROID_*` / `BOOTCLASSPATH` / `ASEC_MOUNTPOINT` / `EXTERNAL_STORAGE`：Android 专有 → 丢
//   - `PREFIX`：Termux 约定（/data/data/com.termux/files/usr），容器里会误导脚本 → 丢
//   - `SHELL`：宿主的 /system/bin/sh 在容器里不存在 → 丢（由 env_fill_defaults 补）
//
// 其余**一律保留**。早期版本把 HOME / TMPDIR / PWD / TMP / TEMP / LANG / LC_* /
// USER / LOGNAME 也一并丢掉，后果是 App 显式传进来的 `HOME=/dsh`、
// `TMPDIR=/tmp` 被静默覆盖回 `/root`：登录 shell 读不到 /dsh 下的用户配置，
// 引擎与终端看到的 HOME 与 App 预期不一致（实测 HOME=/root 而非 /dsh）。
static int env_drop_host(const char *e) {
    static const char *bad[] = {
        "PREFIX", "LD_LIBRARY_PATH", "LD_PRELOAD", "SHELL",
        "BOOTCLASSPATH", "ASEC_MOUNTPOINT", "EXTERNAL_STORAGE",
        "ANDROID_DATA", "ANDROID_ROOT", "ANDROID_STORAGE", "ANDROID_ART_ROOT",
        "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "ANDROID_RUNTIME_ROOT",
        "ANDROID_ASSETS",
        // TMPDIR / TMP / TEMP：宿主的临时目录在容器里不存在（实测宿主 TMPDIR=
        // /data/local/tmp 泄漏进容器后，@deepseek-ai/dsh-spill-local 的
        // mkdtemp(join(tmpdir(), ...)) 直接 ENOENT，插件 activate 失败、
        // profile 被判 degraded）。容器内必须用自己的 /tmp。
        "TMPDIR", "TMP", "TEMP"
    };
    for (unsigned i = 0; i < sizeof(bad) / sizeof(bad[0]); i++) {
        const char *b = bad[i];
        unsigned k = 0;
        while (b[k] && e[k] == b[k]) k++;
        if (b[k]) continue;                       // 前缀没走完
        if (e[k] == '=') return 1;                // 完整变量名 + '='
    }
    return 0;
}

// 补默认 HOME / TERM —— 但**只在 guest 没给的时候**。
//
// 踩过的坑：早期无条件追加 "HOME=/root" 与 "TERM=linux"，结果 App 传进来的
// HOME=/dsh、TERM=xterm-256color 被静默覆盖。后果：
//   - HOME 不对 → 登录 shell 读不到 /dsh 下的配置，用户改的 profile 不生效
//   - TERM=linux → 终端里没有颜色、方向键序列错乱（xterm-256color 才对）
// 正确写法是「先看 guest 给没给，给了就用 guest 的，没给才补默认」。
static void scopy_(char *d, const char *s);


// 读取 DSH_DNS_PORT（App 侧 DNS 代理启动后写入的环境变量）。
//
// 返回 0 表示「不改写」—— 未开代理、或用户在别处跑，行为与之前完全一致。
// 缓存到静态变量：connect 是热路径（每次 DNS 查询 1 次），不能每次都扫 envp。
static int g_dns_port_cached;
static int dns_proxy_port(void) {
    // ★只在「真找到端口」时才缓存。
    //
    // 踩过的坑：第一版把 `g_dns_port_cached = 0`（未找到）也缓存了，而 handler
    // 可能在 `loader_preload` 构建 env 之前就被调用（启动早期的 stat/open 就会
    // trap）—— 那一次返回 0 并被永久记住，之后即使 env 里已经有
    // `DSH_DNS_PORT=15353` 也再读不到，改写永远不生效。表现为「明明传了变量却没用」。
    if (g_dns_port_cached > 0) return g_dns_port_cached;
    static const char key[] = "DSH_DNS_PORT=";
    unsigned kl = sizeof(key) - 1;
    for (int i = 0; i < g_env_count; i++) {
        const char *e = g_env_store[i];
        unsigned k = 0;
        while (k < kl && e[k] && e[k] == key[k]) k++;
        if (k != kl) continue;
        int v = 0;
        const char *d = e + kl;
        while (*d >= '0' && *d <= '9') { v = v * 10 + (*d - '0'); d++; }
        if (v > 1023 && v < 65536) g_dns_port_cached = v;
        break;
    }
    return g_dns_port_cached;
}

static int env_has_(const char *name) {
    unsigned nl = 0;
    while (name[nl]) nl++;
    for (int i = 0; i < g_env_count; i++) {
        const char *e = g_env_store[i];
        unsigned k = 0;
        while (k < nl && e[k] == name[k]) k++;
        if (k == nl && e[k] == '=') return 1;
    }
    return 0;
}

static void env_fill_defaults(void) {
    if (!env_has_("HOME") && g_env_count < 127)
        scopy_(g_env_store[g_env_count++], "HOME=/root");
    if (!env_has_("TERM") && g_env_count < 127)
        scopy_(g_env_store[g_env_count++], "TERM=linux");
    // SHELL 被 env_drop_host 丢掉了（宿主的 /system/bin/sh 在容器里不存在），
    // 这里补容器的。缺了它 `$SHELL -c ...` 类脚本会拿到空值。
    if (!env_has_("SHELL") && g_env_count < 127)
        scopy_(g_env_store[g_env_count++], "SHELL=/bin/sh");
    // TMPDIR 被 env_drop_host 丢掉后必须补容器的 /tmp，否则 os.tmpdir() 会
    // 退回 libc 默认值（musl 是 /tmp，行为正确，但不能依赖它）——补上更确定。
    // 实测：宿主 TMPDIR 泄漏进来时一跑就 ENOENT（见 env_drop_host 的注释）。
    if (!env_has_("TMPDIR") && g_env_count < 127)
        scopy_(g_env_store[g_env_count++], "TMPDIR=/tmp");
}

static int scmp_(const char *a, const char *b) {
    while (*a && *a == *b) { a++; b++; }
    return (unsigned char)*a - (unsigned char)*b;
}
static void scopy_(char *d, const char *s) { while (*s) *d++ = *s++; *d = 0; }

// guest 绝对路径 -> 相对 rootfs fd 的 "./..."（静态缓冲）
static char *script_rel(const char *abs) {
    static char buf[PATH_MAX];
    buf[0] = (char)46;
    const char *p = abs; char *d = buf + 1; int lim = PATH_MAX - 2;
    while (*p && lim-- > 0) *d++ = *p++;
    *d = 0;
    return buf;
}

// v8：把一段 PT_LOAD 直接以文件映射装到目标地址（零拷贝，替代 memcpy）。
//   与内核 load_elf_binary 同思路：建立文件页映射，按需缺页。
//   调用前调用方已把整段预留成 anon RW（段间空隙=零页），这里用 MAP_FIXED 覆盖文件部分。
static int map_seg_file(long fd, unsigned long target_base, const Elf64_Phdr *ph) {
    const unsigned long PAGE = 0x1000UL;
    unsigned long off    = (unsigned long)ph->p_offset;
    unsigned long vaddr  = (unsigned long)ph->p_vaddr;
    unsigned long filesz = (unsigned long)ph->p_filesz;
    unsigned long memsz  = (unsigned long)ph->p_memsz;
    if (memsz == 0) return 0;
    if (filesz == 0) return 0;   // 纯 BSS 段：anon RW 零页已足够

    unsigned long page_off = off & ~(PAGE - 1);
    unsigned long delta    = off - page_off;
    unsigned long map_addr = target_base + vaddr - delta;
    unsigned long file_len = (filesz + delta + PAGE - 1) & ~(PAGE - 1);

    // ★先看文件是否够长，能为整个映射提供后备（关键修复）。
    //   Ubuntu/glibc 的 ld.so 第二段 offset=0x2e988 filesz=0x2814，文件总长 0x31cc0；
    //   file_len 向上取整到 0x4000 后已越过 EOF。此时若用 MAP_FIXED 映射并在
    //   filesz→page_end 之间清零 BSS，就会写到 EOF 之后 → 内核投递 SIGBUS
    //   （实测 rc=135 = 128+7）。musl 的段布局恰好不触发，所以此前一直没暴露。
    //   取文件长度用 lseek 而不是 fstat：手写 struct stat 的字段偏移在
    //   aarch64/bionic 上极易搞错，实测写错会直接 SIGSEGV（rc=139）。
    long fsz = raw6(SYS_lseek, fd, 0, 2 /*SEEK_END*/, 0, 0, 0);
    if (fsz < 0) fsz = 0;
    unsigned long avail = (fsz > 0 && (unsigned long)fsz > page_off)
                          ? ((unsigned long)fsz - page_off) : 0;

    if (avail >= file_len) {
        // 快路径：整段都有文件后备 → 直接文件映射（零拷贝）
        long m = raw6(SYS_mmap, (long)map_addr, (long)file_len, 7 /*RWX*/,
                      0x12 /*MAP_PRIVATE|MAP_FIXED*/, fd, (long)page_off);
        if (m < 0 && m > -4096) return -1;
        // 文件映射会覆盖 filesz 所在整页，把 filesz 之后到页尾的 BSS 部分清零
        if (memsz > filesz && (filesz % PAGE) != 0) {
            unsigned char *z = (unsigned char *)(target_base + vaddr + filesz);
            unsigned long page_end = ((target_base + vaddr + filesz) & ~(PAGE - 1)) + PAGE;
            while ((unsigned long)z < page_end) *z++ = 0;
        }
    } else {
        // 慢路径：映射会越过 EOF → 只 pread 真实存在的 filesz 字节到
        //   已 anon 清零的保留区，绝不触碰 EOF 之后的页（否则 SIGBUS）。
        long got = raw6(SYS_pread64, fd, (long)(target_base + vaddr),
                        (long)filesz, (long)off, 0, 0);
        if (got != (long)filesz) return -1;
    }
    return 0;
}

// ================= 子进程：纯 raw syscall 加载并跳转 =================
static __attribute__((noinline)) void child_run_raw(long argc_dummy) {
    unsigned long ilen = g_interp_len, glen = g_guest_len;
    if (!ilen || !glen) raw6(SYS_exit_group, 126, 0, 0, 0, 0, 0);

    Elf64_Ehdr *ieh = (Elf64_Ehdr *)g_interp_buf;
    Elf64_Ehdr *geh = (Elf64_Ehdr *)g_guest_buf;
    if (geh->e_ident[0] != 0x7F) raw6(SYS_exit_group, 125, 0, 0, 0, 0, 0);



    const unsigned long IHI = 0x7000000000UL;
    // ★ET_EXEC（如 Alpine 的 gcc / binutils）的绝对地址已烙死在 ELF 里，必须原位加载（base=0）；
    //   只有 ET_DYN（PIE，如 busybox）才能挑基址。此前一律按 0x5000000000 偏移映射，
    //   导致 ET_EXEC 的代码按 0x5000400000 拷贝但内部地址仍是 0x400000 -> 取指即崩。
    const int guest_is_dyn = (geh->e_type == ET_DYN);
    const unsigned long GHI = guest_is_dyn ? 0x5000000000UL : 0UL;

    // ---- 静态二进制支持 ----
    // 没有 PT_INTERP 的程序（`gcc -static` 产物）不需要 ld.so 参与。
    // 原先一律先把 interp 映射好再跳 guest 的 e_entry（interp 的 entry），
    // 静态程序会因此被 ld.so 当成「动态程序但没有 PT_DYNAMIC」而断言失败
    // （实测 glibc 报 rtld_setup_main_map 断言；proroot 报 failed no PT_DYNAMIC）。
    // 这里检出后跳过 interp 装载，entry 直接取 guest 自己的 e_entry。
    int guest_static = 1;
    for (int i = 0; i < geh->e_phnum; i++) {
        if (((Elf64_Phdr *)(g_guest_buf + geh->e_phoff))[i].p_type == PT_INTERP) { guest_static = 0; break; }
    }

    // ---- 映射 interp ----
    Elf64_Phdr *iph = (Elf64_Phdr *)(g_interp_buf + ieh->e_phoff);
    unsigned long ilo = ~0UL, ihi = 0;
    for (int i = 0; i < ieh->e_phnum; i++) {
        if (iph[i].p_type != PT_LOAD) continue;
        unsigned long s = iph[i].p_vaddr & ~0xFFFUL;
        unsigned long e = (iph[i].p_vaddr + iph[i].p_memsz + 0xFFF) & ~0xFFFUL;
        if (s < ilo) ilo = s;
        if (e > ihi) ihi = e;
    }
    if (ilo == ~0UL && !guest_static) raw6(SYS_exit_group, 124, 0, 0, 0, 0, 0);
    if (guest_static) { ilo = 0; ihi = 0; }
    unsigned long ispan = ihi - ilo;
    (void)ispan;
    if (!guest_static) {
        // 整段预留 anon RW（段间空隙为零页，可读写），随后文件映射覆盖文件部分
        long rv = raw6(SYS_mmap, (long)(IHI + ilo), (long)ispan, 3 /*RW*/,
                       0x22 /*PRIVATE|ANON*/ | 0x100000 /*FIXED_NOREPLACE*/, -1, 0);
        if (rv < 0 && rv > -4096) {
            raw6(SYS_munmap, (long)(IHI + ilo), (long)ispan, 0, 0, 0, 0);
            rv = raw6(SYS_mmap, (long)(IHI + ilo), (long)ispan, 3, 0x32, -1, 0);
        }
        if (rv < 0 && rv > -4096) raw6(SYS_exit_group, 123, 0, 0, 0, 0, 0);
    }
    if (!guest_static) {
        for (int i = 0; i < ieh->e_phnum; i++) {
            if (iph[i].p_type != PT_LOAD) continue;
            if (map_seg_file((long)g_interp_fd, IHI, &iph[i]) != 0)
                raw6(SYS_exit_group, 131, 0, 0, 0, 0, 0);
        }
    }

    // ---- 映射 guest ----
    Elf64_Phdr *gph = (Elf64_Phdr *)(g_guest_buf + geh->e_phoff);
    unsigned long glo = ~0UL, ghi = 0;
    for (int i = 0; i < geh->e_phnum; i++) {
        if (gph[i].p_type != PT_LOAD) continue;
        unsigned long s = gph[i].p_vaddr & ~0xFFFUL;
        unsigned long e = (gph[i].p_vaddr + gph[i].p_memsz + 0xFFF) & ~0xFFFUL;
        if (s < glo) glo = s;
        if (e > ghi) ghi = e;
    }
    if (glo == ~0UL) raw6(SYS_exit_group, 122, 0, 0, 0, 0, 0);
    unsigned long gspan = ghi - glo;
    {
        long rv = raw6(SYS_mmap, (long)(GHI + glo), (long)gspan, 3 /*RW*/,
                       0x22 | 0x100000, -1, 0);
        if (rv < 0 && rv > -4096) {
            raw6(SYS_munmap, (long)(GHI + glo), (long)gspan, 0, 0, 0, 0);
            rv = raw6(SYS_mmap, (long)(GHI + glo), (long)gspan, 3, 0x32, -1, 0);
        }
        if (rv < 0 && rv > -4096) raw6(SYS_exit_group, 121, 0, 0, 0, 0, 0);
    }
    for (int i = 0; i < geh->e_phnum; i++) {
        if (gph[i].p_type != PT_LOAD) continue;
        if (map_seg_file((long)g_guest_fd, GHI, &gph[i]) != 0)
            raw6(SYS_exit_group, 132, 0, 0, 0, 0, 0);
    }

    // ---- 建栈 ----
    // 栈从 8MB 降到 2MB：实测 guest 程序极少用到 >2MB 栈，
    // 而每次 exec 都 mmap 8MB 匿名区是有代价的（page table + 清零）。
    // 栈：2MB 足够（实测 guest 极少超 1MB）。不带 MAP_POPULATE，按需缺页，
    // 避免每次 exec 预填 512 个页表项（那是固定开销的主要部分之一）。
    unsigned long stksz = 2UL << 20;
    long stk = raw6(SYS_mmap, 0, (long)stksz, 3 /*RW*/, 0x22, -1, 0);
    if (stk < 0 && stk > -4096) raw6(SYS_exit_group, 120, 0, 0, 0, 0, 0);
    unsigned char *sp = (unsigned char *)(stk + stksz);

    // 字符串区（逆序压）
    static char *argv_ptrs[40];
    int ac = g_argv_count;
    for (int i = ac - 1; i >= 0; i--) {
        unsigned long l = slen_(g_argv_store[i]) + 1;
        sp -= l; copy_(sp, g_argv_store[i], l);
    }
    // 重新按正序记录指针（因为上面逆序压，位置需重算）
    // 简单做法：再扫一遍栈不可行，改为正序压并保存指针
    // 重新来：重置 sp 到顶再正序压
    sp = (unsigned char *)(stk + stksz);
    static char *ap[256];
    for (int i = ac - 1; i >= 0; i--) {
        unsigned long l = slen_(g_argv_store[i]) + 1;
        sp -= l; copy_(sp, g_argv_store[i], l); ap[i] = (char *)sp;
    }
    static char *ep[128];
    int ec = g_env_count;
    for (int i = ec - 1; i >= 0; i--) {
        unsigned long l = slen_(g_env_store[i]) + 1;
        sp -= l; copy_(sp, g_env_store[i], l); ep[i] = (char *)sp;
    }
    unsigned long gl2 = slen_(g_guest_path) + 1;
    sp -= gl2; copy_(sp, g_guest_path, gl2); char *execfn = (char *)sp;
    sp -= 16; for (int i = 0; i < 16; i++) sp[i] = (unsigned char)(i * 7 + 13);
    void *at_random = sp;
    sp = (unsigned char *)((unsigned long)sp & ~15UL);

    struct { uint64_t k, v; } aux[16];
    int n = 0;
    // ★AT_PHDR 必须指向「程序头在内存中的真实地址」，不是文件偏移：
    //   ET_EXEC 的 PHDR 在 0x400040（含 0x400000 基），ET_DYN 则是 GHI + e_phoff。
    //   取法：在 PT_LOAD 里找覆盖 e_phoff 的那一段，用 p_vaddr + (e_phoff - p_offset)。
    unsigned long phdr_addr = GHI + geh->e_phoff;
    for (int i = 0; i < geh->e_phnum; i++) {
        if (gph[i].p_type != PT_LOAD) continue;
        if (geh->e_phoff >= gph[i].p_offset &&
            geh->e_phoff < gph[i].p_offset + gph[i].p_filesz) {
            phdr_addr = GHI + gph[i].p_vaddr + (geh->e_phoff - gph[i].p_offset);
            break;
        }
    }
    aux[n].k = AT_PHDR;  aux[n++].v = phdr_addr;
    aux[n].k = AT_PHENT; aux[n++].v = geh->e_phentsize;
    aux[n].k = AT_PHNUM; aux[n++].v = geh->e_phnum;
    aux[n].k = AT_BASE;  aux[n++].v = guest_static ? 0UL : IHI;   // 静态程序没有 interp 基址
    aux[n].k = AT_ENTRY; aux[n++].v = GHI + geh->e_entry;
    aux[n].k = AT_EXECFN;aux[n++].v = (uint64_t)execfn;
    aux[n].k = AT_RANDOM;aux[n++].v = (uint64_t)at_random;
    aux[n].k = AT_PAGESZ;aux[n++].v = 4096;
    aux[n].k = AT_UID;   aux[n++].v = 2000;
    aux[n].k = AT_EUID;  aux[n++].v = 2000;
    aux[n].k = AT_GID;   aux[n++].v = 2000;
    aux[n].k = AT_EGID;  aux[n++].v = 2000;
    aux[n].k = AT_HWCAP; aux[n++].v = 0;
    aux[n].k = AT_SECURE;aux[n++].v = 0;
    aux[n].k = AT_NULL;  aux[n++].v = 0;

    // ★AArch64 ABI 要求进入 ELF 入口时 sp 必须 16 字节对齐。
    //   本函数在 auxv 之后还要压入 8 字节槽：(envp NULL) + ec + (argv NULL) + ac + argc
    //   = 3 + ec + ac 个槽，即偏移 8*(3+ec+ac)。只有 ec+ac 为奇数时它才是 16 的倍数。
    //   漏掉这一步时 sp 会落在 8 mod 16 上，glibc 的 ld.so 入口第一条
    //   `stp x29,x30,[sp,#-16]!` 直接触发 SIGBUS/BUS_ADRALN
    //   （实测 Ubuntu rootfs 必崩，而 musl 不要求对齐所以 Alpine 一直正常）。
    // ★补位必须压在 auxv「之前」（更高地址），绝不能插在 envp NULL 与 auxv 之间 ——
    //   glibc 从 envp 往后走去找 auxv，插在中间会被读成 AT_NULL，导致 auxv 被当成空的
    //   （实测症状：ld.so 误判成「被直接调用」，把 argv[1] 当程序名去 dlopen，
    //    报 `-c: cannot open shared object file`）。
    if ((((unsigned long)ec + (unsigned long)ac) & 1UL) == 0UL) {
        sp -= 8; *(uint64_t *)sp = 0;
    }

    for (int i = n - 1; i >= 0; i--) { sp -= 16; copy_(sp, &aux[i], 16); }

    sp -= 8; *(uint64_t *)sp = 0;
    for (int i = ec - 1; i >= 0; i--) { sp -= 8; *(uint64_t *)sp = (uint64_t)ep[i]; }
    sp -= 8; *(uint64_t *)sp = 0;
    for (int i = ac - 1; i >= 0; i--) { sp -= 8; *(uint64_t *)sp = (uint64_t)ap[i]; }
    sp -= 8; *(uint64_t *)sp = (uint64_t)ac;

    // 静态：直接从 guest 入口进；动态：先进 interp 的入口
    unsigned long entry = guest_static ? (GHI + geh->e_entry) : (IHI + ieh->e_entry);
    // ★跳转前必须解除 SIGSYS 阻塞：本函数可能在 SIGSYS handler 内被调用
    //   （进程内 execve 路径），此时 SIGSYS 在掩码里被阻塞，guest 第一条被拦
    //   syscall 就会被内核直接 kill（Bad system call / exit 159）。
    { unsigned long set = 1UL << (31 - 1);   // SIGSYS=31 -> bit 30
      raw6(SYS_rt_sigprocmask, 1 /*SIG_UNBLOCK*/, (long)&set, 0, 8, 0, 0); }
    // 跳转（不再返回）
    register unsigned long x0 __asm__("x0") = (unsigned long)sp;
    __asm__ volatile("mov sp, %0\n br %1\n" :: "r"(x0), "r"(entry) : "memory");
    raw6(SYS_exit_group, 119, 0, 0, 0, 0, 0);
}

// ================= SIGSYS handler（无 libc） =================
// ================= bind 映射表（等价 proot -b / -m）=================
// 用法： dsh-alpine -m <guest路径>:<宿主路径> ... <rootfs> <interp> <guest> [args]
// 语义：guest 内访问 <guest路径> 前缀时，实际落到宿主的 <宿主路径> 下。
//   例：-m /sdcard:/sdcard  →  guest 的 /sdcard/x 读宿主 /sdcard/x
// 16：固定 3 个别名（/eng /ws /dsh）+ 宿主根（/storage/emulated/0、
// /data/user/0/<pkg>/files）+ 工作区与公共目录的软链解析副本，实测 8 已到顶。
// 预编译产物里的这个值是编译期常量，改这里必须重编 libdsh_alpine.so。
#define MAX_BIND 16
static int  g_bind_n;
static int  g_bind_glen[MAX_BIND];
static int  g_bind_hlen[MAX_BIND];
static char g_bind_g[MAX_BIND][PATH_MAX];
static char g_bind_h[MAX_BIND][PATH_MAX];

static void bind_add(const char *spec) {
    if (g_bind_n >= MAX_BIND) return;
    // 拆 "guest:host"（用最后出现的 ":"，避免 Windows 盘符式 host 被切错）
    int sep = -1;
    for (int i = 0; spec[i]; i++) if (spec[i] == (char)58) sep = i;
    if (sep <= 0) return;
    int gi = 0; for (int i = 0; i < sep && gi < PATH_MAX - 1; i++) g_bind_g[g_bind_n][gi++] = spec[i];
    g_bind_g[g_bind_n][gi] = 0;
    while (gi > 1 && g_bind_g[g_bind_n][gi - 1] == (char)47) g_bind_g[g_bind_n][--gi] = 0;
    int hi = 0; for (int i = sep + 1; spec[i] && hi < PATH_MAX - 1; i++) g_bind_h[g_bind_n][hi++] = spec[i];
    g_bind_h[g_bind_n][hi] = 0;
    while (hi > 1 && g_bind_h[g_bind_n][hi - 1] == (char)47) g_bind_h[g_bind_n][--hi] = 0;
    if (gi == 0 || hi == 0) return;
    g_bind_glen[g_bind_n] = gi;
    g_bind_hlen[g_bind_n] = hi;
    g_bind_n++;
}

// path 命中某个 bind 的 guest 前缀则改写为宿主绝对路径，返回 1
static int bind_map(const char *path, char *out) {
    for (int k = 0; k < g_bind_n; k++) {
        int gl = g_bind_glen[k];
        int ok = 1;
        for (int i = 0; i < gl; i++) if (path[i] != g_bind_g[k][i]) { ok = 0; break; }
        if (!ok) continue;
        // 边界：前缀后必须是 0 或 "/"（避免 /sdc 误匹配 /sdcard）
        if (path[gl] != 0 && path[gl] != (char)47) continue;
        int o = 0;
        for (int i = 0; i < g_bind_hlen[k] && o < PATH_MAX - 1; i++) out[o++] = g_bind_h[k][i];
        const char *rest = path + gl;
        if (*rest == 0) { out[o] = 0; return 1; }
        while (*rest && o < PATH_MAX - 1) out[o++] = *rest++;
        out[o] = 0;
        return 1;
    }
    return 0;
}

static void make_rel(char *buf, const char *path) {
    buf[0] = '.'; char *d = buf + 1; const char *s = path;
    int lim = PATH_MAX - 2;
    while (*s && lim-- > 0) *d++ = *s++;
    *d = 0;
}

// ★拼出「宿主机绝对路径」：g_rootfs_host + guest 路径。
//   仅用于不走 *at 接口的老式系统调用（statfs / fstatfs 这类没有 dirfd 参数的）。
//   有 *at 版本的一律用 g_rootfs_fd 相对解析，避免字符串拼接带来的竞态。
static void make_host(char *buf, const char *path) {
    char *d = buf;
    const char *s = g_rootfs_host;
    int lim = PATH_MAX - 2;
    while (*s && lim-- > 0) *d++ = *s++;
    if (d > buf && d[-1] == (char)47) d--;   // 去掉 rootfs 尾斜杠，避免 "//etc"
    s = path;
    while (*s && lim-- > 0) *d++ = *s++;
    *d = 0;
}

// ★统一的「打开 guest 文件」入口（v1.6.16）。
//
// 背景：本文件里有十几处 `openat(g_rootfs_fd, "./<guest 路径>")` —— 全都只查
// rootfs，不看 bind 表。于是任何落在 bind 挂载点下的 guest 路径（`/eng/...`、
// `/ws/...`、`/dsh/...`、宿主 /storage 与 /data/user）在这些地方都会 ENOENT，
// 而同样的路径经 syscall handler 的 openat 分支却正常（那里先调 bind_map）。
//
// 症状极具误导：`ls -l` 能看到文件、`stat` 能拿到 mode/size、`md5sum` 与
// rootfs 内副本一致，只有 **exec 报 No such file or directory**；把同一文件
// cp 到 /tmp 或 /bin 立刻能跑。
//
// 参数：
//   path — guest 绝对路径（以 / 开头）
//   flags — 传给 openat 的标志位
// 返回：fd（>=0）或负 errno；bind 命中时相对 AT_FDCWD 打开宿主路径，
//       否则相对 g_rootfs_fd 打开 "./<path>"。
static long guest_open(const char *path, long flags) {
    if (!path || path[0] != (char)47) return -2;   // 只接受绝对路径
    // ★只用一个 PATH_MAX 缓冲（v1.6.16）。
    //   本函数在 SIGSYS handler 内被调用（map_file / guest_is_bionic / loader_probe
    //   都在 handler 路径上），而 handler 跑在 64KB altstack 上。开两个缓冲会多占
    //   4KB，实测把 rg 的并发崩溃率从 3/15 放大到 10/15 —— 栈越界后表现为无信息的
    //   SIGSEGV，极难归因。bind_map 只在命中时写 out，不命中时缓冲未被触碰，
    //   所以复用同一个缓冲是安全的。
    char buf[PATH_MAX];
    if (bind_map(path, buf)) return raw6(SYS_openat, -100 /*AT_FDCWD*/, (long)buf, flags, 0, 0, 0);
    make_rel(buf, path);
    return raw6(SYS_openat, g_rootfs_fd, (long)buf, flags, 0, 0, 0);
}

// /dev 节点不能从 rootfs 里找（非 root 无权 mknod），必须透传到宿主真实节点。
// 只放行安全的字符设备，避免 guest 触到宿主敏感设备。
// v7：伪文件系统透传。
//   Android 宿主的 /proc /sys /dev 是非 root 可读的，只要把 guest 里的这些绝对
//   路径原样交给内核（不翻译成 rootfs 内相对路径），程序就能看到真实的
//   /proc/meminfo、/proc/cpuinfo、/sys/class/net 等。
//   这正是 proot -b /proc -b /sys 做的事，效果等价，无需 root。
//
//   注意：透传后看到的是「宿主的」信息（free 显示整机内存、ps 显示全部进程），
//   与 chroot + 独立 procfs 仍不同；但比「空 /proc」强得多，且是零成本。
static int is_host_passthrough(const char *p) {
    // 前缀匹配表：命中即透传给内核按宿主真实路径解析
    static const char *prefix[] = {
        "/proc/", "/proc",
        "/sys/",  "/sys",
        "/dev/",  "/dev",
    };
    for (int i = 0; i < (int)(sizeof(prefix)/sizeof(prefix[0])); i++) {
        const char *a = prefix[i]; const char *b = p;
        while (*a && *a == *b) { a++; b++; }
        if (*a == 0) return 1;   // 前缀匹配成功
    }
    return 0;
}

static int dev_passthrough(const char *p) { return is_host_passthrough(p); }


// ---- bionic 二进制直通（硬件 GPU 桥接的前置能力）----
//   Android 的原生程序（用 NDK 编的、以及所有 vendor 的 EGL/GLES 库）
//   走的是 bionic ABI：PT_INTERP = /system/bin/linker64，依赖 libc.so/libm.so/libdl.so
//   这些 Android 专有库。musl 完全加载不了它们（实测 libc++.so 直接报
//   "Error relocating: __register_atfork: symbol not found"，因为 bionic 的
//   libc 依赖 ld-android.so 且导出符号集与 musl 不兼容）。
//
//   但内核本身能跑 bionic 程序 —— 只要不拦它的 execve，让内核按 PT_INTERP
//   自己调 /system/bin/linker64 即可。所以这里的做法是：识别出目标是 bionic
//   ELF（PT_INTERP 含 "linker64" 或 "linker"），就把路径原样交给内核、
//   完全不翻译、不装载、不重执行。
//
//   实测：这是在本方案里用上 Adreno 硬件 GPU 的唯一可行入口
//   （musl 侧 llvmpipe 软渲染 30.5 ms/帧，bionic 侧 Adreno 840 只要 1.76 ms/帧，17.4 倍）。
static int guest_is_bionic(const char *gpath) {
    char rel[PATH_MAX]; make_rel(rel, gpath);
    long fd = guest_open(gpath, 0);
    if (fd < 0) return 0;
    unsigned char hdr[64];
    long n = raw6(SYS_read, fd, (long)hdr, 64, 0, 0, 0);
    raw6(SYS_close, fd, 0, 0, 0, 0, 0);
    if (n < 64) return 0;
    // ELF64 magic
    if (!(hdr[0]==0x7f && hdr[1]=='E' && hdr[2]=='L' && hdr[3]=='F')) return 0;
    if (hdr[4] != 2) return 0;                       // 只要 64 位
    unsigned int phoff = *(unsigned int *)(hdr + 32);
    unsigned short phentsize = *(unsigned short *)(hdr + 54);
    unsigned short phnum = *(unsigned short *)(hdr + 56);
    if (phoff == 0 || phnum == 0 || phentsize < 56) return 0;
    // 逐个 PT_INTERP 找解释器路径
    for (int i = 0; i < phnum && i < 16; i++) {
        unsigned long off = (unsigned long)phoff + (unsigned long)i * phentsize;
        unsigned char ph[56];
        long f2 = guest_open(gpath, 0);
        if (f2 < 0) return 0;
        raw6(SYS_lseek, f2, (long)off, 0, 0, 0, 0);
        long m = raw6(SYS_read, f2, (long)ph, 56, 0, 0, 0);
        raw6(SYS_close, f2, 0, 0, 0, 0, 0);
        if (m < 56) return 0;
        unsigned int p_type = *(unsigned int *)(ph + 0);
        if (p_type != 3) continue;                   // PT_INTERP = 3
        unsigned long p_offset = *(unsigned long *)(ph + 8);
        unsigned long p_filesz = *(unsigned long *)(ph + 32);
        if (p_filesz == 0 || p_filesz > 256) return 0;
        char interp[260];
        long f3 = guest_open(gpath, 0);
        if (f3 < 0) return 0;
        raw6(SYS_lseek, f3, (long)p_offset, 0, 0, 0, 0);
        long k = raw6(SYS_read, f3, (long)interp, (long)(p_filesz < 255 ? p_filesz : 255), 0, 0, 0);
        raw6(SYS_close, f3, 0, 0, 0, 0, 0);
        if (k <= 0) return 0;
        interp[k] = 0;
        // bionic 的 linker 名字是 linker64 / linker（Android 12+ 在 APEX 里也叫 linker64）
        for (int j = 0; interp[j]; j++) {
            if (interp[j]=='l' && interp[j+1]=='i' && interp[j+2]=='n' && interp[j+3]=='k' &&
                interp[j+4]=='e' && interp[j+5]=='r') return 1;
        }
        return 0;
    }
    return 0;
}


static void sigsys(int s, siginfo_t *info, void *uc) {
    (void)s; (void)info;
    ucontext_t *u = (ucontext_t *)uc;
    unsigned long long *r = (unsigned long long *)u->uc_mcontext.regs;
    long nr = (long)r[8];
    long a0 = (long)(int)(unsigned int)r[0];
    unsigned long long a1 = r[1], a2 = r[2], a3 = r[3], a4 = r[4], a5 = r[5];
    // ★自愈：guest 可能用 pthread_sigmask 把 SIGSYS 阻塞掉（nginx/postgres 这类
    //   多线程程序常见）。一旦 SIGSYS 被阻塞，seccomp TRAP 会直接改判成 kill 进程
    //   （表现为 Bad system call / exit 159），且此时 handler 已被调用过一次。
    //   这里主动解除阻塞，让后续 trap 能正常回到本 handler。
    // ★这条也只在「SIGSYS 真被阻塞」时才需要。sigreturn 会自动恢复原 mask，
    //   所以只要 handler 里没有改过 mask，下一次进来时它仍是未被阻塞的。
    //   用一个静态标志跳过绝大多数调用：只有当程序自己 pthread_sigmask 屏蔽了
    //   SIGSYS 时才真正发这个 syscall（每个被拦 syscall 少一次 svc 往返）。
    static int g_sigsys_unblocked;
    if (!g_sigsys_unblocked) {
        unsigned long set = 1UL << (31 - 1);
        raw6(SYS_rt_sigprocmask, 1 /*SIG_UNBLOCK*/, (long)&set, 0, 8, 0, 0);
        g_sigsys_unblocked = 1;
    }

    // ★不要再在这里做 ensure_rootfs_fd() 的探活：那是一次额外 readlinkat，
    //   等于给每个被拦的 open/stat 加一次 syscall
    //   （实测 stat 9.45us / openread 13.1us，而 proroot 是 4.01us / 8.91us）。
    //   正确做法是「零开销 + 失败重试」：翻译后的 syscall 若返回 -EBADF，
    //   说明 fd 被别人关了（posix_spawn / subprocess 的 close_fds=True），
    //   此时才重开 rootfs fd 并重试一次。见 translate 分支里的 EBADF 处理。
    if (nr == SYS_execve || nr == SYS_execveat) ensure_rootfs_fd();
    g_hits++;
    if (nr < 512) g_nr_cnt[nr]++;

    // ★vfork 降级：只对带 CLONE_VFORK 的 clone 生效（BPF 已过滤）。
    //   清掉 CLONE_VM|CLONE_VFORK，把 vfork 变成普通 fork；然后 PC -= 4 让 guest
    //   重新执行那条 svc 指令（此时 flag 已改，BPF 不再 TRAP，直接放行）。
    //   为什么不用 C handler 里的 raw clone：vfork 子进程的 child stack 可能非零/共享，
    //   在 C 栈上发 clone 会炸。
    if (nr == SYS_clone) {
        unsigned long long flags = r[0];
        if (flags & 0x4000ULL) {                  // CLONE_VFORK
            r[0] = flags & ~(0x100ULL | 0x4000ULL); // 清 CLONE_VM(0x100) | CLONE_VFORK(0x4000)
            r[8] = SYS_clone;
            u->uc_mcontext.pc -= 4;                 // 回退到 svc 指令重试
            return;
        }
    }

    if (nr == SYS_execve || nr == SYS_execveat) {
        const char *gpath = (nr == SYS_execve) ? (const char *)r[0] : (const char *)a1;
        char **gargv = (nr == SYS_execve) ? (char **)a1 : (char **)a2;
        char **genvp = (nr == SYS_execve) ? (char **)a2 : (char **)a3;
        g_execs++;

        // ★bionic 二进制直通：Android NDK 编的程序（以及所有要走 Adreno/Vulkan 的
        //   图形程序）链路是 PT_INTERP=/system/bin/linker64 + bionic libc。
        //   musl 装不了它们（实测 __register_atfork 符号缺失、libadreno_utils 找不到），
        //   但内核能跑 —— 只要不在我们这儿劫持，让内核按 PT_INTERP 自己调 linker64。
        //   这里把路径换成宿主真实路径后原样交给内核：不翻译、不装载、不重执行。
        //   实测这是本方案用上硬件 GPU 的唯一入口：
        //   musl/llvmpipe 软渲染 30.54 ms/帧 vs bionic/Adreno 840 的 1.76 ms/帧（17.4×）。
        if (gpath && gpath[0] == (char)47 && guest_is_bionic(gpath)) {
            char hp[PATH_MAX];
            // ★bind 下的 bionic 二进制也要查 bind 表（v1.6.16）：make_host 只拼
            //   rootfs 前缀，会把 `/eng/...` 拼成 `$rootfs/eng/...`（不存在）。
            if (!bind_map(gpath, hp)) make_host(hp, gpath);
            // 不能在本进程 exec：本进程带 filter，bionic 的 linker64 不会重装
            // SIGSYS handler，第一个被 TRAP 的 syscall 会直接杀掉它。
            // 交给 filter-free 的 cleaner 代跑，拿回退出码后本进程以同码退出。

            int rc2 = cleaner_exec(hp, gargv);
            if (rc2 >= 0) raw6(94 /*exit_group*/, rc2, 0, 0, 0, 0, 0);
            ldr_puts("BIONIC: cleaner_exec FAIL");
            r[0] = (unsigned long long)(-ENOENT);
            return;
        }

        // ★这里只做存在性检查，不做完整预读：
        //   完整 preload（解析 interp/guest、建 argv、开 fd）会在重执行后的
        //   REEXEC 分支再做一次，两边都做等于白算一遍（实测多花 1.1~1.8ms）。
        //   但必须保留「失败返回 -ENOENT」的语义，否则 execlp 的 PATH 搜索链会断。
        long ok = loader_preload(gpath, gargv, genvp, 0);
        // ★preload 失败必须把 errno 还给 guest，而不是让进程退出：
        //   execlp() 会按 PATH 逐个试（/usr/local/sbin/gzip → /usr/local/bin/gzip → …），
        //   靠 execve 的 ENOENT 来判断「这个目录没有，试下一个」。
        //   之前失败就 exit_group(127)，等于把整个进程杀了，PATH 搜索链直接断掉
        //   （实测后果：tar -czf 在第一个路径就停，写出 0 字节且无报错）。
        if (ok < 0) {
            r[0] = (unsigned long long)(-ENOENT);
            return;
        }
        // ===== v10：内核自我重执行 =====
        // 不再 fork+wait（那样 fork 376us + wait 1270us = 1.65ms 全是页表复制与调度等待）。
        // 改为 execveat(loader_self, "", loader_argv, envp, AT_EMPTY_PATH)：让内核真正
        // exec 我自己 —— 内核会创建全新的 mm（因此 posix_spawn/vfork 的 CLONE_VM 子进程
        // 也能安全脱离父进程的 mm），PID/PPID/cwd/umask/rlimit/credentials/父进程关系
        // 全部保持，正是 execve 的标准语义。新 loader 的 _start 重装 SIGSYS handler 后
        // 再手动装载 guest。
        {
            // 构造 loader 自己的 argv：
            //   loader --dsh-reexec-v1 --rootfs <R> --interp <I> --guest <G> <guest argv...>
            // 注意：这些字符串必须复制到本进程内（旧 guest 映射在 exec 后消失，
            //   但内核在替换 mm 之前会把 argv/envp 复制到新栈，所以传旧地址本身是安全的）。
            static char *na[32];
            static char *gv2[256];
            static char rfs[PATH_MAX], itp[PATH_MAX], gst[PATH_MAX];
            // ★bind 表也必须带过去，否则重执行后映射全丢（实测 /sdcard 变回 rootfs 内空目录）
            static char bspec[MAX_BIND][PATH_MAX * 2];
            scopy_(rfs, g_rootfs_host);
            scopy_(itp, g_interp_path);
            scopy_(gst, g_guest_path);
            int k = 0;
            na[k++] = (char *)"dsh-alpine";
            na[k++] = (char *)"--dsh-reexec-v1";
            for (int bi = 0; bi < g_bind_n && k < 28; bi++) {
                int o = 0;
                for (int j = 0; j < g_bind_glen[bi]; j++) bspec[bi][o++] = g_bind_g[bi][j];
                bspec[bi][o++] = (char)58;
                for (int j = 0; j < g_bind_hlen[bi]; j++) bspec[bi][o++] = g_bind_h[bi][j];
                bspec[bi][o] = 0;
                na[k++] = (char *)"-m";
                na[k++] = bspec[bi];
            }
            na[k++] = (char *)"--rootfs"; na[k++] = rfs;
            na[k++] = (char *)"--interp"; na[k++] = itp;
            na[k++] = (char *)"--guest";  na[k++] = gst;
            na[k] = 0;
            // guest 的 argv 已由 loader_preload 存进 g_argv_store（含 argv[0]）
            for (int i = 0; i < g_argv_count && i < 250; i++) gv2[i] = g_argv_store[i];
            gv2[g_argv_count < 250 ? g_argv_count : 249] = 0;
            // 拼成完整 argv：loader 参数 + guest 参数
            static char *fullargv[280];
            int fa = 0;
            for (int i = 0; i < k; i++) fullargv[fa++] = na[i];
            // ★必须从 0 开始：guest 的 argv[0] 也要传下去，否则重执行后 argv[0] 错位，
            //   实测表现为 gcc 报 "no input files"（把 argv[1] 当成了程序名）。
            for (int i = 0; i < g_argv_count && fa < 275; i++) fullargv[fa++] = gv2[i];
            fullargv[fa] = 0;

            // 打开自身（O_PATH 足够，execveat + AT_EMPTY_PATH 接受）
            long self = raw6(SYS_openat, -100, (long)"/proc/self/exe", 0x200000 /*O_PATH*/, 0, 0, 0);
            if (self < 0) {
                // 打不开就退回 fork 老路，保证功能不丢
                goto fallback_fork;
            }
            // ★envp 必须一起传下去：重执行后新 loader 的 ldr_entry 只有拿到它
            //   才能把用户环境（PATH 里的 ccache、http_proxy、LANG…）带到下一次 exec。
            //   早期这里传 0（空环境），导致每次 exec 都退回写死的三行 PATH/HOME/TERM
            //   —— 实测容器内 `export PATH=...` 对子进程完全无效，ccache 永远不生效。
            static char **gve;
            gve = genvp;
            raw6(SYS_execveat, self, (long)"", (long)fullargv, (long)gve, 0x1000, 0);
            // execveat 失败 → 退回 fork 路径，不能把 guest 进程杀掉
            raw6(SYS_close, self, 0, 0, 0, 0, 0);
        }
    fallback_fork:
        {
            long pid = raw6(SYS_clone, 17 /*SIGCHLD*/, 0, 0, 0, 0, 0);
            if (pid == 0) {
                unsigned long set = 1UL << (31 - 1);
                raw6(SYS_rt_sigprocmask, 1, (long)&set, 0, 8, 0, 0);
                child_run_raw(0);
                raw6(SYS_exit_group, 126, 0, 0, 0, 0, 0);
            }
            int status = 0;
            if (pid > 0) raw6(SYS_wait4, pid, (long)&status, 0, 0, 0, 0);
            // [prof] 打印按 syscall 号排序的 trap 统计（写到 stderr，只在本进程）
            {
                char lb[256];
                int k = 0;
                const char *pfx = "[TRAPCNT] total=";
                while (*pfx) lb[k++] = *pfx++;
                k += utoa_(lb + k, (unsigned long)g_hits);
                lb[k++] = 10;
                raw6(SYS_write, 2, (long)lb, k, 0, 0, 0);
                for (int i = 0; i < 512; i++) {
                    if (!g_nr_cnt[i]) continue;
                    int m = 0;
                    lb[m++] = 'n'; lb[m++] = 'r'; lb[m++] = '=';
                    m += utoa_(lb + m, (unsigned long)i);
                    lb[m++] = ' '; lb[m++] = 'c'; lb[m++] = '=';
                    m += utoa_(lb + m, (unsigned long)g_nr_cnt[i]);
                    lb[m++] = 10;
                    raw6(SYS_write, 2, (long)lb, m, 0, 0, 0);
                }
            }
            raw6(SYS_exit_group, (long)((status >> 8) & 0xFF), 0, 0, 0, 0, 0);
        }
        return;
    }

    // ---- 老式（无 dirfd）路径 syscall：path 在 a0 ----
    switch (nr) {
    case SYS_chdir: {
        // chdir 只有 1 个参数（path）。先 openat(O_PATH|O_DIRECTORY) 拿 fd 再 fchdir。
        const char *p0 = (const char *)r[0];
        if (p0 && p0[0] == '/') {
            // ★bind 优先级必须与 openat 一致：否则 `cd /tmp` 会进 rootfs 的 /tmp，
            //   而 `cat /tmp/x` 却去 bind 目标找 —— 同一路径两种视图，程序必错。
            //   （实测症状：-m /tmp:host 之后 `cd /tmp && sh ./cc.sh` 报
            //    `can't open './cc.sh': No such file or directory`，
            //    因为 cwd 落在 rootfs 的 /tmp，而 ls /tmp 走 bind 看到的是宿主目录。）
            char bb[PATH_MAX];
            if (bind_map(p0, bb)) {
                r[0] = (unsigned long long)raw6(SYS_chdir, (long)bb, 0, 0, 0, 0, 0);
                return;
            }
            char b2[PATH_MAX]; make_rel(b2, p0);
            long dfd = raw6(SYS_openat, g_rootfs_fd, (long)b2,
                            0x200000 /*O_PATH*/ | 0x10000 /*O_DIRECTORY*/, 0, 0, 0);
            if (dfd < 0 && dfd > -4096) { r[0] = (unsigned long long)dfd; return; }
            long rv = raw6(SYS_fchdir, dfd, 0, 0, 0, 0, 0);
            raw6(SYS_close, dfd, 0, 0, 0, 0, 0);
            r[0] = (unsigned long long)rv;
            return;
        }
        r[0] = (unsigned long long)raw6(SYS_chdir, (long)p0, 0, 0, 0, 0, 0);
        return;
    }
    case SYS_getcwd: {
        // getcwd(buf, size)：内核返回的是宿主真实路径（/data/local/tmp/alpine_rw/tmp/cm），
        // guest 必须看到 /tmp/cm。做法：先真实取回，再把 rootfs 前缀裁掉。
        char *buf = (char *)r[0];
        long size = (long)a1;
        long rv = raw6(SYS_getcwd, (long)buf, size, 0, 0, 0, 0);
        // ★bind 反向映射必须**先于** rootfs 前缀裁剪。
        //
        // 踩过的坑：只做了 rootfs 前缀裁剪，于是 cwd 在 bind 目录里时（比如
        // 引擎的 workspaces，宿主 /storage/emulated/0/Documents/dsh data/workspaces）
        // 前缀对不上，原样返回宿主路径 —— 终端提示符直接显示
        //   localhost:/storage/emulated/0/Documents/dsh data/workspaces$
        // 用户看不到 /ws，且任何用 getcwd 拼相对路径的程序都会拼错。
        if (rv > 0 && g_bind_n > 0) {
            for (int bi = 0; bi < g_bind_n; bi++) {
                int hl = g_bind_hlen[bi];
                if (hl <= 0 || buf[0] != g_bind_h[bi][0]) continue;
                int match = 1;
                for (int k = 0; k < hl; k++) {
                    if (buf[k] != g_bind_h[bi][k]) { match = 0; break; }
                }
                // 前缀必须落在路径边界上：/ws 匹配 /ws 与 /ws/x，不匹配 /wsx
                if (!match) continue;
                if (buf[hl] != 0 && buf[hl] != (char)47) continue;
                const char *rest = buf + hl;
                const char *gl = g_bind_g[bi];
                int n2 = 0;
                while (gl[n2] && n2 < PATH_MAX - 1) { buf[n2] = gl[n2]; n2++; }
                int k2 = 0;
                while (rest[k2] && n2 < PATH_MAX - 1) buf[n2++] = rest[k2++];
                buf[n2] = 0;
                rv = n2;
                break;
            }
            // 已映射成功就不再走 rootfs 裁剪
            if (buf[0] == (char)47 && g_bind_g[0][0] != 0) {
                int already = 0;
                for (int bi = 0; bi < g_bind_n; bi++) {
                    int gl2 = g_bind_glen[bi];
                    int same = 1;
                    for (int k = 0; k < gl2; k++) {
                        if (buf[k] != g_bind_g[bi][k]) { same = 0; break; }
                    }
                    if (same && (buf[gl2] == 0 || buf[gl2] == (char)47)) { already = 1; break; }
                }
                if (already) { r[0] = (unsigned long long)rv; return; }
            }
        }
        if (rv > 0) {
            if (g_rootfs_host_len > 0 &&
                buf[0] == g_rootfs_host[0]) {
                int match = 1;
                for (int k = 0; k < g_rootfs_host_len; k++) {
                    if (buf[k] != g_rootfs_host[k]) { match = 0; break; }
                }
                if (match) {
                    char *rest = buf + g_rootfs_host_len;
                    if (*rest == 0) { buf[0] = (char)47; buf[1] = 0; rv = 1; }
                    else {
                        int n2 = 0;
                        while (rest[n2]) { buf[n2] = rest[n2]; n2++; }
                        buf[n2] = 0;
                        rv = n2;
                    }
                }
            }
        }
        r[0] = (unsigned long long)rv;
        return;
    }
    // ★statfs(path, buf) / fstatfs(fd, buf)：没有 *at 变体，只能拼宿主机绝对路径。
    //   statvfs() 内部走 statfs，apt / df / du 都依赖它。
    // ---- DNS 端口改写：musl 把 53 写死，我们把它转给代理的非特权端口 ----
    //
    // musl 的 getaddrinfo 把 DNS 端口硬编码成 53（src/network/res_msend.c 的
    // __dns_ports 只解析 nameserver 的 IP，冒号后缀整个忽略 —— 实测写
    // `nameserver 127.0.0.1:15353` 后包仍发往 53）。而 53 是特权端口
    // （ip_unprivileged_port_start=1024），App uid 绑不上（实测 EACCES）。
    //
    // 唯一真正免 root 的出路：在发送 syscall 上改写目标端口。musl 对此毫无察觉。
    //
    // ★sendto 与 sendmsg 两个都要处理：
    //   musl 的解析器走 sendto；但程序自己写的 resolver（node 的 cares、
    //   curl 的 c-ares）可能走 sendmsg。第一版只拦了 connect（实测无效果，
    //   因为 musl 从不 connect），第二版只拦 sendto（仍不生效），所以两个都拦。
    //
    //   sendto(fd, buf, len, flags, addr, addrlen)  → addr 在 a4
    //   sendmsg(fd, msg, flags)                     → msghdr 的 name 在偏移 0，
    //                                                 namelen 在偏移 8
    //
    // 端口来自环境变量 DSH_DNS_PORT；没有它就完全不动，语义与之前一致。
    // 只改回环（外部 DNS 本就被 Android 拦，改了没用还会掩盖问题）。
    // ★connect 也要改写（v1.6.14）：musl 的 resolver 不用 connect（所以早期只在
    // sendto 上改就够了），但 **curl / libcurl 用的是 connect + send** ——
    // 包发往 127.0.0.1:53，那里没人监听，表现为 curl 完全无法解析域名，
    // 而 wget / apk / node 都正常（它们走 musl resolver）。
    //
    // 这里把目标端口改成代理端口。连上之后 socket 的对端就是代理，
    // 后续 send 不需要再改写，回程 recv 也不需要（对端端口本就是代理端口，
    // 但 curl 不校验来源端口，与 musl 的 __res_msend_rc 不同）。
    case SYS_connect: {
        int port = dns_proxy_port();
        const unsigned char *q = (const unsigned char *)a1;
        long salen = (long)a2;
        if (port > 0 && q && salen >= 8) {
            unsigned int fam = (unsigned int)q[0] | ((unsigned int)q[1] << 8);
            int hit = 0, need = 0;
            unsigned char tmp[32];
            if (fam == 2 /*AF_INET*/ && salen >= 16) {
                unsigned int p_ = ((unsigned int)q[2] << 8) | (unsigned int)q[3];
                if (p_ == 53 && q[4] == 127 && q[5] == 0 && q[6] == 0 && q[7] == 1) {
                    hit = 1; need = 16;
                }
            } else if (fam == 10 /*AF_INET6*/ && salen >= 28) {
                unsigned int p_ = ((unsigned int)q[2] << 8) | (unsigned int)q[3];
                int is_lo = 1;
                for (int k = 0; k < 15; k++) if (q[8 + k] != 0) { is_lo = 0; break; }
                if (!(is_lo && q[23] == 1)) is_lo = 0;
                if (p_ == 53 && is_lo) { hit = 1; need = 28; }
            }
            if (hit) {
                for (int i = 0; i < need; i++) tmp[i] = q[i];
                tmp[2] = (unsigned char)((port >> 8) & 0xFF);
                tmp[3] = (unsigned char)(port & 0xFF);
                r[0] = raw6(SYS_connect, a0, (long)tmp, need, 0, 0, 0);
                return;
            }
        }
        r[0] = raw6(SYS_connect, a0, a1, a2, a3, a4, a5);
        return;
    }

    case SYS_sendto:
    case SYS_sendmsg: {
        const unsigned char *q = 0;
        long salen = 0;
        int is_msg = (nr == SYS_sendmsg);
        if (is_msg) {
            // struct msghdr { void *name; unsigned int namelen; ... } (aarch64)
            const unsigned char *mh = (const unsigned char *)a1;
            if (mh) {
                q = (const unsigned char *)(*(void *const *)mh);
                salen = (long)(*(const unsigned int *)(mh + 8));
            }
        } else {
            q = (const unsigned char *)a4;
            salen = (long)a5;
        }
        int port = dns_proxy_port();
        if (port > 0 && q && salen >= 8) {
            unsigned int fam = (unsigned int)q[0] | ((unsigned int)q[1] << 8);
            int hit = 0;
            unsigned char tmp[32];
            int need = 0;
            if (fam == 2 /*AF_INET*/ && salen >= 16) {
                unsigned int p_ = ((unsigned int)q[2] << 8) | (unsigned int)q[3];
                int loop = (q[4] == 127 && q[5] == 0 && q[6] == 0 && q[7] == 1);
                if (p_ == 53 && loop) { hit = 1; need = 16; }
            } else if (fam == 10 /*AF_INET6*/ && salen >= 28) {
                unsigned int p_ = ((unsigned int)q[2] << 8) | (unsigned int)q[3];
                int is_lo = 1;
                for (int k = 0; k < 15; k++) if (q[8 + k] != 0) { is_lo = 0; break; }
                if (!(is_lo && q[23] == 1)) is_lo = 0;
                if (p_ == 53 && is_lo) { hit = 1; need = 28; }
            }
            if (hit) {
                for (int i = 0; i < need; i++) tmp[i] = q[i];
                tmp[2] = (unsigned char)((port >> 8) & 0xFF);
                tmp[3] = (unsigned char)(port & 0xFF);
                if (is_msg) {
                    // 复制 msghdr，把 name 指向改写后的 tmp
                    unsigned char mh2[64];
                    for (int i = 0; i < 64; i++) mh2[i] = ((const unsigned char *)a1)[i];
                    *(unsigned char **)(mh2) = tmp;
                    r[0] = raw6(SYS_sendmsg, a0, (long)mh2, a2, 0, 0, 0);
                } else {
                    r[0] = raw6(SYS_sendto, a0, a1, a2, a3, (long)tmp, need);
                }
                return;
            }
        }
        break;
    }

    // ---- DNS 回程：把代理回的源端口 15353 改回 53 ----
    //
    // musl 的 __res_msend_rc 会校验应答来源必须等于它发的目标 (127.0.0.1:53)。
    // 我们出程把目标改成代理端口后，回程源端口也变成代理端口 → musl 判为
    // 「不是我要的服务器」直接丢弃，表现为 sendto 成功、代理也确实 replied，
    // 但 getaddrinfo 仍返回 EAI_AGAIN。所以两端都要改。
    case SYS_recvfrom:
    case SYS_recvmsg: {
        int port = dns_proxy_port();
        r[0] = (unsigned long long)raw6(nr, (long)a0, (long)a1, (long)a2, (long)a3, (long)a4, (long)a5);
        if (port > 0 && (long)r[0] > 0) {
            if (nr == SYS_recvfrom) {
                // recvfrom(fd, buf, len, flags, src_addr, addrlen)
                unsigned char *sa = (unsigned char *)a4;
                if (sa) {
                    unsigned int fam = (unsigned int)sa[0] | ((unsigned int)sa[1] << 8);
                    unsigned int p_ = ((unsigned int)sa[2] << 8) | (unsigned int)sa[3];
                    if (fam == 2 && p_ == (unsigned int)port &&
                        sa[4] == 127 && sa[5] == 0 && sa[6] == 0 && sa[7] == 1) {
                        sa[2] = 0; sa[3] = 53;
                    } else if (fam == 10 && p_ == (unsigned int)port) {
                        int is_lo = 1;
                        for (int k = 0; k < 15; k++) if (sa[8 + k] != 0) { is_lo = 0; break; }
                        if (is_lo && sa[23] == 1) { sa[2] = 0; sa[3] = 53; }
                    }
                }
            } else {
                // recvmsg(fd, msghdr, flags)：msghdr.name / .namelen 由内核填好
                unsigned char *mh = (unsigned char *)a1;
                if (mh) {
                    unsigned char *sa = (unsigned char *)(*(void *const *)mh);
                    if (sa) {
                        unsigned int fam = (unsigned int)sa[0] | ((unsigned int)sa[1] << 8);
                        unsigned int p_ = ((unsigned int)sa[2] << 8) | (unsigned int)sa[3];
                        if (fam == 2 && p_ == (unsigned int)port &&
                            sa[4] == 127 && sa[5] == 0 && sa[6] == 0 && sa[7] == 1) {
                            sa[2] = 0; sa[3] = 53;
                        } else if (fam == 10 && p_ == (unsigned int)port) {
                            int is_lo = 1;
                            for (int k = 0; k < 15; k++) if (sa[8 + k] != 0) { is_lo = 0; break; }
                            if (is_lo && sa[23] == 1) { sa[2] = 0; sa[3] = 53; }
                        }
                    }
                }
            }
        }
        return;
    }

    case SYS_statfs: {
        const char *sp = (const char *)r[0];
        if (sp && sp[0] == (char)47) {
            if (is_host_passthrough(sp)) {
                r[0] = (unsigned long long)raw6(SYS_statfs, (long)sp, (long)a1, 0, 0, 0, 0);
                return;
            }
            char hb[PATH_MAX]; make_host(hb, sp);
            r[0] = (unsigned long long)raw6(SYS_statfs, (long)hb, (long)a1, 0, 0, 0, 0);
            return;
        }
        r[0] = (unsigned long long)raw6(nr, (long)sp, (long)a1, (long)a2, (long)a3, (long)a4, (long)a5);
        return;
    }
    case SYS_fstatfs:
        // fd 已经在 rootfs 内（由我们翻译过的 open 得到），直接用即可
        r[0] = (unsigned long long)raw6(SYS_fstatfs, (long)r[0], (long)a1, 0, 0, 0, 0);
        return;

    case SYS_faccessat2: {
        const char *p2 = (const char *)a1;
        if (a0 == -100 && p2 && p2[0] == '/') {
            char b3[PATH_MAX]; make_rel(b3, p2);
            r[0] = (unsigned long long)raw6(SYS_faccessat2, g_rootfs_fd, (long)b3, (long)a2, (long)a3, (long)a4, (long)a5);
            return;
        }
        r[0] = (unsigned long long)raw6(SYS_faccessat2, a0, (long)a1, (long)a2, (long)a3, (long)a4, (long)a5);
        return;
    }
    case SYS_openat2: {
        const char *p2 = (const char *)a1;
        if (a0 == -100 && p2 && p2[0] == '/') {
            char b3[PATH_MAX]; make_rel(b3, p2);
            r[0] = (unsigned long long)raw6(SYS_openat2, g_rootfs_fd, (long)b3, (long)a2, (long)a3, (long)a4, (long)a5);
            return;
        }
        r[0] = (unsigned long long)raw6(SYS_openat2, a0, (long)a1, (long)a2, (long)a3, (long)a4, (long)a5);
        return;
    }
    // ★关键防护：guest（nginx/postgres 等）会用 sigaction 把 SIGSYS 重置成
    //   SIG_DFL/SIG_IGN，一旦重置，第一个被 TRAP 的 syscall 就直接以默认动作
    //   终止进程（exit 159 Bad system call），我的 handler 再也不会被调用。
    //   同样严禁把 SIGSYS 加入阻塞掩码（TRAP 遇阻塞 SIGSYS 同样是 kill）。
    case SYS_rt_sigaction: {
        long signo = (long)(int)(unsigned int)a0;
        if (signo == SIGSYS) {
            // ★ 一律静默拒绝：既不读 guest 内存（node 传的 act 布局不可预期，
            //   读错会 SEGV 在 handler 里），也不真的调用 rt_sigaction。
            //   guest 拿到 0 = 「设置成功」，但实际处置仍是我们自己装的 handler，
            //   seccomp TRAP 才能继续把控制权交回来。
            r[0] = 0;
            return;
        }
        r[0] = (unsigned long long)raw6(nr, a0, a1, a2, a3, a4, a5);
        return;
    }
    case SYS_rt_sigprocmask: {
        // how: 0=BLOCK 1=UNBLOCK 2=SETMASK；set 是 sigset_t*（低 8 字节含 SIGSYS bit30）
        long how = (long)(int)(unsigned int)a0;
        unsigned long *setp = (unsigned long *)(uintptr_t)a1;
        if (setp && how != 1 /*非 UNBLOCK*/) {
            unsigned long v = *setp;
            if (v & (1UL << (31 - 1))) {
                // guest 想阻塞 SIGSYS -> 悄悄去掉这一位后继续
                unsigned long nv = v & ~(1UL << (31 - 1));
                r[0] = (unsigned long long)raw6(nr, a0, (long)&nv, a2, a3, a4, a5);
                return;
            }
        }
        r[0] = (unsigned long long)raw6(nr, a0, a1, a2, a3, a4, a5);
        return;
    }
    default: break;
    }

    // ---- 单路径 *at 类：path 在 a1，dirfd 在 a0 ----
    const char *path = (const char *)a1;
    switch (nr) {
    case SYS_openat: case SYS_newfstatat: case SYS_faccessat: case SYS_readlinkat:
    case SYS_statx: case SYS_unlinkat: case SYS_mkdirat: case SYS_fchmodat:
    case SYS_fchownat: case SYS_utimensat: case SYS_mknodat: {
        // /dev/urandom 等在 rootfs 里不存在（非 root 无法 mknod），透传到宿主真实节点。
        // ★必须排在 dev_passthrough 之前：/proc 整棵前缀是透传给宿主的，
        //   而 /proc/self/exe 透传后会读到 loader 自己的路径（实测），
        //   所以这一条要在透传判断之前拦下来。
        if (nr == SYS_readlinkat && a0 == -100 && is_proc_self_exe(path)) {
            int n = 0;
            while (g_guest_path[n] && n < (int)a3 - 1) n++;
            for (int i = 0; i < n; i++) ((char *)a2)[i] = g_guest_path[i];
            r[0] = (unsigned long long)n;
            return;
        }
        if (a0 == -100 && path && path[0] == '/' && dev_passthrough(path)) {
            r[0] = (unsigned long long)raw6(nr, -100 /*AT_FDCWD*/, (long)path,
                                            (long)a2, (long)a3, (long)a4, (long)a5);
            return;
        }
        if (a0 == -100 && path && path[0] == '/') {
            char buf[PATH_MAX];
            // ★bind 映射优先于 rootfs 翻译（等价 proot -b）
            if (bind_map(path, buf)) {
                long rvb = raw6(nr, -100 /*AT_FDCWD*/, (long)buf,
                                (long)a2, (long)a3, (long)a4, (long)a5);
                r[0] = (unsigned long long)rvb;
                return;
            }
            make_rel(buf, path);
            long rv;
            switch (nr) {
            case SYS_openat:     rv = raw6(SYS_openat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            case SYS_newfstatat: rv = raw6(SYS_newfstatat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            case SYS_faccessat:  rv = raw6(SYS_faccessat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            case SYS_readlinkat: rv = raw6(SYS_readlinkat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            case SYS_statx:      rv = raw6(SYS_statx, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            case SYS_unlinkat:   rv = raw6(SYS_unlinkat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            case SYS_mkdirat:    rv = raw6(SYS_mkdirat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            case SYS_fchmodat:   rv = raw6(SYS_fchmodat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            case SYS_fchownat:   rv = raw6(SYS_fchownat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            case SYS_utimensat:  rv = raw6(SYS_utimensat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            case SYS_mknodat:    rv = raw6(SYS_mknodat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
            default:             rv = -ENOSYS; break;
            }
            // ★零开销安全的兜底：若 rootfs fd 被别人关了（posix_spawn /
            //   subprocess 的 close_fds=True 会关掉除 0/1/2 外全部 fd），
            //   这里会看到 -EBADF。此时重开 fd 并重试一次，正常路径完全不付代价。
            if (rv == -EBADF) {
                g_rootfs_fd = -1;
                ensure_rootfs_fd();
                switch (nr) {
                case SYS_openat:     rv = raw6(SYS_openat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                case SYS_newfstatat: rv = raw6(SYS_newfstatat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                case SYS_faccessat:  rv = raw6(SYS_faccessat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                case SYS_readlinkat: rv = raw6(SYS_readlinkat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                case SYS_statx:      rv = raw6(SYS_statx, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                case SYS_unlinkat:   rv = raw6(SYS_unlinkat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                case SYS_mkdirat:    rv = raw6(SYS_mkdirat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                case SYS_fchmodat:   rv = raw6(SYS_fchmodat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                case SYS_fchownat:   rv = raw6(SYS_fchownat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                case SYS_utimensat:  rv = raw6(SYS_utimensat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                case SYS_mknodat:    rv = raw6(SYS_mknodat, g_rootfs_fd, (long)buf, (long)a2, (long)a3, (long)a4, (long)a5); break;
                }
            }
            r[0] = (unsigned long long)rv;
            return;
        }
        r[0] = (unsigned long long)raw6(nr, a0, (long)a1, (long)a2, (long)a3, (long)a4, (long)a5);
        return;
    }
    // ---- 双路径类：symlinkat / linkat / renameat / renameat2 ----
    // symlinkat(target, newdirfd, linkpath)  —— target 是「链接内容」，不翻译；linkpath 要翻译
    case SYS_symlinkat: {
        const char *newp = (const char *)a2;
        char b[PATH_MAX], hb[PATH_MAX];   // 单路径，独立缓冲无冲突
        if ((long)(int)(unsigned int)a1 == -100 && newp && newp[0] == '/') {
            // 同样必须先查 bind（否则往 /dsh 等映射目录建链会落到 rootfs 里）
            if (bind_map(newp, hb)) {
                r[0] = (unsigned long long)raw6(SYS_symlinkat, (long)r[0], -100, (long)hb, 0, 0, 0);
                return;
            }
            make_rel(b, newp);
            r[0] = (unsigned long long)raw6(SYS_symlinkat, (long)r[0], g_rootfs_fd, (long)b, 0, 0, 0);
            return;
        }
        r[0] = (unsigned long long)raw6(SYS_symlinkat, (long)r[0], (long)a1, (long)a2, (long)a3, (long)a4, (long)a5);
        return;
    }
    // linkat(olddirfd, oldpath, newdirfd, newpath, flags)
    case SYS_linkat: {
        const char *op = (const char *)a1;
        const char *np = (const char *)a3;
        int nf = (long)(int)(unsigned int)a2;
        // ★两个路径必须用**各自独立**的缓冲：共用一个 hb 时，第二个 bind_map
        //   会把第一个的结果覆盖掉，于是 rename/link 变成「自己改自己」→ ENOENT。
        //   （实测症状：mv /dsh/.t1 /dsh/.t2 报 can't rename: No such file）
        char ob[PATH_MAX], nbuf[PATH_MAX], oh[PATH_MAX], nh[PATH_MAX];
        long ofd = a0, nfd = nf;
        int o_rel = 0, n_rel = 0;
        if (a0 == -100 && op && op[0] == '/') {
            if (bind_map(op, oh)) { ofd = -100; op = oh; }
            else { make_rel(ob, op); ofd = g_rootfs_fd; o_rel = 1; }
        }
        if (nf == -100 && np && np[0] == '/') {
            if (bind_map(np, nh)) { nfd = -100; np = nh; }
            else { make_rel(nbuf, np); nfd = g_rootfs_fd; n_rel = 1; }
        }
        r[0] = (unsigned long long)raw6(SYS_linkat, ofd,
                                        o_rel ? (long)ob : (long)op,
                                        nfd,
                                        n_rel ? (long)nbuf : (long)np,
                                        (long)a4, 0);
        return;
    }
    // renameat(olddirfd, oldpath, newdirfd, newpath) / renameat2(..., flags)
    case SYS_renameat:
    case SYS_renameat2: {
        const char *op = (const char *)a1;
        const char *np = (const char *)a3;
        int nf = (long)(int)(unsigned int)a2;
        // ★两个路径必须用各自独立的缓冲（详见 linkat 处注释）：
        //   共用一个缓冲时第二个 bind_map 会覆盖第一个，rename 退化成 self-rename。
        char ob[PATH_MAX], nbuf[PATH_MAX], oh[PATH_MAX], nh[PATH_MAX];
        long ofd = a0, nfd = nf;
        int o_rel = 0, n_rel = 0;
        // ★必须先查 bind 映射，再退回 rootfs 相对路径。
        //
        // 踩过的坑：这个 handler 漏了 bind_map，而 openat 分支有 ——
        // 于是 `/dsh/x`（bind 到宿主目录）写入/读取都正常（openat 走了 bind），
        // 但**改名**被翻译成 rootfs 内的相对路径，一个在宿主一个在 rootfs，
        // rename 直接 ENOENT。实测症状是插件健康探针报：
        //   marker write failed: ENOENT: rename '/dsh/.android-plugin-health.json.tmp-15381'
        //   -> '/dsh/.android-plugin-health.json'
        // （临时文件被 openat 正确写到宿主 dsh-home，rename 却去 rootfs 里找）
        if (a0 == -100 && op && op[0] == '/') {
            if (bind_map(op, oh)) { ofd = -100; op = oh; }
            else { make_rel(ob, op); ofd = g_rootfs_fd; o_rel = 1; }
        }
        if (nf == -100 && np && np[0] == '/') {
            if (bind_map(np, nh)) { nfd = -100; np = nh; }
            else { make_rel(nbuf, np); nfd = g_rootfs_fd; n_rel = 1; }
        }
        r[0] = (unsigned long long)raw6(nr, ofd,
                                        o_rel ? (long)ob : (long)op,
                                        nfd,
                                        n_rel ? (long)nbuf : (long)np,
                                        (long)a4, 0);
        return;
    }
    default:
        // ★绝不返回 -ENOSYS。BPF 树是按 NR 白名单精确匹配的，理论上不该有未知
        //   nr 被 trap 到这里；但一旦出现（新增 trap 而 handler 忘了写 case、
        //   guest 用了变体 syscall），返回 -ENOSYS 会让 guest 看到
        //   "Errno 38 Function not implemented" —— 表现为某个功能整个不可用，
        //   却查不到任何权限或路径问题（curl 曾因此完全无法联网）。
        //
        //   这里透明转发：既然我们不知道该怎么处理，就不要妨碍它。
        //   代价是这类 syscall 不再受路径翻译保护，但比"功能直接报废"好得多。
        r[0] = (unsigned long long)raw6(nr, a0, a1, a2, a3, a4, a5);
        return;
    }
}

// ★ rootfs fd 可能被 guest 的 close_fds 关掉（python subprocess / posix_spawn
//   默认 close_fds=True，会关掉除 0/1/2 之外的所有 fd，包括我们持有的 rootfs fd）。
//   一旦被关，后续所有 *at 路径翻译都会 ENOENT，表现为「文件明明存在却报找不到」。
//   每次翻译前调用本函数，失效就按宿主真实路径重新 open。
// /proc/self/exe 与 /proc/<pid>/exe 在非 chroot 下会被内核按宿主进程解析，
// 读出来的必然是 loader 自己的路径（实测 /proc/self/exe -> dsh-alpine）。
// proot 这里给出的是 guest 路径，我们对齐它：拦 readlinkat 直接回 g_guest_path。
static int is_proc_self_exe(const char *p) {
    if (!p || p[0] != (char)47) return 0;
    const char *pre = "/proc/";
    for (int i = 0; pre[i]; i++) if (p[i] != pre[i]) return 0;
    const char *q = p + 6;
    int digits = 0;
    if (q[0] == (char)115 && q[1] == (char)101 && q[2] == (char)108 && q[3] == (char)102) { q += 4; }
    else { while (q[digits] >= (char)48 && q[digits] <= (char)57) digits++; if (digits == 0) return 0; q += digits; }
    if (q[0] != (char)47) return 0;
    q++;
    const char *exe = "exe";
    for (int i = 0; exe[i]; i++) if (q[i] != exe[i]) return 0;
    return q[3] == 0;
}
static void ensure_rootfs_fd(void) {
    if (g_rootfs_fd >= 0) {
        // ★校验方式必须是「这个 fd 现在还指向一个目录吗」，不能只看 EBADF。
        //
        // 踩过的坑（Android App 内实测，宿主 PTY 启动）：App 用 Pty.nativeForkPty
        // 起容器时，fd 3 已被 PTY slave 占用并被落到 0/1/2 之后第一个空闲位。
        // 于是 g_rootfs_fd 里存的「3」在 guest 看来是个**字符设备**：readlinkat 返回
        // -EINVAL（不是 EBADF），旧判据 `rv != -EBADF` 判定为「fd 有效」直接 return，
        // 之后所有 openat(3, "./usr/lib/libicuuc.so.76") 都打到 pty 上 ->
        // `Error loading shared library ...: Permission denied`，node 一个符号都
        // 解析不出来。而在 adb shell 里手动跑时 fd 3 恰好是打开的 rootfs，所以
        // 同一份 rootfs 手动能过、App 内必挂 —— 这个差异骗了很久。
        //
        // 用 fstat 取 st_mode 判断 S_IFDIR(0040000) 才能真正确认。
        // aarch64 的 struct stat：st_mode 在偏移 16 处（u32）。
        unsigned char stbuf[256];
        long rv = raw6(SYS_newfstatat, g_rootfs_fd, (long)"", (long)stbuf, 0x1000 /*AT_EMPTY_PATH*/, 0, 0);
        if (rv == 0) {
            unsigned int mode = *(unsigned int *)(stbuf + 16);
            if ((mode & 0170000u) == 0040000u) return;   // 确实是目录 → 有效
        }
    }
    if (g_rootfs_host_len <= 0) return;
    long nfd = raw6(SYS_openat, -100 /*AT_FDCWD*/, (long)g_rootfs_host, 0x200000 /*O_PATH*/ | 0x10000 /*O_DIRECTORY*/, 0, 0, 0);
    if (nfd >= 0) {
        // 挪到高位固定号：低位（3 起）会被 guest 程序自己的 open 抢走。
        // 用 F_DUPFD(1024) 复制后关掉临时 fd，之后即使在 App 的 PTY 环境里
        // 也不会被 0/1/2 之后的第一个空位覆盖。
        long hi = raw6(SYS_fcntl, nfd, 0 /*F_DUPFD*/, 1024, 0, 0, 0);
        if (hi >= 1024) {
            raw6(SYS_close, nfd, 0, 0, 0, 0, 0);
            nfd = hi;
        }
        g_rootfs_fd = (int)nfd;
    }
}

static void install_filter(void) {
    // ★必须是编译期常量才能进 static const 数组初始化器
    //   （用变量 lo/hi 会报 initializer element is not a compile-time constant）
// ★stub 白名单：只要 instruction_pointer 的高 32 位等于 loader 基址高 32 位，
    //   就认为这是 loader 自己的裸 svc 桩（loader 映像整体在 0x2000000000 起的一段内）。
    //   用「高 32 位」而不是精确 IP，是因为精确 IP 无法作为编译期常量，
    //   而 static const 数组的初始化器必须是常量表达式。
#define LOADER_BASE_HI 0x20u
    ldr_sigaction_sigsys((void (*)(int, void *, void *))sigsys);

    unsigned long lo = g_stub_ip & 0xFFFFFFFFUL, hi = (g_stub_ip >> 32) & 0xFFFFFFFFUL;
#define TRAP_PAIR(num) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, (num), 0, 1), BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_TRAP)
    // ★BPF 改成「按 syscall 号排序的二叉搜索树」。
    //   旧写法是对 25 条 TRAP + 12 条 dirfd 检查做线性扫描，合计约 108 条指令，
    //   每条 syscall（哪怕最终放行）都要从头扫一遍 —— 实测未拦截的 getpid 被拖到
    //   0.7~1.4us，而原生只要 0.002us。
    //   新写法 balds tree：命中/未命中最多 13 条指令，与表大小无关。
    //   注意 cBPF 只允许向前跳，所以 CLONE_CHK / ALLOW / TRAP 都放在树之后。
    // ★必须 static const：127 条指令的局部数组初始化会让编译器生成一个
    //   .rodata -> 栈 的 memcpy，而我们是 freestanding（没有 libc 的 memcpy），
    //   链接会直接失败（实测 `undefined symbol: memcpy`）。static 让它留在 rodata。
    // ★BPF 换成「按 syscall 号排序的二叉搜索树」。
    //   旧写法：25 条 TRAP + 12 条 dirfd 检查线性扫描 ≈108 条指令，
    //   每条 syscall（即使最终放行）都要全扫一遍 —— 实测未拦截的 getpid
    //   被拖到 0.7~1.4us（原生 0.002us），是当时最大的固定开销。
    //   新写法：命中/未命中最多 13 条指令，与表大小无关。
    //   cBPF 只允许向前跳，所以 CLONE_CHK / ALLOW / TRAP 都排在树之后。
    //   语义自检脚本见 scripts/verify_bpf.py（26 个分支 + 未登记 syscall 全过）。
    static const struct sock_filter f[] = {
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, LOADER_BASE_HI, 146, 0),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_newfstatat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_newfstatat, 0, 67),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 142, 141),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_recvmsg, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_recvmsg, 0, 33),
        BPF_STMT(BPF_JMP|BPF_JA, 139),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_execveat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_execveat, 0, 17),
        BPF_STMT(BPF_JMP|BPF_JA, 136),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_openat2, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_openat2, 0, 8),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 132, 131),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_faccessat2, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_faccessat2, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 128, 127),
        BPF_STMT(BPF_JMP|BPF_JA, 126),
        BPF_STMT(BPF_JMP|BPF_JA, 125),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_statx, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_statx, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 122, 121),
        BPF_STMT(BPF_JMP|BPF_JA, 120),
        BPF_STMT(BPF_JMP|BPF_JA, 119),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_execve, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_execve, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 117),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_renameat2, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_renameat2, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 114),
        BPF_STMT(BPF_JMP|BPF_JA, 112),
        BPF_STMT(BPF_JMP|BPF_JA, 111),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_clone, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_clone, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 101),
        BPF_STMT(BPF_JMP|BPF_JA, 107),
        BPF_STMT(BPF_JMP|BPF_JA, 106),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_connect, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_connect, 0, 14),
        BPF_STMT(BPF_JMP|BPF_JA, 104),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_recvfrom, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_recvfrom, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 101),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_sendmsg, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_sendmsg, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 98),
        BPF_STMT(BPF_JMP|BPF_JA, 96),
        BPF_STMT(BPF_JMP|BPF_JA, 95),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_sendto, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_sendto, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 93),
        BPF_STMT(BPF_JMP|BPF_JA, 91),
        BPF_STMT(BPF_JMP|BPF_JA, 90),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_rt_sigaction, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_rt_sigaction, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 88),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_rt_sigprocmask, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_rt_sigprocmask, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 85),
        BPF_STMT(BPF_JMP|BPF_JA, 83),
        BPF_STMT(BPF_JMP|BPF_JA, 82),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_utimensat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_utimensat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 79, 78),
        BPF_STMT(BPF_JMP|BPF_JA, 77),
        BPF_STMT(BPF_JMP|BPF_JA, 76),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_statfs, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_statfs, 0, 35),
        BPF_STMT(BPF_JMP|BPF_JA, 74),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_fchmodat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_fchmodat, 0, 18),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 70, 69),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_openat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_openat, 0, 8),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 66, 65),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_readlinkat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_readlinkat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 62, 61),
        BPF_STMT(BPF_JMP|BPF_JA, 60),
        BPF_STMT(BPF_JMP|BPF_JA, 59),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_fchownat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_fchownat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 56, 55),
        BPF_STMT(BPF_JMP|BPF_JA, 54),
        BPF_STMT(BPF_JMP|BPF_JA, 53),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_faccessat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_faccessat, 0, 7),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 50, 49),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_chdir, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_chdir, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 47),
        BPF_STMT(BPF_JMP|BPF_JA, 45),
        BPF_STMT(BPF_JMP|BPF_JA, 44),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_fstatfs, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_fstatfs, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 42),
        BPF_STMT(BPF_JMP|BPF_JA, 40),
        BPF_STMT(BPF_JMP|BPF_JA, 39),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_unlinkat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_unlinkat, 0, 15),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 36, 35),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_linkat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_linkat, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 33),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_renameat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_renameat, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 30),
        BPF_STMT(BPF_JMP|BPF_JA, 28),
        BPF_STMT(BPF_JMP|BPF_JA, 27),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_symlinkat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_symlinkat, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 25),
        BPF_STMT(BPF_JMP|BPF_JA, 23),
        BPF_STMT(BPF_JMP|BPF_JA, 22),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_mknodat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_mknodat, 0, 8),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 19, 18),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_mkdirat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_mkdirat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 15, 14),
        BPF_STMT(BPF_JMP|BPF_JA, 13),
        BPF_STMT(BPF_JMP|BPF_JA, 12),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_getcwd, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_getcwd, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 10),
        BPF_STMT(BPF_JMP|BPF_JA, 8),
        BPF_STMT(BPF_JMP|BPF_JA, 7),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 8),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0, 0, 5),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 12),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_STMT(BPF_ALU|BPF_AND|BPF_K, 0x4000),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0, 0, 1),
        BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_TRAP),
    };



#undef TRAP_PAIR
    struct sock_fprog pr = { .len = sizeof(f)/sizeof(f[0]), .filter = (struct sock_filter *)f };
    long _sc = raw6(277 /*SYS_seccomp*/, 1 /*SECCOMP_SET_MODE_FILTER*/, 0, (long)&pr, 0, 0, 0);
    if (_sc != 0) {
        ldr_log1("LOADER: seccomp FAIL rc=", _sc, 10);
        raw6(94 /*SYS_exit_group*/, 70, 0, 0, 0, 0, 0);
    }
}

// ---------- 完全 raw 的读文件 ----------
// 关键：loader_preload 会被 SIGSYS handler 在「guest 已接管 TLS」之后调用，
// 那时任何 bionic libc 调用（open/read/fstat/close/malloc）都会崩，
// 所以这里一律走 raw6，且路径相对 g_rootfs_fd 解析。
// 读文件到「按需 mmap 的缓冲」：*bufp 为空或容量不足时先 raw mmap 一块足够大的区。
// 注意全程 raw syscall —— 这个函数会被 SIGSYS handler 调用，此时 libc 不可用。
// 直接把 rootfs 内的 ELF 文件 mmap 到内存（零拷贝），返回映射地址与长度。
// 相比 read_into（先 open+read 到缓冲、再逐段 memcpy 到目标基址），省掉一次完整拷贝。
static unsigned char *map_file(const char *path, unsigned long *out_len) {
    if (!path || path[0] != (char)47) return 0;
    long fd = guest_open(path, 0);
    if (fd < 0) return 0;
    unsigned char st[256];
    if (raw6(SYS_fstat, fd, (long)st, 0, 0, 0, 0) < 0) { raw6(SYS_close, fd, 0,0,0,0,0); return 0; }
    unsigned long sz = *(unsigned long *)(st + 48);
    if (sz == 0) { raw6(SYS_close, fd, 0,0,0,0,0); return 0; }
    // PROT_READ + MAP_PRIVATE：文件内容可直接读，不做写时拷贝
    long m = raw6(SYS_mmap, 0, (long)sz, 1 /*PROT_READ*/, 2 /*MAP_PRIVATE*/, fd, 0);
    raw6(SYS_close, fd, 0,0,0,0,0);
    if (m < 0 && m > -4096) return 0;
    *out_len = sz;
    return (unsigned char *)m;
}

static int read_into(const char *path, unsigned char **bufp, unsigned long *capp,
                     unsigned long *out) {
    long fd = guest_open(path, 0);
    if (fd < 0) return -1;
    unsigned char stbuf[256];
    if (raw6(SYS_fstat, fd, (long)stbuf, 0, 0, 0, 0) < 0) { raw6(SYS_close, fd, 0, 0, 0, 0, 0); return -1; }
    // aarch64 struct stat: st_size 位于偏移 48
    unsigned long sz = *(unsigned long *)(stbuf + 48);
    if (sz == 0) { raw6(SYS_close, fd, 0, 0, 0, 0, 0); return -1; }
    if (*bufp == 0 || *capp < sz) {
        if (*bufp) raw6(SYS_munmap, (long)*bufp, (long)*capp, 0, 0, 0, 0);
        unsigned long want = (sz + 0xFFFUL) & ~0xFFFUL;
        long m = raw6(SYS_mmap, 0, (long)want, 3 /*RW*/, 0x22, -1, 0);
        if (m < 0 && m > -4096) { raw6(SYS_close, fd, 0, 0, 0, 0, 0); return -1; }
        *bufp = (unsigned char *)m;
        *capp = want;
    }
    unsigned long off = 0;
    while (off < sz) {
        long n = raw6(SYS_read, fd, (long)(*bufp + off), (long)(sz - off), 0, 0, 0);
        if (n <= 0) break;
        off += (unsigned long)n;
    }
    raw6(SYS_close, fd, 0, 0, 0, 0, 0);
    if (off != sz) return -1;
    *out = off;
    return 0;
}

long loader_preload(const char *gpath, char **gargv, char **genvp, int full);

// 预读：interp + guest 镜像 + argv/env
// 在 rootfs 内解析符号链接链（绝对目标按 rootfs 根解析），全程 raw syscall。
// 这是 execve 的关键：/bin/cat -> /bin/busybox 若交给内核解析，内核会按宿主真实根去找。
// 返回 0 = 路径存在（普通文件或已解析完链接），-2 = 不存在。
// 这样 full=0 的存在性检查就不用再单独 openat 一次（省一次 syscall 往返）。
static long resolve_in_rootfs(const char *in, char *out) {
    char cur[PATH_MAX];
    { const char *p = in; char *d = cur; int lim = PATH_MAX - 1;
      while (*p && lim-- > 0) *d++ = *p++; *d = 0; }
    for (int hop = 0; hop < 12; hop++) {
        char rel[PATH_MAX];
        rel[0] = '.'; { const char *p = cur; char *d = rel + 1; int lim = PATH_MAX - 2;
                        while (*p && lim-- > 0) *d++ = *p++; *d = 0; }
        char tgt[PATH_MAX];
        long n = raw6(SYS_readlinkat, g_rootfs_fd, (long)rel, (long)tgt, (long)(PATH_MAX - 1), 0, 0);
        if (n == -2 /*ENOENT*/) { goto copyout_missing; }   // 路径不存在
        if (n <= 0) break;                 // -EINVAL 等 -> 是普通文件，结束
        tgt[n] = 0;
        if (tgt[0] == '/') {
            // 绝对目标：作为新的 guest 绝对路径继续解析
            const char *p = tgt; char *d = cur; int lim = PATH_MAX - 1;
            while (*p && lim-- > 0) *d++ = *p++; *d = 0;
        } else {
            // ★相对目标必须按「符号链接所在目录」解析成绝对路径再继续。
            //   早期版本直接 break，导致 /usr/bin/env -> ../../bin/busybox
            //   这类相对链接解析失败（实测 npm 走 #!/usr/bin/env node 时报
            //   REEXEC: preload FAIL，而 v10 正常）。
            char base[PATH_MAX];
            const char *p = cur; char *d = base; int lim = PATH_MAX - 1;
            while (*p && lim-- > 0) *d++ = *p++; *d = 0;
            int bl = 0; while (base[bl]) bl++;
            while (bl > 0 && base[bl - 1] != '/') bl--;
            if (bl > 0) bl--;
            base[bl] = 0;
            char joined[PATH_MAX]; int ji = 0;
            for (int k = 0; k < bl && ji < PATH_MAX - 1; k++) joined[ji++] = base[k];
            if (ji == 0) joined[ji++] = '/';
            joined[ji] = 0;
            const char *q = tgt;
            if (*q != '/' && ji > 0 && joined[ji - 1] != '/') joined[ji++] = '/';
            while (*q && ji < PATH_MAX - 2) joined[ji++] = *q++;
            joined[ji] = 0;
            // 归一化 . 与 ..
            char norm[PATH_MAX]; int ni = 0; norm[ni++] = '/';
            int ci = 0;
            while (joined[ci]) {
                while (joined[ci] == '/') ci++;
                if (!joined[ci]) break;
                int st = ci;
                while (joined[ci] && joined[ci] != '/') ci++;
                int seg = ci - st;
                if (seg == 1 && joined[st] == '.') continue;
                if (seg == 2 && joined[st] == '.' && joined[st + 1] == '.') {
                    while (ni > 1 && norm[ni - 1] != '/') ni--;
                    if (ni > 1) ni--;
                    continue;
                }
                if (ni > 1 && norm[ni - 1] != '/') norm[ni++] = '/';
                for (int k = 0; k < seg && ni < PATH_MAX - 1; k++) norm[ni++] = joined[st + k];
            }
            if (ni == 0) norm[ni++] = '/';
            norm[ni] = 0;
            const char *p2 = norm; char *d2 = cur; int lim2 = PATH_MAX - 1;
            while (*p2 && lim2-- > 0) *d2++ = *p2++; *d2 = 0;
        }
    }
    goto copyout_ok;
copyout_missing:
    { const char *p = cur; char *d = out; int lim = PATH_MAX - 1;
      while (*p && lim-- > 0) *d++ = *p++; *d = 0; }
    return -2;
copyout_ok:
    { const char *p = cur; char *d = out; int lim = PATH_MAX - 1;
      while (*p && lim-- > 0) *d++ = *p++; *d = 0; }
    return 0;
}

// 相对路径补全成 guest 视角绝对路径，并在 rootfs 内解析符号链接链。
// 抽出来是因为 handler 的轻量存在性检查与完整 preload 都要用。
static int g_resolve_missing;

static long resolve_guest_path(const char *gpath, char *out) {
    char full[PATH_MAX];
    if (gpath[0] == (char)47) {
        scopy_(full, gpath);
    } else {
        char cwdbuf[PATH_MAX];
        long cn = raw6(SYS_getcwd, (long)cwdbuf, PATH_MAX - 1, 0, 0, 0, 0);
        int base_len = 0;
        if (cn > 0) {
            cwdbuf[cn] = 0;
            if (g_rootfs_host_len > 0 && cn >= g_rootfs_host_len) {
                int same = 1;
                for (int k = 0; k < g_rootfs_host_len; k++) {
                    if (cwdbuf[k] != g_rootfs_host[k]) { same = 0; break; }
                }
                if (same) {
                    const char *rest = cwdbuf + g_rootfs_host_len;
                    if (*rest == 0) { full[base_len++] = (char)47; }
                    else { while (*rest && base_len < PATH_MAX - 2) full[base_len++] = *rest++; }
                }
            }
        }
        if (base_len == 0) { full[base_len++] = (char)47; }
        if (base_len > 1 && full[base_len - 1] != (char)47) full[base_len++] = (char)47;
        const char *rp = gpath;
        while (*rp == (char)46 && rp[1] == (char)47) rp += 2;
        while (*rp && base_len < PATH_MAX - 2) full[base_len++] = *rp++;
        full[base_len] = 0;
    }
    // ★bind 挂载点下的路径不参与 rootfs 符号链接解析（v1.6.16）。
    //
    //   resolve_in_rootfs 全程用 g_rootfs_fd 相对解析，从不看 bind 表 —— 于是
    //   `-m /eng:<宿主 engine 目录>` 之后，guest 里的 `/eng/xxx` 被当成
    //   `$rootfs/eng/xxx`（不存在），execve 直接 ENOENT。
    //
    //   这不是理论问题：引擎的 `@vscode/ripgrep` 按 `process.platform-arch` 解析
    //   平台包，容器里 platform=linux，只能把静态 rg 放进 engine 目录下的
    //   `@vscode/ripgrep-linux-arm64/bin/rg`，由引擎用**绝对路径 spawn** ——
    //   走的就是 /eng/... 。实测：文件 stat 得 mode=755 size=4771240、md5 与
    //   /bin/rg 完全一致，但 exec 报 `No such file or directory`；同一文件 cp 到
    //   /tmp 或 /bin 立刻能跑。典型的「bind 路径没翻译」症状。
    //
    //   这里**只跳过解析、保留 guest 路径**（不返回宿主路径）：后续装载统一走
    //   guest_open()，由它查 bind 表拿到宿主 fd。若这里返回宿主路径，下游
    //   make_rel() 会把它当 guest 路径再拼一次，变成 "$rootfs/<宿主路径>"。
    {
        char hp[PATH_MAX];
        if (bind_map(full, hp)) {
            scopy_(out, full);
            g_resolve_missing = 0;
            return 0;
        }
    }
    long _r = resolve_in_rootfs(full, out);
    g_resolve_missing = (_r == -2);
    return _r;
}
// 轻量存在性检查：只为 execve 失败时把 errno 还给 guest（保住 execlp 的 PATH 搜索链）。
// 不做 interp/guest 完整预读 —— 那部分由重执行后的 loader_preload 完成，
// 两边都做等于把同一件事干两遍（实测各约 1.1~1.8ms，是 exec 剩余开销的主项）。
static long loader_probe(const char *gpath) {
    char resolved[PATH_MAX];
    resolve_guest_path(gpath, resolved);
    // ★必须写回 g_guest_path：exec 分支要用它拼 --guest 参数，
    //   否则重执行拿到空路径（实测表现为空转/重开失败）。
    scopy_(g_guest_path, resolved);
    // (1) 先按原路径直接开（普通 ELF / 脚本）
    long fd = guest_open(resolved, 0 /*O_RDONLY*/);
    if (fd >= 0) { raw6(SYS_close, fd, 0,0,0,0,0); return 0; }
    // (2) 脚本的 shebang 解释器可能存在但脚本本体路径不同 —— 这里只关心本体
    return -1;
}
long loader_preload(const char *gpath, char **gargv, char **genvp, int full) {
    g_is_script = 0;
    // ---- mode 2：argv 已是「完整」形态 ----------------
    // 重执行时 exec handler 已经把 [interp, arg, script, args...] 拼好并作为
    // --guest 之后的 argv 传过来，gpath 也已是解析完符号链接的最终 ELF。
    // 此时若再走下面的「gpath 作 argv[0]」逻辑，会把 shebang 的首元素覆盖掉
    // （实测 npm 报 `-1: applet not found`：busybox 拿到 argv[0]=/bin/busybox、
    //   argv[1]=node，于是把 node 当 applet 名去找）。
    if (full == 2) {
        // ★仍要解析符号链接：/usr/local/sbin/node 这类链接的目标是绝对路径，
        //   内核会按宿主真实根解析 -> 打不开（实测 env->node 链报 preload FAIL）。
        // ★gpath 已是「解析完符号链接、且确认存在」的最终 ELF 路径：
        //   full=0 阶段返回 0 就代表路径存在，故这里直接信任，不再重复
        //   readlinkat/openat（每层 exec 省 1~2 次 syscall 往返）。
        scopy_(g_guest_path, gpath);
        g_argv_count = 0;
        if (gargv) {
            for (int i = 0; i < 250 && gargv[i] && g_argv_count < 250; i++)
                scopy_(g_argv_store[g_argv_count++], gargv[i]);
        }
        if (g_argv_count == 0) scopy_(g_argv_store[g_argv_count++], gpath);
        // cleaner 跨自我重执行的状态标记：塞进即将传给新 loader 的 envp
        if (g_cleaner_on && g_env_count < 127)
            scopy_(g_env_store[g_env_count++], "DSH_CLEANER=1");
    // ★环境变量必须继承 guest 传进来的 envp。
    //   早期版本无条件写死 PATH/HOME/TERM 三行、完全丢弃 envp —— 后果：
    //   容器里 `export PATH=/usr/lib/ccache/bin:$PATH` 在下一次 exec 就被抹掉，
    //   ccache / http_proxy / LANG / NODE_ENV 等一切自定义环境变量全部失效
    //   （实测 make 全量重建时 ccache 永远 miss，因为 shim 根本不在 PATH 里）。
        g_env_count = 0;
        int need_def_path = 0;
        if (genvp && genvp[0]) {
            for (int gi = 0; gi < 120 && genvp[gi]; gi++) {
                const char *ev = genvp[gi];
                // PATH 特殊处理：容器内进程设置的 PATH 必须原样保留（否则 ccache 等
                // 注入的目录会在下一次 exec 丢失）；只有首次启动从宿主继承的那份
                // 才需要替换成容器默认值。
                if (ev[0]=='P' && ev[1]=='A' && ev[2]=='T' && ev[3]=='H' && ev[4]=='=') {
                    // 宿主 PATH 也要替换：re-exec 后 g_force_container_path 会被
                    // .data 重初始化清零，光看这个标志会把宿主的 /system/bin 那套
                    // 当成用户设置保留下来（见 path_looks_like_android 的说明）。
                    if (g_force_container_path || path_looks_like_android(ev + 5)) {
                        // ★用过一次就必须清掉。早期只在 main 里置 1、从不清零，
                        //   后果是**每一次 exec** 都把容器内的 PATH 覆盖回默认值：
                        //   容器里 `export PATH=/usr/lib/ccache/bin:$PATH` 之后，
                        //   下一个 gcc 又被换回默认 PATH，ccache 永远 miss
                        //   （实测 command -v gcc 始终返回 /usr/bin/gcc）。
                        g_force_container_path = 0;
                        need_def_path = 1;
                        continue;
                    }
                    scopy_(g_env_store[g_env_count++], ev);
                    need_def_path = 0;
                    continue;
                }
                if (env_drop_host(ev)) continue;
                scopy_(g_env_store[g_env_count++], ev);
            }
            if (need_def_path)
                scopy_(g_env_store[g_env_count++], "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            env_fill_defaults();
        }
        if (g_env_count == 0) {
            scopy_(g_env_store[g_env_count++], "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            env_fill_defaults();
        }
        goto load_images;
    }
    {
        // ★相对路径必须补全成 guest 视角的绝对路径：
        //   `./capp`、`sh -c ./x` 这类调用传进来的是相对路径，
        //   直接拼 "./" 前缀会变成 "././capp"，在 rootfs 下虽然能开，
        //   但 execve 的语义是相对「当前工作目录」，必须先用真实 getcwd 定位 cwd。
        resolve_guest_path(gpath, g_guest_path);
    }
    // ★shebang 支持：npm / pip 等是 #! 脚本不是 ELF，内核会自动改写 argv，我们必须复刻
    { long fd = guest_open(g_guest_path, 0);
      if (fd >= 0) {
        char hdr[256]; long n = raw6(SYS_read, fd, (long)hdr, 255, 0, 0, 0);
        raw6(SYS_close, fd, 0, 0, 0, 0, 0);
        if (n > 3 && hdr[0] == (char)35 && hdr[1] == (char)33) {
          hdr[n] = 0;
          char *q2 = hdr + 2;
          while (*q2 == (char)32 || *q2 == (char)9) q2++;
          char *e2 = q2;
          while (*e2 && *e2 != (char)10 && *e2 != (char)13 && *e2 != (char)32 && *e2 != (char)9) e2++;
          int i2 = 0; while (q2 + i2 < e2 && i2 < PATH_MAX - 1) { g_script_interp[i2] = q2[i2]; i2++; }
          g_script_interp[i2] = 0;
          // 可选参数：`#!/usr/bin/env node` 的 "node" 要作为 argv[1] 传下去
          g_script_arg[0] = 0;
          { char *a2 = e2;
            while (*a2 == (char)32 || *a2 == (char)9) a2++;
            char *b2 = a2;
            while (*b2 && *b2 != (char)10 && *b2 != (char)13) b2++;
            while (b2 > a2 && (b2[-1] == (char)32 || b2[-1] == (char)9)) b2--;
            int j2 = 0; while (a2 + j2 < b2 && j2 < PATH_MAX - 1) { g_script_arg[j2] = a2[j2]; j2++; }
            g_script_arg[j2] = 0; }
          if (g_script_interp[0]) {
            g_is_script = 1;
            scopy_(g_script_path, g_guest_path);
            scopy_(g_guest_path, g_script_interp);
            resolve_in_rootfs(g_guest_path, g_guest_path);
          }
        }
      }
    }
    g_argv_count = 0;
    if (g_is_script) {
        scopy_(g_argv_store[g_argv_count++], g_script_interp);
        if (g_script_arg[0]) scopy_(g_argv_store[g_argv_count++], g_script_arg);
        scopy_(g_argv_store[g_argv_count++], g_script_path);
        if (gargv) {
            for (int i = 1; i < 250 && gargv[i] && g_argv_count < 250; i++)
                scopy_(g_argv_store[g_argv_count++], gargv[i]);
        }
    } else {
        scopy_(g_argv_store[g_argv_count++], gpath);
        if (gargv) {
            for (int i = 1; i < 250 && gargv[i] && g_argv_count < 250; i++)
                scopy_(g_argv_store[g_argv_count++], gargv[i]);
        }
    }
    // ★环境变量必须继承 guest 传进来的 envp。
    //   早期版本无条件写死 PATH/HOME/TERM 三行、完全丢弃 envp —— 后果：
    //   容器里 `export PATH=/usr/lib/ccache/bin:$PATH` 在下一次 exec 就被抹掉，
    //   ccache / http_proxy / LANG / NODE_ENV 等一切自定义环境变量全部失效
    //   （实测 make 全量重建时 ccache 永远 miss，因为 shim 根本不在 PATH 里）。
    g_env_count = 0;
    int need_def_path = 0;
    if (genvp && genvp[0]) {
        for (int gi = 0; gi < 120 && genvp[gi]; gi++) {
            const char *ev = genvp[gi];
            // PATH 特殊处理：容器内进程设置的 PATH 必须原样保留（否则 ccache 等
            // 注入的目录会在下一次 exec 丢失）；只有首次启动从宿主继承的那份
            // 才需要替换成容器默认值。
            if (ev[0]=='P' && ev[1]=='A' && ev[2]=='T' && ev[3]=='H' && ev[4]=='=') {
                // 同上：宿主 PATH 也要替换（见 path_looks_like_android）
                if (g_force_container_path || path_looks_like_android(ev + 5)) {
                            // ★用过一次就必须清掉。早期只在 main 里置 1、从不清零，
                            //   后果是**每一次 exec** 都把容器内的 PATH 覆盖回默认值：
                            //   容器里 `export PATH=/usr/lib/ccache/bin:$PATH` 之后，
                            //   下一个 gcc 又被换回默认 PATH，ccache 永远 miss
                            //   （实测 command -v gcc 始终返回 /usr/bin/gcc）。
                    g_force_container_path = 0;
                    need_def_path = 1;
                    continue;
                }
                scopy_(g_env_store[g_env_count++], ev);
                need_def_path = 0;
                continue;
            }
            if (env_drop_host(ev)) continue;
            scopy_(g_env_store[g_env_count++], ev);
        }
        if (need_def_path)
            scopy_(g_env_store[g_env_count++], "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env_fill_defaults();
    }
    if (g_env_count == 0) {
        scopy_(g_env_store[g_env_count++], "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env_fill_defaults();
    }

load_images:
    // ★轻量模式（full==0）：只做路径解析 + argv/env 填充 + shebang 探测，
    //   不装载 interp/guest 镜像 —— 那部分由重执行后的完整 preload 完成。
    //   这样 handler 侧不再白算一遍（实测省 1.1~1.8ms）。
    // full==0：只做路径解析 + argv/env 填充 + shebang 探测。
    //   路径存在性由 resolve_guest_path 直接回传（省掉一次 openat 往返）。
    //   不存在时返回 -ENOENT，保住 execlp()/env 的 PATH 搜索链。
    if (!full) return (g_resolve_missing ? -2 : 0);

    // ★interp 缓存：musl ld 内容在同一 rootfs 下不变，首次读入后不再重读。
    //   每次 exec 重读 723KB 是本方案最大的固定开销来源（实测单次 exec 11.9ms vs 原生 0.73ms）。
    // ★interp 零拷贝：首次把 musl ld 直接 mmap 进固定区（只读、PRIVATE），
    //   fork 出的子进程继承该映射，省掉每次 exec 的 723KB memcpy（实测占单次
    //   exec 固定开销的 70%，1.1ms/9.8ms）。
    if (!g_interp_filemap_ok) {
        int ifd = (int)raw6(SYS_openat, -100, (long)g_interp_path, 0 /*O_RDONLY*/, 0, 0, 0);
        if (ifd < 0) {
            if (read_into(g_interp_path, &g_interp_buf, &g_interp_cap, &g_interp_len) != 0) return -1;
        } else {
            struct { long st_dev, st_ino, st_mode, st_nlink, st_uid, st_gid;
                     long _pad0; long st_rdev, st_size; } stx;
            if (raw6(SYS_fstat, ifd, (long)&stx, 0,0,0,0) != 0) {
                raw6(SYS_close, ifd, 0,0,0,0,0);
                if (read_into(g_interp_path, &g_interp_buf, &g_interp_cap, &g_interp_len) != 0) return -1;
            } else {
                unsigned long flen = (unsigned long)stx.st_size;
                long mp = raw6(SYS_mmap, 0, (long)flen, 1 /*PROT_READ*/,
                               2 /*MAP_PRIVATE*/, ifd, 0);
                raw6(SYS_close, ifd, 0,0,0,0,0);
                if (mp < 0 && mp > -4096) {
                    if (read_into(g_interp_path, &g_interp_buf, &g_interp_cap, &g_interp_len) != 0) return -1;
                } else {
                    g_interp_filemap = (unsigned char *)mp;
                    g_interp_filemap_ok = 1;
                    g_interp_len = flen;
                }
            }
        }
    }
    // guest ELF 用 mmap 零拷贝映射；失败再退回 read_into（兼容极少数不能 mmap 的场景）
    // v8：打开 rootfs 内 interp / guest 的 fd，供子进程文件映射装载
    if (g_interp_fd < 0) {
        long f = guest_open(g_interp_path, 0);
        if (f >= 0) g_interp_fd = (int)f;
    }
    {
        if (g_guest_fd >= 0) { raw6(SYS_close, g_guest_fd, 0,0,0,0,0); g_guest_fd = -1; }
        long f = guest_open(g_guest_path, 0);
        if (f >= 0) g_guest_fd = (int)f;
    }

    if (g_guest_buf) { raw6(SYS_munmap, (long)g_guest_buf, (long)g_guest_cap, 0,0,0,0); g_guest_buf = 0; g_guest_cap = 0; }
    {
        unsigned long glen2 = 0;
        unsigned char *gm = map_file(g_guest_path, &glen2);
        if (gm) { g_guest_buf = gm; g_guest_len = glen2; g_guest_cap = glen2; }
        else if (read_into(g_guest_path, &g_guest_buf, &g_guest_cap, &g_guest_len) != 0) return -1;
    }
    return 0;
}

int ldr_main(int argc, char **argv, char **envp);
int ldr_main(int argc, char **argv, char **envp) {
    // 前置选项： -m <guest>:<宿主>  可重复（等价 proot -b）
    int base = 1;
    while (base + 1 < argc && argv[base][0] == (char)45 && argv[base][1] == (char)109 && argv[base][2] == 0) {
        bind_add(argv[base + 1]);
        base += 2;
    }
    if (argc < base + 3) {
        ldr_puts("usage: dsh-alpine [-m g:h]... <rootfs> <interp> <guest> [args...]");
        return 2;
    }
    g_force_container_path = 1;
    const char *rootfs = argv[base];
    scopy_(g_interp_path, argv[base + 1]);

    g_rootfs_fd = (int)raw6(56 /*SYS_openat*/, -100 /*AT_FDCWD*/, (long)rootfs,
                            0x200000 /*O_PATH*/ | 0x10000 /*O_DIRECTORY*/, 0, 0, 0);
    if (g_rootfs_fd < 0) { ldr_log1("LOADER: rootfs open FAIL rc=", (long)g_rootfs_fd, 10); return 3; }
    // ★立刻挪到高位固定号（1024 起）。原因见 ensure_rootfs_fd 的详细注释：
    //   App 用 PTY 启动容器时 fd 3 是 pty slave，guest 一 open 东西就会抢占低位 fd，
    //   留在低位的 rootfs fd 会被顶掉且 fstat 才发现，表现为「库文件 Permission denied」。
    { long hi = raw6(SYS_fcntl, g_rootfs_fd, 0 /*F_DUPFD*/, 1024, 0, 0, 0);
      if (hi >= 1024) { raw6(SYS_close, g_rootfs_fd, 0, 0, 0, 0, 0); g_rootfs_fd = (int)hi; } }
    find_stub();
    if (!g_stub_ip) return 4;
    // 记录 rootfs 的宿主真实路径（getcwd 前缀裁剪用）
    g_rootfs_host_len = 0;
    { const char *rp = rootfs; char *dp = g_rootfs_host;
      while (*rp && g_rootfs_host_len < PATH_MAX - 1) { *dp++ = *rp++; g_rootfs_host_len++; }
      *dp = 0;
      while (g_rootfs_host_len > 1 && g_rootfs_host[g_rootfs_host_len - 1] == (char)47) g_rootfs_host_len--;
      g_rootfs_host[g_rootfs_host_len] = 0; }
    // 启动诊断输出默认静默。
    //
    // 早期无条件打印这两行，后果是**每一个**从 GUI 拉起的终端/引擎第一屏都
    // 被 "LOADER: rootfs_fd=1024 stub=0x..." 占掉两行 —— 用户打开终端看到的是
    // 调试噪声而不是提示符。只有排查问题（App 侧把 DSH_ALPINE_DEBUG 传进 envp）
    // 时才该出现。
    const int dbg = env_has_("DSH_ALPINE_DEBUG");
    if (dbg) ldr_log2("LOADER: rootfs_fd=", (long)g_rootfs_fd, " stub=0x", (long)g_stub_ip);

    // 父进程先预读（此时 TLS 是 bionic，libc 安全）
    {
        char *gv[32]; int n = 0;
        gv[n++] = (char *)argv[base + 2];
        for (int i = base + 3; i < argc && n < 31; i++) gv[n++] = argv[i];
        gv[n] = 0;
        if (loader_preload(argv[base + 2], gv, envp, 1) != 0) { ldr_puts("LOADER: preload FAIL"); return 5; }
        if (dbg) ldr_log2("LOADER: preloaded interp=", (long)g_interp_len, " guest=", (long)g_guest_len);
    }

    // ★cleaner 必须在 install_filter **之前** fork：
    //   filter 装了就无法移除，且 fork 会继承；只有此刻 fork 的子进程是干净的。
    //   bionic 的 linker64 不认识 seccomp、不会重装 SIGSYS handler，
    //   带 filter 的进程跑它必 Bad system call（实测 status=31）。
    //   实测：父 PR_GET_SECCOMP=2 / cleaner=0 / Adreno 840 硬件 GPU 可用。
    ldr_prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    install_filter();
    child_run_raw(0);   // 直接在本进程加载（首次不计 execve）
    return 10;
}

// ================= v10：自我重执行入口 =================
// 内核 execveat 之后，SIGSYS 处置被重置为默认，bionic 的启动代码在被 trap 的
// syscall 上会直接被杀（实测静态 bionic 版 rc=159）。所以必须：
//   1) 自定义 _start，全程裸 svc；
//   2) _start 第一件事就是重装 SIGSYS handler；
//   3) 再用 prctl(PR_GET_SECCOMP) 判断 filter 是否已存在（跨 exec 存活），
//      已存在就不再重复安装（避免 filter 叠加）。

#define LDR_MAGIC "--dsh-reexec-v1"

static int ldr_streq(const char *a, const char *b) {
    while (*a && *a == *b) { a++; b++; }
    return *a == 0 && *b == 0;
}

// 由 _start 调用：argc / argv / envp 都来自内核给的初始栈
void ldr_entry(long argc, char **argv, char **envp) {
    extern void ldr_child_jump_never_used(void);
    (void)ldr_child_jump_never_used;

    // ---- 第一件事：解析 DSH_DNS_PORT ----
    //
    // ★必须在这里（最早的入口）读，不能等到 handler 里扫 g_env_store。
    //
    // 踩过的坑：第一版在 handler 里扫 g_env_store，但那是 loader_preload 里才
    // 填充的；而 handler 可能在填充之前就被调用（启动早期的 stat/open 就 trap），
    // 那一次返回 0。即便后来改成「只在找到时缓存」，`getaddrinfo` 的 sendto
    // 也可能发生在 g_env_store 还没就绪的时刻 —— 实测诊断打印出的 sockaddr
    // 完全正确（f=02 p=0035 a=7f.00），但端口改写就是不生效。
    //
    // 直接扫内核给的 envp，与任何内部状态无关。
    if (envp) {
        static const char K[] = "DSH_DNS_PORT=";
        for (int i = 0; envp[i]; i++) {
            const char *e = envp[i];
            unsigned k = 0;
            while (k < sizeof(K) - 1 && e[k] && e[k] == K[k]) k++;
            if (k != sizeof(K) - 1) continue;
            int v = 0;
            const char *d = e + sizeof(K) - 1;
            while (*d >= '0' && *d <= '9') { v = v * 10 + (*d - '0'); d++; }
            if (v > 1023 && v < 65536) g_dns_port_cached = v;
            break;
        }
    }

    // ---- 第二件事：重装 SIGSYS handler（内核 exec 后已被重置）----
    ldr_sigaction_sigsys((void (*)(int, void *, void *))sigsys);

    // ---- altstack（handler 内可能 unmap guest 旧栈）----
    {
        static unsigned char sigstk[256 * 1024] __attribute__((aligned(16)));
        // struct stack_t { void *ss_sp; int ss_flags; size_t ss_size; }  (aarch64 布局)
        struct { void *sp; int flags; unsigned long size; } ss;
        ss.sp = sigstk; ss.flags = 0; ss.size = sizeof(sigstk);
        raw6(132 /*SYS_sigaltstack*/, (long)&ss, 0, 0, 0, 0, 0);
    }

    // ---- filter 是否已存在（跨 execve 存活）----
    // ★必须先求 stub 地址，再装 filter：install_filter 里的 cBPF 用 g_stub_ip 做
    //   IP 白名单（放行我们自己的裸 svc 桩）。若为 0，连 loader 自己的 raw6 都会
    //   被 TRAP，handler 返回后重试 → 无限循环（实测表现为超时无输出）。
    find_stub();

    int mode = ldr_prctl(PR_GET_SECCOMP, 0, 0, 0, 0);
    // ★cleaner 必须在这里启动：此刻进程还没装 filter（mode<2 分支马上要装），
    //   fork 出来的子进程才是干净的。装在 filter 之后 fork 会继承 filter，
    //   实测子进程 PR_GET_SECCOMP=2，bionic 程序立刻 Bad system call。
    if (mode < 2) {
        // ★cleaner 必须在这里 fork —— 这是全程序第一次装 filter 的位置，此刻进程
        //   还是干净的。错过这个点就再无机会：filter 装上不可移除、fork 必然继承。
        //   实测教训：曾把它放进 ldr_main（那里也有一处 install_filter，但首次启动
        //   根本走不到），结果 cleaner 的 PR_GET_SECCOMP 已是 2，bionic 程序立刻
        //   Bad system call 159。
        g_envp_for_cleaner = envp;
        cleaner_start();
        // 首次启动：必须先 PR_SET_NO_NEW_PRIVS，否则 seccomp(SET_MODE_FILTER) 返回 EACCES(-13)
        ldr_prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        install_filter();
    }

    // ---- 解析 argv 判断是否重执行 -------
    //   重执行时 argv 形如: loader --dsh-reexec-v1 --rootfs <p> --interp <p> --guest <g> [guest argv...]
    if (argc > 1 && ldr_streq(argv[1], LDR_MAGIC)) {
        char *rootfs = 0, *interp = 0, *guest = 0;
        int gstart = 0;
        for (int i = 2; i < argc; i++) {
            if (ldr_streq(argv[i], "--rootfs") && i + 1 < argc) { rootfs = argv[++i]; }
            else if (ldr_streq(argv[i], "--interp") && i + 1 < argc) { interp = argv[++i]; }
            else if (ldr_streq(argv[i], "-m") && i + 1 < argc) { bind_add(argv[++i]); }
            else if (ldr_streq(argv[i], "--guest") && i + 1 < argc) { guest = argv[++i]; gstart = i + 1; break; }
        }
        if (!rootfs || !interp || !guest) {
            ldr_puts("REEXEC: 参数不完整");
            raw6(94, 9, 0, 0, 0, 0, 0);
        }
        // 重开 rootfs fd（exec 后 fd 可能被 CLOEXEC 关掉）
        g_rootfs_fd = (int)raw6(56, -100, (long)rootfs, 0x200000 | 0x10000, 0, 0, 0);
        if (g_rootfs_fd < 0) { ldr_puts("REEXEC: rootfs 重开失败"); raw6(94, 10, 0, 0, 0, 0, 0); }
        // 记录宿主真实路径（getcwd 前缀裁剪用）
        g_rootfs_host_len = 0;
        { const char *rp = rootfs; char *dp = g_rootfs_host;
          while (*rp && g_rootfs_host_len < PATH_MAX - 1) { *dp++ = *rp++; g_rootfs_host_len++; }
          *dp = 0;
          while (g_rootfs_host_len > 1 && g_rootfs_host[g_rootfs_host_len - 1] == (char)47) g_rootfs_host_len--;
          g_rootfs_host[g_rootfs_host_len] = 0; }
        scopy_(g_interp_path, interp);
        // ★自我重执行后 .data 段重新初始化，g_cleaner_on 会变回 0。
        //   但 cleaner 进程还在（exec 前 fork 的，父进程 exec 不影响它），
        //   两个管道 fd 用 dup3 固定成 198/199、不带 CLOEXEC，跨 exec 保留。
        //   用环境标记 DSH_CLEANER=1 传递状态（envp 已全链路透传），
        //   比 fstat 探活更可靠：fd 号可能被 guest 程序占用。
        g_cleaner_on = 0;
        if (envp) {
            for (int i = 0; envp[i]; i++) {
                const char *e = envp[i];
                if (e[0]=='D'&&e[1]=='S'&&e[2]=='H'&&e[3]=='_'&&e[4]=='C'&&e[5]=='L'&&e[6]=='E'&&e[7]=='A'&&e[8]=='N') { g_cleaner_on = 1; break; }
            }
        }
        find_stub();
        // ★loader_preload 内部会 g_argv_count = 0 并放 gpath 作 argv[0]，
        //   再从传入的 gargv[1..] 追加。所以这里**不能**预填 g_argv_store（会被清掉），
        //   而应把重执行后 argv 的 guest 段直接交给它 —— 语义正好对齐。
        //   （早期版本预填导致 gcc 报 "no input files"：argv 全丢只剩程序名。）
        char **gargv_ptr = (gstart < argc) ? &argv[gstart] : 0;
        if (loader_preload(guest, gargv_ptr, envp, 2) != 0) { ldr_puts("REEXEC: preload FAIL"); raw6(94, 11, 0, 0, 0, 0, 0); }
        if (g_argv_count == 0) { g_argv_count = 1; scopy_(g_argv_store[0], guest); }
        // 直接装载并跳转（不再返回）
        child_run_raw(0);
        raw6(94, 12, 0, 0, 0, 0, 0);
    }

    // ---- 首次启动：正常 main 流程 ----
    int rc = ldr_main((int)argc, argv, envp);
    raw6(94 /*SYS_exit_group*/, rc, 0, 0, 0, 0, 0);
}

// 自定义 _start：从内核给的初始栈取 argc/argv/envp
__attribute__((naked)) void _start(void) {
    __asm__ volatile("mov x19, sp");
    __asm__ volatile("ldr x0, [x19]");
    __asm__ volatile("add x1, x19, #8");
    __asm__ volatile("add x2, x1, x0, lsl #3");
    __asm__ volatile("add x2, x2, #8");
    __asm__ volatile("bl ldr_entry");
    __asm__ volatile("mov x8, #94");
    __asm__ volatile("svc #0");
}
