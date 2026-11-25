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
    # ★2026-11-07 补三条 apt/dpkg 在 Ubuntu 上必经的老式 syscall。
    #
    #   lchown(path, uid, gid) —— dpkg 建完符号链接后要给**链接本身**改属主
    #     （不能跟随目标，所以用不了 chown/fchownat）。
    #     漏拦的后果（模拟器 strace 实测，装 sqlite3）：
    #         symlinkat("../libsqlite3-0/changelog.Debian.gz", 1024, "./usr/…") = 0   ← 已修好
    #         lchown("/usr/share/doc/sqlite3/changelog.Debian.gz.dpkg-new", 0, 0) = -1 ENOENT
    #     宿主上没有那个路径 —— dpkg 报 `error setting ownership of symlink`，
    #     **整包安装失败**（不是软失败：包不会进 installed 状态）。
    #
    #   aarch64 的通用 ABI 没有 lchown/utimes/mknod（glibc 用 fchownat +
    #   AT_SYMLINK_NOFOLLOW 等模拟），所以只有 x86_64 需要这三条。真机是
    #   aarch64，不影响；但模拟器验收与「x86_64 端到端可用」需要它们。
    "lchown",
    "utimes",
    "mknod",
    # ★2026-11-25 补：time（issue #132 的第一性成因）。
    #
    #   Android 的 zygote seccomp 拦 time(201)、放行 clock_gettime(228)：
    #   容器内裸 syscall 实测（python ctypes，模拟器 x86_64 / Android 14）
    #       time(201, &t)                        rc=-1 errno=1  EPERM
    #       clock_gettime(228, CLOCK_REALTIME)   rc=0  sec 正确
    #       gettimeofday(96)                     rc=0  sec 正确
    #   不拦它的后果不是「少个功能」，而是**整个 apt 装不了包**：
    #   gpgv 判定签名是否在有效期内要拿当前时间，拿不到就退化成 0（1970），
    #   于是每个 Release 都被判成「未来」——
    #       E: Release file ... is not valid yet (invalid for another 19838d ...)
    #   而 19838 天恰好等于该 Release 的 Date 距 epoch 的天数，差一天都不差。
    #   gpgv 自己也会直说 `Fatal: time() failed`。
    #   用户看到的是「GPG error / Unable to locate package」，极易误判成
    #   镜像源或签名坏了 —— 实际是容器里没有可信的当前时间。
    "time",
    # ★2026-11-22 补：pipe。
    #
    #   Android 给 App 装的那层 seccomp 放行 pipe2(293)、**拦 pipe(22)**，
    #   同样只看 syscall 号、不看 IP（与 poll/select/fork/dup2 同族）。
    #   实测（模拟器 x86_64、App 域容器内 python3 裸 syscall 交替 3 次）：
    #       syscall(22)  pipe  -> rc=-1 errno=1 EPERM          3/3
    #       syscall(293) pipe2 -> rc=0  fds=(17,18)(19,20)(21,22)  3/3
    #   漏拦的后果（用户侧）：busybox ash 里任何管道都失败 ——
    #       /bin/sh: can not create pipe: Bad file descriptor   (rc=2)
    #   于是 a | b、strace -o（open_dummy_desc 要先 pipe）、Xvfb（用
    #   pipe/socketpair 起子进程）全部废掉：容器能起、命令能跑，一带管道就坏。
    #
    #   为什么只有 x86_64 需要：aarch64 的通用 ABI 没有 __NR_pipe，musl 的
    #   pipe() 在那里直接走 pipe2。所以真机 arm64 一直正常，只有 x86_64
    #   模拟器踩这个坑 —— 它藏得久、直到「模拟器上装 Xvfb」才暴露。
    "pipe",
    # ★2026-11-22 补：accept / epoll_create / eventfd / inotify_init / signalfd。
    #
    #   与 pipe 同一晚的同一类坑：Android 那层 seccomp 只认 syscall 号，
    #   x86_64 上的「老式」号被拦，对应的「新式」号放行。实测（模拟器
    #   x86_64 / Android 34 / App 域容器内 python3 裸 syscall，各带真实参数）：
    #       accept(43)        -> EPERM      accept4(288)        -> fd
    #       epoll_create(213) -> EPERM      epoll_create1(291)  -> fd
    #       eventfd(284)      -> EPERM      eventfd2(290)       -> fd
    #       inotify_init(253) -> EPERM      inotify_init1(294)  -> fd
    #       signalfd(282)     -> EPERM      signalfd4(289)      -> 仅 EINVAL(参数错)
    #
    #   漏拦的后果（Xvfb 端到端实测）：Xvfb 能起、能 bind、socket 能连上，
    #   但**永远不 accept** —— 日志每秒刷
    #       _XSERVTransSocketUNIXAccept: accept() failed
    #   客户端连上后一个字节都收不到（connect OK 0.00s / NO REPLY）。
    #   此前把「Xvfb 起不来」记成 XKB 编译失败，真因是 accept 被拒：
    #   Xorg 的 Xtrans 用 accept()，读失败就丢弃该连接，界面永远黑屏。
    #
    #   为什么只有 x86_64 需要：aarch64 的通用 ABI 没有 accept/epoll_create/
    #   eventfd/inotify_init/signalfd 这些老号，musl 在那里直接走 *4/*1 版本。
    #   所以真机 arm64 一直是好的，只有 x86_64 模拟器踩。
    "accept", "epoll_create", "eventfd", "inotify_init", "signalfd",
]

# ⚠️ 这个集合**不参与生成**，也不是 TRAP 的依据。
#
# 真正决定「哪些 syscall 被拦」的是上面的 NEEDED + LEGACY_X86_64；
# 真正决定「怎么翻译」的是 dsh_alpine.c 里那几个 case 分派。本集合只是一份
# 「path 在 a0、没有 dirfd」的分类备忘（当初想给 handler 分派用，后来 handler
# 改成手写 switch，它就悬空了）。
#
# 保留它是为了让下一个人查「这条 syscall 的 path 在哪個参数」时有个现成答案，
# 但**改它不会产生任何行为变化** —— 这正是 2026-11-07 那次踩坑的一半：
# 把 lchown 加进这里、以为就够了，实际必须加进 LEGACY_X86_64（拦）与源码 case
# （翻译）。为免再次误判，名字与注释都按「备忘」写，而不是「配置」。
LEGACY_PATH_A0 = {
    "open", "stat", "lstat", "access", "unlink", "rmdir", "mkdir",
    "rename", "link", "symlink", "chmod", "chown", "truncate", "utime",
    "readlink",
    # ★2026-11-07 补：apt/dpkg 在 Ubuntu 上装包必经的三条。
    #
    #   lchown(path, uid, gid) —— dpkg 建完符号链接后要「给链接本身改属主」，
    #     必须改链接 itself 而不是跟随目标（所以不能用 chown/fchownat）。
    #     漏拦的后果（strace 实测）：symlinkat 已返回 0，紧接着
    #         lchown("/usr/share/doc/sqlite3/changelog.Debian.gz.dpkg-new", 0, 0) = -1 ENOENT
    #     宿主根本没有那个路径 —— dpkg 报 `error setting ownership of symlink`，
    #     **整包安装失败**。aarch64 的通用 ABI 没有 lchown，所以只有 x86_64 受影响，
    #     但真机是 aarch64 —— 不能因此漏补（见下条 fchmod/fchown 同理）。
    "lchown",
    #   fchmod/fchown(fd, ...) —— 无路径，但有 fd 参数；列进来只是为了
    #     「不进树 = 直通宿主」的风险归零（它们作用在 fd 上，本来就安全）。
    #   实际需要拦的是下面两条带路径的：
    "utimes",   # utimes(path, timeval[2])：老式版本，dpkg 维护脚本会调
    "mknod",    # mknod(path, mode, dev)：某些包装设备节点
}

# 由 load_numbers() 填充，供 verify_bpf_semantics.py 复用
NR_NAMES = {}

DIRFD = {"openat", "newfstatat", "faccessat", "readlinkat", "statx",
         "unlinkat", "mkdirat", "fchmodat", "fchownat", "utimensat",
         "mknodat", "openat2", "faccessat2"}


def find_ndk():
    """找一个可用的 NDK。找不到返回 None（不是 SystemExit —— 见 load_numbers）。

    `DSH_NO_NDK=1` 强制走兜底编号表：探针用它在**任意机器**上确定性地验证
    「没有 NDK 的环境（CI）也能生成逐行一致的树」——否则本地有 NDK 就永远
    只测到一条路径，CI 缺 NDK 那条直到红了才发现（2026-11-22 的真实教训）。
    """
    if os.environ.get('DSH_NO_NDK'):
        return None
    cands = [os.environ.get('ANDROID_NDK'), os.environ.get('ANDROID_NDK_HOME')]
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if sdk:
        cands.append(os.path.join(sdk, 'ndk'))
        # SDK 里的 ndk/<version> 需要再展开一层
        ndkdir = os.path.join(sdk, 'ndk')
        if os.path.isdir(ndkdir):
            subs = sorted(os.listdir(ndkdir))
            if subs:
                cands.append(os.path.join(ndkdir, subs[-1]))
    cands.append(r'E:\Android\Sdk\ndk\28.2.13676358')

    for root in cands:
        if not root or not os.path.isdir(root):
            continue
        # 光有目录不算 —— CI 上 `ndk/` 可能存在但一个版本都没装。
        if os.path.isdir(os.path.join(root, 'toolchains')):
            return root
    return None


# 编号兜底表：`scripts/syscall_numbers.json`（由 tools/dump-syscall-numbers.py
# 从 NDK 头导出，随源码入库）。
#
# ★为什么需要它（2026-11-24 真实事故）：CI 的 `i18n-check` runner 上**没有 NDK**，
#   而探针 `_test_dsh_alpine_source_sync.py` 要实跑本生成器来验「树里真的含
#   SYS_xxx」。没有 NDK 时生成器以前直接 `SystemExit`，探针拿到的 stdout 是空的，
#   于是 5~9 条「x86_64 生成的树里含 SYS_accept/epoll_create/eventfd/inotify_init/
#   signalfd」全红 —— 从 2026-11-22 起 CI 一直是红的，而本地全绿。
#
#   兜底表让「没有 NDK」也能算出一棵**与入库内联树逐行一致**的树（编号是常量，
#   不随宿主变），于是探针在 CI 上能真跑而不是假红。有 NDK 时仍然优先读头，
#   这样「NDK 换了版本、编号表过期」能被发现（两者不一致会触发下面的 must 断言）。
FALLBACK_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             'syscall_numbers.json')
_fallback_cache = None


def load_fallback(arch):
    global _fallback_cache
    if _fallback_cache is None:
        if not os.path.isfile(FALLBACK_PATH):
            return None
        import json
        with open(FALLBACK_PATH, encoding='utf-8') as f:
            _fallback_cache = json.load(f)
    tbl = _fallback_cache.get(arch)
    return dict(tbl) if tbl else None


def load_numbers(arch, verbose=False):
    global NR_NAMES
    cfg = ARCHS[arch]
    ndk = find_ndk()
    path = None
    sysroot = None
    if ndk:
        # prebuilt 目录名随宿主变（windows-x86_64 / linux-x86_64 / darwin-x86_64），
        # 写死 windows-x86_64 会让 CI（linux）与本地共用代码却各找一个不存在的路径。
        for pre in ('windows-x86_64', 'linux-x86_64', 'darwin-x86_64'):
            cand = os.path.join(ndk, 'toolchains', 'llvm', 'prebuilt', pre,
                                'sysroot', 'usr', 'include')
            if os.path.isdir(cand):
                sysroot = cand
                break
            # 有些发行版直接放 sysroot（没有 usr/include 层）
            cand2 = os.path.join(ndk, 'toolchains', 'llvm', 'prebuilt', pre, 'sysroot')
            if os.path.isdir(cand2):
                sysroot = cand2
                break
    if sysroot:
        cands = [
            os.path.join(sysroot, cfg['header']),
            os.path.join(sysroot, 'aarch64-linux-android', 'asm',
                         os.path.basename(cfg['header'])),
            os.path.join(sysroot, 'asm-generic', os.path.basename(cfg['header'])),
            os.path.join(sysroot, 'usr', 'include', cfg['header']),
        ]
        path = next((p for p in cands if os.path.isfile(p)), None)

    if not path:
        # 没有 NDK（CI）或头文件缺失 → 用入库编号表。表里缺任何一个名字都算错，
        # 静默跳过会让「加了名字却没编进树」永远发现不了。
        tbl = load_fallback(arch)
        if tbl:
            wanted = list(NEEDED) + (list(LEGACY_X86_64) if arch == 'x86_64' else [])
            missing = [n for n in wanted if n not in tbl]
            if missing:
                raise SystemExit(
                    'syscall_numbers.json 缺 %s 的编号：%s（有 NDK 时请重跑 '
                    'tools/dump-syscall-numbers.py 更新该表）' % (arch, missing))
            out = {n: int(tbl[n]) for n in wanted}
            NR_NAMES = out
            if verbose:
                sys.stderr.write('%s NR from syscall_numbers.json (no NDK)\n' % arch)
            return out, cfg['audit']
        raise SystemExit(
            '找不到 NDK，也没有可用的 scripts/syscall_numbers.json —— '
            '请设 ANDROID_NDK，或先跑 tools/dump-syscall-numbers.py 生成编号表。')

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
    text = '\n'.join(lines)
    print(text)
    sys.stderr.write('%s INSTR=%d (NR from %s)\n' % (arch, len(body), arch))

    # ★自检：生成了不等于生成对了。只断言「这个 arch 该有的关键 NR 确实进了
    #   输出」—— 漏一条的表现是运行时容器看到宿主根，静态看不出来。
    #
    #   2026-11-07 补 lchown/utimes/mknod 时踩过：只把它们加进 LEGACY_PATH_A0
    #   （handler 分派用的集合）而没加进 LEGACY_X86_64（真正决定 TRAP 的集合），
    #   生成器静默输出 213 条、一条没多，看起来「成功」。所以这里必须验。
    must = ['openat', 'execve', 'symlinkat', 'fchownat', 'chdir', 'getcwd']
    if arch == 'x86_64':
        must += ['symlink', 'lchown', 'utimes', 'mknod', 'open', 'stat', 'pipe',
                 'accept', 'epoll_create', 'eventfd', 'inotify_init', 'signalfd']
    missing = [m for m in must if ('SYS_' + m) not in text]
    if missing:
        raise SystemExit('%s tree is missing key NR: %s' % (arch, missing))

    # 反向：x86_64 专属的老式 syscall 不得出现在 aarch64 树里
    # （aarch64 的通用 ABI 没有这些宏，编不过）
    if arch != 'x86_64':
        leaked = [m for m in ('lchown', 'utimes', 'mknod', 'symlink', 'chown', 'pipe',
                              'epoll_create', 'eventfd', 'inotify_init', 'signalfd')
                  if ('SYS_' + m + ',') in text]
        if leaked:
            raise SystemExit('%s tree has x86_64-only NR: %s' % (arch, leaked))


if __name__ == '__main__':
    main()
