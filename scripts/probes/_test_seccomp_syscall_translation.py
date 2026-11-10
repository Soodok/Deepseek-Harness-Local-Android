#!/usr/bin/env python3
"""守「x86_64 上被 Android 外层 seccomp 拦掉的系统调用，loader 有没有做等价翻译」。

## 为什么需要这个探针

Android 给每个 App 进程额外装一层 seccomp（zygote 那层），它**只按 syscall 号判、
不看 instruction_pointer**。而 dsh_alpine 自己的过滤器是按 IP 白名单放行的，
于是 handler 里通过 raw6 重发同一个 syscall 时，外层照样 trap → 重入守卫命中 →
guest 拿到 -EPERM。这类问题的共同特征是：**命令在 adb shell / su / run-as 里
一切正常（Seccomp=0），只有从 App 内触发才失败**，而且报错文案极具误导性。

已经踩过的四个（按发现顺序）：

    nr=23  select    → apk update 等满 30s 后报 IO ERROR
                       （翻译成 pselect6）
    nr=57  fork      → 容器内任何 fork() 拿 EPERM，sh 子进程全废
                       （翻译成 clone(SIGCHLD)）
    nr=7   poll      → musl 的 select/poll 走 poll
                       （翻译成 ppoll）
    nr=33  dup2      → apk 的 post-install 脚本里 `cat >&2 <<EOF` 会死循环，
                       CPU 打满、apk 数据库锁一直被占，界面上表现为
                       「一直转圈 / 一直加载中」，后续 apk update 全报
                       Unable to lock database (rc=99)
                       （翻译成 dup3，flags=0）

## 判据

1. 每个必须翻译的 syscall 都要有对应的 `if (nr == SYS_xxx)` 分支
2. 每个分支都要真的用 raw6 发出等价 syscall（不能只是留个空的 if）
3. 翻译分支必须在 `#if defined(DSH_ARCH_X86_64)` 里
   —— aarch64 的通用 ABI 根本没有 select/fork/poll/dup2 这几个号，
      不加守卫会直接编译失败
4. 守卫之外不得出现裸的 `nr == SYS_select` 等（否则 aarch64 编不过）

跑法：python scripts/probes/_test_seccomp_syscall_translation.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app/src/main/cpp/dsh_alpine.c")

checks = 0
failed = 0

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def ok(cond, msg):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   " + msg)
    else:
        failed += 1
        print("  FAIL " + msg)


def read(p):
    return io.open(p, encoding="utf-8", errors="replace").read()


def strip_comments(s):
    """去掉 /* */ 与 //，避免注释里的示例代码被当成真实实现。"""
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    return s


src_raw = read(SRC)
src = strip_comments(src_raw)

print("[A] 必须翻译的 syscall 都要有分派分支")
# (guest 侧 syscall 名, 应翻译成的等价 syscall 名, 触发它的真实症状)
REQUIRED = [
    ("SYS_select", "SYS_pselect6", "apk update 等 30s 后 IO ERROR"),
    ("SYS_fork", "SYS_clone", "容器内 fork() 拿 EPERM，sh 子进程全废"),
    ("SYS_poll", "SYS_ppoll", "musl 的 select/poll 走 poll"),
    ("SYS_dup2", "SYS_dup3", "apk post-install 死循环，一直转圈"),
]
for guest, target, symptom in REQUIRED:
    pat = re.compile(r"if\s*\(\s*nr\s*==\s*" + guest + r"\s*\)")
    ok(pat.search(src) is not None, f"{guest} 有分派分支（{symptom}）")

print("\n[B] 每个分支都要真的发出等价 syscall")
for guest, target, _ in REQUIRED:
    pat = re.compile(r"if\s*\(\s*nr\s*==\s*" + guest + r"\s*\)\s*\{")
    m = pat.search(src)
    if not m:
        ok(False, f"{guest} 分支存在性（无法定位块）")
        continue
    # 取该 if 块的正文：从 { 起配对花括号
    i = src.index("{", m.end() - 1)
    depth = 0
    j = i
    while j < len(src):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    body = src[i:j + 1]
    ok(re.search(r"raw6\s*\(\s*" + target, body) is not None,
       f"{guest} 分支里调用了 raw6({target})")

print("\n[C] 四个翻译分支都必须在 DSH_ARCH_X86_64 守卫内")
# 做法：去掉所有 #if defined(DSH_ARCH_X86_64) ... #endif 块，剩下的正文里
# 不允许再出现这四个 syscall 的判断 —— 出现说明漏了守卫，aarch64 会编不过。
flat = src
while True:
    m = re.search(r"#if\s+defined\s*\(\s*DSH_ARCH_X86_64\s*\)", flat)
    if not m:
        break
    # 从 #if 往前找行首，往后配对 #endif
    start = flat.rfind("\n", 0, m.start()) + 1
    depth = 1
    pos = m.end()
    while pos < len(flat) and depth > 0:
        nif = re.search(r"#\s*if", flat[pos:])
        nend = re.search(r"#\s*endif", flat[pos:])
        if not nend:
            break
        if nif and nif.start() < nend.start():
            depth += 1
            pos += nif.end()
        else:
            depth -= 1
            pos += nend.end()
    flat = flat[:start] + flat[pos:]

for guest, _, _ in REQUIRED:
    pat = re.compile(r"if\s*\(\s*nr\s*==\s*" + guest + r"\s*\)")
    ok(pat.search(flat) is None,
       f"{guest} 的判断只出现在 x86_64 守卫内")

print("\n[D] select 分支必须用命名 struct（历史坑）")
# 只有 select 分支有这个约束：它要同时处理 timeval（guest 传入）与
# timespec（pselect6 接收）两种类型，并互相转换。两个**匿名** struct 在 C 里
# 是互不兼容的不同类型，clang 会直接报 incompatible pointer types。
# poll 分支用匿名 struct 是合法的 —— 它只在本块内自用，不跨声明比较类型，
# 所以判据必须限定在 select 分支体内，不能全文扫。
#
# （这条曾经写错过：第一版全文扫 `struct { ... tv_sec`，把 poll 分支的
#   合法匿名 struct 判成缺陷，属于「判据越宽越易假绿/假红」的典型。）


def block_of(name):
    """取 `if (nr == <name>)` 那个块的花括号正文。"""
    m = re.search(r"if\s*\(\s*nr\s*==\s*" + name + r"\s*\)\s*\{", src)
    if not m:
        return ""
    i = src.index("{", m.end() - 1)
    depth = 0
    j = i
    while j < len(src):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    return src[i:j + 1]


sel_body = block_of("SYS_select")
ok(sel_body != "", "能定位 select 分支正文")
ok(re.search(r"struct\s+dsh_timeval\s*\{", sel_body) is not None,
   "select 分支里 timeval 用命名 struct dsh_timeval")
ok(re.search(r"struct\s+dsh_timespec\s*\{", sel_body) is not None,
   "select 分支里 timespec 用命名 struct dsh_timespec")
# select 分支里不得出现匿名 struct（那正是会 trigger incompatible pointer types 的写法）
ok(re.search(r"struct\s*\{[^}]*tv_sec", sel_body) is None,
   "select 分支里没有匿名 struct 定义 timeval/timespec")

print("\n[D2] dup2 分支不需要命名 struct（只搬 int，不与其它类型比较）")
dup2_body = block_of("SYS_dup2")
ok(dup2_body != "", "能定位 dup2 分支正文")
ok("struct" not in dup2_body,
   "dup2 分支里没有多余的 struct 定义（只需两个 int 与一次 fcntl 校验）")

print("\n[E] dup2 的等价语义细节")
# dup2(old, new) 在 old == new 时的特殊行为：若 old 有效则返回 new，不关闭。
# 直接转发给 dup3 会得到 EINVAL，某些调用方据此判失败。
dup2_m = re.search(r"if\s*\(\s*nr\s*==\s*SYS_dup2\s*\)\s*\{", src)
if dup2_m:
    i = src.index("{", dup2_m.end() - 1)
    depth = 0
    j = i
    while j < len(src):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    body = src[i:j + 1]
    ok(re.search(r"d_old\s*==\s*d_new", body) is not None,
       "dup2 分支特判 old == new（dup3 在这里返回 EINVAL，dup2 不返回）")
    ok("SYS_fcntl" in body or "F_GETFD" in body,
       "old == new 时先验 fd 有效性（dup2 语义：无效才返回 EBADF）")
else:
    ok(False, "定位不到 dup2 分支")
    ok(False, "定位不到 dup2 分支")

print(f"\nchecks={checks} failed={failed}")
sys.exit(1 if failed else 0)
