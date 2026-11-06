#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 dsh_alpine.c 的 cBPF 二叉搜索树（架构无关）。

编号来源：**不要硬编码**，一律从 NDK 的 asm-generic / asm/unistd_64.h 读取。
硬编码是移植时踩过的坑：x86_64 的 openat 是 257，而 aarch64 是 56，
用 aarch64 表生成的树在 x86_64 上「一个都匹配不上」—— 表现为容器内
`ls /` 列出的是**宿主**根目录（所有路径 syscall 完全没被 TRAP）。

用法：
    python tools/gen_bpf_tree.py aarch64     # -> 供 C 源码 include 的片段
    python tools/gen_bpf_tree.py x86_64
"""
import os
import re
import subprocess
import sys

ARCHS = {
    'aarch64': {
        'header': 'asm-generic/unistd.h',      # 需在 *-linux-android/asm/ 下找
        'audit': 'AUDIT_ARCH_AARCH64',
    },
    'x86_64': {
        'header': 'x86_64-linux-android/asm/unistd_64.h',
        'audit': 'AUDIT_ARCH_X86_64',
    },
}

# 需要 TRAP 的 syscall（按语义名，编号运行时查）
NEEDED = [
    "getcwd", "mknodat", "mkdirat", "unlinkat", "symlinkat", "linkat",
    "renameat", "statfs", "fstatfs", "faccessat", "chdir", "fchmodat",
    "fchownat", "openat", "readlinkat", "newfstatat", "utimensat",
    "rt_sigaction", "rt_sigprocmask", "clone", "execve", "renameat2",
    "execveat", "statx", "sendto", "sendmsg", "recvfrom", "recvmsg",
    "connect", "openat2", "faccessat2",
    # ★inotify_add_watch(fd, pathname, mask) —— pathname 在 a1，没有 *at 变体。
    #
    #   不拦它的后果（实测）：Node 的 fs.watch 走 inotify_add_watch，guest 传的是
    #   `/dsh/profiles/web`，内核按**宿主**根解析 → ENOENT。chokidar 收到 ENOENT
    #   后会沿父目录逐级回退找「最近存在的目录」，最后退到 `/`，于是爆
    #   `EACCES: permission denied, watch '/'`，dsh-hmr 插件激活失败，
    #   整个引擎 `startup failed: 2 required plugins did not activate`。
    #   症状离根因极远：日志里只有 watch('/')，看不到任何路径翻译线索。
    "inotify_add_watch",
    # ★bind(fd, addr, addrlen) —— sockaddr_un.sun_path 也是 guest 路径。
    #
    #   不拦它的后果（实测）：容器里任何 AF_UNIX socket 都起不来。bind 传
    #   `/tmp/wl/test`，内核按**宿主**根解析 → 那个目录在宿主不存在 →
    #   `bind() failed with error: No such file or directory`。
    #
    #   表现最容易误判的一段：weston/compositor 日志说
    #       libwayland: bind() failed with error: No such file or directory
    #       fatal: failed to add socket: No such file or directory
    #   看起来像 socket 路径写错或权限问题，实际是路径没翻译。X11（Xvfb）同样
    #   卡在这一条 —— 之前记成「SELinux 禁 link()」，那只是另一个独立限制。
    "bind",
]

# ★x86_64 独有的「老式」路径 syscall。
#
# 为什么必须单独列：aarch64 的通用 ABI **没有** open/stat/access/unlink 这些
# 无 dirfd 的老式 syscall（只有 openat/newfstatat/faccessat/unlinkat），
# 所以原作者的树只拦了 *at 版本就够了。而 x86_64 全都保留着，glibc/musl 在
# x86_64 上会**直接使用老式版本** —— 实测容器里 `ls /etc`，strace 显示
#     stat("/etc", ...) = 0
#     open("/etc", O_RDONLY|O_DIRECTORY) = 5
# 两条都绕过了我们的过滤器（编号不在树里 → 不 TRAP → 直接落到宿主根）。
# 表现在用户侧就是「容器里看到的是 Android 的 /etc」。
#
# 这是移植到 x86_64 时**仅次于 IP 白名单**的第二个必踩坑：树合法、seccomp()
# 返回 0、编译通过，只有跑起来才看得出路径没翻译。
LEGACY_X86_64 = [
    "open", "stat", "lstat", "access", "unlink", "rmdir", "mkdir",
    "rename", "link", "symlink", "chmod", "chown", "truncate", "utime",
    "readlink",
]

# 老式 syscall 里 path 在第一个参数（无 dirfd）的集合 —— handler 按此分派
LEGACY_PATH_A0 = {
    "open", "stat", "lstat", "access", "unlink", "rmdir", "mkdir",
    "rename", "link", "symlink", "chmod", "chown", "truncate", "utime",
    "readlink",
}

# 由 load_numbers() 填充，供 verify_bpf_semantics.py 复用
NR_NAMES = {}

DIRFD = {"openat", "newfstatat", "faccessat", "readlinkat", "statx",
         "unlinkat", "mkdirat", "fchmodat", "fchownat", "utimensat",
         "mknodat", "openat2", "faccessat2"}


def find_ndk():
    for root in (os.environ.get('ANDROID_NDK'),
                 r'E:\Android\Sdk\ndk\28.2.13676358'):
        if root and os.path.isdir(root):
            return root
    raise SystemExit('找不到 NDK，请设 ANDROID_NDK')


def load_numbers(arch, verbose=False):
    ndk = find_ndk()
    sysroot = os.path.join(ndk, 'toolchains', 'llvm', 'prebuilt',
                           'windows-x86_64', 'sysroot', 'usr', 'include')
    cfg = ARCHS[arch]
    cands = [
        os.path.join(sysroot, cfg['header']),
        os.path.join(sysroot, 'aarch64-linux-android', 'asm',
                     os.path.basename(cfg['header'])),
        os.path.join(sysroot, 'asm-generic', os.path.basename(cfg['header'])),
    ]
    path = next((p for p in cands if os.path.isfile(p)), None)
    if not path:
        raise SystemExit('找不到 %s 的 unistd 头（试过 %s）' % (arch, cands))
    txt = open(path, encoding='utf-8', errors='replace').read()
    # aarch64 的 asm-generic 对少数 syscall 用了不同的宏名，这里给出别名。
    # 例如：statfs 写作 __NR3264_statfs；newfstatat 在 asm-generic 里叫 fstatat。
    ALIAS = {
        'newfstatat': ['newfstatat', 'fstatat'],
        'fstatfs': ['fstatfs', 'fstatfs'],
    }

    wanted = list(NEEDED)
    if arch == 'x86_64':
        # x86_64 独有的老式路径 syscall（aarch64 的通用 ABI 没有它们）
        wanted += LEGACY_X86_64

    out = {}
    for name in wanted:
        cands = ALIAS.get(name, [name])
        m = None
        for c in cands:
            m = re.search(r'#define\s+__NR_%s\s+(\d+)' % re.escape(c), txt)
            if not m:
                m = re.search(r'#define\s+__NR3264_%s\s+(\d+)' % re.escape(c), txt)
            if m:
                break
        if not m:
            raise SystemExit('%s 里没有 __NR_%s' % (path, name))
        out[name] = int(m.group(1))
    # 供 verify_bpf_semantics.py 复用同一份权威编号（不要再各写一份）
    global NR_NAMES
    NR_NAMES = out
    if verbose:
        sys.stderr.write('%s NR from %s\n' % (arch, os.path.basename(path)))
    return out, cfg['audit']


def main():
    arch = sys.argv[1] if len(sys.argv) > 1 else 'aarch64'
    if arch not in ARCHS:
        raise SystemExit('arch 必须是 %s' % list(ARCHS))
    NR, AUDIT = load_numbers(arch)

    names = sorted(NR, key=lambda n: NR[n])

    L = []
    def label(n): L.append(('label', n))
    def stmt(t): L.append(('stmt', t))
    def jmp(op, k, t, f): L.append(('jmp', (op, k, t, f)))
    def ja(t): L.append(('ja', t))

    def build(lo, hi):
        if lo > hi:
            ja('ALLOW')
            return
        mid = (lo + hi) // 2
        n = names[mid]
        jmp('JGT', n, 'R_' + n, 'NX_' + n)
        label('NX_' + n)
        jmp('JEQ', n, 'ACT_' + n, 'LEFT_' + n)
        label('ACT_' + n)
        if n in DIRFD:
            stmt('BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0)')
            jmp('JEQ', '0xFFFFFF9C', 'TRAP', 'ALLOW')
        elif n == 'clone':
            ja('CLONE_CHK')
        else:
            ja('TRAP')
        label('R_' + n); build(mid + 1, hi)
        label('LEFT_' + n); build(lo, mid - 1)

    build(0, len(names) - 1)

    label('CLONE_CHK')
    stmt('BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 8)')
    jmp('JEQ', '0', 'C1', 'ALLOW')
    label('C1')
    stmt('BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 12)')
    jmp('JEQ', '0', 'C2', 'ALLOW')
    label('C2')
    stmt('BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, args) + 0)')
    stmt('BPF_STMT(BPF_ALU|BPF_AND|BPF_K, 0x4000)')
    jmp('JEQ', '0', 'ALLOW', 'TRAP')

    label('ALLOW'); stmt('BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_ALLOW)')
    label('TRAP');  stmt('BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_TRAP)')

    PRO = [
        ('stmt', 'BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, arch))'),
        ('jmp', ('JEQ', 'AUDIT_ARCH', 'ARCH_OK', 'KILLP')),
        ('label', 'KILLP'),
        ('stmt', 'BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_KILL_PROCESS)'),
        ('label', 'ARCH_OK'),
        # ★这条不能少：放行 loader 自己的裸 syscall 桩（见 pitfalls #27）
        ('stmt', 'BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, instruction_pointer) + 4)'),
        ('jmp', ('JEQ', 'BASEHI', 'ALLOW', 'NOTSTUB')),
        ('label', 'NOTSTUB'),
        ('stmt', 'BPF_STMT(BPF_LD|BPF_W|BPF_ABS, (unsigned)offsetof(struct seccomp_data, nr))'),
    ]
    full = PRO + L

    pos = {}
    body = []
    for k, p in full:
        if k == 'label':
            pos[p] = len(body)
        else:
            body.append((k, p))

    lines = []
    for i, (k, p) in enumerate(body):
        if k == 'stmt':
            lines.append('        ' + p + ',')
        elif k == 'ja':
            lines.append('        BPF_STMT(BPF_JMP|BPF_JA, %d),' % (pos[p] - i - 1))
        else:
            op, kk, t, f = p
            if kk == 'AUDIT_ARCH':
                kx = AUDIT
            elif kk == 'BASEHI':
                kx = 'LOADER_BASE_HI'
            elif kk in ('0', '0xFFFFFF9C', '0x4000'):
                kx = kk
            else:
                kx = 'SYS_' + kk
            lines.append('        BPF_JUMP(BPF_JMP|BPF_%s|BPF_K, %s, %d, %d),'
                         % (op, kx, pos[t] - i - 1, pos[f] - i - 1))
    print('\n'.join(lines))
    sys.stderr.write('%s INSTR=%d (NR from %s)\n' % (arch, len(body), arch))


if __name__ == '__main__':
    main()
