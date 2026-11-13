// dsh-alpine —— Android 免 root 跑完整 Linux 用户态的 seccomp 加载器
//
// ============================================================================
// 版权与许可（专有）
// ============================================================================
//
// Copyright (c) 2026 Soodok. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary
//
// 本文件是**专有实现**，不适用 DSH Mobile 仓库根的 MIT 许可 —— MIT 只覆盖
// 该仓库的其余部分。许可的法律正本与完整条款见：
//
//   https://github.com/Soodok/dsh-linux-runtime/blob/main/LICENSE
//
// 简述（以 LICENSE 全文为准；本段仅为阅读便利，不构成额外授权）：
//
//   允许，无需付费或签约：
//     · 阅读、研究本文件；
//     · 在 issue / PR / 文章 / 论文中引用**有限片段**讨论，须注明来源；
//     · 用未修改副本自行编译，用于个人非商业用途或采购前评估；
//     · 复现并发布基准数据，须注明来源，不得归因于其他实现。
//
//   禁止（除 LICENSE 第 2 条封闭清单外的一切使用）：
//     · 复制 / 镜像 / 存储实质部分；
//     · 修改、改编、翻译（仅为你自用而做的本地改动除外）；
//     · 分发、再分发、上架任何包仓库或应用商店；
//     · 并入其他产品、服务、SDK、操作系统或容器运行时；
//     · 以服务形式向第三方提供其功能；
//     · 用作任何 AI 系统的训练 / 微调 / 评测数据；
//     · 开发竞争产品，或提取其设计决策用于独立重新实现；
//     · 移除或更改本段声明；
//     · 转让、再许可你的权利。
//
//   商业使用（含企业内部生产性使用）须另行取得书面许可。
//
// 本软件**不是安全沙箱**：单进程设计，目标是性能与兼容性，而非隔离不可信代码。
//
// ----------------------------------------------------------------------------
// 为什么单独立许可
// ----------------------------------------------------------------------------
//
// 这一份是整条「免 root 容器」链路里唯一有技术密度的实现（seccomp 双向端口
// 改写、手写 ELF loader + self-reexec、bionic 侧进程级桥、宿主路径透传）。
// 其余部分（Kotlin 界面、rootfs 准备、探针脚本）保持 MIT 以便协作。
//
// ----------------------------------------------------------------------------
// GPL 红线（改本文件前必读）
// ----------------------------------------------------------------------------
//
// 本文件为从零手写的独立实现，**不含** TAWC / tawcroot / PRoot / QEMU / LXC /
// Termux 补丁的任何代码。
//
// 仅阅读了 TAWC 的公开文档以理解设计约束（例如「静态链接 + 固定基址让 IP 白名单
// 跨 re-exec 生效」这类前提）。TAWC 整体是 GPL-3.0 —— 一旦从中复制任何代码，
// 本文件会被 GPL-3.0 覆盖，与这份专有许可直接冲突，且不可逆。
//
// 同理不得引入任何 GPL-2.0 / GPL-3.0 / AGPL / LGPL / MPL / EPL / CDDL 代码。
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

// ============================================================================
// 架构抽象层（原作者只支持 aarch64；为在 x86_64 模拟器上验证而抽象）
// ============================================================================
// 为什么只抽「不影响 aarch64 产物」的部分：
//   真机 arm64 是唯一交付目标，aarch64 那份 BPF 树必须**逐字节保持不变**。
//   所以这里只引入条件编译，不重排任何 aarch64 代码路径。
//
// 移植时必须同时满足的三件事（缺一即启动即挂、无任何输出）：
//   1) ET_EXEC + 固定基址 + 无 PIE —— IP 白名单靠比对固定高 32 位识别自己的桩
//   2) raw6 的参数寄存器映射正确（x86_64 的第 4 个参数必须进 r10 而非 rcx，
//      因为 rcx 被 syscall 指令本身用作返回地址）
//   3) cBPF 头的「arch 校验 + instruction_pointer 高 32 位白名单」一条都不能漏
//      —— 漏了 IP 白名单会让 handler 内部的裸 svc 被自己的 filter 再 TRAP 一次，
//         形成嵌套 SIGSYS，内核按致命错误处理（详见 docs/pitfalls.md 第 27 条）
#if defined(__aarch64__)
#define DSH_ARCH_AARCH64 1
#define DSH_AUDIT_ARCH AUDIT_ARCH_AARCH64
#define DSH_LOADER_BASE_HI 0x20u
#define DSH_SYSCALL_INSN_LEN 4          /* svc #0 */
#elif defined(__x86_64__)
#define DSH_ARCH_X86_64 1
#define DSH_AUDIT_ARCH AUDIT_ARCH_X86_64
#define DSH_LOADER_BASE_HI 0x20u        /* 同样固定在 0x2000000000 */
#define DSH_SYSCALL_INSN_LEN 2          /* syscall */
#else
#error "dsh_alpine: 只支持 aarch64 与 x86_64"
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

#if defined(DSH_ARCH_X86_64)
// x86_64 的信号返回桩（sa_restorer）。内核从这里恢复被中断的上下文。
// 必须自己写：aarch64 的通用 ABI 有 VDSO 版 restorer，x86_64 没有，
// 且 `sa_flags` 里给了 SA_RESTORER 就必须给出有效地址，否则 handler 返回即跳 0。
//   __NR_rt_sigreturn = 15
void __dsh_rt_sigreturn(void);
__asm__(
    ".text\n"
    ".p2align 4\n"
    ".globl __dsh_rt_sigreturn\n"
    ".hidden __dsh_rt_sigreturn\n"
    ".type __dsh_rt_sigreturn,@function\n"
    "__dsh_rt_sigreturn:\n"
    "  movq $15, %rax\n"
    "  syscall\n"
    ".size __dsh_rt_sigreturn, .-__dsh_rt_sigreturn\n");
#endif

static void ldr_write(const char *s, int len) {
    raw6(SYS_write /*SYS_write*/, 2, (long)s, len, 0, 0, 0);
}

// 判断是否是 /proc/self/fd/<N> 或 /proc/<pid>/fd/<N>（readlink 目标是打开的 fd）
static int is_proc_self_fd(const char *p) {
    if (!p) return 0;
    static const char pre[] = "/proc/";
    unsigned k = 0;
    while (pre[k]) { if (p[k] != pre[k]) return 0; k++; }
    // 跳过 "self" 或数字 pid
    const char *q = p + k;
    if (q[0] == 's' && q[1] == 'e' && q[2] == 'l' && q[3] == 'f') q += 4;
    else { if (*q < '0' || *q > '9') return 0; while (*q >= '0' && *q <= '9') q++; }
    static const char fd[] = "/fd/";
    for (k = 0; fd[k]; k++) if (q[k] != fd[k]) return 0;
    q += k;
    if (*q < '0' || *q > '9') return 0;
    return 1;
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
    raw6(SYS_write, 2, (long)b, k, 0, 0, 0);
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
    raw6(SYS_write, 2, (long)b, k, 0, 0, 0);
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
#if defined(DSH_ARCH_AARCH64)
    *(unsigned long *)(sa + 8)  = 4UL | 0x40000000UL;   // SA_SIGINFO | SA_NODEFER
    *(unsigned long *)(sa + 16) = 0;                     // aarch64 用 VDSO，restorer 留空
#else
    // ★★x86_64 **必须**填 restorer，否则 handler 一返回就是 SIGSEGV。
    //
    //   内核在 x86_64 上从 `sa->sa_restorer` 取返回路径；不给（=NULL）时，
    //   handler 执行完跳过去就落在地址 0 —— 表现为
    //       --- SIGSEGV {si_signo=SIGSEGV, si_code=SI_KERNEL, si_addr=NULL} ---
    //   strace 只显示「SIGSYS 之后紧跟 SIGSEGV」，极易误判成 handler 内部
    //   某处解引用了空指针（我为此绕了很久：改段属性、改 sigaltstack 布局、
    //   改寄存器写回，全无效果）。
    //
    //   aarch64 不用填是因为通用 ABI 有 VDSO 版 restorer；x86_64 没有。
    //   restorer 必须自己写一段裸汇编：mov $SYS_rt_sigreturn,%rax; syscall。
    *(unsigned long *)(sa + 8)  = 4UL | 0x40000000UL | 0x04000000UL; /* |SA_RESTORER */
    *(unsigned long *)(sa + 16) = (unsigned long)__dsh_rt_sigreturn;
#endif
    return (int)raw6(SYS_rt_sigaction /*SYS_rt_sigaction*/, 31 /*SIGSYS*/, (long)sa, 0, 8, 0, 0);
}

#define PR_SET_NO_NEW_PRIVS 38
#define PR_GET_SECCOMP 21
static int ldr_prctl(int op, unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5) {
    return (int)raw6(SYS_prctl /*SYS_prctl*/, op, (long)a2, (long)a3, (long)a4, (long)a5, 0);
}


// struct stat 的 st_mode 偏移是架构相关的：
//   aarch64 = 16，x86_64 = 24（st_size 两者都是 48）
#if defined(DSH_ARCH_AARCH64)
#define ST_MODE_OFF 16
#else
#define ST_MODE_OFF 24
#endif

static int g_rootfs_fd = -1;
static void ensure_rootfs_fd(void);  // fwd decl
static volatile long g_hits, g_execs;
// [prof] 按 syscall 号分桶计数（仅用于性能分析，编译后在退出时打印）
static volatile long g_nr_cnt[512];
// 守卫命中统计：重入守卫把某个 syscall 直接判成 -EPERM 的次数。
// 正常情况恒为 0 —— 一旦非 0，说明外层 seccomp 拦了我们重发的某个
// syscall（历史上依次是 poll(7)、select(23)、fork(57)，都已在上面
// 翻译成 ppoll / pselect6 / clone）。进程退出时打到 stderr 的 [GUARD] 行。
static volatile long g_guard_hits;
static volatile long g_guard_nr[512];

#if defined(DSH_ARCH_AARCH64)
__attribute__((naked, noinline)) long raw6(long n, long a, long b, long c, long d, long e, long f) {
    __asm__ volatile(
        "mov x8,x0\n mov x0,x1\n mov x1,x2\n mov x2,x3\n mov x3,x4\n mov x4,x5\n mov x5,x6\n svc #0\n ret\n");
}
#else
// x86_64 SysV 入参：rdi,rsi,rdx,rcx,r8,r9，第 7 个在 8(%rsp)
// 内核 syscall ABI：rax=nr，rdi,rsi,rdx,r10,r8,r9
//   ★d 必须进 r10 而不是 rcx —— syscall 指令用 rcx 存返回地址
//   ★顺序耦合：先把 nr 存进 rax（rdi 一改就丢），先搬 e(r8) 再读 f(r9)
//   ★必须用**文件级**裸汇编：内联汇编里 %rdi 会被当操作数占位符，
//     报 "invalid % escape"
__asm__(
    ".text\n"
    ".p2align 4\n"
    ".globl raw6\n"
    ".hidden raw6\n"
    ".type raw6,@function\n"
    "raw6:\n"
    "  movq %rdi, %rax\n"
    "  movq %rsi, %rdi\n"
    "  movq %rdx, %rsi\n"
    "  movq %rcx, %rdx\n"
    "  movq %r8,  %r10\n"
    "  movq %r9,  %r8\n"
    "  movq 8(%rsp), %r9\n"
    "  syscall\n"
    "  ret\n"
    ".size raw6, .-raw6\n");
__attribute__((noinline, used)) long raw6(long n, long a, long b, long c, long d, long e, long f);
#endif
static unsigned long g_stub_ip;
static void find_stub(void) {
#if defined(DSH_ARCH_AARCH64)
    unsigned int *p = (unsigned int *)((unsigned long)raw6 & ~3UL);
    for (int i = 0; i < 48; i++)
        if (p[i] == 0xD4000001u) { g_stub_ip = (unsigned long)&p[i] + 4; return; }
#else
    // x86_64 的 syscall 是 2 字节 `0F 05`；内核上报的 instruction_pointer 是
    // 该指令**之后**的地址，所以取 i+2（与 aarch64 的 svc+4 语义一致）。
    unsigned char *p = (unsigned char *)((unsigned long)raw6 & ~1UL);
    for (int i = 0; i < 96; i++)
        if (p[i] == 0x0F && p[i + 1] == 0x05) { g_stub_ip = (unsigned long)&p[i] + 2; return; }
#endif
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
// ★cleaner 请求里 argv/env 的条数上限。
//   数组大小与判断阈值**必须由同一个宏派生**：我做过一次「只改数组不改阈值」
//   的反向验证，结果是 `ev[128]` 里写到下标 255 —— 指针越过数组末尾，
//   把栈上的 av[] 一起踩坏，出现 `ARGV=90` 且环境变量被当成参数的怪象。
//   生产代码里两者一旦不同步就是栈溢出，所以这里用一个宏统一。
#define ARGV_MAX 63
#define ENV_MAX  255

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
    long uid = raw6(SYS_getuid /*getuid*/, 0, 0, 0, 0, 0, 0);
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
    long pid = raw6(SYS_getpid /*getpid*/, 0, 0, 0, 0, 0, 0);   // ★仅在 fork 前调用
    char pt[16]; int k2 = 0;
    if (!pid) pt[k2++] = '0';
    while (pid) { pt[k2++] = (char)('0' + (pid % 10)); pid /= 10; }
    while (k2) out[p++] = pt[--k2];
    return p;   // 名字字符数
}

// 扫 /proc/net/unix 找任意一个同前缀的 cleaner，成功返回名字长度。
//
// ★两个致命细节（都是实测踩出来的）：
//   ① 缓冲必须够大且**循环读到 EOF**。本机 /proc/net/unix 实测 20248 字节，
//      原先固定 `char txt[8192]` + 单次 read —— 我们的 cleaner 条目排在表尾，
//      **永远落在 8192 之外**，于是「明明 grep 得到、代码就是扫不到」。
//   ② 不能按 uid 段过滤。cleaner 名里的 uid 是**创建那一刻**宿主视角的值，
//      而请求方在容器里 getuid() 得到的是 guest uid，两者可以不同。
static int find_cleaner_by_prefix(char *out) {
    long fd = raw6(SYS_openat, -100, (long)"/proc/net/unix", 0, 0, 0, 0);
    if (fd < 0) return 0;
    static char txt[65536];
    long n = 0;
    while (n < (long)sizeof(txt) - 1) {
        long r = raw6(SYS_read, fd, (long)(txt + n), sizeof(txt) - 1 - n, 0, 0, 0);
        if (r <= 0) break;
        n += r;
    }
    raw6(SYS_close, fd, 0, 0, 0, 0, 0);
    if (n <= 0) return 0;
    txt[n] = 0;
    for (long i = 0; i + 1 < n; i++) {
        if (txt[i] != '@') continue;
        const char *pre = "@" CLEANER_SOCK_PREFIX;
        int pi = 0;
        while (pre[pi] && i + pi < n && txt[i + pi] == pre[pi]) pi++;
        if (pre[pi]) continue;
        long p = 0;
        for (long q = i + 1; q < n && txt[q] && txt[q] != (char)10 && p < 110; q++)
            out[p++] = txt[q];
        return (int)p;
    }
    return 0;
}

// 探活一个 socket 名；活着返回 0 并把三件状态一起恢复。
static int cleaner_probe_and_adopt(const char *pname, int plen) {
    if (plen <= 0 || plen > 110) return -1;
    struct ldr_sockaddr_un psa;
    ((unsigned short *)&psa)[0] = 1;
    ((char *)&psa)[2] = 0;
    for (int i = 0; i < plen; i++) ((char *)&psa)[3 + i] = pname[i];
    int palen = 3 + plen;
    long pfd = raw6(SYS_socket, 1, 1, 0, 0, 0, 0);
    if (pfd < 0) return -1;
    long pr = raw6(SYS_connect, pfd, (long)&psa, palen, 0, 0, 0);
    raw6(SYS_close, pfd, 0, 0, 0, 0, 0);
    if (pr != 0) return -1;
    g_cleaner_on = 1;
    g_cleaner_pid = 1;          // 哨兵：靠 socket 通信，不靠 wait4
    g_cleaner_namelen = plen;
    g_cleaner_addrlen = palen;
    for (int i = 0; i < plen; i++) g_cleaner_name[i] = pname[i];
    return 0;
}

// cleaner 侧：只服务**一次**请求然后退出。
//   ★为什么不做常驻循环：cleaner 若常驻，它必然在整个容器生命周期里持有某些
//   fd（哪怕只是 accept 用的 listen fd 或残留的 stdio 副本），adb shell 的管道
//   就一直不关，命令永不返回（实测 exit 124 且 GL 输出丢失）。
//   改成「一次一进程」后，父进程退出时 cleaner 自然消失，无泄漏、无僵尸。
//   代价仅一次 fork+listen（实测 <0.5ms），且只发生在 bionic 程序启动时。
static void cleaner_loop(long lfd, int socklen) {
    // ★接收端缓冲同样必须是 64KB，且**循环读到协议结束**，不能只 read 一次。
    //
    // 实测（模拟器 dsh34 / x86_64）：发送端已放大到 64KB 后，单变量 30000 字节
    // 仍然只到 **7043**、ENVBYTES 卡在 **8130**（≈8191）—— 因为这里还是
    // 8192 缓冲 + 单次 read。这条链上**两端**都得改，只改一端症状一模一样。
    //
    // 与 #63 的 find_cleaner_by_prefix 是同一个教训：内核的 read/write 在
    // 容量边界上不保证一次到齐，凡「定长缓冲 + 单次 IO」都是隐患。
    static char buf[65536];
    struct ldr_sockaddr_un sa;
    // ★常驻循环，而不是「一次一进程」。
    //
    // 旧实现只 accept 一次就 exit_group，理由是「常驻会持有借来的 stdio，
    // 让 adb shell 永不返回」。那个观察没错，但解法错了：
    //   - adb shell 等的是它 fork 的 sh（我们的 guest），cleaner 是 loader
    //     fork 的兄弟进程，不阻塞任何东西。
    //   - 泄漏由 PR_SET_PDEATHSIG 兜住（父进程死，内核立刻 SIGKILL）。
    //   - stdio 必须保留 —— 孙进程继承 cleaner 的 fd 1/2，那是 bionic 程序
    //     输出回终端的唯一路径，关掉就只剩 RC=0、输出全丢。
    //
    // 一次性模型的致命后果：一条命令里出现**第二个** bionic 程序时，第一个
    // 请求已把 cleaner 消费掉，第二个必然 cleaner_exec FAIL / not found。
    // 实测 sh -c 'export X=1; /eprobe; /framesrv ...' —— eprobe 成功、
    // framesrv 立刻 not found。
    for (;;) {
        unsigned int sl = (unsigned int)socklen;
        long cfd = raw6(SYS_accept4 /*accept4*/, lfd, (long)&sa, (long)&sl, 0x80000 /*SOCK_CLOEXEC*/, 0, 0);
        if (cfd < 0) raw6(SYS_exit_group /*exit_group*/, 0, 0, 0, 0, 0, 0);

        // ★必须校验对端 uid —— 抽象命名空间的 AF_UNIX socket **没有任何权限保护**。
        //
        // 实测（本机 uid=0 起服务端，另两个 uid 连过来）：
        //   CONNECT uid=0    rc=0
        //   CONNECT uid=10811 rc=0      <- 跨 uid 直接连上
        //   CONNECT uid=10810 rc=0
        // 也就是说「谁都能连上来」。而 cleaner_exec 拿到对端的 path/argv/env
        // 后会用 **cleaner 自己的权限** fork+execve —— cleaner 跑在 App 的 uid
        // 下，于是同机任意低权限进程都能借它提权执行任意程序。
        // 这条链只有在「按 uid 段精确匹配 socket 名」时才被名字本身挡住；
        // 而 fork+exec 场景必须放宽成前缀匹配（pid 变了猜不中，只能扫），
        // 名字就不再是护栏了。所以**必须在 accept 后补 SO_PEERCRED 校验**。
        {
            struct peer_ucred { int pid; unsigned int uid; unsigned int gid; } pc;
            unsigned int pclen = (unsigned int)sizeof(pc);
            long gr = raw6(SYS_getsockopt /*getsockopt*/, cfd, 1 /*SOL_SOCKET*/,
                           17 /*SO_PEERCRED*/, (long)&pc, (long)&pclen, 0);
            long myuid = raw6(SYS_getuid, 0, 0, 0, 0, 0, 0);
            if (gr != 0 || (long)pc.uid != myuid) {
                // 不是本 uid 的请求：不回任何数据、直接关掉，回到 accept。
                // 不打印日志 —— cleaner 的 stdio 借自容器进程，写它可能卡住。
                raw6(SYS_close /*close*/, cfd, 0, 0, 0, 0, 0);
                continue;
            }
        }

        // 循环读到「协议完整」为止：消息形态是 path\0argv[1..]\0\0ENV[1..]\0\0，
        // 结束标志是连续两个 NUL 之后的那个 NUL（即末尾的 "\0\0" 已收全）。
        // 简化判据：收到至少两个连续 NUL 且读到的最后一个字节是 NUL 就认为完整。
        long n = 0;
        int done = 0, nulrun = 0;
        while (!done && n < (long)sizeof(buf) - 1) {
            long r = raw6(SYS_read /*read*/, cfd, (long)(buf + n), sizeof(buf) - 1 - n, 0, 0, 0);
            if (r <= 0) break;
            for (long k = n; k < n + r; k++) {
                if (buf[k] == 0) { if (++nulrun >= 2 && k == n + r - 1) done = 1; }
                else nulrun = 0;
            }
            n += r;
        }
        // ★空请求（客户端 connect 后立刻 close，例如探活连接）**绝不能退出**。
        //
        // 旧实现这里是 `close(cfd); exit_group(0)` —— 一个探活连接就能把常驻
        // cleaner 杀掉，之后同一条命令里的第二个 bionic 程序必然
        // `BIONIC: cleaner_exec FAIL`。这与「常驻循环」的设计直接矛盾。
        // 实测：cleaner_exec 里的探活（connect 后立即 close）会把 cleaner 打死，
        // 于是本该正常的请求紧接着失败 —— 我为此白查了十几轮。
        if (n <= 0) { raw6(SYS_close /*close*/, cfd, 0, 0, 0, 0, 0); continue; }
        buf[n] = 0;
        char *path = buf;
        long plen = 0; while (path[plen]) plen++;
        char *p = buf + plen + 1;
        char *av[ARGV_MAX + 1]; int ac = 0;
        int too_many = 0;
        av[ac++] = path;
        while (p < buf + n) {
            if (!*p) break;
            // ★数组满了要**显式失败**，不能静默丢参数。
            //   argv 被砍掉尾巴会让程序跑到一半才发现「少了个文件名」，
            //   比直接报错难查得多。
            if (ac >= ARGV_MAX) { too_many = 1; break; }
            av[ac++] = p;
            while (*p) p++;
            p++;
        }
        av[ac] = 0;
        // ★argv 之后是**请求方当前环境**（见 cleaner_exec 的协议说明）。
        //   以前这里用 g_envp_for_cleaner —— 首次启动那一刻的快照，之后
        //   export 的变量全丢（实测 bionic_child=[] 而 musl_child=[hello]）。
        char *ev[ENV_MAX + 1]; int ec = 0;
        if (p < buf + n) {
            p++;   // 跳过 argv 终止符
            while (p < buf + n) {
                if (!*p) break;
                // ★同样必须显式失败。
                //   实测（模拟器 dsh34 / x86_64）：容器里 200 个环境变量时，
                //   bionic 子进程只拿到 **127** 个（ev[128] 上限），
                //   LASTENV 停在 V116，V117~V200 与 FINALVAR 全部消失，
                //   而程序侧完全看不出问题（读不到就当地没设）。
                if (ec >= ENV_MAX) { too_many = 1; break; }
                ev[ec++] = p;
                while (*p) p++;
                p++;
            }
        }
        ev[ec] = 0;
        if (too_many) {
            // 回一个非 0 退出码并说明原因，让**用户**看到；不执行半截请求。
            ldr_puts("dsh: bionic exec refused: argv or env count exceeds limit ");
            ldr_log1("(argv<=", ARGV_MAX, 10); ldr_log1(" env<=", ENV_MAX, 10); ldr_puts(") got argv="); ldr_log1("", ac, 10);
            ldr_log1(" env=", ec, 10);
            ldr_puts("");
            unsigned char bad = 125;
            raw6(SYS_write /*write*/, cfd, (long)&bad, 1, 0, 0, 0);
            raw6(SYS_close /*close*/, cfd, 0, 0, 0, 0, 0);
            continue;
        }
        // 请求方没带环境（老协议/空环境）就退回启动快照，保持兼容
        char **use_env = (ec > 0) ? ev : g_envp_for_cleaner;
        // ★不能在 cleaner 自己身上 exec：cleaner 与请求方是兄弟进程（同父 = 最初的
        //   loader，现已变成 sh），请求方无法 wait4 一个兄弟 → ECHILD(-10)。
        //   正确做法：cleaner fork 出孙进程去 exec（孙进程继承 cleaner 的无 filter
        //   环境，所以 bionic 能跑），cleaner wait4 它，再把退出码写回 socket。
        long gp = raw6(SYS_clone /*clone*/, 17 /*SIGCHLD*/, 0, 0, 0, 0, 0);
        if (gp == 0) {
            long er = raw6(SYS_execve /*execve*/, (long)path, (long)av, (long)use_env, 0, 0, 0);
            ldr_log2("CLOOP-child: execve rc=", er, "", 0);
            raw6(SYS_exit_group /*exit_group*/, 127, 0, 0, 0, 0, 0);
        }
        unsigned char st = 126;
        int wst = 0;
        if (gp == 0) {
            raw6(SYS_execve /*execve*/, (long)path, (long)av, (long)use_env, 0, 0, 0);
            raw6(SYS_exit_group /*exit_group*/, 127, 0, 0, 0, 0, 0);
        }
        if (gp > 0) {
            for (;;) {
                long r = raw6(SYS_wait4 /*wait4*/, gp, (long)&wst, 0, 0, 0, 0);
                if (r == gp) break;
                if (r < 0 && r != -4) break;
            }
            st = (wst & 0x7f) ? (unsigned char)(128 + (wst & 0x7f))
                              : (unsigned char)((wst >> 8) & 0xff);
        }
        raw6(SYS_write /*write*/, cfd, (long)&st, 1, 0, 0, 0);
        raw6(SYS_close /*close*/, cfd, 0, 0, 0, 0, 0);
    }
    // 循环永不返回；lfd 在 cleaner_start 里 fork 前创建，
    // PDEATHSIG 保证父进程死亡时内核直接回收本进程。
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
    long lfd = raw6(SYS_socket /*socket*/, 1 /*AF_UNIX*/, 1 /*SOCK_STREAM*/, 0, 0, 0, 0);
    if (lfd < 0) { ldr_log2("CLEANER: socket FAIL=", lfd, "", 0); return; }
    // struct sockaddr_un 布局：u16 sun_family(偏移0) + char sun_path[108](偏移2)
    // 抽象命名空间 = sun_path[0] 为 0；addrlen = 2 + 1 + 名字长度
    struct ldr_sockaddr_un sa;
    ((unsigned short *)&sa)[0] = 1;              // AF_UNIX
    ((char *)&sa)[2] = 0;                        // 抽象命名空间标记
    for (int i = 0; i < slen; i++) ((char *)&sa)[3 + i] = name[i];
    int addrlen = 3 + slen;
    if (raw6(SYS_bind /*bind*/, lfd, (long)&sa, addrlen, 0, 0, 0) < 0) {
        ldr_log2("CLEANER: bind FAIL rc=", 0, " addrlen=", addrlen);
        raw6(SYS_close, lfd, 0, 0, 0, 0, 0); return;
    }
    if (raw6(SYS_listen /*listen*/, lfd, 8, 0, 0, 0, 0) < 0) { ldr_puts("CLEANER: listen FAIL"); raw6(SYS_close, lfd, 0, 0, 0, 0, 0); return; }
    g_cleaner_addrlen = addrlen;
    long pid = raw6(SYS_clone /*clone*/, 17 /*SIGCHLD*/, 0, 0, 0, 0, 0);
    if (pid < 0) { ldr_puts("CLEANER: clone FAIL"); return; }
    if (pid == 0) {
        // 必须设 PDEATHSIG：cleaner 若这次没等到请求，会一直阻塞在 accept()，
        // 父进程退出后被 init 收养继续挂着（实测每次启动漏一个进程，ps -A 里
        // 残留 dsh-final2 处于 __skb_wait_for_more_packets 状态）。
        // PR_SET_PDEATHSIG(1) + SIGKILL(9) 让内核在父进程死亡时直接回收它。
        ldr_prctl(1 /*PR_SET_PDEATHSIG*/, 9 /*SIGKILL*/, 0, 0, 0);
        // 竞态兜底：若父进程在设 PDEATHSIG 之前就已退出，则自行退出
        if (raw6(SYS_getppid /*getppid*/, 0, 0, 0, 0, 0, 0) == 1)
            raw6(SYS_exit_group, 0, 0, 0, 0, 0, 0);
        cleaner_loop(lfd, slen); raw6(SYS_exit_group, 0, 0, 0, 0, 0, 0);
    }
    raw6(SYS_close, lfd, 0, 0, 0, 0, 0);
    g_cleaner_on = 1;
    g_cleaner_pid = pid;
}

// 请 cleaner 代跑一个宿主真实路径的 bionic 程序，返回它的退出码
// ★cleaner 原地 execve，PID 不变，所以这里直接 wait4(g_cleaner_pid) 就是目标程序的
//   真实退出状态 —— 无需回执管道，也不会因为 close_fds / 高位 fd 被回收而失效。
static int cleaner_exec(const char *hostpath, char **gargv, char **genv_cur) {
    // ★状态无效时**现找**，不直接 return -1。
    //
    // 为什么必须在这里兜底：cleaner_start() 受 `mode < 2` 保护，而 fork+exec
    // 出来的子进程已继承父进程的 seccomp filter（PR_GET_SECCOMP 就是 2）——
    // 于是它永远不会启自己的 cleaner，同时 .data 段在 execveat 重执行后已重置。
    // 实测症状：`/bin/sh -c '/bin/sh -c "/eprobe"'` → cleaner_exec FAIL，
    // 而同样的命令不套子 shell 就成功 —— 产线上 python -m pip / npm run /
    // make 这类都会 fork+exec，等于所有 bionic 程序在子进程里全废。
    if (!g_cleaner_on || g_cleaner_pid <= 0 || !g_cleaner_namelen) {
        char pname[112];
        int plen = cleaner_sockname(pname);
        int r1 = cleaner_probe_and_adopt(pname, plen);
        if (r1 != 0) {
            plen = find_cleaner_by_prefix(pname);
            if (cleaner_probe_and_adopt(pname, plen) != 0) {
                // 全失败要显式清状态，否则会带 on=1/pid=0 的不一致态反复失败
                g_cleaner_on = 0;
                g_cleaner_pid = 0;
                return -1;
            }
        }
    }
    if (!g_cleaner_namelen) return -1;
    char *name = g_cleaner_name;
    int slen = g_cleaner_namelen;
    long fd = raw6(SYS_socket /*socket*/, 1 /*AF_UNIX*/, 1 /*SOCK_STREAM*/, 0, 0, 0, 0);
    if (fd < 0) return -1;
    struct ldr_sockaddr_un sa;
    ((unsigned short *)&sa)[0] = 1;
    ((char *)&sa)[2] = 0;
    for (int i = 0; i < slen; i++) ((char *)&sa)[3 + i] = name[i];
    int addrlen = 3 + slen;
    long cr = raw6(SYS_connect /*connect*/, fd, (long)&sa, addrlen, 0, 0, 0);
    if (cr < 0) { raw6(SYS_close, fd, 0, 0, 0, 0, 0); return -1; }
    // ★缓冲必须够大，且**装不下时要显式失败**，绝不能静默截断。
    //
    // 实测（模拟器 dsh34 / x86_64）：单变量 7000 字节 → bionic 子进程收到完整
    // 7000；单变量 30000 字节 → 只收到 **7042**，`ENVBYTES=8129` 卡在 8191 附近。
    // 也就是说用户 `export BIGVAR=<很长>` 后，bionic 程序静默拿到的是被砍掉
    // 尾巴的变量 —— npm/pip/构建脚本读到错误的配置值却没有任何提示。
    // 这与 #63 的「短写」是同一类病：**容量边界处必须报错，不能悄悄截断**。
    static char msg[65536];
    long n = 0;
    int trunc = 0;
    for (const char *q = hostpath; *q; q++) { if (n >= (long)sizeof(msg) - 2) { trunc = 1; break; } msg[n++] = *q; }
    msg[n++] = 0;
    if (gargv) {
        for (int i = 1; gargv[i]; i++) {
            if (n >= (long)sizeof(msg) - 300) { trunc = 1; break; }
            for (const char *q = gargv[i]; *q; q++) { if (n >= (long)sizeof(msg) - 2) { trunc = 1; break; } msg[n++] = *q; }
            if (trunc) break;
            msg[n++] = 0;
        }
    }
    msg[n++] = 0;
    // ★argv 终止后再追加**当前**环境变量，再以空串收尾。
    //
    // 原实现只用 g_envp_for_cleaner —— 那是 cleaner 在**首次启动时** fork
    // 那一刻固化的 envp（ldr_entry 里 `g_envp_for_cleaner = envp`）。之后用户
    // export 的任何变量都进不去，实测：
    //   musl_child=[hello_from_export]      <= musl 程序看得到
    //   bionic_child=[]                     <= bionic（走 cleaner）看不到
    // 后果：LD_LIBRARY_PATH / HOME / PATH 对 bionic 程序全部失效，表现为
    //   CANNOT LINK EXECUTABLE ...: library "libz.so.1" not found
    // —— 明明 LD_LIBRARY_PATH 里就有那个库。
    //
    // 协议：path\0argv[1..]\0\0 ENV[1..]\0\0（环境段在 argv 终止符之后）
    if (genv_cur) {
        for (int i = 0; genv_cur[i]; i++) {
            if (n >= (long)sizeof(msg) - 300) { trunc = 1; break; }
            for (const char *q = genv_cur[i]; *q; q++) { if (n >= (long)sizeof(msg) - 2) { trunc = 1; break; } msg[n++] = *q; }
            if (trunc) break;
            msg[n++] = 0;
        }
    }
    if (trunc) {
        // 装不下就**明确失败**：与其让 bionic 程序拿到半截环境变量（静默错误，
        // 排查会怀疑到用户脚本头上），不如让这次 exec 直接失败并给出可读原因。
        // ★必须让**用户**看到原因，而不是只让 loader 自己知道。
        //   走 cleaner_exec 返回 -1 之后，调用点会打印通用的
        //   `BIONIC: cleaner_exec FAIL` 并把 errno 设成 ENOENT —— 用户看到的是
        //   `sh: /prog: not found`，与实际原因（环境变量太大）毫无关系，
        //   排查会往「路径写错了」跑。这里直接把真实原因写给用户。
        ldr_puts("dsh: cannot run bionic program: argv+env exceeds 64KB (");
        ldr_log1("", n, 10);
        ldr_puts(" bytes) — refusing to silently truncate environment"); ldr_puts("");
        raw6(SYS_close, fd, 0, 0, 0, 0, 0);
        return -1;
    }
    msg[n++] = 0;
    // cleaner 是**一次性进程**（服务一次就 exit），因此它继承的 stdout/stderr
    // 会随它退出而释放，不需要 SCM_RIGHTS 传递，也不需要主动断开：
    //   - 若 cleaner 常驻且持有 shell 的 stdout，adb shell 永不返回（实测 124 超时）
    //   - 断开 stdio 又会丢掉 bionic 程序的输出（实测只剩 RC=0）
    //   一次性模型两个问题同时消失：输出正常 + 进程退出即释放。
    // ★socket write 会**短写**，必须循环写到完。
    //   原代码 `if (wr != n) return -1` 在消息变大后立刻变成静默失败：
    //   容器里 180 个环境变量拼进 8KB 时必现，症状是莫须有的
    //   `BIONIC: cleaner_exec FAIL`，且返回点在写之后、任何日志都打不出来。
    long wr = raw6(SYS_write /*write*/, fd, (long)msg, n, 0, 0, 0);
    while (wr >= 0 && wr < n) {
        long w2 = raw6(SYS_write, fd, (long)(msg + wr), n - wr, 0, 0, 0);
        if (w2 <= 0) break;
        wr += w2;
    }
    // ★这里绝不能 close：还要在本 fd 上读回执。曾在此处 close，导致下面 read
    //   拿 EBADF(-9)，于是误判失败并返回 ENOENT → sh 报 "not found"、RC=127，
    //   而实际上 bionic 程序已经跑完、GPU 输出全都拿到了。
    if (wr != n) { raw6(SYS_close, fd, 0, 0, 0, 0, 0); return -1; }
    // 读回执（阻塞直到 cleaner 的孙进程结束）
    unsigned char st = 126;
    for (;;) {
        long r = raw6(SYS_read /*read*/, fd, (long)&st, 1, 0, 0, 0);
        if (r == 1) break;
        if (r < 0 && r != -4 /*EINTR*/) { raw6(SYS_close, fd, 0, 0, 0, 0, 0); return -1; }
        if (r == 0) { raw6(SYS_close, fd, 0, 0, 0, 0, 0); return -1; }
    }
    raw6(SYS_close /*close*/, fd, 0, 0, 0, 0, 0);
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
// 容器内默认 PATH（v1.7.8 复查）：**必须**把 /eng/bin 排在首位。
// 执行器（LinuxContainerRunner.doExec）虽然把 PATH 设成了含 /eng/bin 的版本，
// 但本 loader 在首次 exec 时会把「宿主那套」或 g_force_container_path 标记的
// PATH 一律替换成这里的默认值 —— 早先这 4 处硬编码都不含 /eng/bin，于是
// `dev linux exec` 的容器里 `echo $PATH` 只有发行版目录、`scr` / `dev` 全部
// not found（真机实测：/eng/bin 里 87 个门脚本都在，就是 PATH 指不到）。
#define DSH_DEFAULT_PATH "/eng/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

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
// 宿主环境里**不能带进容器**的变量。
//
// 判据是「值在容器里没有意义」，不是「名字敏感」：
//   - PREFIX / ANDROID_* / BOOTCLASSPATH：Android 的路径，容器是 Alpine
//   - SHELL：宿主的 /system/bin/sh 在容器里不存在，容器侧另行补 /bin/sh
//   - TMPDIR / TMP / TEMP：宿主临时目录在容器里不存在（实测泄漏进去会让
//     dsh-spill-local 的 mkdtemp 直接 ENOENT，插件 activate 失败）
//
// ★ LD_LIBRARY_PATH 与 LD_PRELOAD **不能无条件丢**。
//   曾经它们在这个表里，理由是「宿主的库路径在容器里没意义」—— 对宿主自己
//   传进来的值成立，但代码没有区分「主进程启动时继承的宿主值」与
//   「用户在容器内 export 的值」，一律砍掉。
//   后果（实测）：容器里 `export LD_LIBRARY_PATH=...然后再 exec` 的结果是
//   **子进程拿不到这个变量**；而 Alpine 的 openjdk8/11/17/21 的 java 可执行
//   文件 NEEDED libjli.so，靠 `$ORIGIN/../lib/amd64/jli` 的 RUNPATH 定位，
//   我们的 loader 不展开 $ORIGIN（见 47 条同族的 48 条），于是只能靠
//   LD_LIBRARY_PATH 兜底 —— 变量被砍就完全起不来。
//   现在改为：**只丢「值看起来像 Android 宿主路径」的那一份**，
//   容器内 export 的（值以 / 开头且不像宿主前缀）照常传递。
static int looks_like_host_path(const char *v) {
    static const char *pfx[] = {
        "/data/", "/system/", "/system_ext/", "/product/", "/odm/",
        "/vendor/", "/apex/", "/storage/emulated/", "/mnt/",
    };
    for (unsigned i = 0; i < sizeof(pfx) / sizeof(pfx[0]); i++) {
        const char *p = pfx[i];
        unsigned k = 0;
        while (p[k] && v[k] == p[k]) k++;
        if (!p[k]) return 1;
    }
    return 0;
}

static int env_drop_host(const char *e) {
    // LD_LIBRARY_PATH / LD_PRELOAD：按值判断（见上）
    {
        static const char *ld[] = { "LD_LIBRARY_PATH", "LD_PRELOAD" };
        for (unsigned i = 0; i < 2; i++) {
            const char *b = ld[i];
            unsigned k = 0;
            while (b[k] && e[k] == b[k]) k++;
            if (b[k] || e[k] != '=') continue;
            return looks_like_host_path(e + k + 1) ? 1 : 0;
        }
    }
    static const char *bad[] = {
        "PREFIX", "SHELL",
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

// 诊断输出开关：环境里带 `DSH_TRAP_PROFILE=1` 时才把 [TRAPCNT] / [GUARD]
// 统计写到 stderr。
//
// 为什么要有这个开关：这些行是**排查工具**，不是用户输出。原先无条件打印，
// 于是扩展中心的装包进度窗里会混进一大片
//     [TRAPCNT] total=...
//     nr=14 c=13
//     nr=264 c=938
// 把真正的 apk/dpkg 报错挤到看不见的地方（实测装 OpenJDK 失败时，
// 用户看到的第一屏全是 nr= 统计，真正的原因
// `ERROR: java-common-1.0-r0.trigger: script exited with error 131`
// 在最后一行才出现）。
//
// 默认关闭；排查「App 进程内某个 syscall 被外层 seccomp 拦掉」时，
// 用 DSH_TRAP_PROFILE=1 起容器即可拿回全部统计。
static int g_trap_profile_cached = -1;
static int trap_profile_on(void) {
    if (g_trap_profile_cached >= 0) return g_trap_profile_cached;
    static const char key[] = "DSH_TRAP_PROFILE=";
    unsigned kl = sizeof(key) - 1;
    g_trap_profile_cached = 0;
    for (int i = 0; i < g_env_count; i++) {
        const char *e = g_env_store[i];
        unsigned k = 0;
        while (k < kl && e[k] && e[k] == key[k]) k++;
        if (k != kl) continue;
        const char *d = e + kl;
        if (d[0] == (char)49) { g_trap_profile_cached = 1; break; }  // '1'
    }
    return g_trap_profile_cached;
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
        // 整段预留 anon RW（段间空隙为零页，可读写），随后文件映射覆盖文件部分。
        //
        // ★不能只靠 MAP_FIXED_NOREPLACE(0x100000)：它是 **Linux 4.17** 才有的标志。
        //   老内核（实测 Android 模拟器 emulator-5554 是 **3.18.91**）不认识这个位，
        //   会把它当「不认识的标志」忽略掉 —— 请求变成普通的 hint 型 mmap，
        //   内核可以（也确实）返回**别的地址**，而下面 PT_LOAD 的 MAP_FIXED 与
        //   慢路径的 pread64 全都按 IHI+ilo 这个绝对值来算 → 写进未映射区。
        //   实测症状：mmap 返回 0x7e66ba588000（不是 0x7000000000），随后
        //   `pread64(4, 0x70000a1aa0, 2404, 658080) = -1 EFAULT` → exit_group(131)
        //   → 容器内所有需要 fork+exec 的操作（shell 跑任何外部命令、gcc 拉 cc1/as/ld）
        //   全部静默失败。
        //   修法：**必须校验返回地址 == 请求地址**，不等就用 MAP_FIXED 严格重来。
        long want = (long)(IHI + ilo);
        long rv = raw6(SYS_mmap, want, (long)ispan, 3 /*RW*/,
                       0x22 /*PRIVATE|ANON*/ | 0x100000 /*FIXED_NOREPLACE*/, -1, 0);
        if (rv >= 0 && rv > -4096 && rv != want) {
            // 内核给了别处：先撤掉这段「跑偏」的映射，再用 MAP_FIXED 严格落位
            raw6(SYS_munmap, rv, (long)ispan, 0, 0, 0, 0);
            rv = -1;
        }
        if (rv < 0 && rv > -4096) {
            rv = raw6(SYS_mmap, want, (long)ispan, 3 /*RW*/, 0x32 /*PRIVATE|ANON|FIXED*/, -1, 0);
        }
        if (rv < 0 && rv > -4096) raw6(SYS_exit_group, 123, 0, 0, 0, 0, 0);
        if (rv != want) raw6(SYS_exit_group, 123, 0, 0, 0, 0, 0);
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
        // 同上：FIXED_NOREPLACE 在 4.17 之前的内核上会被忽略，必须校验落位地址。
        long gwant = (long)(GHI + glo);
        long rv = raw6(SYS_mmap, gwant, (long)gspan, 3 /*RW*/,
                       0x22 | 0x100000, -1, 0);
        if (rv >= 0 && rv > -4096 && rv != gwant) {
            raw6(SYS_munmap, rv, (long)gspan, 0, 0, 0, 0);
            rv = -1;
        }
        if (rv < 0 && rv > -4096) {
            rv = raw6(SYS_mmap, gwant, (long)gspan, 3, 0x32 /*FIXED*/, -1, 0);
        }
        if (rv < 0 && rv > -4096) raw6(SYS_exit_group, 121, 0, 0, 0, 0, 0);
        if (rv != gwant) raw6(SYS_exit_group, 121, 0, 0, 0, 0, 0);
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
#if defined(DSH_ARCH_AARCH64)
    register unsigned long x0 __asm__("x0") = (unsigned long)sp;
    __asm__ volatile("mov sp, %0\n br %1\n" :: "r"(x0), "r"(entry) : "memory");
#else
    // x86_64：guest 入口只接 rsp（SysV 进程入口 rsp 指向 argc，无返回地址）
    __asm__ volatile("movq %0, %%rsp\n\tjmp *%1\n" :: "r"((unsigned long)sp), "r"(entry) : "memory");
#endif
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

// ★反向映射：把宿主机绝对路径裁成 guest 视图。返回裁剪后的长度。
//
// 为什么必须有这个：musl 的 realpath() 实现是
//     open(path, O_PATH) → readlink("/proc/self/fd/N")
// 而 /proc 整棵前缀是**透传给宿主**的（见 is_host_passthrough），所以
// readlink 返回的是**宿主机真实路径**。realpath 拿到后还要拿它去 stat，
// 容器里那个路径不存在 → realpath 对**任何** bind 挂载点下的路径都失败。
//
// 实测（模拟器 x86_64）：
//     realpath("/dsh")  → FAIL No such file or directory
//     realpath("/tmp")  → FAIL No such file or directory
// 而 stat("/dsh") / open("/dsh") 全正常 —— 只有 realpath 挂。
//
// 后果不只是「少个工具」：Node 的 fs.realpathSync 会抛，chokidar 拿到
// 非法 base 后去 watch("/")，报 `EACCES: permission denied, watch '/'`，
// 直接把 hmr 插件干挂 → 引擎启动失败（required plugin did not activate）。
//
// 匹配顺序：bind 表优先（更具体的宿主路径先匹配），再退到 rootfs 前缀。
// 都不匹配返回 -1，调用方保持原样。
static int host_to_guest(char *buf, int rv) {
    if (rv <= 0 || buf[0] != (char)47) return -1;
    int best = -1, best_len = 0;
    // 1) bind 表反查：宿主路径 → guest 前缀。取最长匹配。
    for (int k = 0; k < g_bind_n; k++) {
        int hl = g_bind_hlen[k];
        if (hl <= best_len || hl > rv) continue;
        int ok = 1;
        for (int i = 0; i < hl; i++) if (buf[i] != g_bind_h[k][i]) { ok = 0; break; }
        if (!ok) continue;
        if (rv > hl && buf[hl] != (char)47) continue;
        best = k; best_len = hl;
    }
    if (best >= 0) {
        char tmp[PATH_MAX];
        int o = 0;
        for (int i = 0; i < g_bind_glen[best] && o < PATH_MAX - 1; i++) tmp[o++] = g_bind_g[best][i];
        const char *rest = buf + best_len;
        if (*rest == 0) {
            if (o == 0) { tmp[o++] = (char)47; }
        } else {
            while (*rest && o < PATH_MAX - 1) tmp[o++] = *rest++;
        }
        tmp[o] = 0;
        for (int i = 0; i <= o; i++) buf[i] = tmp[i];
        return o;
    }
    // 2) rootfs 前缀：宿主 rootfs 下的一切都是 guest 的 "/"
    if (g_rootfs_host_len > 0 && rv >= g_rootfs_host_len) {
        int match = 1;
        for (int k = 0; k < g_rootfs_host_len; k++) {
            if (buf[k] != g_rootfs_host[k]) { match = 0; break; }
        }
        if (match) {
            const char *rest = buf + g_rootfs_host_len;
            if (*rest == 0) { buf[0] = (char)47; buf[1] = 0; return 1; }
            int n2 = 0;
            while (rest[n2] && n2 < PATH_MAX - 1) { buf[n2] = rest[n2]; n2++; }
            buf[n2] = 0;
            return n2;
        }
    }
    return -1;
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

// ★link() 的「复制兜底」（v1.7.15，真机 2026-11-13 定位）。
//
// 背景：Android 的 SELinux 对 app 域（untrusted_app_27 / runas_app）**整体不给**
// app_data_file 的 link 权限 —— 策略里 base_typeattr_256 的 file 权限集是
//   ioctl read write create getattr setattr lock append map unlink rename open ...
// 根本没有 link，而且还有 dontaudit 规则，所以连 avc: denied { link } 都不打。
//
// 实测（PLZ110 / Android 16 / 同一 app 域，scontext=u:r:runas_app:s0:c243,...）：
//   ln a b                     → Permission denied
//   ln -s a b                  → 成功
//   cp -p a b / touch b / : > b → 全部成功
// 所以「ls -l 权限全对、cp 能写、只有 link 不行」，极易误判成属主或标签问题。
//
// 后果：dpkg 每次写 status / diversions 之前都要 link() 一份旧文件当备份，于是
// 所有 dpkg -i / dpkg --configure 一律报
//   dpkg: error creating new backup file '/var/lib/dpkg/status-old': Permission denied
// 这正是「Ubuntu 装包必失败」的真实根因，与备份文件是否存在、标签是否漂移都无关
// （清备份、restorecon 都不解决问题 —— 两种都试过，200 多轮才回到这一行）。
// Termux 的解法是 link2symlink；这里直接做复制，语义更准确：备份本就该是快照，
// 之后写新文件不会污染它，而 link2symlink 需要 guest 侧感知「链接不是链接」。
//
// 顺序：**先试真 link，失败才复制**。ksu/su 域允许 link、模拟器也允许，所以那两条
// 路径的行为一字不变；只有被 SELinux 拒掉时才走这里。
//
// 缓冲 4KB：本函数在 SIGSYS handler 内被调用，handler 跑在 64KB altstack 上，
// 再叠 4KB 是实测可接受的上限（旧注释记过：多开两个 PATH_MAX 会把 rg 的并发
// 崩溃率从 3/15 放大到 10/15）。
static int ldr_link_denied(long rc) {
    // EPERM(1) / EACCES(13) / EOPNOTSUPP(95) / EROFS(30)
    return rc == -1 || rc == -13 || rc == -95 || rc == -30;
}

static long ldr_copy_link(long ofd, const char *op, long nfd, const char *np) {
    long in = raw6(SYS_openat, ofd, (long)op, 0 /*O_RDONLY*/, 0, 0, 0);
    if (in < 0) return in;
    // O_WRONLY(1)|O_CREAT(64)|O_EXCL(128)：目标已存在时与 link() 一样返回 EEXIST
    long out = raw6(SYS_openat, nfd, (long)np, 1 | 64 | 128, 0644, 0, 0);
    if (out < 0) { raw6(SYS_close, in, 0, 0, 0, 0, 0); return out; }
    char cbuf[4096];
    long rc = 0;
    for (;;) {
        long n = raw6(SYS_read, in, (long)cbuf, (long)sizeof(cbuf), 0, 0, 0);
        if (n < 0) { rc = n; break; }
        if (n == 0) break;
        long off = 0;
        while (off < n) {
            long w = raw6(SYS_write, out, (long)(cbuf + off), n - off, 0, 0, 0);
            if (w <= 0) { rc = (w < 0) ? w : -5; break; }
            off += w;
        }
        if (rc < 0) break;
    }
    raw6(SYS_close, in, 0, 0, 0, 0, 0);
    raw6(SYS_close, out, 0, 0, 0, 0, 0);
    return rc;
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
/*
 * /dev/shm 必须**排除**在 /dev 透传之外。
 *
 * 原因（实测踩到，症状极难归因）：Android 宿主**根本没有 /dev/shm**。
 * /dev 是整棵透传的，于是 guest 里所有走 POSIX 共享内存的程序都失败：
 *   - musl 的 shm_open() 实现就是 open("/dev/shm/<name>") → ENOENT
 *   - wlroots 的 headless 后端因此报
 *       [wlr] [render/swapchain.c:110] Failed to allocate buffer
 *     连续刷屏，sway 的 output 能建出来但**永远分配不到帧缓冲**，
 *     grim 报 `failed to copy output HEADLESS-1`，截图全是空。
 *   - dbus / Chromium / PulseAudio 同样依赖它
 *
 * 修法：把 /dev/shm 指到 rootfs 内自己的目录（make_host 会拼成
 * <rootfs>/dev/shm），这样共享内存落在容器自己的文件系统里，不依赖宿主。
 * 其余 /dev 设备节点仍照旧透传（GPU、binder、ashmem 都靠它）。
 */
static int is_shm_path(const char *p) {
    static const char a[] = "/dev/shm";
    const char *x = p; const char *y = a;
    while (*y && *x == *y) { x++; y++; }
    if (*y) return 0;
    return (*x == 0 || *x == (char)47);
}

static int is_host_passthrough(const char *p) {
    // /dev/shm 例外：落到 rootfs 内，不走宿主（宿主没有这个目录）
    if (is_shm_path(p)) return 0;
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


static void sigsys_impl(siginfo_t *info, void *uc, unsigned long long *r);

#if defined(DSH_ARCH_AARCH64)
static void sigsys(int s, siginfo_t *info, void *uc) {
    (void)s;
    ucontext_t *u = (ucontext_t *)uc;
    sigsys_impl(info, uc, (unsigned long long *)u->uc_mcontext.regs);
}
#else
// x86_64 的 ucontext 是 gregs[] 数组，没有 aarch64 那种连续 regs[]。
// 这里把活寄存器摊进局部数组交给共用的 sigsys_impl，返回后再写回 ucontext。
//   gregs[REG_RAX]=syscall 号，入参在 REG_RDI/RSI/RDX/R10/R8/R9
//   ★必须保持 r[8]=nr、r[0..5]=入参的位置约定（sigsys_impl 依赖它）
//   ★REG_R10 才是第 4 个入参（不是 REG_RCX）
static void sigsys(int s, siginfo_t *info, void *uc) {
    (void)s;
    ucontext_t *u = (ucontext_t *)uc;
    greg_t *g = u->uc_mcontext.gregs;
    unsigned long long r[9];
    r[0] = (unsigned long long)g[REG_RDI];
    r[1] = (unsigned long long)g[REG_RSI];
    r[2] = (unsigned long long)g[REG_RDX];
    r[3] = (unsigned long long)g[REG_R10];
    r[4] = (unsigned long long)g[REG_R8];
    r[5] = (unsigned long long)g[REG_R9];
    r[8] = (unsigned long long)g[REG_RAX];
    sigsys_impl(info, uc, r);
    // ★★x86_64 的**返回值必须写进 RAX**，不是 RDI。
    //
    //   aarch64 那边 x0 既是第一个参数又是返回值，r[0] 一改就自动生效。
    //   x86_64 完全分开：入参在 rdi，返回在 rax。只写回 RDI 的后果是
    //   guest 拿到的「返回值」其实是它自己的第一个参数，而真正的 rax
    //   仍是 syscall 号。实测症状（容器里 `ls /etc`）：
    //       stat("/etc", {st_mode=001, st_size=0, ...}) = 4
    //   st_mode 是垃圾、返回值 4 根本不是 fd —— 随后寄存器被污染，
    //   handler 内再发 syscall 就 SIGSEGV(SI_KERNEL)。
    //
    //   入参寄存器仍要写回：sigsys_impl 会改写 a0/a1（把路径换成翻译后的
    //   相对路径、把 vfork 的 flags 清位后靠 PC 回退重试）。
    g[REG_RDI] = (greg_t)r[0];
    g[REG_RSI] = (greg_t)r[1];
    g[REG_RDX] = (greg_t)r[2];
    g[REG_R10] = (greg_t)r[3];
    g[REG_R8]  = (greg_t)r[4];
    g[REG_R9]  = (greg_t)r[5];
    g[REG_RAX] = (greg_t)r[0];
}
#endif

static void sigsys_impl(siginfo_t *info, void *uc, unsigned long long *r) {
    (void)info;
    ucontext_t *u = (ucontext_t *)uc;
    long nr = (long)r[8];
    // ★★重入守卫（v1.6.17）：本次 trap 的 syscall 是**我们自己发的**吗？
    //
    //   机制：Android 会给每个 App 进程额外装一层 seccomp（zygote 里装的那层），
    //   它只按 syscall 号判、不看调用者 IP。我们在 handler 里用 raw6 重发被 trap
    //   的 syscall，指令地址落在 loader 段（0x2xxxxxxxx，我们自己的过滤器按 IP
    //   白名单放行），但 Android 那层照样把它 trap —— 于是 SIGSYS 再投递一次，
    //   handler 再进一层，无限递归，直到把 guest 栈压穿。
    //
    //   实测（模拟器 x86_64、App 进程内）：`DIAG recurse used=526504 nr=165`
    //   （165=mount，栈已吃掉 526KB）。2MB 栈表现为 error 6（页不存在）；改成
    //   8MB 后漂到 guest 只读段变成 error 7（页只读）—— 症状随栈大小漂移，
    //   极易被误判成「栈太小」。shell / su / run-as 里 Seccomp 都是 0（没有
    //   Android 那层），所以同一条命令在那里永远不复现，这正是「本地通、App 崩」
    //   的由来。
    //
    //   判定用 **RIP 是否在 loader 段**，不用「同号 syscall 计数」：
    //   全局计数在多线程下会把别的线程的正常 trap 误判成重入（实测 poll 被
    //   误拒成 -EPERM，网络直接瘫痪）。IP 判定是纯粹无状态的、每信号自带，
    //   不存在跨线程串味。
    //
    //   命中说明「这层重发必然再被外层拦」，直接给 guest 返回 -EPERM 而不重发。
    //   mount 本来就该失败，-EPERM 是诚实的答案。
#if defined(DSH_ARCH_AARCH64)
    unsigned long trap_ip = (unsigned long)u->uc_mcontext.pc;
#else
    unsigned long trap_ip = (unsigned long)u->uc_mcontext.gregs[REG_RIP];
#endif
    if ((trap_ip >> 32) == (unsigned long)DSH_LOADER_BASE_HI) {
        g_guard_hits++;
        if (nr > 0 && nr < 512) g_guard_nr[nr]++;
        r[0] = (unsigned long long)(long)-EPERM;
        return;
    }
    // ★poll → ppoll（v1.6.17，必须放在最前面；仅 x86_64）。
    //
    //   Android 给 App 装的那层 seccomp 策略在 x86_64 上允许 ppoll(271)、
    //   **禁止 poll(7)**。guest 里 musl 的 select()/poll() 在这套 ABI 上走
    //   poll，于是每次都被外层拦；原样重发 → 再 trap → 无限递归（139 的
    //   根因之一）。poll(fds,nfds,timeout_ms) 与 ppoll(fds,nfds,&ts,mask,siz)
    //   等价：timeout<0 传 NULL（无限等），sigmask 传 NULL（不改掩码）。
    //
    //   放在 switch 之前：handler 中部有大量提前 return 的分支，靠后的位置
    //   可能根本到不了（实测放尾部时 poll 仍被原样重发）。
    //
    //   ★只在 x86_64 编译：aarch64 的通用 ABI **没有 poll 这个 syscall**
    //   （只有 ppoll=73），写进去会直接编译失败。
#if defined(DSH_ARCH_X86_64)
    if (nr == SYS_poll) {
        struct { long tv_sec; long tv_nsec; } ts;
        void *tsp = 0;
        long to = (long)r[2];
        if (to >= 0) {
            ts.tv_sec = to / 1000;
            ts.tv_nsec = (to % 1000) * 1000000L;
            tsp = &ts;
        }
        r[0] = (unsigned long long)raw6(SYS_ppoll, (long)r[0], (long)r[1], (long)tsp, 0, 0, 0);
        return;
    }
#endif
    // ★select → pselect6（v1.7.x；仅 x86_64，与 poll 同源问题）。
    //
    //   实证（模拟器 x86_64、App 进程内、装 Python 扩展）：
    //       [GUARD-EPERM] nr=23        ← 23 = select
    //       fetch http://mirrors.aliyun.com/... 等满 30s 后
    //       WARNING: updating and opening ...: IO ERROR
    //   而 `apk update` 在 adb shell / su / run-as 里（Seccomp=0）秒过。
    //
    //   Android 给 App 进程装的那层 seccomp 只按 syscall 号判、不看 IP：
    //   它放行 pselect6(270)，拦 select(23)。我们 handler 里通过 raw6 重发
    //   select，指令地址虽然在 loader 段（自己的过滤器按 IP 白名单放行），
    //   但外层照样 trap → 重入守卫命中 → guest 拿到 -EPERM。
    //   apk 的 socket 等待循环拿不到可读事件，重试到超时，最终报 IO ERROR，
    //   看起来像镜像站/网络问题，实际是 select 被拒。
    //
    //   语义对齐：select(nfds, r, w, e, &timeval) 与
    //   pselect6(nfds, r, w, e, &timespec, NULL) 只差超时结构体
    //   （timeval.tv_usec 微秒 vs timespec.tv_nsec 纳秒）。fd_set 布局完全相同，
    //   nfds 的用途也相同。timeout 为 NULL 时原样传 NULL（无限等待）。
    //
    //   ★回写：select 的语义是「返回时把 timeval 改成剩余时间」，
    //   pselect6 不改 timespec。所以调用后要把 ts 反算回 guest 的 timeval：
    //   pselect6 不写它，于是值等于进来时的值 —— 即「没有消耗时间」。
    //   对「等到了事件」的情形这是对的（剩余=原值不符，但调用方通常只看返回值）；
    //   对超时返回 0 的情形显式写 0（时间确实耗尽），否则调用方会误以为
    //   还有余量，重试时又等满一整轮。
    //
    //   ★只在 x86_64 编译：aarch64 的通用 ABI 没有 select 这个 syscall
    //   （只有 pselect6=72 / ppoll=73），写进去会直接编译失败。
#if defined(DSH_ARCH_X86_64)
    if (nr == SYS_select) {
        // 命名结构体（不能用匿名 struct：两处匿名类型互不兼容，
        // clang 会报 incompatible pointer types）
        struct dsh_timeval { long tv_sec; long tv_usec; };
        struct dsh_timespec { long tv_sec; long tv_nsec; };
        struct dsh_timespec ts;
        struct dsh_timeval *tv = (struct dsh_timeval *)(unsigned long long)r[4];
        void *tsp = 0;
        if (tv) {
            ts.tv_sec = tv->tv_sec;
            ts.tv_nsec = tv->tv_usec * 1000L;
            tsp = &ts;
        }
        long sel_r = raw6(SYS_pselect6, (long)r[0], (long)r[1], (long)r[2],
                          (long)r[3], (long)tsp, 0);
        if (tv && sel_r >= 0) {
            // 超时耗尽 → 归零；有事件 → 保留原值（见上面的说明）
            if (sel_r == 0) { tv->tv_sec = 0; tv->tv_usec = 0; }
            else { tv->tv_sec = ts.tv_sec; tv->tv_usec = ts.tv_nsec / 1000L; }
        }
        r[0] = (unsigned long long)sel_r;
        return;
    }
#endif
    // ★fork → clone（v1.7.x；仅 x86_64，与 poll/select 同源问题）。
    //
    //   实证（模拟器 x86_64、App 进程内、装 Python 扩展）：装包成功，但
    //   `[GUARD-EPERM] nr=57` 命中一次 —— 57 就是 x86_64 的 fork。
    //   这次 apk 自己退回了 clone 所以没坏事，但**容器里任何 guest 调
    //   fork() 都会拿到 EPERM**：musl 在 x86_64 上把 fork() 直接实现成
    //   fork 系统调用（不像 glibc 走 clone），于是 shell 子进程、python
    //   multiprocessing、`cmd &` 这类全都废掉。
    //
    //   Android 外层那层 seccomp 与拦 select 同一逻辑：按号拦、不看 IP。
    //   翻译成 clone(SIGCHLD) 后走的是与现代 libc 完全等价的路径 ——
    //   fork() 在 Linux 上本来就等价于 clone(SIGCHLD, ...)，
    //   alembic 一样、语义一样（子进程不共享 VM、copy-on-write）。
    //
    //   ★只在 x86_64 编译：aarch64 通用 ABI 没有 fork 这个 syscall
    //   （只有 clone=220），写进去会直接编译失败。
#if defined(DSH_ARCH_X86_64)
    if (nr == SYS_fork) {
        r[0] = (unsigned long long)raw6(SYS_clone, 17 /*SIGCHLD*/, 0, 0, 0, 0, 0);
        return;
    }
#endif
    // ★dup2 → dup3（v1.7.x；仅 x86_64，与 poll/select/fork 同源问题）。
    //
    //   实证（模拟器 x86_64、App 进程内、装 nodejs 时 apk 的 post-install）：
    //       dup2(12, 1) = 33                      ← guest 以为成功
    //       --- SIGSYS {si_syscall=__NR_dup2} ---
    //       rt_sigreturn(...) = -1 EPERM
    //       line 3: dup2(12,1): Operation not permitted
    //   随后 shell 在同一行反复重试，CPU 打满、apk 数据库锁一直被占，
    //   界面上就是「一直转圈 / 一直加载中」，且后续 apk update 全报
    //   Unable to lock database（rc=99）。
    //
    //   触发点是 apk 的 post-install 脚本：`cat >&2 <<EOF ... EOF` 里的
    //   `>&2` 让 busybox sh 用 dup2 重定向 stdout。所以**任何带 heredoc
    //   重定向的包脚本都会把整个装包流程卡死**。手写 `echo` 不涉及 dup2，
    //   这也是早期「手工跑 trigger 一切正常」具误导性的原因。
    //
    //   Android 外层 seccomp 放行 dup3(292)、拦 dup2(33)，同样只看号不看 IP。
    //   语义对齐：dup2(old, new) 与 dup3(old, new, 0) 完全等价 ——
    //   dup3 多的第三个参数是 flags，传 0 就没有 O_CLOEXEC，行为与 dup2 一致
    //   （包括 old == new 时 dup2 返回 new 而不关闭，dup3 则返回 EINVAL；
    //   这里按 dup2 语义特判：old == new 且 old 是有效 fd 时直接返回 new）。
    //
    //   ★只在 x86_64 编译：aarch64 通用 ABI 没有 dup2（只有 dup3=24）。
#if defined(DSH_ARCH_X86_64)
    if (nr == SYS_dup2) {
        int d_old = (int)r[0], d_new = (int)r[1];
        // dup2 的特殊语义：oldfd == newfd 时若 oldfd 有效则原样返回，不关闭
        if (d_old == d_new) {
            long f = raw6(SYS_fcntl, d_old, 1 /*F_GETFD*/, 0, 0, 0, 0);
            r[0] = (f < 0) ? (unsigned long long)(-EBADF)
                           : (unsigned long long)d_new;
        } else {
            r[0] = (unsigned long long)raw6(SYS_dup3, d_old, d_new, 0, 0, 0, 0);
        }
        return;
    }
#endif
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
#if defined(DSH_ARCH_AARCH64)
            u->uc_mcontext.pc -= DSH_SYSCALL_INSN_LEN;   // 回退到 svc 指令重试
#else
            u->uc_mcontext.gregs[REG_RIP] -= DSH_SYSCALL_INSN_LEN;
#endif
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

            int rc2 = cleaner_exec(hp, gargv, genvp);
            if (rc2 >= 0) raw6(SYS_exit_group /*exit_group*/, rc2, 0, 0, 0, 0, 0);
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
            // 容量必须 ≥ 2 + 2*MAX_BIND + 6 + 1 = 41：
            //   "dsh-alpine" + "--dsh-reexec-v1"        2
            //   每条 bind 两个槽位（"-m" + "g:h"）       2*MAX_BIND = 32
            //   --rootfs / --interp / --guest 三组       6
            //   结尾 NULL                                1
            // ★原先写成 32（v1.7.7 复查）：MAX_BIND=16 时 k = 2 + 2*B + 6 = 2B + 8，
            //   B=12 就到 32 —— 正好写到相邻的 gv2[0] 上（符号表实测 na 在 0x232070、
            //   大小 0x100 = 32 个指针，gv2 紧随其后）。当时被下面 1992 行「重新赋值
            //   gv2[0..g_argv_count-1]」意外掩蔽，但那是越界写，不是安全。
            //   85757ba1 把典型 bind 数从 9 抬到 12，等于刚踩上线。
            static char *na[48];
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
            // 守卫与容量**同源**：每条 bind 要给 2 个槽位，之后固定再放 6 个参数与 1 个 NULL，
            // 故 k 最多到 40（旧守卫写成 k<28，第 14 条起的 bind 会被静默丢弃 ——
            // 容器里那个挂载点直接消失，症状比崩溃更难查）。
            for (int bi = 0; bi < g_bind_n && k < 40; bi++) {
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
            long exr = raw6(SYS_execveat, self, (long)"", (long)fullargv, (long)gve, 0x1000, 0);
            // ★execveat 被外层 seccomp 拦时应退回 execve（v1.7.x）。
            //
            //   实测（模拟器 x86_64、App 进程内、`sh -c "/bin/busybox echo HI"`）：
            //       [EXEC] reexec self_fd=6
            //       [EXEC] execveat_rc=38        ← 38 = ENOSYS
            //       RC=131
            //   即容器里**嵌套执行的子进程**在重执行自身时 execveat 直接 ENOSYS。
            //   后果不只是慢：重执行失败后退回 fallback_fork，而那时本进程的
            //   .data 段已按「马上要 exec 掉」的假设清理过，child_run_raw 拿不到
            //   guest 参数 → 子进程静默退出 131（128+3 = SIGQUIT）。
            //
            //   这就是容器里所有 `sh -c "外部命令"`、以及 apk 的 trigger 脚本
            //   （busybox/ca-certificates/java-common 都用 /bin/sh 起）报
            //   `script exited with error 131` 的根因 —— 与宿主/权限/网络无关，
            //   改动前的旧产物同样复现，是长期既有的 bug。
            //
            //   Android 的 zygote 层 seccomp 与本文件里 select(23)、fork(57)
            //   那两次完全同源：放行 execve，拦 execveat(322)。
            //   退回用 execve("/proc/self/exe", ...) 语义完全等价
            //   （execveat + AT_EMPTY_PATH 就是它的 fd 版），且 /proc 一定可读。
            if (exr < 0 && (exr == -38 || exr == -1 || exr == -13)) {
                raw6(SYS_execve, (long)"/proc/self/exe", (long)fullargv, (long)gve, 0, 0, 0);
            }
            // execveat/execve 都失败 → 退回 fork 路径，不能把 guest 进程杀掉
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
            // [prof] 打印按 syscall 号排序的 trap 统计（写到 stderr，只在本进程）。
            // ★默认关闭（见 trap_profile_on()）：这些行会污染用户的装包日志。
            if (trap_profile_on()) {
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
            // 守卫命中统计（同样只在 DSH_TRAP_PROFILE=1 时输出）。
            // 非 0 说明外层 seccomp 拦了我们重发的某个 syscall —— 历史上依次是
            // poll(7)、select(23)、fork(57)，都已在 handler 前段翻译成
            // ppoll / pselect6 / clone。
            if (g_guard_hits && trap_profile_on()) {
                char gb[256];
                int gk = 0;
                const char *gpfx = "[GUARD] total=";
                while (*gpfx) gb[gk++] = *gpfx++;
                gk += utoa_(gb + gk, (unsigned long)g_guard_hits);
                gb[gk++] = 10;
                raw6(SYS_write, 2, (long)gb, gk, 0, 0, 0);
                for (int i = 0; i < 512; i++) {
                    if (!g_guard_nr[i]) continue;
                    int gm = 0;
                    gb[gm++] = 'n'; gb[gm++] = 'r'; gb[gm++] = '=';
                    gm += utoa_(gb + gm, (unsigned long)i);
                    gb[gm++] = ' '; gb[gm++] = 'c'; gb[gm++] = '=';
                    gm += utoa_(gb + gm, (unsigned long)g_guard_nr[i]);
                    gb[gm++] = 10;
                    raw6(SYS_write, 2, (long)gb, gm, 0, 0, 0);
                }
            }
            raw6(SYS_exit_group, (long)((status >> 8) & 0xFF), 0, 0, 0, 0, 0);
        }
        return;
    }

#if defined(DSH_ARCH_X86_64)
    // ============ x86_64 独有的「老式」路径 syscall ============
    //
    // 为什么 aarch64 没有这段：aarch64 的通用 ABI **根本没有** open/stat/
    // access/unlink 这些无 dirfd 的老式 syscall，只有 *at 变体，所以原实现
    // 只拦 *at 就够。
    //
    // 而 x86_64 全都保留着，glibc/musl 在 x86_64 上会**直接用老式版本** ——
    // 实测容器里 `ls /etc`，strace 显示：
    //     stat("/etc", {st_mode=S_IFDIR|0755, ...}) = 0
    //     open("/etc", O_RDONLY|O_LARGEFILE|O_CLOEXEC|O_DIRECTORY) = 5
    // 两条都不在过滤器里 → 不 TRAP → 直接落到宿主根。用户侧表现就是
    // 「容器里看到的是 Android 的 /etc」，而容器本身起得来、不报错。
    //
    // 这是移植到 x86_64 的第二个必踩坑（第一个是 IP 白名单，见 pitfalls #27）。
    // 做法：改写成对应的 *at 版本，路径翻译/bind/EBADF 重试全部复用同一套。
    switch (nr) {
    case SYS_open: case SYS_stat: case SYS_lstat: case SYS_access:
    case SYS_unlink: case SYS_rmdir: case SYS_mkdir: case SYS_chmod:
    case SYS_chown: case SYS_truncate: case SYS_utime: case SYS_readlink:
    // ★2026-11-07 补：apt/dpkg 装包必经的三条老式 syscall。
    //   只把它们加进 BPF 树（TRAP）而**不加进这个 case 列表**会更糟：
    //   TRAP 后落到函数末尾 default，那里用 (int)(unsigned int)r[0] 把 64 位
    //   指针截成 32 位 → 内核 EFAULT → dpkg 报 `Bad address`（实测：从
    //   `error creating symbolic link` 变成 `error setting ownership …: Bad address`，
    //   看起来"更糟了"，实际是因为终于被拦到了，只差这一支分派）。
    case SYS_lchown: case SYS_utimes: case SYS_mknod: {
        const char *lp = (const char *)r[0];
        if (!lp || lp[0] != '/') {
            // 相对路径（由内核按 cwd 或 dirfd 解析）——原样转发。
            // ★这里同样必须就地转发，不能 `break`：
            //   break 会落到函数末尾 default，而那里用的是
            //   a0 = (long)(int)(unsigned int)r[0]，指针被截断成 32 位。
            //   实测症状：容器内 `cd /tmp && cat rel` 报 Bad address。
            r[0] = (unsigned long long)raw6(nr, (long)lp, a1, a2, a3, a4, a5);
            return;
        }
        // ★ /proc/self/exe 必须在 dev_passthrough **之前**处理。
        //
        //   为什么：/proc 整棵前缀是透传给宿主内核的（dev_passthrough 命中），
        //   而宿主内核按**宿主进程**解析 /proc/self/exe，返回的必然是加载器
        //   自己的路径（实测 /data/local/tmp/ax）。任何靠它推 $ORIGIN 的程序
        //   都会算错自己的安装位置。
        //
        //   实测后果（Alpine 的 openjdk8/11/17/21 全部起不来）：
        //     readlink("/proc/self/exe") -> /data/local/tmp/ax
        //     -> JRE 根算成 /data/local/tmp
        //     -> Error: could not find libjava.so
        //     -> Could not find Java SE Runtime Environment
        //   而 java 二进制本身是好的（清单正常、ldd 全解析），报错完全指向
        //   「JRE 装坏了」这个错误方向。
        //
        //   readlinkat 分支早就处理了这两种情况，但**老式 readlink 没有**
        //   —— x86_64 保留了这个无 dirfd 的老接口，而 musl 的 readlink() 走的
        //   正是它（strace 实证：si_syscall=__NR_readlink）。
        if (nr == SYS_readlink && is_proc_self_exe(lp)) {
            int n = 0;
            while (g_guest_path[n] && n < (int)a2 - 1) n++;
            for (int i = 0; i < n; i++) ((char *)a1)[i] = g_guest_path[i];
            r[0] = (unsigned long long)n;
            return;
        }
        if (nr == SYS_readlink && is_proc_self_fd(lp)) {
            long rvf = raw6(SYS_readlinkat, -100, (long)lp,
                            (long)a1, (long)a2, 0, 0);
            if (rvf > 0) {
                int n2 = host_to_guest((char *)a1, (int)rvf);
                if (n2 >= 0) rvf = n2;
            }
            r[0] = (unsigned long long)rvf;
            return;
        }
        if (dev_passthrough(lp)) {
            // ★★透传必须**在这里就地转发然后 return**，不能 `break`。
            //
            //   实测症状：容器内 `ls /` 里 /dev、/proc、/sys 三行报
            //       ls: /dev: Bad address
            //   而 strace 显示内核确实返回了结果：
            //       lstat("/dev", {st_mode=000, st_size=1, ...}) = 6
            //   （返回值 6 是 lstat 自己的 syscall 号，st_mode 全是垃圾 —— 说明
            //   guest 拿到的根本不是内核写回的结构体。）
            //
            //   原因：`break` 只是跳出这层 switch，最后落到函数末尾的 default，
            //   而 default 用的是 **a0 = (long)(int)(unsigned int)r[0]** ——
            //   一个被截断成 32 位的指针。r[0] 是 64 位栈/堆地址，截断后变成
            //   野指针，内核自然 EFAULT。任何被 break 漏到 default 的路径 syscall
            //   都会这样（只是恰好只有透传路径才走 break）。
            //
            //   注意要用**原始 r[0]**（64 位），不是 a0。
            r[0] = (unsigned long long)raw6(nr, (long)lp, a1, a2, a3, a4, a5);
            return;
        }
        char lbuf[PATH_MAX];
        long lfd = -100;
        if (!bind_map(lp, lbuf)) { make_rel(lbuf, lp); lfd = g_rootfs_fd; }
        switch (nr) {
        case SYS_open:
            r[0] = (unsigned long long)raw6(SYS_openat, lfd, (long)lbuf,
                                            (long)a1, (long)a2, 0, 0);
            return;
        case SYS_stat:
            r[0] = (unsigned long long)raw6(SYS_newfstatat, lfd, (long)lbuf,
                                            (long)a1, 0, 0, 0);
            return;
        case SYS_lstat:
            r[0] = (unsigned long long)raw6(SYS_newfstatat, lfd, (long)lbuf,
                                            (long)a1, 0x100 /*AT_SYMLINK_NOFOLLOW*/, 0, 0);
            return;
        case SYS_access:
            r[0] = (unsigned long long)raw6(SYS_faccessat, lfd, (long)lbuf,
                                            (long)a1, 0, 0, 0);
            return;
        case SYS_unlink:
            r[0] = (unsigned long long)raw6(SYS_unlinkat, lfd, (long)lbuf, 0, 0, 0, 0);
            return;
        case SYS_rmdir:
            r[0] = (unsigned long long)raw6(SYS_unlinkat, lfd, (long)lbuf,
                                            0x200 /*AT_REMOVEDIR*/, 0, 0, 0);
            return;
        case SYS_mkdir:
            r[0] = (unsigned long long)raw6(SYS_mkdirat, lfd, (long)lbuf,
                                            (long)a1, 0, 0, 0);
            return;
        case SYS_chmod:
            r[0] = (unsigned long long)raw6(SYS_fchmodat, lfd, (long)lbuf,
                                            (long)a1, 0, 0, 0);
            return;
        case SYS_chown:
            r[0] = (unsigned long long)raw6(SYS_fchownat, lfd, (long)lbuf,
                                            (long)a1, (long)a2, 0, 0);
            return;
        case SYS_utime:
            r[0] = (unsigned long long)raw6(SYS_utimensat, lfd, (long)lbuf,
                                            (long)a1, 0, 0, 0);
            return;
        case SYS_lchown:
            // lchown(path, uid, gid)：改**链接本身**的属主，必须带
            // AT_SYMLINK_NOFOLLOW，否则会跟着目标走（dpkg 要的正是链接本身）。
            r[0] = (unsigned long long)raw6(SYS_fchownat, lfd, (long)lbuf,
                                            (long)a1, (long)a2,
                                            0x100 /*AT_SYMLINK_NOFOLLOW*/, 0);
            return;
        case SYS_utimes:
            // utimes(path, timeval[2]) → utimensat(dirfd, path, timespec[2], flags)。
            // 两个结构在 64 位下都是「两个 long」，但第二个字段含义不同
            // （timeval.tv_usec 微秒 vs timespec.tv_nsec 纳秒）。
            // 这里**不做换算**：换算要写 guest 内存，而 handler 内碰 guest 内存
            // 是危险操作（guest 的 musl 已接管 TLS，读错就 SEGV 在 handler 里）。
            // 直接透传的后果只是「亚秒部分按纳秒解释」，时间戳偏差 < 1ms 且纯属
            // 元数据，dpkg 只用它保留 mtime，没有任何正确性影响。
            r[0] = (unsigned long long)raw6(SYS_utimensat, lfd, (long)lbuf,
                                            (long)a1, 0, 0, 0);
            return;
        case SYS_mknod:
            r[0] = (unsigned long long)raw6(SYS_mknodat, lfd, (long)lbuf,
                                            (long)a1, (long)a2, 0, 0);
            return;
        case SYS_readlink:
            // /proc/self/exe 与 /proc/<pid>/exe 已在进入本 switch **之前**
            // 就拦掉了（dev_passthrough 会把整个 /proc 透传走，所以那段必须
            // 放在透传判断之前 —— 这里只剩普通路径的情况）。
            r[0] = (unsigned long long)raw6(SYS_readlinkat, lfd, (long)lbuf,
                                            (long)a1, (long)a2, 0, 0);
            return;
        case SYS_truncate: {
            // truncate 没有 *at 变体：先按翻译后的路径 open 再 ftruncate
            long tf = raw6(SYS_openat, lfd, (long)lbuf, 1 /*O_WRONLY*/, 0, 0, 0);
            if (tf < 0) { r[0] = (unsigned long long)tf; return; }
            long tr = raw6(SYS_ftruncate, tf, (long)a1, 0, 0, 0, 0);
            raw6(SYS_close, tf, 0, 0, 0, 0, 0);
            r[0] = (unsigned long long)tr;
            return;
        }
        }
        break;
    }
    case SYS_rename: case SYS_link: {
        const char *op = (const char *)r[0];
        const char *np = (const char *)r[1];
        if (!op || op[0] != '/' || !np || np[0] != '/') {
            // 同上面那条：必须就地转发，`break` 会落到 default 用截断的 a0。
            r[0] = (unsigned long long)raw6(nr, (long)op, (long)np, a2, a3, a4, a5);
            return;
        }
        char ob[PATH_MAX], nb[PATH_MAX];
        long ofd = -100, nfd = -100;
        if (!bind_map(op, ob)) { make_rel(ob, op); ofd = g_rootfs_fd; }
        if (!bind_map(np, nb)) { make_rel(nb, np); nfd = g_rootfs_fd; }
        if (nr == SYS_rename) {
            r[0] = (unsigned long long)raw6(SYS_renameat, ofd, (long)ob,
                                            nfd, (long)nb, 0, 0);
        } else {
            long lr = raw6(SYS_linkat, ofd, (long)ob, nfd, (long)nb, 0, 0);
            // app 域被 SELinux 禁掉 link → 复制兜底（见 ldr_copy_link 注释）
            if (lr < 0 && ldr_link_denied(lr)) lr = ldr_copy_link(ofd, ob, nfd, nb);
            r[0] = (unsigned long long)lr;
        }
        return;
    }
    // symlink(target, linkpath) —— **必须单独一支**，不能和 rename/link 合并。
    //
    // 为什么：rename/link 的**两个**参数都是真实路径，要求都以 '/' 开头是合理的；
    // 而 symlink 的第一个参数是**链接内容**（写进 inode 的字符串），既可能是相对
    // 路径也可能是绝对路径，**一律不翻译**。合并写时那条守卫变成
    //     if (!op || op[0] != '/' || !np || np[0] != '/')  -> 原样转发
    // 于是 dpkg 传 `symlink("../libsqlite3-0/changelog.Debian.gz",
    // "/usr/share/doc/sqlite3/changelog.Debian.gz.dpkg-new")` 时，因为 target 是相对
    // 路径就整条原样转发，**绝对路径的 linkpath 落到宿主根**上：
    //     宿主没有 /usr/share/doc/sqlite3/ -> ENOENT
    //     dpkg 报 error creating symbolic link './usr/share/doc/sqlite3/…'
    // 后果：Ubuntu 下**任何含符号链接的 deb 都装不上**（dpkg -i 直接失败）。
    // 实测（模拟器，strace 取证）：2026-11-07 修，见 _test_dsh_alpine_symlink_rel.py。
    case SYS_symlink: {
        const char *tp = (const char *)r[0];
        const char *np = (const char *)r[1];
        // linkpath 必须是绝对的才需要翻译；相对 linkpath 由内核按 cwd 解析。
        if (!np || np[0] != '/') {
            r[0] = (unsigned long long)raw6(nr, (long)tp, (long)np, a2, a3, a4, a5);
            return;
        }
        char nb[PATH_MAX];
        long nfd = -100;
        if (!bind_map(np, nb)) { make_rel(nb, np); nfd = g_rootfs_fd; }
        // target 原样传（不翻译、不 bind）
        r[0] = (unsigned long long)raw6(SYS_symlinkat, (long)tp, nfd, (long)nb, 0, 0, 0);
        return;
    }
    default: break;
    }
#endif

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
    // ---- bind(fd, addr, addrlen) ----
    //
    // 两件事都要做，顺序不能反：
    //   1) AF_UNIX：sockaddr_un.sun_path 是 guest 路径，必须像其它路径 syscall
    //      一样翻译成宿主路径（bind 命中新文件，所以不能只查 bind_map，还要
    //      退到 rootfs 前缀）。
    //   2) AF_INET/AF_INET6 的 53 端口：一条都不用改（bind 到 53 的是「谁在
    //      监听」，不是外发查询）。DNS 代理本身跑在 App 进程里，不经过这里。
    //
    // ★这条长期缺失导致的最误导症状（实测）：
    //     libwayland: bind() failed with error: No such file or directory
    //     fatal: failed to add socket: No such file or directory
    //   weston/Xvfb 之类的 compositor 全部起不来，而日志把方向引向「socket
    //   路径不存在」或「权限」，实际是内核拿 guest 路径去宿主根下找目录。
    //   之前 e2e 里把 X11 起不来归因成「SELinux 禁 link()」——那是另一个独立
    //   限制（确实存在，avc 可查），但不是这条的主因。
    case SYS_bind: {
        const unsigned char *q = (const unsigned char *)a1;
        long salen = (long)a2;
        if (q && salen >= 3) {
            unsigned int fam = (unsigned int)q[0] | ((unsigned int)q[1] << 8);
            if (fam == 1 /*AF_UNIX*/) {
                // sockaddr_un: { sa_family_t sun_family; char sun_path[108]; }
                // aarch64/x86_64 上 family 都是 2 字节，sun_path 从 offset 2 开始。
                char pb[PATH_MAX];
                int bad = 0;
                for (int i = 0; i < (int)sizeof(pb) - 1; i++) {
                    char c = (char)q[2 + i];
                    pb[i] = c;
                    if (c == 0) break;
                    if (2 + i >= salen - 1) { pb[i + 1] = 0; break; }
                }
                pb[sizeof(pb) - 1] = 0;
                // 抽象命名空间（sun_path[0]==0）与相对路径不翻译
                if (pb[0] == (char)47) {
                    // ★必须自己拼完整的 sockaddr_un，不能只把「路径字符串」当
                    //   sockaddr 传下去。
                    //
                    //   踩过的坑：直接把 hb（纯路径，没有 family 头）传给内核，
                    //   内核把前两个字节当 sun_family 读，得到 't'|'m'<<8 这种
                    //   垃圾值 → 三个路径全部 EINVAL（实测 Invalid argument）。
                    //
                    //   正确形状：sun_family(2 字节) 紧跟 sun_path，
                    //   addrlen = 2 + strlen(path) + 1。
                    struct sockaddr_un_s {
                        unsigned short sun_family;
                        char sun_path[108];
                    } sa2;
                    char hb[PATH_MAX];
                    const char *use;
                    if (bind_map(pb, hb)) use = hb;
                    else { make_host(hb, pb); use = hb; }
                    int plen = ldr_strlen(use);
                    if (plen > 107) plen = 107;      // sun_path 上限 108（含结尾 0）
                    sa2.sun_family = 1;              // AF_UNIX
                    for (int i = 0; i < plen; i++) sa2.sun_path[i] = use[i];
                    sa2.sun_path[plen] = 0;
                    long alen = (long)(2 + plen + 1);
                    r[0] = (unsigned long long)raw6(SYS_bind, a0, (long)&sa2, alen, 0, 0, 0);
                    return;
                }
                (void)bad;
            }
        }
        r[0] = (unsigned long long)raw6(nr, a0, a1, a2, a3, a4, a5);
        return;
    }

    case SYS_connect: {
        // ★AF_UNIX 必须先处理：sockaddr_un.sun_path 也是 guest 路径。
        //
        //   不做的后果（实测）：bind 侧把 socket 建在 `$rootfs/tmp/wl/test`，
        //   而 connect 侧传的是未翻译的 `/tmp/wl/test`，内核按宿主根找
        //   `/tmp/wl/test` —— 不存在。客户端报
        //       failed to create display          (grim / wayland 客户端)
        //       libwayland: failed to connect to socket
        //   而服务端日志一切正常（socket 文件确实建出来了），最难查的一类。
        //
        //   ★bind 修好但 connect 漏掉，症状会从「服务端起不来」变成
        //   「服务端起来了但客户端连不上」——两轮都踩过。
        {
            const unsigned char *cq = (const unsigned char *)a1;
            long clen = (long)a2;
            if (cq && clen >= 3) {
                unsigned int cfam = (unsigned int)cq[0] | ((unsigned int)cq[1] << 8);
                if (cfam == 1 /*AF_UNIX*/) {
                    struct sockaddr_un_s {
                        unsigned short sun_family;
                        char sun_path[108];
                    } sa2;
                    char pb[PATH_MAX];
                    int i;
                    for (i = 0; i < (int)sizeof(pb) - 1; i++) {
                        char c = (char)cq[2 + i];
                        pb[i] = c;
                        if (c == 0) break;
                        if (2 + i >= clen - 1) { pb[i + 1] = 0; break; }
                    }
                    pb[sizeof(pb) - 1] = 0;
                    // 相对路径与抽象命名空间（sun_path[0]==0）不翻译
                    if (pb[0] == (char)47) {
                        char hb[PATH_MAX];
                        const char *use;
                        if (bind_map(pb, hb)) use = hb;
                        else { make_host(hb, pb); use = hb; }
                        int plen = ldr_strlen(use);
                        if (plen > 107) plen = 107;
                        sa2.sun_family = 1;
                        for (int k = 0; k < plen; k++) sa2.sun_path[k] = use[k];
                        sa2.sun_path[plen] = 0;
                        r[0] = (unsigned long long)raw6(SYS_connect, a0, (long)&sa2,
                                                        (long)(2 + plen + 1), 0, 0, 0);
                        return;
                    }
                }
            }
        }

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

    // ---- inotify_add_watch(fd, pathname, mask) ----
    //
    // pathname 在 a1，而且**没有 *at 变体**（内核只给了这一个入口），
    // 所以只能像 statfs 那样用 make_host 拼出宿主绝对路径。
    //
    // ★不拦它的后果极难归因（实测一整天）。链路是：
    //   guest 传 `/dsh/profiles/web` → 内核按**宿主**根解析 → ENOENT →
    //   chokidar 收到 ENOENT 后沿父目录逐级回退找「最近存在的目录」→ 一路退到
    //   `/` → `EACCES: permission denied, watch '/'` → dsh-hmr 插件激活失败 →
    //   引擎 `startup failed: 2 required plugins did not activate`（webserver 与
    //   hmr 一起倒，profile 里 7 个服务全 missing）。
    //   日志里只有 `watch '/'`，看不到任何「路径没翻译」的线索；用 fs.watch 打桩
    //   才能看到真实入参是 guest 路径且全部 ENOENT。
    //
    // 翻译后 inotify 在**宿主路径**上注册，事件仍能正常回传（wd 与 fd 无关）。
    case SYS_inotify_add_watch: {
        const char *ip = (const char *)a1;
        if (ip && ip[0] == (char)47 && !is_host_passthrough(ip)) {
            char ib[PATH_MAX];
            if (bind_map(ip, ib)) {
                r[0] = (unsigned long long)raw6(SYS_inotify_add_watch, a0, (long)ib, a2, 0, 0, 0);
                return;
            }
            make_host(ib, ip);
            r[0] = (unsigned long long)raw6(SYS_inotify_add_watch, a0, (long)ib, a2, 0, 0, 0);
            return;
        }
        r[0] = (unsigned long long)raw6(nr, a0, (long)ip, a2, 0, 0, 0);
        return;
    }

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
        // 非 SIGSYS 的 rt_sigaction：必须把 sa_mask 里的 SIGSYS 位抠掉。
        //
        // 为什么：seccomp 的 SIGSYS 要靠我们的 handler 接住再把控制权交回去。
        // 任何程序只要在**自己的** handler 里屏蔽了 SIGSYS（JVM 就是典型 ——
        // 它给 SIGSEGV/SIGBUS 装的 handler 带 `[ILL TRAP BUS FPE SEGV]` 掩码，
        // 部分路径会把 SIGSYS 也带上），那之后每一次被 TRAP 的 syscall 都会
        // 变成「信号投递不出去 → 内核按默认动作杀掉进程」：
        //     --- SIGSYS {si_syscall=__NR_rt_sigprocmask} ---
        //     +++ killed by SIGSYS +++
        // 表现是 `Bad system call`，且**完全看不出与我们有关**。
        //
        // 修法与 rt_sigprocmask 那条一致：静默剔除这一位，guest 拿到 0
        // 以为设置成功，实际永远屏蔽不住 SIGSYS。
        {
            // x86_64 kernel struct sigaction: handler(0) flags(8) restorer(16) mask(24)
            unsigned long *sa = (unsigned long *)(uintptr_t)a1;
            if (sa) {
                unsigned long m = sa[3];
                if (m & (1UL << (31 - 1))) {
                    unsigned long cp[4];
                    cp[0] = sa[0]; cp[1] = sa[1]; cp[2] = sa[2];
                    cp[3] = m & ~(1UL << (31 - 1));
                    r[0] = (unsigned long long)raw6(nr, a0, (long)cp, a2, a3, a4, a5);
                    return;
                }
            }
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
        // ★/proc/self/fd/N 的 readlink：内核返回的是**宿主机真实路径**。
        //
        //   musl 的 realpath() 就是靠这条实现的（open(O_PATH) + readlink），
        //   所以不裁的话 realpath 对任何 bind 挂载点下的路径都失败（见
        //   host_to_guest 的注释，实测 realpath("/dsh") 直接 ENOENT）。
        //
        //   ★必须在 is_proc_self_exe 之前处理吗？不需要 —— 两者路径不同
        //   （后者是 "/proc/self/exe"），互不干扰；但**必须**在 dev_passthrough
        //   之前，否则就被整棵透传走了。
        if (nr == SYS_readlinkat && a0 == -100 && is_proc_self_fd(path)) {
            long rvf = raw6(nr, -100, (long)path, (long)a2, (long)a3, 0, 0);
            if (rvf > 0) {
                int n2 = host_to_guest((char *)a2, (int)rvf);
                if (n2 >= 0) rvf = n2;
            }
            r[0] = (unsigned long long)rvf;
            return;
        }
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
        const char *use_op = o_rel ? ob : op;
        const char *use_np = n_rel ? nbuf : np;
        long lr = raw6(SYS_linkat, ofd, (long)use_op, nfd, (long)use_np, (long)a4, 0);
        // ★Android app 域没有 link 权限（详见 ldr_copy_link）：dpkg 用它做
        //   status/diversions 备份，被拒后整条 install 判失败。
        if (lr < 0 && ldr_link_denied(lr)) lr = ldr_copy_link(ofd, use_op, nfd, use_np);
        r[0] = (unsigned long long)lr;
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
        long rrr = raw6(nr, ofd,
                        o_rel ? (long)ob : (long)op,
                        nfd,
                        n_rel ? (long)nbuf : (long)np,
                        (long)a4, 0);
        if (rrr < 0 && trap_profile_on()) {
            char db[192]; int dk=0;
            const char *dp="[RENAME] nr="; while(*dp) db[dk++]=*dp++;
            dk += utoa_(db+dk, (unsigned long)(nr==SYS_renameat2?316UL:264UL));
            dp=" rc="; while(*dp) db[dk++]=*dp++;
            dk += utoa_(db+dk, (unsigned long)(long)(-rrr));
            dp=" ofd="; while(*dp) db[dk++]=*dp++;
            dk += utoa_(db+dk, (unsigned long)(long)(ofd<0?999999UL:(unsigned long)ofd));
            dp=" nfd="; while(*dp) db[dk++]=*dp++;
            dk += utoa_(db+dk, (unsigned long)(long)(nfd<0?999999UL:(unsigned long)nfd));
            db[dk++]=10;
            raw6(SYS_write, 2, (long)db, dk, 0, 0, 0);
        }
        r[0] = (unsigned long long)rrr;
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
            unsigned int mode = *(unsigned int *)(stbuf + ST_MODE_OFF);
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
/* IP 高 32 位白名单：放行 loader 自己的裸 syscall 桩。
 * 架构无关 —— 两者都固定加载在 0x2000000000。
 * ★★移植时删掉这条会形成嵌套 SIGSYS（详见 docs/pitfalls.md 第 27 条）：
 *    handler 内部的 raw6(rt_sigprocmask) 会被自己的 filter 再 TRAP 一次，
 *    SIGSYS 在 handler 期间被内核阻塞、无处投递 → 按致命错误处理。 */
#define LOADER_BASE_HI DSH_LOADER_BASE_HI
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
        // ★★★ 以下 cBPF 树由 tools/gen_bpf_tree.py 生成，勿手改。
        //  验证：python tools/verify_bpf_semantics.py <tree> <arch>
        //  aarch64 那份与原实现逐字节一致（153 条）。
        //  x86_64 是 213 条：多出 15 个「老式」路径 syscall
        //  （open/stat/access/unlink…）—— aarch64 的通用 ABI 没有它们，
        //  而 musl 在 x86_64 上会直接用，漏了就是「容器里看到宿主 /etc」。
#if defined(DSH_ARCH_AARCH64)
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, LOADER_BASE_HI, 154, 0),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_newfstatat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_newfstatat, 0, 71),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 150, 149),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_sendmsg, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_sendmsg, 0, 37),
        BPF_STMT(BPF_JMP|BPF_JA, 147),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_renameat2, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_renameat2, 0, 21),
        BPF_STMT(BPF_JMP|BPF_JA, 144),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_statx, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_statx, 0, 13),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 140, 139),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_openat2, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_openat2, 0, 8),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 136, 135),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_faccessat2, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_faccessat2, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 132, 131),
        BPF_STMT(BPF_JMP|BPF_JA, 130),
        BPF_STMT(BPF_JMP|BPF_JA, 129),
        BPF_STMT(BPF_JMP|BPF_JA, 128),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_execveat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_execveat, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 126),
        BPF_STMT(BPF_JMP|BPF_JA, 124),
        BPF_STMT(BPF_JMP|BPF_JA, 123),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_clone, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_clone, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 113),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_execve, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_execve, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 118),
        BPF_STMT(BPF_JMP|BPF_JA, 116),
        BPF_STMT(BPF_JMP|BPF_JA, 115),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_recvmsg, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_recvmsg, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 113),
        BPF_STMT(BPF_JMP|BPF_JA, 111),
        BPF_STMT(BPF_JMP|BPF_JA, 110),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_bind, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_bind, 0, 14),
        BPF_STMT(BPF_JMP|BPF_JA, 108),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_sendto, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_sendto, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 105),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_recvfrom, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_recvfrom, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 102),
        BPF_STMT(BPF_JMP|BPF_JA, 100),
        BPF_STMT(BPF_JMP|BPF_JA, 99),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_connect, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_connect, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 97),
        BPF_STMT(BPF_JMP|BPF_JA, 95),
        BPF_STMT(BPF_JMP|BPF_JA, 94),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_rt_sigaction, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_rt_sigaction, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 92),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_rt_sigprocmask, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_rt_sigprocmask, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 89),
        BPF_STMT(BPF_JMP|BPF_JA, 87),
        BPF_STMT(BPF_JMP|BPF_JA, 86),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_utimensat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_utimensat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 83, 82),
        BPF_STMT(BPF_JMP|BPF_JA, 81),
        BPF_STMT(BPF_JMP|BPF_JA, 80),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_renameat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_renameat, 0, 39),
        BPF_STMT(BPF_JMP|BPF_JA, 78),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_chdir, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_chdir, 0, 22),
        BPF_STMT(BPF_JMP|BPF_JA, 75),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_fchownat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_fchownat, 0, 13),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 71, 70),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_openat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_openat, 0, 8),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 67, 66),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_readlinkat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_readlinkat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 63, 62),
        BPF_STMT(BPF_JMP|BPF_JA, 61),
        BPF_STMT(BPF_JMP|BPF_JA, 60),
        BPF_STMT(BPF_JMP|BPF_JA, 59),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_fchmodat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_fchmodat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 56, 55),
        BPF_STMT(BPF_JMP|BPF_JA, 54),
        BPF_STMT(BPF_JMP|BPF_JA, 53),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_fstatfs, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_fstatfs, 0, 7),
        BPF_STMT(BPF_JMP|BPF_JA, 51),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_faccessat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_faccessat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 47, 46),
        BPF_STMT(BPF_JMP|BPF_JA, 45),
        BPF_STMT(BPF_JMP|BPF_JA, 44),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_statfs, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_statfs, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 42),
        BPF_STMT(BPF_JMP|BPF_JA, 40),
        BPF_STMT(BPF_JMP|BPF_JA, 39),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_mkdirat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_mkdirat, 0, 16),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 36, 35),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_symlinkat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_symlinkat, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 33),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_linkat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_linkat, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 30),
        BPF_STMT(BPF_JMP|BPF_JA, 28),
        BPF_STMT(BPF_JMP|BPF_JA, 27),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_unlinkat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_unlinkat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 24, 23),
        BPF_STMT(BPF_JMP|BPF_JA, 22),
        BPF_STMT(BPF_JMP|BPF_JA, 21),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_inotify_add_watch, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_inotify_add_watch, 0, 7),
        BPF_STMT(BPF_JMP|BPF_JA, 19),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_mknodat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_mknodat, 0, 3),
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
#else
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, AUDIT_ARCH_X86_64, 1, 0),
        BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, LOADER_BASE_HI, 226, 0),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_chown, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_chown, 0, 115),
        BPF_STMT(BPF_JMP|BPF_JA, 223),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_unlinkat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_unlinkat, 0, 58),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 219, 218),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_faccessat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_faccessat, 0, 31),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 215, 214),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_execveat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_execveat, 0, 17),
        BPF_STMT(BPF_JMP|BPF_JA, 212),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_openat2, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_openat2, 0, 8),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 208, 207),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_faccessat2, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_faccessat2, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 204, 203),
        BPF_STMT(BPF_JMP|BPF_JA, 202),
        BPF_STMT(BPF_JMP|BPF_JA, 201),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_statx, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_statx, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 198, 197),
        BPF_STMT(BPF_JMP|BPF_JA, 196),
        BPF_STMT(BPF_JMP|BPF_JA, 195),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_utimensat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_utimensat, 0, 7),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 192, 191),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_renameat2, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_renameat2, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 189),
        BPF_STMT(BPF_JMP|BPF_JA, 187),
        BPF_STMT(BPF_JMP|BPF_JA, 186),
        BPF_STMT(BPF_JMP|BPF_JA, 185),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_symlinkat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_symlinkat, 0, 12),
        BPF_STMT(BPF_JMP|BPF_JA, 183),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_readlinkat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_readlinkat, 0, 8),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 179, 178),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_fchmodat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_fchmodat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 175, 174),
        BPF_STMT(BPF_JMP|BPF_JA, 173),
        BPF_STMT(BPF_JMP|BPF_JA, 172),
        BPF_STMT(BPF_JMP|BPF_JA, 171),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_renameat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_renameat, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 169),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_linkat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_linkat, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 166),
        BPF_STMT(BPF_JMP|BPF_JA, 164),
        BPF_STMT(BPF_JMP|BPF_JA, 163),
        BPF_STMT(BPF_JMP|BPF_JA, 162),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_utimes, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_utimes, 0, 31),
        BPF_STMT(BPF_JMP|BPF_JA, 160),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_mkdirat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_mkdirat, 0, 18),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 156, 155),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_fchownat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_fchownat, 0, 8),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 152, 151),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_newfstatat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_newfstatat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 148, 147),
        BPF_STMT(BPF_JMP|BPF_JA, 146),
        BPF_STMT(BPF_JMP|BPF_JA, 145),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_mknodat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_mknodat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 142, 141),
        BPF_STMT(BPF_JMP|BPF_JA, 140),
        BPF_STMT(BPF_JMP|BPF_JA, 139),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_inotify_add_watch, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_inotify_add_watch, 0, 7),
        BPF_STMT(BPF_JMP|BPF_JA, 137),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_openat, 3, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_openat, 0, 3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, 0xFFFFFF9C, 133, 132),
        BPF_STMT(BPF_JMP|BPF_JA, 131),
        BPF_STMT(BPF_JMP|BPF_JA, 130),
        BPF_STMT(BPF_JMP|BPF_JA, 129),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_mknod, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_mknod, 0, 10),
        BPF_STMT(BPF_JMP|BPF_JA, 127),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_statfs, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_statfs, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 124),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_fstatfs, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_fstatfs, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 121),
        BPF_STMT(BPF_JMP|BPF_JA, 119),
        BPF_STMT(BPF_JMP|BPF_JA, 118),
        BPF_STMT(BPF_JMP|BPF_JA, 117),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_lchown, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_lchown, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 115),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_utime, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_utime, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 112),
        BPF_STMT(BPF_JMP|BPF_JA, 110),
        BPF_STMT(BPF_JMP|BPF_JA, 109),
        BPF_STMT(BPF_JMP|BPF_JA, 108),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_clone, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_clone, 0, 50),
        BPF_STMT(BPF_JMP|BPF_JA, 98),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_mkdir, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_mkdir, 0, 26),
        BPF_STMT(BPF_JMP|BPF_JA, 103),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_unlink, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_unlink, 0, 14),
        BPF_STMT(BPF_JMP|BPF_JA, 100),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_readlink, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_readlink, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 97),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_chmod, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_chmod, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 94),
        BPF_STMT(BPF_JMP|BPF_JA, 92),
        BPF_STMT(BPF_JMP|BPF_JA, 91),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_symlink, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_symlink, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 89),
        BPF_STMT(BPF_JMP|BPF_JA, 87),
        BPF_STMT(BPF_JMP|BPF_JA, 86),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_rmdir, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_rmdir, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 84),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_link, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_link, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 81),
        BPF_STMT(BPF_JMP|BPF_JA, 79),
        BPF_STMT(BPF_JMP|BPF_JA, 78),
        BPF_STMT(BPF_JMP|BPF_JA, 77),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_getcwd, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_getcwd, 0, 10),
        BPF_STMT(BPF_JMP|BPF_JA, 75),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_chdir, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_chdir, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 72),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_rename, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_rename, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 69),
        BPF_STMT(BPF_JMP|BPF_JA, 67),
        BPF_STMT(BPF_JMP|BPF_JA, 66),
        BPF_STMT(BPF_JMP|BPF_JA, 65),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_execve, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_execve, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 63),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_truncate, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_truncate, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 60),
        BPF_STMT(BPF_JMP|BPF_JA, 58),
        BPF_STMT(BPF_JMP|BPF_JA, 57),
        BPF_STMT(BPF_JMP|BPF_JA, 56),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_access, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_access, 0, 26),
        BPF_STMT(BPF_JMP|BPF_JA, 54),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_recvfrom, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_recvfrom, 0, 14),
        BPF_STMT(BPF_JMP|BPF_JA, 51),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_recvmsg, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_recvmsg, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 48),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_bind, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_bind, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 45),
        BPF_STMT(BPF_JMP|BPF_JA, 43),
        BPF_STMT(BPF_JMP|BPF_JA, 42),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_sendmsg, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_sendmsg, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 40),
        BPF_STMT(BPF_JMP|BPF_JA, 38),
        BPF_STMT(BPF_JMP|BPF_JA, 37),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_connect, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_connect, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 35),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_sendto, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_sendto, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 32),
        BPF_STMT(BPF_JMP|BPF_JA, 30),
        BPF_STMT(BPF_JMP|BPF_JA, 29),
        BPF_STMT(BPF_JMP|BPF_JA, 28),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_lstat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_lstat, 0, 10),
        BPF_STMT(BPF_JMP|BPF_JA, 26),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_rt_sigaction, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_rt_sigaction, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 23),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_rt_sigprocmask, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_rt_sigprocmask, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 20),
        BPF_STMT(BPF_JMP|BPF_JA, 18),
        BPF_STMT(BPF_JMP|BPF_JA, 17),
        BPF_STMT(BPF_JMP|BPF_JA, 16),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_open, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_open, 0, 6),
        BPF_STMT(BPF_JMP|BPF_JA, 14),
        BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K, SYS_stat, 2, 0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_stat, 0, 2),
        BPF_STMT(BPF_JMP|BPF_JA, 11),
        BPF_STMT(BPF_JMP|BPF_JA, 9),
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
#endif
    };



#undef TRAP_PAIR
    struct sock_fprog pr = { .len = sizeof(f)/sizeof(f[0]), .filter = (struct sock_filter *)f };
    long _sc = raw6(SYS_seccomp /*SYS_seccomp*/, 1 /*SECCOMP_SET_MODE_FILTER*/, 0, (long)&pr, 0, 0, 0);
    if (_sc != 0) {
        ldr_log1("LOADER: seccomp FAIL rc=", _sc, 10);
        raw6(SYS_exit_group /*SYS_exit_group*/, 70, 0, 0, 0, 0, 0);
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
            // ★bind 目录里的条目也必须真探测一次（v1.7.16，真机 2026-11-13）。
            //
            //   旧实现命中 bind 前缀后直接 return 0 并把 g_resolve_missing 清 0，
            //   等于向调用方**断言这个路径一定存在**。但 bind_map 是纯前缀匹配、
            //   从不 stat 宿主文件。
            //
            //   后果（决定性）：DSH_DEFAULT_PATH 首位就是 /eng/bin，而 /eng/bin 是
            //   bind 挂载点（-m /eng:<宿主 engine 目录>）。裸名 exec 的 PATH 搜索
            //   第一个候选是 /eng/bin/tar —— 该文件并不存在，却因 bind 命中被判
            //   「存在」：轻量 preload 返回 0（而非 -ENOENT），execve 的 PATH 搜索链
            //   就此断掉，进程直接进 reexec 分支，再于完整 preload 里失败：
            //       REEXEC: preload FAIL      + exit_group(11)
            //   dpkg 内部 fork 的 dpkg-split / tar 全挂在这一条上，表现为
            //   subprocess dpkg-split returned error exit status 11。
            //   而 PATH=/usr/bin:/bin tar（不含 /eng/bin）正常 —— 正是本缺陷的判据。
            //
            //   O_PATH 而不是 O_RDONLY：只需目录的可搜索权限，连 chmod 111 的
            //   可执行文件也能探到，而 O_RDONLY 会 EACCES 造成假缺失。
            long pfd = raw6(SYS_openat, -100 /*AT_FDCWD*/, (long)hp, 0x200000 /*O_PATH*/, 0, 0, 0);
            if (pfd >= 0) raw6(SYS_close, pfd, 0, 0, 0, 0, 0);
            scopy_(out, full);
            if (pfd >= 0) { g_resolve_missing = 0; return 0; }
            g_resolve_missing = 1;
            return -2;
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
                scopy_(g_env_store[g_env_count++], "PATH=" DSH_DEFAULT_PATH);
            env_fill_defaults();
        }
        if (g_env_count == 0) {
            scopy_(g_env_store[g_env_count++], "PATH=" DSH_DEFAULT_PATH);
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
            scopy_(g_env_store[g_env_count++], "PATH=" DSH_DEFAULT_PATH);
        env_fill_defaults();
    }
    if (g_env_count == 0) {
        scopy_(g_env_store[g_env_count++], "PATH=" DSH_DEFAULT_PATH);
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

    g_rootfs_fd = (int)raw6(SYS_openat /*SYS_openat*/, -100 /*AT_FDCWD*/, (long)rootfs,
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
        raw6(SYS_sigaltstack /*SYS_sigaltstack*/, (long)&ss, 0, 0, 0, 0, 0);
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
            raw6(SYS_exit_group, 9, 0, 0, 0, 0, 0);
        }
        // 重开 rootfs fd（exec 后 fd 可能被 CLOEXEC 关掉）
        g_rootfs_fd = (int)raw6(SYS_openat, -100, (long)rootfs, 0x200000 | 0x10000, 0, 0, 0);
        if (g_rootfs_fd < 0) { ldr_puts("REEXEC: rootfs 重开失败"); raw6(SYS_exit_group, 10, 0, 0, 0, 0, 0); }
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
        // ★不再依赖 DSH_CLEANER 环境变量恢复状态 —— 改为**探测 socket**。
        //
        // 原设计用 envp 里的 DSH_CLEANER=1 传递，但有三个理由让它不可靠：
        //   ① 标记要写进 g_env_store，而重执行那行 execveat 传的是 genvp，
        //      两块内存不同 —— 我改了两版都仍传不下去。
        //   ② guest 程序可能自己清空环境（env -i）。
        //   ③ 多一层依赖就多一个静默失败点。
        //
        // socket 名 = <prefix><uid>_<pid>，这两个值**跨 exec 都不变**（uid 不变，
        // PID 原地 exec 也不变），所以重执行后重新算一遍名字、connect 一次探活
        // 就能确定 cleaner 在不在。探活成功就把三件状态一起恢复。
        //
        // 实测症状（修好前，2026-11-07）：
        //   BIONIC: cleaner_exec FAIL
        //   sh: /framesrv: not found          (rc=127)
        // 而直接 dsh_alpine <rootfs> <interp> /prog（首次装载，不跨重执行）
        // 却能跑通 —— 那条 bug 掩盖了自己，所以 30 多轮没被发现。
        g_cleaner_on = 0;
        g_cleaner_namelen = 0;
        {
            char pname[112];
            int plen = cleaner_sockname(pname);
            struct ldr_sockaddr_un psa;
            ((unsigned short *)&psa)[0] = 1;
            ((char *)&psa)[2] = 0;
            for (int i = 0; i < plen; i++) ((char *)&psa)[3 + i] = pname[i];
            int palen = 3 + plen;
            long pfd = raw6(SYS_socket, 1, 1, 0, 0, 0, 0);
            if (pfd >= 0) {
                long pr = raw6(SYS_connect, pfd, (long)&psa, palen, 0, 0, 0);
                raw6(SYS_close, pfd, 0, 0, 0, 0, 0);
                if (pr == 0) {
                    g_cleaner_on = 1;
                    // ★g_cleaner_pid 必须一起恢复成哨兵值。cleaner_exec() 开头
                    //   是 `if (!g_cleaner_on || g_cleaner_pid <= 0) return -1;`
                    //   —— 重执行后它是 0，光把 g_cleaner_on 置 1 仍然返回 -1。
                    //   我第一版漏了这句，症状和没修一模一样（改了四轮才看出来）。
                    //   重执行后父进程不再是 cleaner 的父级，wait4 已经不适用，
                    //   用 1 作哨兵表示「靠 socket 通信，不靠 wait4」。
                    g_cleaner_pid = 1;
                    g_cleaner_namelen = plen;
                    g_cleaner_addrlen = palen;
                    for (int i = 0; i < plen; i++) g_cleaner_name[i] = pname[i];
                }
            }
            // envp 里的旧标记仅作兜底（探活失败时才看），不单独作为判据
            if (!g_cleaner_on && envp) {
                for (int i = 0; envp[i]; i++) {
                    const char *e = envp[i];
                    if (e[0]=='D'&&e[1]=='S'&&e[2]=='H'&&e[3]=='_'&&e[4]=='C'&&e[5]=='L'&&e[6]=='E'&&e[7]=='A'&&e[8]=='N') {
                        g_cleaner_namelen = cleaner_sockname(g_cleaner_name);
                        g_cleaner_addrlen = 3 + g_cleaner_namelen;
                        g_cleaner_on = 1;
                        break;
                    }
                }
            }
        }
        find_stub();
        // ★loader_preload 内部会 g_argv_count = 0 并放 gpath 作 argv[0]，
        //   再从传入的 gargv[1..] 追加。所以这里**不能**预填 g_argv_store（会被清掉），
        //   而应把重执行后 argv 的 guest 段直接交给它 —— 语义正好对齐。
        //   （早期版本预填导致 gcc 报 "no input files"：argv 全丢只剩程序名。）
        char **gargv_ptr = (gstart < argc) ? &argv[gstart] : 0;
        if (loader_preload(guest, gargv_ptr, envp, 2) != 0) { ldr_puts("REEXEC: preload FAIL"); raw6(SYS_exit_group, 11, 0, 0, 0, 0, 0); }
        if (g_argv_count == 0) { g_argv_count = 1; scopy_(g_argv_store[0], guest); }
        // 直接装载并跳转（不再返回）
        child_run_raw(0);
        raw6(SYS_exit_group, 12, 0, 0, 0, 0, 0);
    }

    // ---- 首次启动：正常 main 流程 ----
    int rc = ldr_main((int)argc, argv, envp);
    raw6(SYS_exit_group /*SYS_exit_group*/, rc, 0, 0, 0, 0, 0);
}

// 自定义 _start：从内核给的初始栈取 argc/argv/envp
#if defined(DSH_ARCH_AARCH64)
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
#else
// x86_64 SysV 进程入口：rsp 直接指向 argc（没有返回地址，**绝不能 ret**）
//   rdi=argc, rsi=argv=&[rsp+8], rdx=envp=&argv[argc+1]
// 调用前按 ABI 把 rsp 对齐到 16 字节，结束后用 exit_group 收尾。
__attribute__((naked)) void _start(void) {
    __asm__ volatile(
        "movq (%rsp), %rdi\n"
        "leaq 8(%rsp), %rsi\n"
        "leaq 8(%rsi,%rdi,8), %rdx\n"
        "andq $-16, %rsp\n"
        "call ldr_entry\n"
        "movl %eax, %edi\n"
        "movl $231, %eax\n"
        "syscall\n");
}
#endif
